package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@StarBotComponent
public class MiraiGroupMessageService implements AutoCloseable {
    private final MiraiAdapterProperties properties;
    private final MiraiAdapterService adapter;
    private final MiraiCommandHandler commands;
    private final ScheduledExecutorService receiver = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "receiver-mirai");
        thread.setDaemon(true);
        return thread;
    });
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
        receiver.scheduleWithFixedDelay(this::poll, 0, Math.max(200, properties.getPollInterval()), TimeUnit.MILLISECONDS);
        log.info("Mirai 消息接收已启用，命令模式 {}", properties.getCommandMode());
    }

    void poll() {
        if (!properties.isEnabled() || !properties.isReceiveEnabled()) return;
        try {
            JSONArray events = adapter.fetchMessages(20);
            if (failed) log.info("Mirai 群消息接收已恢复");
            failed = false;
            for (int i = 0; i < events.size(); i++) {
                try {
                    var event = events.getJSONObject(i);
                    if (event != null && event.getString("type") != null && event.getString("type").endsWith("Message")) {
                        lastMessageTime = Instant.now();
                    }
                    if (commands.handle(event)) {
                        log.info("已处理 Mirai {} 指令", event.getString("type"));
                    }
                } catch (Exception e) {
                    log.warn("已跳过无法处理的 Mirai 群消息：{}", e.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            if (!failed) log.warn("Mirai 群消息接收失败，将继续重试（{}）", e.getClass().getSimpleName());
            failed = true;
        }
    }

    public Instant getLastMessageTime() {
        return lastMessageTime;
    }

    @Override
    @PreDestroy
    public void close() {
        receiver.shutdownNow();
    }
}
