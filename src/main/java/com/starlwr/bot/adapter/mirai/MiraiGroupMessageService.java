package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
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
import java.util.List;
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
    private static final long BOT_SWITCH_CHECK_INTERVAL_MILLIS = 5_000L;
    private static final long WS_QUICK_CLOSE_THRESHOLD_MILLIS = 2_000L;

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
    private volatile long wsBotQq;
    private volatile long wsOpenedAtMillis;
    private volatile long lastWsAttemptMillis;
    private volatile long lastBotCheckMillis = System.currentTimeMillis();
    private volatile boolean closed;
    private boolean started;
    private boolean failed;
    private String lastFailureReason;
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
            Clock.systemUTC(),
            () -> {
                long active = adapter.getActiveBotQq();
                return active > 0 ? active : properties.getBotQq();
            });
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || !properties.isEnabled() || !properties.isReceiveEnabled()) return;
        if (properties.getBotQq() <= 0 && !properties.isAutoFollowOnlineBot()) return;
        started = true;
        if (properties.isWebsocketEnabled()) {
            receiver.execute(this::connectWebSocketAsync);
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
            long targetQq = adapter.resolveActiveBotQq();
            if (targetQq <= 0) {
                wsConnecting.set(false);
                wsConnected = false;
                return;
            }
            String encodedKey = URLEncoder.encode(properties.getVerifyKey(), StandardCharsets.UTF_8);
            URI wsUri = URI.create("ws://" + properties.getHost() + ":" + properties.getPort()
                + "/all?verifyKey=" + encodedKey + "&qq=" + targetQq);
            MessageWebSocketListener listener = new MessageWebSocketListener();
            wsHttpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri, listener)
                .whenComplete((ws, err) -> {
                    wsConnecting.set(false);
                    if (err != null) {
                        wsConnected = false;
                        wsBotQq = 0;
                        activeWebSocket = null;
                        log.debug("Mirai WebSocket 连接未就绪，暂时使用 HTTP 轮询：{}", MiraiApiClient.describeFailure(err));
                        return;
                    }
                    if (closed || listener.isTerminal()) {
                        if (closed) {
                            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
                        }
                        return;
                    }
                    activeWebSocket = ws;
                    wsBotQq = targetQq;
                    wsOpenedAtMillis = System.currentTimeMillis();
                    wsConnected = true;
                    failed = false;
                    lastFailureReason = null;
                    log.info("Mirai WebSocket 实时消息推送已连接 ({}:{})", properties.getHost(), properties.getPort());
                });
        } catch (Exception e) {
            wsConnecting.set(false);
            wsConnected = false;
            wsBotQq = 0;
            log.debug("Mirai WebSocket 初始化未就绪，暂时使用 HTTP 轮询：{}", MiraiApiClient.describeFailure(e));
        }
    }

    void poll() {
        if (closed || !properties.isEnabled() || !properties.isReceiveEnabled()) return;
        if (properties.isAutoFollowOnlineBot()) {
            maybeCheckActiveBotSwitch();
        }
        if (properties.isWebsocketEnabled()) {
            if (wsConnected) return;
            connectWebSocketAsync();
        }
        try {
            JSONArray events = adapter.fetchMessages(20);
            if (failed) log.info("Mirai 群消息接收已恢复");
            failed = false;
            lastFailureReason = null;
            for (int i = 0; i < events.size(); i++) {
                handleEvent(events.getJSONObject(i));
            }
        } catch (Exception e) {
            String reason = MiraiApiClient.describeFailure(e);
            if (!failed || !reason.equals(lastFailureReason)) {
                log.warn("Mirai 群消息接收失败，将继续重试：{}", reason);
            }
            lastFailureReason = reason;
            failed = true;
        }
    }

    private void maybeCheckActiveBotSwitch() {
        long now = System.currentTimeMillis();
        if (now - lastBotCheckMillis < BOT_SWITCH_CHECK_INTERVAL_MILLIS) return;
        lastBotCheckMillis = now;
        checkActiveBotSwitch();
    }

    void checkActiveBotSwitch() {
        if (closed || !properties.isEnabled() || !properties.isReceiveEnabled() || !properties.isAutoFollowOnlineBot()) return;
        long currentQq = wsConnected && wsBotQq > 0 ? wsBotQq : adapter.getActiveBotQq();
        if (currentQq <= 0) return;
        List<Long> onlineBots = adapter.fetchOnlineBots();
        if (onlineBots == null || onlineBots.isEmpty() || onlineBots.contains(currentQq)) return;
        if (onlineBots.size() == 1) {
            log.info("检测到 Mirai 当前在线 Bot 已切换，正在自动重新绑定并重连");
            adapter.invalidateSession();
            wsBotQq = 0;
            lastWsAttemptMillis = 0;
            WebSocket ws = activeWebSocket;
            wsConnected = false;
            activeWebSocket = null;
            if (ws != null) {
                try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bot-switched"); } catch (Exception ignored) {}
                try { ws.abort(); } catch (Exception ignored) {}
            }
        }
    }

    void handleWebSocketPayload(String payload) {
        handleWebSocketPayloadInternal(payload);
    }

    private boolean handleWebSocketPayloadInternal(String payload) {
        if (payload == null || payload.isBlank()) return false;
        try {
            JSONObject root = JSON.parseObject(payload);
            if (root != null) {
                JSONObject data = root.getJSONObject("data");
                Integer errorCode = extractErrorCode(root, data);
                if (errorCode != null) {
                    handleWebSocketErrorFrame(errorCode);
                    return true;
                }
                if (data != null && data.containsKey("type")) {
                    handleEvent(data);
                } else if (root.containsKey("type")) {
                    handleEvent(root);
                }
            }
        } catch (Exception e) {
            log.debug("忽略无法解析的 Mirai WebSocket 帧（{}）", e.getClass().getSimpleName());
        }
        return false;
    }

    private static Integer extractErrorCode(JSONObject root, JSONObject data) {
        if (data != null && !data.containsKey("type") && data.containsKey("code")) {
            Integer code = data.getInteger("code");
            if (code != null && code != 0) return code;
        }
        if (!root.containsKey("type") && root.containsKey("code")) {
            Integer code = root.getInteger("code");
            if (code != null && code != 0) return code;
        }
        return null;
    }

    private void handleWebSocketErrorFrame(int code) {
        wsConnected = false;
        wsBotQq = 0;
        adapter.invalidateSession();
        WebSocket ws = activeWebSocket;
        activeWebSocket = null;
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "auth-failed"); } catch (Exception ignored) {}
            try { ws.abort(); } catch (Exception ignored) {}
        }
        if (!closed) {
            log.warn("Mirai WebSocket 鉴权或绑定失败（{}），将重新解析在线 Bot 并自动重连", MiraiApiClient.describeCode(code));
        }
    }

    private void handleEvent(JSONObject event) {
        if (event == null) return;
        try {
            String type = event.getString("type");
            if (type != null && type.endsWith("Message")) {
                lastMessageTime = Instant.now();
            }
            handleBotStateEvent(type, event);
            if (commands.handle(event)) {
                log.info("已处理 Mirai {} 指令", type);
            }
        } catch (Exception e) {
            log.warn("已跳过无法处理的 Mirai 消息：{}", e.getClass().getSimpleName());
        }
    }

    private void handleBotStateEvent(String type, JSONObject event) {
        if (type == null) return;
        switch (type) {
            case "BotGroupPermissionChangeEvent" -> {
                JSONObject group = event.getJSONObject("group");
                Long groupId = group == null ? null : group.getLong("id");
                String current = event.getString("current");
                if (groupId != null && current != null) {
                    adapter.onBotGroupPermissionChanged(groupId, current);
                }
            }
            case "BotJoinGroupEvent" -> {
                JSONObject group = event.getJSONObject("group");
                Long groupId = group == null ? null : group.getLong("id");
                String perm = group == null ? null : group.getString("permission");
                if (groupId != null) {
                    adapter.onBotJoinedGroup(groupId, perm);
                }
            }
            case "BotLeaveEventActive", "BotLeaveEventKick", "BotLeaveEventDisband" -> {
                JSONObject group = event.getJSONObject("group");
                Long groupId = group == null ? null : group.getLong("id");
                if (groupId != null) {
                    adapter.onBotLeftGroup(groupId);
                }
            }
            case "BotMuteEvent" -> {
                Integer seconds = event.getInteger("durationSeconds");
                log.warn("机器人在群内被禁言（时长 {} 秒）", seconds == null ? -1 : seconds);
            }
            case "BotUnmuteEvent" -> log.info("机器人在群内已解除禁言");
            case "GroupMuteAllEvent" -> {
                Boolean current = event.getBoolean("current");
                if (current != null) {
                    log.info("检测到群全员禁言已{}", current ? "开启" : "关闭");
                }
            }
            default -> {
            }
        }
    }

    private final class MessageWebSocketListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();
        private final AtomicBoolean terminal = new AtomicBoolean(false);

        boolean isTerminal() {
            return terminal.get();
        }

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
                if (handleWebSocketPayloadInternal(payload)) {
                    terminal.set(true);
                    try { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "auth-failed"); } catch (Exception ignored) {}
                    try { webSocket.abort(); } catch (Exception ignored) {}
                    return CompletableFuture.completedFuture(null);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            terminal.set(true);
            boolean wasActive = activeWebSocket == webSocket;
            long openedAt = wsOpenedAtMillis;
            if (wasActive) {
                wsConnected = false;
                wsBotQq = 0;
                activeWebSocket = null;
            }
            if (openedAt > 0 && System.currentTimeMillis() - openedAt < WS_QUICK_CLOSE_THRESHOLD_MILLIS) {
                adapter.invalidateSession();
            }
            if (!closed && wasActive) {
                log.warn("Mirai WebSocket 连接已断开 (code={})，暂时回退 HTTP 轮询并自动重连", statusCode);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            terminal.set(true);
            boolean wasActive = activeWebSocket == webSocket;
            if (wasActive) {
                wsConnected = false;
                wsBotQq = 0;
                activeWebSocket = null;
            }
            if (!closed && wasActive) {
                log.warn("Mirai WebSocket 通道异常：{}，暂时回退 HTTP 轮询并自动重连", MiraiApiClient.describeFailure(error));
            }
        }
    }

    public boolean isWebSocketConnected() {
        return wsConnected;
    }

    public Instant getLastMessageTime() {
        return lastMessageTime;
    }

    boolean isStarted() {
        return started;
    }

    @Override
    @PreDestroy
    public void close() {
        closed = true;
        wsConnected = false;
        wsBotQq = 0;
        WebSocket ws = activeWebSocket;
        activeWebSocket = null;
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
            try { ws.abort(); } catch (Exception ignored) {}
        }
        receiver.shutdownNow();
    }
}
