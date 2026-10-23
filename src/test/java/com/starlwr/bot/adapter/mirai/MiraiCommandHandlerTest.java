package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MiraiCommandHandlerTest {
    private final MiraiAdapterProperties properties = new MiraiAdapterProperties();
    private final List<PushUser> users = new ArrayList<>();
    private final List<Long> destinations = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final List<PushTargetType> types = new ArrayList<>();
    private Instant now = Instant.ofEpochSecond(1800000000L);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    };
    private MiraiCommandHandler handler;

    @BeforeEach
    void setUp() {
        properties.setBotQq(123456789L);
        properties.setCommandMode("extended");
        users.add(user(111L, 987654321L));
        users.add(user(222L, 555555555L));
        handler = new MiraiCommandHandler(properties, () -> users, message -> {
            destinations.add(message.getNum());
            replies.add(message.getContent());
            types.add(message.getType());
        }, clock);
    }

    @Test
    void statusRepliesToOriginGroupWithOnlyItsSubscriptions() {
        assertTrue(handler.handle(event("status")));
        assertEquals(List.of(987654321L), destinations);
        assertTrue(replies.get(0).contains("StarBot 3"));
        assertTrue(replies.get(0).contains("本群订阅：1"));
        assertFalse(replies.get(0).contains("555555555"));
    }

    @Test
    void acceptsOwnMentionAndSlashCommand() {
        JSONObject message = event(" /STATUS ");
        message.getJSONArray("messageChain").add(1, JSON.parseObject("{\"type\":\"At\",\"target\":123456789}"));
        assertTrue(handler.handle(message));
    }

    @Test
    void ignoresForeignMentionsEvenWhenAnotherMentionTargetsThisBot() {
        JSONObject message = event("status");
        message.getJSONArray("messageChain").add(JSON.parseObject("{\"type\":\"At\",\"target\":999999999}"));
        message.getJSONArray("messageChain").add(JSON.parseObject("{\"type\":\"At\",\"target\":123456789}"));
        assertFalse(handler.handle(message));
        assertTrue(replies.isEmpty());
    }

    @Test
    void ignoresOtherGroupsSelfMessagesNonGroupEventsAndUnknownCommands() {
        JSONObject otherGroup = event("status");
        otherGroup.getJSONObject("sender").getJSONObject("group").put("id", 444444444L);
        assertFalse(handler.handle(otherGroup));
        JSONObject self = event("status");
        self.getJSONObject("sender").put("id", 123456789L);
        assertFalse(handler.handle(self));
        JSONObject friend = event("status");
        friend.put("type", "FriendMessage");
        assertFalse(handler.handle(friend));
        assertFalse(handler.handle(event("status please")));
        assertTrue(replies.isEmpty());
    }

    @Test
    void ignoresDisabledTargetsAndDisabledReceiver() {
        users.get(0).getTargets().get(0).setEnabled(false);
        assertFalse(handler.handle(event("status")));
        users.get(0).getTargets().get(0).setEnabled(true);
        properties.setReceiveEnabled(false);
        assertFalse(handler.handle(event("status")));
    }

    @Test
    void ignoresStaleMissingSourceAndDuplicateMessages() {
        JSONObject stale = event("status");
        stale.getJSONArray("messageChain").getJSONObject(0).put("time", 1799999900);
        assertFalse(handler.handle(stale));
        JSONObject missingSource = event("status");
        missingSource.getJSONArray("messageChain").remove(0);
        assertFalse(handler.handle(missingSource));
        assertTrue(handler.handle(event("status")));
        assertFalse(handler.handle(event("status")));
        assertEquals(1, replies.size());
    }

    @Test
    void rateLimitsDifferentCommandsInSameGroup() {
        assertTrue(handler.handle(event("status")));
        JSONObject next = event("help");
        next.getJSONArray("messageChain").getJSONObject(0).put("id", 43);
        assertFalse(handler.handle(next));
        assertEquals(1, replies.size());
    }

    @Test
    void rateLimitUsesElapsedMillisecondsAcrossSecondBoundaries() {
        now = Instant.ofEpochSecond(1800000000L, 999000000);
        assertTrue(handler.handle(event("status")));
        now = Instant.ofEpochSecond(1800000003L);
        JSONObject early = event("help");
        early.getJSONArray("messageChain").getJSONObject(0).put("id", 43);
        assertFalse(handler.handle(early));
        now = Instant.ofEpochSecond(1800000004L);
        JSONObject ready = event("help");
        ready.getJSONArray("messageChain").getJSONObject(0).put("id", 44);
        assertTrue(handler.handle(ready));
        assertEquals(2, replies.size());
    }

    @Test
    void helpListsOnlyImplementedCommands() {
        assertTrue(handler.handle(event("帮助")));
        assertTrue(replies.get(0).contains("status"));
        assertTrue(replies.get(0).contains("subscriptions"));
        assertFalse(replies.get(0).contains("排行榜"));
    }

    @Test
    void subscriptionsShowsCurrentGroupsStreamerOnly() {
        assertTrue(handler.handle(event("订阅")));
        assertTrue(replies.get(0).contains("111"));
        assertFalse(replies.get(0).contains("222"));
    }

    @Test
    void nativeStatusUsesOfficialWordingWithoutSubscriptionRequirement() {
        properties.setCommandMode("native");
        users.clear();
        assertTrue(handler.handle(event("STATUS")));
        assertEquals(List.of("Running on StarBot v3.0.0"), replies);
        assertEquals(List.of(PushTargetType.GROUP), types);
    }

    @Test
    void nativeStatusRepliesToFriendSender() {
        properties.setCommandMode("native");
        JSONObject friend = event("status");
        friend.put("type", "FriendMessage");
        friend.getJSONObject("sender").remove("group");
        assertTrue(handler.handle(friend));
        assertEquals(List.of(333333333L), destinations);
        assertEquals(List.of(PushTargetType.FRIEND), types);
        assertEquals(List.of("Running on StarBot v3.0.0"), replies);
    }

    @Test
    void nativeModeDoesNotAddExtendedAliasesOrCooldown() {
        properties.setCommandMode("native");
        assertFalse(handler.handle(event("help")));
        assertFalse(handler.handle(event("状态")));
        assertFalse(handler.handle(event("/status")));
        assertTrue(handler.handle(event("status")));
        JSONObject next = event("status");
        next.getJSONArray("messageChain").getJSONObject(0).put("id", 43);
        assertTrue(handler.handle(next));
        assertEquals(2, replies.size());
    }

    private JSONObject event(String text) {
        JSONObject message = JSON.parseObject("""
            {"type":"GroupMessage","sender":{"id":333333333,"memberName":"Example",
            "permission":"MEMBER","group":{"id":987654321,"name":"Example group","permission":"MEMBER"}},
            "messageChain":[{"type":"Source","id":42,"time":1800000000},{"type":"Plain","text":""}]}
            """);
        message.getJSONArray("messageChain").getJSONObject(1).put("text", text);
        return message;
    }

    private PushUser user(long uid, long group) {
        PushTarget target = new PushTarget();
        target.setPlatform("mirai");
        target.setType(PushTargetType.GROUP);
        target.setNum(group);
        target.setEnabled(true);
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform("bilibili");
        user.setUname("Example " + uid);
        user.setEnabled(true);
        user.getTargets().add(target);
        return user;
    }
}
