package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.StarBotSenderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Implements the official Core Sender HTTP contract on this application's port. */
@Slf4j
@RestController
@StarBotComponent
public class MiraiController implements AutoCloseable {
    private final MiraiAdapterProperties properties;
    private final MiraiAdapterService adapter;
    private final StarBotSenderService senders;
    private final WebServerApplicationContext context;
    private final StarBotCoreProperties coreProperties;
    private final ExecutorService transport = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "transport-mirai");
        thread.setDaemon(true);
        return thread;
    });
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private volatile boolean registered;

    public MiraiController(MiraiAdapterProperties properties, MiraiAdapterService adapter,
                           StarBotSenderService senders, WebServerApplicationContext context, StarBotCoreProperties coreProperties) {
        this.properties = properties;
        this.adapter = adapter;
        this.senders = senders;
        this.context = context;
        this.coreProperties = coreProperties;
    }

    @Order(-20000)
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onApplicationReady() {
        if (registered || !properties.isEnabled()) return;
        if (properties.getBotQq() <= 0 || properties.getPlatform() == null || properties.getPlatform().isBlank()) {
            throw new IllegalArgumentException("Mirai bot-qq 与 platform 必须配置");
        }
        String prefix = context.getEnvironment() == null ? "" :
            context.getEnvironment().getProperty("server.servlet.context-path", "");
        if ("/".equals(prefix)) prefix = "";
        String url = "http://127.0.0.1:" + context.getWebServer().getPort() + prefix + "/mirai/send";
        senders.addSender(new Sender(properties.getPlatform(), url, token, Math.max(0, properties.getDelay())));
        registered = true;
        log.info("Mirai 已注册为原生推送平台 {}，使用 Core 消息队列", properties.getPlatform());
    }

    @PostMapping("/mirai/send")
    public JSONObject send(@RequestBody Map<String, Object> payload,
                           @RequestHeader(value = "Authorization", required = false) String authorization,
                           HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote))) {
            return MiraiAdapterService.failure("仅允许本机 Core 发送请求");
        }
        String presented = authorization;
        if (presented != null && presented.regionMatches(true, 0, "Bearer ", 0, 7)) presented = presented.substring(7).strip();
        if (!registered || presented == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8))) return MiraiAdapterService.failure("未授权");
        try {
            JSONObject message = new JSONObject(payload);
            Integer type = message.getInteger("type");
            Long target = message.getLong("num");
            if (type == null || target == null) return MiraiAdapterService.failure("缺少 type 或 num");
            long outer = coreProperties.getNetwork().getReadTimeout() * 1000L;
            long budget = outer <= 0 ? 55_000L : Math.min(55_000L, Math.max(100L, outer - Math.min(5000L, outer / 2)));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
            Future<JSONObject> result = transport.submit(() -> MiraiApiClient.beforeDeadline(deadline, () ->
                adapter.send(message.getString("platform"), type, target, message.getString("content"))));
            try {
                return result.get(budget, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                result.cancel(true);
                return MiraiAdapterService.failure("Mirai 发送超时，结果不确定；为避免重复消息不自动重发");
            } catch (InterruptedException e) {
                result.cancel(true);
                Thread.currentThread().interrupt();
                return MiraiAdapterService.failure("Mirai 发送已中断，结果不确定");
            } catch (ExecutionException e) {
                return MiraiAdapterService.failure("Mirai 发送执行失败");
            }
        } catch (RuntimeException e) {
            return MiraiAdapterService.failure("Mirai 请求字段格式错误");
        }
    }

    @Override
    @PreDestroy
    public void close() { transport.shutdownNow(); }
}
