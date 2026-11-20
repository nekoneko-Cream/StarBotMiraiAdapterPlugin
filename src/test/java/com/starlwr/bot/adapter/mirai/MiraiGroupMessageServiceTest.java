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
}
