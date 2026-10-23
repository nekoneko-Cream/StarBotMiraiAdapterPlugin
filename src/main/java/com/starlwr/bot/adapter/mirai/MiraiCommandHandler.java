package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.Message;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Read-only commands scoped to enabled Mirai group subscriptions. */
public final class MiraiCommandHandler {
    private final MiraiAdapterProperties properties;
    private final Supplier<List<PushUser>> users;
    private final Consumer<Message> reply;
    private final Clock clock;
    private final Map<String, Boolean> seen = new LinkedHashMap<>();
    private final Map<Long, Long> lastReply = new LinkedHashMap<>();

    public MiraiCommandHandler(MiraiAdapterProperties properties, Supplier<List<PushUser>> users,
                               Consumer<Message> reply, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.reply = reply;
        this.clock = clock;
    }

    public synchronized boolean handle(JSONObject event) {
        if (!properties.isEnabled() || !properties.isReceiveEnabled() || event == null
            || !("GroupMessage".equals(event.getString("type")) || "FriendMessage".equals(event.getString("type")))) {
            return false;
        }
        JSONObject sender = event.getJSONObject("sender");
        JSONObject group = sender == null ? null : sender.getJSONObject("group");
        Long groupId = group == null ? null : group.getLong("id");
        Long senderId = sender == null ? null : sender.getLong("id");
        boolean groupMessage = "GroupMessage".equals(event.getString("type"));
        if ((groupMessage && groupId == null) || (!groupMessage && group != null)
            || senderId == null || senderId == properties.getBotQq()) {
            return false;
        }
        boolean extended = "extended".equalsIgnoreCase(properties.getCommandMode());
        List<PushUser> subscribed = !extended || !groupMessage ? List.of() : users.get().stream()
            .filter(user -> Boolean.TRUE.equals(user.getEnabled()))
            .filter(user -> user.getTargets().stream().anyMatch(target ->
                Boolean.TRUE.equals(target.getEnabled()) && properties.getPlatform().equals(target.getPlatform())
                && target.getType() == PushTargetType.GROUP && groupId.equals(target.getNum())))
            .toList();
        if (extended && groupMessage && subscribed.isEmpty()) {
            return false;
        }
        JSONArray chain = event.getJSONArray("messageChain");
        if (chain == null) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        JSONObject source = null;
        for (int i = 0; i < chain.size(); i++) {
            JSONObject element = chain.getJSONObject(i);
            if (element == null) {
                return false;
            }
            String type = element.getString("type");
            if ("Source".equals(type)) {
                source = element;
            } else if ("Plain".equals(type)) {
                String plain = element.getString("text");
                if (plain != null) text.append(plain);
            } else if ("At".equals(type)) {
                if (!Long.valueOf(properties.getBotQq()).equals(element.getLong("target"))) return false;
            } else if (!"Quote".equals(type)) {
                return false;
            }
        }
        long now = clock.instant().getEpochSecond();
        Long time = source == null ? null : source.getLong("time");
        Long messageId = source == null ? null : source.getLong("id");
        if (time == null || messageId == null || now - time > 60 || time - now > 10) return false;
        String command = text.toString().strip().toLowerCase(Locale.ROOT);
        if (extended && command.startsWith("/")) command = command.substring(1);
        String response = !extended ? ("status".equals(command) ? "Running on StarBot v3.0.0" : null) : switch (command) {
            case "status", "状态" -> "StarBot 3\n连接：Mirai HTTP 已连接\n本群订阅：" + subscribed.size()
                + " 位主播\n群消息指令：已启用";
            case "help", "帮助", "菜单" -> "StarBot 3 指令\nstatus / 状态：运行状态\nsubscriptions / 订阅 / 主播列表：本群订阅\nversion / 版本：版本信息\nhelp / 帮助：本菜单\n支持直接发送，或 @本机器人 后发送。";
            case "version", "版本" -> "StarBot 3 · MiraiAdapter 1.1.0-beta.1";
            case "subscriptions", "订阅", "主播列表" -> subscriptions(subscribed);
            default -> null;
        };
        if (response == null) return false;
        Long targetId = groupMessage ? groupId : senderId;
        String key = event.getString("type") + ":" + targetId + ":" + senderId + ":" + messageId + ":" + time;
        if (seen.containsKey(key)) return false;
        seen.put(key, true);
        if (seen.size() > 512) seen.remove(seen.keySet().iterator().next());
        Long previous = lastReply.get(targetId);
        long nowMillis = clock.millis();
        if (extended && previous != null && nowMillis - previous < 3000) return false;
        lastReply.put(targetId, nowMillis);
        if (lastReply.size() > 512) lastReply.remove(lastReply.keySet().iterator().next());
        Message.create(properties.getPlatform(), groupMessage ? PushTargetType.GROUP : PushTargetType.FRIEND,
            targetId, response).forEach(reply);
        return true;
    }

    private String subscriptions(List<PushUser> subscribed) {
        StringBuilder result = new StringBuilder("本群订阅：" + subscribed.size() + " 位主播");
        subscribed.stream().limit(20).forEach(user -> result.append('\n').append(user.getUname())
            .append(" (UID: ").append(user.getUid()).append(')'));
        if (subscribed.size() > 20) result.append("\n仅显示前 20 位");
        return result.toString();
    }
}
