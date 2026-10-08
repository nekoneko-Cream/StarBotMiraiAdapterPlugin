package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.web.client.RestTemplate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Routes one configured StarBot platform through the existing Mirai API HTTP service. */
@Slf4j
@Aspect
@StarBotComponent
public class MiraiAdapterService implements AutoCloseable {
    private final MiraiAdapterProperties properties;
    private final MiraiMessageConverter converter;
    private final MiraiApiClient client;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sender-mirai");
        thread.setDaemon(true);
        return thread;
    });

    public MiraiAdapterService(MiraiAdapterProperties properties, MiraiMessageConverter converter, MiraiApiClient client) {
        this.properties = properties;
        this.converter = converter;
        this.client = client;
    }

    @Autowired
    public MiraiAdapterService(MiraiAdapterProperties properties, MiraiMessageConverter converter, RestTemplate http) {
        this(properties, converter, new MiraiApiClient(properties.getBaseUrl(), http));
    }

    @PostConstruct
    public void initialize() {
        log.info("Mirai 适配器 Bean 已创建，配置: {}:{} / {} / {}", properties.getHost(), properties.getPort(), properties.getPlatform(), properties.getBotQq());
        tryConnect();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            log.info("Mirai 适配器已禁用");
            return;
        }
        if (properties.getBotQq() <= 0) {
            log.error("Mirai 适配器未配置 starbot.adapter.mirai.bot-qq");
            return;
        }
        log.info("Mirai 适配器已启用: {}:{}，平台 {}，Bot QQ {}", properties.getHost(), properties.getPort(), properties.getPlatform(), properties.getBotQq());
    }

    private void tryConnect() {
        if (!properties.isEnabled() || properties.getBotQq() <= 0) {
            return;
        }
        try {
            client.connect(properties.getBotQq(), properties.getVerifyKey());
            log.info("Mirai API HTTP 鉴权和 Bot 绑定成功");
        } catch (Exception e) {
            log.error("Mirai API HTTP 鉴权或 Bot 绑定失败，推送将在下次发送时重试: {}", e.getMessage());
        }
    }

    @Around("execution(* com.starlwr.bot.core.sender.StarBotMessageSender.send(..))")
    public Object aroundSend(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        if (args.length == 0 || !(args[0] instanceof Message message) || !shouldHandle(message)) {
            return joinPoint.proceed();
        }
        send(message);
        return null;
    }

    public boolean shouldHandle(Message message) {
        return properties.isEnabled() && message != null && properties.getPlatform().equals(message.getPlatform());
    }

    public void send(Message message) {
        if (!shouldHandle(message)) {
            return;
        }
        sender.execute(() -> {
            try {
                doSend(message);
            } finally {
                try {
                    Thread.sleep(Math.max(0, properties.getDelay()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void doSend(Message message) {
        boolean success = false;
        try {
            for (var interceptor : message.getOnBeforeSendInterceptors()) {
                if (!interceptor.test(message)) {
                    return;
                }
            }
            JSONArray chain = converter.convert(message.getContent());
            if (chain.isEmpty()) {
                throw new IllegalArgumentException("Mirai message is empty");
            }
            String id;
            if (message.getType() == PushTargetType.GROUP) {
                id = client.sendGroupMessage(message.getNum(), chain);
            } else if (message.getType() == PushTargetType.FRIEND) {
                id = client.sendFriendMessage(message.getNum(), chain);
            } else {
                throw new IllegalArgumentException("Unsupported Mirai target type: " + message.getType());
            }
            message.setId(id);
            success = true;
            log.info("StarBot -> Mirai ([{}] {}) [{}]: {}", message.getType().getStr(), message.getNum(), id, message.getDisplay());
        } catch (Exception e) {
            log.error("Mirai 消息发送失败 [{}]: {}", message.getSequence(), e.getMessage());
        }
        message.setCompleteTime(Instant.now());
        runCallbacks(message.getOnCompleteCallbacks());
        runCallbacks(success ? message.getOnSuccessCallbacks() : message.getOnFailureCallbacks());
    }

    private void runCallbacks(List<Runnable> callbacks) {
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("Mirai 消息回调执行失败", e);
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        sender.shutdownNow();
    }
}
