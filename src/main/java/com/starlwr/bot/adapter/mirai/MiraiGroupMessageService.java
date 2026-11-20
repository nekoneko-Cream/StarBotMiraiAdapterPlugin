package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@StarBotComponent
public class MiraiGroupMessageService implements AutoCloseable {
    private static final long WS_RECONNECT_COOLDOWN_MILLIS = 3_000L;

    private final MiraiAdapterProperties properties;
    private final MiraiAdapterService adapter;
    private final MiraiCommandHandler commands;
    private final ScheduledExecutorService receiver = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "receiver-mirai");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient wsHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final AtomicBoolean wsConnecting = new AtomicBoolean(false);
    private volatile WebSocket activeWebSocket;
    private volatile boolean wsConnected;
    private volatile long lastWsAttemptMillis;
    private volatile boolean closed;
    private boolean started;
    private boolean failed;
    private volatile Instant lastMessageTime = Instant.now();

    public MiraiGroupMessageService(MiraiAdapterProperties properties, MiraiAdapterService adapter,
                                    AbstractDataSource dataSource, StarBotMessageSender sender) {
        this.properties = properties;
        this.adapter = adapter;
        this.commands = new MiraiCommandHandler(properties, () -> {
            synchronized (dataSource) {
                // Core 3.0 update replaces userMap entries but leaves old objects in getAllUsers().
                return dataSource.getAllUsers().stream()
                    .flatMap(user -> dataSource.getUser(user.getPlatform(), user.getUid()).stream()).toList();
            }
        }, sender::send,
            Clock.systemUTC());
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || !properties.isEnabled() || !properties.isReceiveEnabled() || properties.getBotQq() <= 0) return;
        started = true;
        if (properties.isWebsocketEnabled()) {
            connectWebSocketAsync();
        }
        receiver.scheduleWithFixedDelay(this::poll, 200, Math.max(100, properties.getPollInterval()), TimeUnit.MILLISECONDS);
        if (properties.isWebsocketEnabled()) {
            log.info("Mirai 消息接收已启用，命令模式 {}（优先 WebSocket 实时推送，不可用时自动回退 HTTP 轮询）", properties.getCommandMode());
        } else {
            log.info("Mirai 消息接收已启用，命令模式 {}（HTTP 轮询）", properties.getCommandMode());
        }
    }

    private void connectWebSocketAsync() {
        if (closed || !properties.isEnabled() || !properties.isReceiveEnabled() || !properties.isWebsocketEnabled()) return;
        long now = System.currentTimeMillis();
        if (wsConnected || now - lastWsAttemptMillis < WS_RECONNECT_COOLDOWN_MILLIS) return;
        if (!wsConnecting.compareAndSet(false, true)) return;
        lastWsAttemptMillis = now;
        try {
            String encodedKey = URLEncoder.encode(properties.getVerifyKey(), StandardCharsets.UTF_8);
            URI wsUri = URI.create("ws://" + properties.getHost() + ":" + properties.getPort()
                + "/message?verifyKey=" + encodedKey + "&qq=" + properties.getBotQq());
            wsHttpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri, new MessageWebSocketListener())
                .whenComplete((ws, err) -> {
                    wsConnecting.set(false);
                    if (err != null) {
                        wsConnected = false;
                        activeWebSocket = null;
                        log.debug("Mirai WebSocket 连接未就绪，暂时使用 HTTP 轮询（{}）", err.getClass().getSimpleName());
                        return;
                    }
                    if (closed) {
                        try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
                        return;
                    }
                    activeWebSocket = ws;
                    wsConnected = true;
                    failed = false;
                    log.info("Mirai WebSocket 实时消息推送已连接 ({}:{})", properties.getHost(), properties.getPort());
                });
        } catch (Exception e) {
            wsConnecting.set(false);
            wsConnected = false;
            log.debug("Mirai WebSocket 初始化异常，回退 HTTP 轮询（{}）", e.getClass().getSimpleName());
        }
    }

    void poll() {
        if (closed || !properties.isEnabled() || !properties.isReceiveEnabled()) return;
        if (properties.isWebsocketEnabled()) {
            if (wsConnected) return;
            connectWebSocketAsync();
        }
        try {
            JSONArray events = adapter.fetchMessages(20);
            if (failed) log.info("Mirai 群消息接收已恢复");
            failed = false;
            for (int i = 0; i < events.size(); i++) {
                handleEvent(events.getJSONObject(i));
            }
        } catch (Exception e) {
            if (!failed) log.warn("Mirai 群消息接收失败，将继续重试（{}）", e.getClass().getSimpleName());
            failed = true;
        }
    }

    void handleWebSocketPayload(String payload) {
        if (payload == null || payload.isBlank()) return;
        try {
            JSONObject root = JSON.parseObject(payload);
            if (root != null) {
                JSONObject event = root.getJSONObject("data");
                if (event != null && event.containsKey("type")) {
                    handleEvent(event);
                } else if (root.containsKey("type")) {
                    handleEvent(root);
                }
            }
        } catch (Exception e) {
            log.debug("忽略无法解析的 Mirai WebSocket 帧（{}）", e.getClass().getSimpleName());
        }
    }

    private void handleEvent(JSONObject event) {
        if (event == null) return;
        try {
            String type = event.getString("type");
            if (type != null && type.endsWith("Message")) {
                lastMessageTime = Instant.now();
            }
            if (commands.handle(event)) {
                log.info("已处理 Mirai {} 指令", type);
            }
        } catch (Exception e) {
            log.warn("已跳过无法处理的 Mirai 消息：{}", e.getClass().getSimpleName());
        }
    }

    private final class MessageWebSocketListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                handleWebSocketPayload(payload);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            wsConnected = false;
            if (activeWebSocket == webSocket) activeWebSocket = null;
            if (!closed) {
                log.warn("Mirai WebSocket 连接已断开 (code={})，暂时回退 HTTP 轮询并自动重连", statusCode);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            wsConnected = false;
            if (activeWebSocket == webSocket) activeWebSocket = null;
            if (!closed) {
                log.warn("Mirai WebSocket 通道异常（{}），暂时回退 HTTP 轮询并自动重连", error.getClass().getSimpleName());
            }
        }
    }

    public boolean isWebSocketConnected() {
        return wsConnected;
    }

    public Instant getLastMessageTime() {
        return lastMessageTime;
    }

    @Override
    @PreDestroy
    public void close() {
        closed = true;
        wsConnected = false;
        WebSocket ws = activeWebSocket;
        activeWebSocket = null;
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
            try { ws.abort(); } catch (Exception ignored) {}
        }
        receiver.shutdownNow();
    }
}
