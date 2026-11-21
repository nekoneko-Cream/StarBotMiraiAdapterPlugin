package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MiraiGroupMessageServiceTest {

    @Test
    void defaultsUseFastDelayPollAndEnableWebsocket() {
        MiraiAdapterProperties props = new MiraiAdapterProperties();
        assertThat(props.getDelay()).isEqualTo(200);
        assertThat(props.getPollInterval()).isEqualTo(200);
        assertThat(props.isWebsocketEnabled()).isTrue();
        assertThat(props.isAutoFollowOnlineBot()).isTrue();
    }

    @Test
    void startsWithoutConfiguredBotQqOnlyWhenAutoFollowEnabled() {
        MiraiAdapterProperties strictProps = new MiraiAdapterProperties();
        strictProps.setBotQq(0L);
        strictProps.setAutoFollowOnlineBot(false);
        strictProps.setWebsocketEnabled(false);
        MiraiAdapterService adapter = mock(MiraiAdapterService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        StarBotMessageSender sender = mock(StarBotMessageSender.class);

        try (MiraiGroupMessageService strictService = new MiraiGroupMessageService(strictProps, adapter, dataSource, sender)) {
            strictService.start();
            assertThat(strictService.isStarted()).isFalse();
        }

        MiraiAdapterProperties autoProps = new MiraiAdapterProperties();
        autoProps.setBotQq(0L);
        autoProps.setAutoFollowOnlineBot(true);
        autoProps.setWebsocketEnabled(false);
        try (MiraiGroupMessageService autoService = new MiraiGroupMessageService(autoProps, adapter, dataSource, sender)) {
            autoService.start();
            assertThat(autoService.isStarted()).isTrue();
        }
    }

    @Test
    void invalidatesSessionOnWebSocketErrorFrame() {
        MiraiAdapterProperties props = new MiraiAdapterProperties();
        props.setBotQq(10001L);
        MiraiAdapterService adapter = mock(MiraiAdapterService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        StarBotMessageSender sender = mock(StarBotMessageSender.class);

        try (MiraiGroupMessageService service = new MiraiGroupMessageService(props, adapter, dataSource, sender)) {
            service.handleWebSocketPayload("{\"syncId\":\"\",\"data\":{\"code\":2,\"msg\":\"指定的Bot不存在\"}}");
            verify(adapter, times(1)).invalidateSession();
            assertThat(service.isWebSocketConnected()).isFalse();
        }
    }

    @Test
    void detectsLiveBotSwitchAndInvalidatesSession() {
        MiraiAdapterProperties props = new MiraiAdapterProperties();
        props.setBotQq(10001L);
        props.setAutoFollowOnlineBot(true);
        MiraiAdapterService adapter = mock(MiraiAdapterService.class);
        when(adapter.getActiveBotQq()).thenReturn(10001L);
        when(adapter.fetchOnlineBots()).thenReturn(List.of(20002L));
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        StarBotMessageSender sender = mock(StarBotMessageSender.class);

        try (MiraiGroupMessageService service = new MiraiGroupMessageService(props, adapter, dataSource, sender)) {
            service.checkActiveBotSwitch();
            verify(adapter, times(1)).invalidateSession();
        }
    }

    @Test
    void matchesAtMentionAgainstAutoFollowedActiveBotQq() {
        MiraiAdapterProperties props = new MiraiAdapterProperties();
        props.setBotQq(10001L);
        MiraiAdapterService adapter = mock(MiraiAdapterService.class);
        when(adapter.getActiveBotQq()).thenReturn(20002L);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());
        StarBotMessageSender sender = mock(StarBotMessageSender.class);

        try (MiraiGroupMessageService service = new MiraiGroupMessageService(props, adapter, dataSource, sender)) {
            long nowSec = Instant.now().getEpochSecond();
            JSONObject oldMention = new JSONObject()
                .fluentPut("syncId", "")
                .fluentPut("data", new JSONObject()
                    .fluentPut("type", "GroupMessage")
                    .fluentPut("sender", new JSONObject()
                        .fluentPut("id", 50005L)
                        .fluentPut("group", new JSONObject().fluentPut("id", 30003L)))
                    .fluentPut("messageChain", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("type", "Source").fluentPut("id", 78).fluentPut("time", nowSec))
                        .fluentAdd(new JSONObject().fluentPut("type", "At").fluentPut("target", 10001L))
                        .fluentAdd(new JSONObject().fluentPut("type", "Plain").fluentPut("text", " status"))));
            service.handleWebSocketPayload(oldMention.toJSONString());
            verify(sender, never()).send(any(Message.class));

            JSONObject newMention = new JSONObject()
                .fluentPut("syncId", "")
                .fluentPut("data", new JSONObject()
                    .fluentPut("type", "GroupMessage")
                    .fluentPut("sender", new JSONObject()
                        .fluentPut("id", 50005L)
                        .fluentPut("group", new JSONObject().fluentPut("id", 30003L)))
                    .fluentPut("messageChain", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("type", "Source").fluentPut("id", 79).fluentPut("time", nowSec))
                        .fluentAdd(new JSONObject().fluentPut("type", "At").fluentPut("target", 20002L))
                        .fluentAdd(new JSONObject().fluentPut("type", "Plain").fluentPut("text", " status"))));
            service.handleWebSocketPayload(newMention.toJSONString());
            verify(sender, times(1)).send(any(Message.class));
        }
    }

    @Test
    void handlesWrappedWebSocketGroupStatusFrame() {
        MiraiAdapterProperties props = new MiraiAdapterProperties();
        props.setBotQq(10001L);
        props.setWebsocketEnabled(true);
        MiraiAdapterService adapter = mock(MiraiAdapterService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());
        StarBotMessageSender sender = mock(StarBotMessageSender.class);

        try (MiraiGroupMessageService service = new MiraiGroupMessageService(props, adapter, dataSource, sender)) {
            long nowSec = Instant.now().getEpochSecond();
            JSONObject payload = new JSONObject()
                .fluentPut("syncId", "")
                .fluentPut("data", new JSONObject()
                    .fluentPut("type", "GroupMessage")
                    .fluentPut("sender", new JSONObject()
                        .fluentPut("id", 20002L)
                        .fluentPut("group", new JSONObject().fluentPut("id", 30003L)))
                    .fluentPut("messageChain", new JSONArray()
                        .fluentAdd(new JSONObject().fluentPut("type", "Source").fluentPut("id", 77).fluentPut("time", nowSec))
                        .fluentAdd(new JSONObject().fluentPut("type", "Plain").fluentPut("text", "status"))));

            service.handleWebSocketPayload(payload.toJSONString());

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(sender, times(1)).send(captor.capture());
            assertThat(captor.getValue().getContent()).isEqualTo("Running on StarBot v3.0.0");
            assertThat(captor.getValue().getNum()).isEqualTo(30003L);
        }
    }

    @Test
    void fallsBackToHttpPollingWhenWebsocketDisabled() {
        MiraiAdapterProperties props = new MiraiAdapterProperties();
        props.setBotQq(10001L);
        props.setWebsocketEnabled(false);
        MiraiAdapterService adapter = mock(MiraiAdapterService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());
        StarBotMessageSender sender = mock(StarBotMessageSender.class);

        long nowSec = Instant.now().getEpochSecond();
        JSONArray events = new JSONArray().fluentAdd(new JSONObject()
            .fluentPut("type", "GroupMessage")
            .fluentPut("sender", new JSONObject()
                .fluentPut("id", 20002L)
                .fluentPut("group", new JSONObject().fluentPut("id", 40004L)))
            .fluentPut("messageChain", new JSONArray()
                .fluentAdd(new JSONObject().fluentPut("type", "Source").fluentPut("id", 88).fluentPut("time", nowSec))
                .fluentAdd(new JSONObject().fluentPut("type", "Plain").fluentPut("text", "status"))));
        when(adapter.fetchMessages(20)).thenReturn(events);

        try (MiraiGroupMessageService service = new MiraiGroupMessageService(props, adapter, dataSource, sender)) {
            service.poll();
            verify(adapter, times(1)).fetchMessages(20);
            verify(sender, times(1)).send(any(Message.class));
            assertThat(service.isWebSocketConnected()).isFalse();
        }
    }

    @Test
    void forwardsBotPermissionAndMembershipEventsToAdapter() {
        MiraiAdapterProperties props = new MiraiAdapterProperties();
        props.setBotQq(10001L);
        MiraiAdapterService adapter = mock(MiraiAdapterService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        StarBotMessageSender sender = mock(StarBotMessageSender.class);

        try (MiraiGroupMessageService service = new MiraiGroupMessageService(props, adapter, dataSource, sender)) {
            service.handleWebSocketPayload("""
                {"syncId":"","data":{"type":"BotGroupPermissionChangeEvent","origin":"MEMBER","current":"ADMINISTRATOR","group":{"id":30003,"name":"Test","permission":"ADMINISTRATOR"}}}
                """);
            service.handleWebSocketPayload("""
                {"syncId":"","data":{"type":"BotJoinGroupEvent","group":{"id":40004,"name":"New","permission":"MEMBER"}}}
                """);
            service.handleWebSocketPayload("""
                {"syncId":"","data":{"type":"BotLeaveEventKick","group":{"id":40004,"name":"New","permission":"MEMBER"}}}
                """);
            verify(adapter, times(1)).onBotGroupPermissionChanged(30003L, "ADMINISTRATOR");
            verify(adapter, times(1)).onBotJoinedGroup(40004L, "MEMBER");
            verify(adapter, times(1)).onBotLeftGroup(40004L);
        }
    }
}
