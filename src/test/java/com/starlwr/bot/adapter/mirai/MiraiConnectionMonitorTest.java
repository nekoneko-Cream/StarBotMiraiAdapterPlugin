package com.starlwr.bot.adapter.mirai;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MiraiConnectionMonitorTest {
    private final MiraiAdapterProperties properties = new MiraiAdapterProperties();
    private final AtomicBoolean online = new AtomicBoolean();
    private final List<String> alarms = new ArrayList<>();
    private Instant now = Instant.ofEpochSecond(1800000000);
    private Instant lastMessage = now;
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    };

    private MiraiConnectionMonitor monitor() {
        return new MiraiConnectionMonitor(properties, () -> {
            if (!online.get()) throw new IllegalStateException("offline");
        }, () -> lastMessage, (title, detail) -> alarms.add(title), clock);
    }

    @Test
    void detectorsDefaultOffAndDoNotSendAlarms() {
        monitor().check();
        assertTrue(alarms.isEmpty());
    }

    @Test
    void connectionAlarmsAreThrottledAndRecoveryClearsFailureState() {
        properties.getDetect().setEnableHttpDetect(true);
        properties.getDetect().setHttpAlarmMailInterval(10);
        MiraiConnectionMonitor monitor = monitor();
        monitor.check();
        monitor.check();
        assertEquals(1, alarms.size());
        now = now.plusSeconds(10);
        monitor.check();
        assertEquals(2, alarms.size());
        online.set(true);
        monitor.check();
        assertFalse(monitor.isConnectionFailed());
    }

    @Test
    void idleAlarmUsesLastActualMessageAndResetsAfterMessage() {
        properties.getDetect().setEnableMessageDetect(true);
        properties.getDetect().setMessageDetectInterval(30);
        MiraiConnectionMonitor monitor = monitor();
        now = now.plusSeconds(31);
        monitor.check();
        assertEquals(1, alarms.size());
        lastMessage = now;
        monitor.check();
        assertFalse(monitor.isMessageIdle());
    }

    @Test
    void alarmFailureDoesNotPreventFutureMonitoring() {
        properties.getDetect().setEnableHttpDetect(true);
        MiraiConnectionMonitor monitor = new MiraiConnectionMonitor(properties,
            () -> { throw new IllegalStateException(); }, () -> lastMessage,
            (title, detail) -> { throw new IllegalStateException("mail unavailable"); }, clock);
        assertDoesNotThrow(monitor::check);
        assertTrue(monitor.isConnectionFailed());
    }
}
