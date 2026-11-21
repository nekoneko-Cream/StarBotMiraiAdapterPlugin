package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Mirai transport only; StarBot Core owns message queue and callbacks. */
@Slf4j
@StarBotComponent
public class MiraiAdapterService implements AutoCloseable {
    private static final long AT_ALL_NOTICE_COOLDOWN_MILLIS = 15_000L;
    private static final long MEMBER_RECHECK_MIN_AGE_MILLIS = 2_000L;

    private final MiraiAdapterProperties properties;
    private final MiraiMessageConverter converter;
    private final MiraiApiClient client;
    private final Clock clock;
    private final Map<Long, String> groupPermissions = new ConcurrentHashMap<>();
    private final Map<Long, LocalDate> atAllLimitedDateByGroup = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastAtAllNoticeAtMillis = new ConcurrentHashMap<>();
    private volatile long groupPermissionsFetchedAtMillis;
    private volatile long groupPermissionsBotQq;

    public MiraiAdapterService(MiraiAdapterProperties properties, MiraiMessageConverter converter, MiraiApiClient client) {
        this(properties, converter, client, Clock.systemDefaultZone());
    }

    public MiraiAdapterService(MiraiAdapterProperties properties, MiraiMessageConverter converter,
                               MiraiApiClient client, Clock clock) {
        this.properties = properties;
        this.converter = converter;
        this.client = client;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Autowired
    public MiraiAdapterService(MiraiAdapterProperties properties, MiraiMessageConverter converter, RestTemplate http) {
        this(properties, converter, new MiraiApiClient(properties.getBaseUrl(), http), Clock.systemDefaultZone());
        this.client.configure(properties.getBotQq(), properties.getVerifyKey(), properties.isAutoFollowOnlineBot());
    }

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) return;
        if (properties.getBotQq() <= 0 && !properties.isAutoFollowOnlineBot()) return;
        try {
            client.connect(properties.getBotQq(), properties.getVerifyKey(), properties.isAutoFollowOnlineBot());
            if (client.isAutoFollowed()) {
                if (properties.getBotQq() > 0) {
                    log.info("配置的 Mirai Bot 当前不在线，已自动跟随并绑定当前唯一在线 Bot");
                } else {
                    log.info("已自动发现并绑定 Mirai 当前唯一在线 Bot");
                }
            } else {
                log.info("Mirai API HTTP 鉴权和 Bot 绑定成功");
            }
            refreshGroupPermissionsSafe();
        } catch (Exception e) {
            log.error("Mirai 鉴权或绑定失败，下次请求将重试：{}", MiraiApiClient.describeFailure(e));
        }
    }

    public JSONObject send(String platform, int type, long target, String content) {
        if (!properties.isEnabled() || !properties.getPlatform().equals(platform)) return failure("Mirai 平台不存在或已禁用");
        if (target <= 0 || content == null) return failure("Mirai 消息目标或内容无效");
        try {
            JSONArray chain = converter.convert(content);
            if (chain.isEmpty()) return failure("Mirai 消息为空");
            return switch (PushTargetType.of(type)) {
                case GROUP -> sendGroupWithProtection(target, chain);
                case FRIEND -> {
                    String id = client.sendFriendMessage(target, chain);
                    yield success(id);
                }
                default -> throw new IllegalArgumentException("Unsupported target type");
            };
        } catch (Exception e) {
            String reason = MiraiApiClient.describeFailure(e);
            log.warn("Mirai 发送接口异常：{}", reason);
            return failure("Mirai 消息发送失败：" + reason);
        }
    }

    private JSONObject sendGroupWithProtection(long target, JSONArray rawChain) {
        if (!containsAtAll(rawChain)) {
            String id = client.sendGroupMessage(target, rawChain);
            if (properties.isVerifyGroupReceipt() && isUnconfirmedMessageId(id)) {
                log.warn("Mirai 群消息未收到服务端回执 (messageId=-1)，消息可能因被禁言、群聊风控或网络波动被服务端静默丢弃");
                return failure("Mirai 群消息未收到服务端回执，可能被禁言或触发群聊风控");
            }
            return success(id);
        }

        JSONArray chain = deduplicateAtAll(rawChain);
        LocalDate today = LocalDate.now(clock);

        if (today.equals(atAllLimitedDateByGroup.get(target))) {
            if (properties.isAtAllFallbackEnabled()) {
                log.warn("目标群今日 @全体成员 次数已达上限，已自动移除 @全体成员 并回退为普通消息发送");
                return sendAtAllFallback(target, chain, properties.getAtAllLimitedMessage());
            }
            return failure("Mirai 消息发送失败：今日 @全体成员 次数已达上限");
        }

        if (properties.isAtAllFallbackEnabled()) {
            String permission = resolveGroupPermission(target);
            if ("MEMBER".equals(permission)) {
                log.warn("机器人在目标群无管理员权限（当前：MEMBER），已自动移除 @全体成员 并回退为普通消息发送");
                return sendAtAllFallback(target, chain, properties.getAtAllNoPermissionMessage());
            }
        }

        try {
            String id = client.sendGroupMessage(target, chain);
            if (properties.isVerifyGroupReceipt() && isUnconfirmedMessageId(id)) {
                String refreshedPerm = refreshAndLookupPermission(target);
                boolean noPerm = "MEMBER".equals(refreshedPerm);
                String notice = noPerm ? properties.getAtAllNoPermissionMessage() : properties.getAtAllLimitedMessage();
                if (properties.isAtAllFallbackEnabled()) {
                    if (noPerm) {
                        log.warn("含 @全体成员 的群消息未收到服务端回执 (messageId=-1)，复核确认当前无管理员权限，正在自动移除 @全体成员 后重发普通消息");
                    } else {
                        log.warn("含 @全体成员 的群消息未收到服务端回执 (messageId=-1)，疑似无管理员权限、配额受限或风控被静默丢弃，正在自动移除 @全体成员 后重发普通消息");
                    }
                    return sendAtAllFallback(target, chain, notice);
                }
                return failure("Mirai 群消息未收到服务端回执，可能因无 @全体成员 权限或配额不足被服务端静默丢弃");
            }
            return success(id);
        } catch (MiraiApiClient.MiraiApiException apiError) {
            if (apiError.isPermissionDenied()) {
                groupPermissions.put(target, "MEMBER");
                if (properties.isAtAllFallbackEnabled()) {
                    log.warn("Mirai 返回无管理员权限错误 (code=10)，已自动移除 @全体成员 并回退为普通消息发送");
                    return sendAtAllFallback(target, chain, properties.getAtAllNoPermissionMessage());
                }
            } else if (apiError.isAtAllLimited()) {
                atAllLimitedDateByGroup.put(target, today);
                if (properties.isAtAllFallbackEnabled()) {
                    log.warn("Mirai 返回今日 @全体成员 次数已达上限，已自动移除 @全体成员 并回退为普通消息发送");
                    return sendAtAllFallback(target, chain, properties.getAtAllLimitedMessage());
                }
            }
            throw apiError;
        }
    }

    private JSONObject sendAtAllFallback(long target, JSONArray originalChain, String noticeMessage) {
        JSONArray fallbackChain = stripAtAll(originalChain);
        String resultId = null;
        if (!fallbackChain.isEmpty()) {
            resultId = client.sendGroupMessage(target, fallbackChain);
            if (properties.isVerifyGroupReceipt() && isUnconfirmedMessageId(resultId)) {
                log.warn("回退普通群消息仍未收到服务端回执 (messageId=-1)，消息可能因被禁言或风控被服务端静默丢弃");
                return failure("回退普通群消息未收到服务端回执，可能被禁言或触发群聊风控");
            }
        }

        String noticeId = sendAtAllNoticeIfDue(target, noticeMessage);
        if (resultId == null) {
            resultId = noticeId != null ? noticeId : "atall-fallback-skipped";
        }
        return success(resultId);
    }

    private String sendAtAllNoticeIfDue(long target, String noticeMessage) {
        if (noticeMessage == null || noticeMessage.isBlank()) {
            return null;
        }
        long now = clock.millis();
        Long last = lastAtAllNoticeAtMillis.get(target);
        if (last != null && now - last < AT_ALL_NOTICE_COOLDOWN_MILLIS) {
            return null;
        }
        lastAtAllNoticeAtMillis.put(target, now);
        try {
            JSONArray noticeChain = new JSONArray();
            noticeChain.add(new JSONObject().fluentPut("type", "Plain").fluentPut("text", noticeMessage));
            String id = client.sendGroupMessage(target, noticeChain);
            if (properties.isVerifyGroupReceipt() && isUnconfirmedMessageId(id)) {
                log.warn("无法 @全体成员 的群提示消息未收到服务端回执 (messageId=-1)");
                return null;
            }
            return id;
        } catch (Exception e) {
            log.warn("发送无法 @全体成员 提示消息失败：{}", MiraiApiClient.describeFailure(e));
            return null;
        }
    }

    private String resolveGroupPermission(long groupId) {
        long activeQq = getActiveBotQq();
        long ttlMillis = Math.max(0, properties.getGroupPermissionCacheSeconds()) * 1000L;
        long now = clock.millis();
        long age = now - groupPermissionsFetchedAtMillis;
        boolean botChanged = activeQq > 0 && groupPermissionsBotQq != activeQq;
        boolean expired = groupPermissionsFetchedAtMillis == 0 || ttlMillis == 0 || age >= ttlMillis;

        if (botChanged || expired || !groupPermissions.containsKey(groupId)) {
            refreshGroupPermissionsSafe();
        } else if ("MEMBER".equals(groupPermissions.get(groupId)) && age >= MEMBER_RECHECK_MIN_AGE_MILLIS) {
            // Re-verify MEMBER status in case the group owner just promoted the Bot.
            refreshGroupPermissionsSafe();
        }
        return groupPermissions.get(groupId);
    }

    private String refreshAndLookupPermission(long groupId) {
        refreshGroupPermissionsSafe();
        return groupPermissions.get(groupId);
    }

    private void refreshGroupPermissionsSafe() {
        Map<Long, String> fetched = client.fetchGroupPermissionsSafe();
        if (fetched == null) {
            return;
        }
        groupPermissions.clear();
        groupPermissions.putAll(fetched);
        groupPermissionsBotQq = getActiveBotQq();
        groupPermissionsFetchedAtMillis = clock.millis();
    }

    public void onBotGroupPermissionChanged(long groupId, String currentPermission) {
        if (groupId <= 0 || currentPermission == null || currentPermission.isBlank()) return;
        String normalized = currentPermission.strip().toUpperCase(Locale.ROOT);
        groupPermissions.put(groupId, normalized);
        groupPermissionsBotQq = getActiveBotQq();
        groupPermissionsFetchedAtMillis = clock.millis();
        log.info("已同步机器人群权限变更（当前：{}）", normalized);
    }

    public void onBotJoinedGroup(long groupId, String permission) {
        if (groupId <= 0) return;
        if (permission != null && !permission.isBlank()) {
            groupPermissions.put(groupId, permission.strip().toUpperCase(Locale.ROOT));
            groupPermissionsBotQq = getActiveBotQq();
            groupPermissionsFetchedAtMillis = clock.millis();
        } else {
            groupPermissionsFetchedAtMillis = 0;
        }
        log.info("检测到机器人加入新群，已刷新群权限缓存");
    }

    public void onBotLeftGroup(long groupId) {
        if (groupId <= 0) return;
        groupPermissions.remove(groupId);
        atAllLimitedDateByGroup.remove(groupId);
        lastAtAllNoticeAtMillis.remove(groupId);
    }

    static boolean containsAtAll(JSONArray chain) {
        if (chain == null) return false;
        for (int i = 0; i < chain.size(); i++) {
            JSONObject elem = chain.getJSONObject(i);
            if (elem != null && "AtAll".equals(elem.getString("type"))) {
                return true;
            }
        }
        return false;
    }

    static JSONArray deduplicateAtAll(JSONArray chain) {
        JSONArray result = new JSONArray();
        boolean seenAtAll = false;
        for (int i = 0; i < chain.size(); i++) {
            JSONObject elem = chain.getJSONObject(i);
            if (elem == null) continue;
            if ("AtAll".equals(elem.getString("type"))) {
                if (seenAtAll) continue;
                seenAtAll = true;
            }
            result.add(elem);
        }
        return result;
    }

    static JSONArray stripAtAll(JSONArray chain) {
        JSONArray result = new JSONArray();
        boolean afterAtAll = false;
        for (int i = 0; i < chain.size(); i++) {
            JSONObject elem = chain.getJSONObject(i);
            if (elem == null) continue;
            String type = elem.getString("type");
            if ("AtAll".equals(type)) {
                afterAtAll = true;
                continue;
            }
            if (afterAtAll && "Plain".equals(type)) {
                afterAtAll = false;
                String text = elem.getString("text");
                if (text != null) {
                    String trimmed = text.stripLeading();
                    if (!trimmed.isEmpty()) {
                        result.add(new JSONObject().fluentPut("type", "Plain").fluentPut("text", trimmed));
                    }
                }
                continue;
            }
            afterAtAll = false;
            result.add(elem);
        }
        return result;
    }

    static boolean isUnconfirmedMessageId(String id) {
        return id != null && "-1".equals(id.strip());
    }

    static JSONObject success(String id) {
        return new JSONObject().fluentPut("code", 0).fluentPut("message", "success").fluentPut("id", id);
    }

    static JSONObject failure(String reason) {
        return new JSONObject().fluentPut("code", 1).fluentPut("message", reason).fluentPut("id", null);
    }

    public JSONArray fetchMessages(int count) {
        return client.fetchMessages(count);
    }

    public JSONObject checkConnection() {
        return client.checkConnection();
    }

    public long getActiveBotQq() {
        long active = client.getActiveBotQq();
        return active > 0 ? active : properties.getBotQq();
    }

    public long resolveActiveBotQq() {
        return client.resolveActiveBotQq(properties.getBotQq(), properties.getVerifyKey(), properties.isAutoFollowOnlineBot());
    }

    public List<Long> fetchOnlineBots() {
        return client.fetchOnlineBotsSafe();
    }

    public void invalidateSession() {
        client.invalidateSession();
        groupPermissions.clear();
        atAllLimitedDateByGroup.clear();
        lastAtAllNoticeAtMillis.clear();
        groupPermissionsFetchedAtMillis = 0;
        groupPermissionsBotQq = 0;
    }

    public String fetchApiVersion() {
        try {
            return client.fetchVersion();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("释放 Mirai 会话失败：{}", MiraiApiClient.describeFailure(e));
        }
    }
}
