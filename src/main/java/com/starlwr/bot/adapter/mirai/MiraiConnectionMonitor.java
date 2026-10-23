package com.starlwr.bot.adapter.mirai;

import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.StarBotMailService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Optional detectors, separated from the receive loop as in the official adapter. */
@Slf4j
@StarBotComponent
public class MiraiConnectionMonitor implements AutoCloseable {
    private final MiraiAdapterProperties properties;
    private final Runnable connectionCheck;
    private final Supplier<Instant> lastMessage;
    private final BiConsumer<String, String> alarm;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "monitor-mirai");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean connectionFailed;
    private volatile boolean messageIdle;
    private Instant lastHttpAlarm;
    private Instant lastMessageAlarm;
    private boolean started;

    public MiraiConnectionMonitor(MiraiAdapterProperties properties, Runnable connectionCheck,
            Supplier<Instant> lastMessage, BiConsumer<String, String> alarm, Clock clock) {
        this.properties = properties;
        this.connectionCheck = connectionCheck;
        this.lastMessage = lastMessage;
        this.alarm = alarm;
        this.clock = clock;
    }

    @Autowired
    public MiraiConnectionMonitor(MiraiAdapterProperties properties, MiraiAdapterService adapter,
            MiraiGroupMessageService receiver, StarBotMailService mailService) {
        this(properties, () -> adapter.checkConnection(), receiver::getLastMessageTime, mailService::sendMail, Clock.systemUTC());
    }

    @Order(100)
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started || !properties.isEnabled()) return;
        started = true;
        var detect = properties.getDetect();
        if (detect.isEnableHttpDetect()) scheduler.scheduleWithFixedDelay(this::checkHttp,
            Math.max(1, detect.getHttpDetectInterval()), Math.max(1, detect.getHttpDetectInterval()), TimeUnit.SECONDS);
        if (detect.isEnableMessageDetect() && properties.isReceiveEnabled()) scheduler.scheduleWithFixedDelay(this::checkMessages,
            Math.max(1, detect.getMessageDetectInterval()), Math.max(1, detect.getMessageDetectInterval()), TimeUnit.SECONDS);
    }

    public void check() {
        checkHttp();
        checkMessages();
    }

    private synchronized void checkHttp() {
        if (!properties.isEnabled() || !properties.getDetect().isEnableHttpDetect()) return;
        try {
            connectionCheck.run();
            if (connectionFailed) log.info("Mirai HTTP 与 Bot 绑定检测已恢复");
            connectionFailed = false;
        } catch (Exception e) {
            if (!connectionFailed) log.warn("Mirai HTTP 或 Bot 绑定检测失败（{}）", e.getClass().getSimpleName());
            connectionFailed = true;
            if (due(lastHttpAlarm, properties.getDetect().getHttpAlarmMailInterval())) {
                lastHttpAlarm = clock.instant();
                notifyAlarm("StarBot Mirai 服务异常告警", "Mirai HTTP 或 Bot 绑定/资料接口检测失败，请检查 MCL 与 QQ 登录状态。");
            }
        }
    }

    private synchronized void checkMessages() {
        var detect = properties.getDetect();
        if (!properties.isEnabled() || !properties.isReceiveEnabled() || !detect.isEnableMessageDetect()) return;
        Instant last = lastMessage.get();
        boolean idle = last == null || !clock.instant().isBefore(last.plusSeconds(Math.max(1, detect.getMessageDetectInterval())));
        if (idle && !messageIdle) log.warn("Mirai 在配置的检测周期内没有收到消息，请检查连接或群活跃情况");
        if (!idle && messageIdle) log.info("Mirai 消息接收检测已恢复");
        messageIdle = idle;
        if (idle && due(lastMessageAlarm, detect.getMessageAlarmMailInterval())) {
            lastMessageAlarm = clock.instant();
            notifyAlarm("StarBot Mirai 消息接收异常告警", "Mirai 在配置周期内没有收到实际消息；空轮询不会重置此检测，请检查连接或群活跃情况。");
        }
    }

    private boolean due(Instant previous, int seconds) {
        return previous == null || !clock.instant().isBefore(previous.plusSeconds(Math.max(1, seconds)));
    }

    private void notifyAlarm(String title, String detail) {
        try {
            alarm.accept(title, detail);
        } catch (Exception e) {
            log.warn("Mirai 告警调用失败（{}）", e.getClass().getSimpleName());
        }
    }

    public boolean isConnectionFailed() { return connectionFailed; }
    public boolean isMessageIdle() { return messageIdle; }

    @Override
    @PreDestroy
    public void close() { scheduler.shutdownNow(); }
}
