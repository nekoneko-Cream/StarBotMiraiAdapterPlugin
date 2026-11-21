package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Minimal client for Mirai API HTTP 2.x. */
public final class MiraiApiClient implements AutoCloseable {
    public static final class MiraiApiException extends IllegalStateException {
        private final int code;
        private final String apiMessage;
        private final String operation;

        public MiraiApiException(String operation, Integer code, String apiMessage) {
            super("Mirai " + operation + " failed: code=" + code + ", msg=" + apiMessage);
            this.code = code == null ? -1 : code;
            this.apiMessage = apiMessage;
            this.operation = operation;
        }

        public int getCode() {
            return code;
        }

        public String getApiMessage() {
            return apiMessage;
        }

        public String getOperation() {
            return operation;
        }

        public boolean isPermissionDenied() {
            return code == 10;
        }

        public boolean isBotMuted() {
            return code == 20;
        }

        public boolean isAtAllLimited() {
            return apiMessage != null && apiMessage.contains("AT_ALL_LIMITED");
        }

        public boolean isGroupChatLimited() {
            return apiMessage != null && apiMessage.contains("GROUP_CHAT_LIMITED");
        }

        public boolean isLimitedMessaging() {
            return apiMessage != null && apiMessage.contains("LIMITED_MESSAGING");
        }
    }
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final String baseUrl;
    private final RestTemplate http;
    private volatile String sessionKey;
    private volatile long activeBotQq;
    private volatile boolean autoFollowed;
    private boolean closed;
    private long configuredBotQq;
    private String verifyKey;
    private boolean autoFollowOnlineBot;

    public MiraiApiClient(String baseUrl, RestTemplate http) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = http;
    }

    public synchronized void configure(long botQq, String verifyKey, boolean autoFollowOnlineBot) {
        this.configuredBotQq = botQq;
        this.verifyKey = verifyKey;
        this.autoFollowOnlineBot = autoFollowOnlineBot;
    }

    public synchronized void connect(long botQq, String verifyKey) {
        connect(botQq, verifyKey, false);
    }

    public synchronized void connect(long botQq, String verifyKey, boolean autoFollowOnlineBot) {
        ensureActive();
        if (closed) throw new IllegalStateException("Mirai client is closed");
        if (verifyKey == null || verifyKey.isBlank()) {
            throw new IllegalArgumentException(botQq <= 0 && !autoFollowOnlineBot
                ? "Mirai QQ and verifyKey must be configured"
                : "Mirai verifyKey must be configured");
        }
        if (!autoFollowOnlineBot && botQq <= 0) {
            throw new IllegalArgumentException("Mirai QQ and verifyKey must be configured");
        }
        this.configuredBotQq = botQq;
        this.verifyKey = verifyKey;
        this.autoFollowOnlineBot = autoFollowOnlineBot;

        JSONObject verified = post("/verify", Map.of("verifyKey", verifyKey));
        check(verified, "verify");
        String key = verified.getString("session");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Mirai verify returned no session key");
        }

        if (botQq > 0) {
            JSONObject bound = post("/bind", Map.of("sessionKey", key, "qq", botQq));
            Integer code = bound.getInteger("code");
            if (code != null && code == 0) {
                this.activeBotQq = botQq;
                this.autoFollowed = false;
                this.sessionKey = key;
                return;
            }
            if (!autoFollowOnlineBot || code == null || code != 2) {
                check(bound, "bind");
            }
        }

        List<Long> onlineBots = fetchBotList();
        if (onlineBots.isEmpty()) {
            throw new IllegalStateException("Mirai has no online bot");
        }
        if (onlineBots.size() > 1) {
            throw new IllegalStateException("Mirai has multiple online bots: count=" + onlineBots.size());
        }
        long targetQq = onlineBots.get(0);
        JSONObject bound = post("/bind", Map.of("sessionKey", key, "qq", targetQq));
        check(bound, "bind");
        this.activeBotQq = targetQq;
        this.autoFollowed = true;
        this.sessionKey = key;
    }

    public long getActiveBotQq() {
        return activeBotQq;
    }

    public boolean isAutoFollowed() {
        return autoFollowed;
    }

    public synchronized long resolveActiveBotQq(long botQq, String verifyKey, boolean autoFollowOnlineBot) {
        ensureActive();
        if (closed) throw new IllegalStateException("Mirai client is closed");
        if (sessionKey != null && activeBotQq > 0) {
            return activeBotQq;
        }
        connect(botQq, verifyKey, autoFollowOnlineBot);
        return activeBotQq;
    }

    public synchronized void invalidateSession() {
        this.sessionKey = null;
        this.activeBotQq = 0;
        this.autoFollowed = false;
    }

    public List<Long> fetchBotList() {
        ensureActive();
        JSONObject result = http.getForObject(baseUrl + "/botList", JSONObject.class);
        if (result == null) {
            throw new IllegalStateException("Mirai returned an empty response for /botList");
        }
        check(result, "botList");
        JSONArray data = result.getJSONArray("data");
        if (data == null) {
            throw new IllegalStateException("Mirai botList returned no bot array");
        }
        List<Long> bots = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            Long id = data.getLong(i);
            if (id != null && id > 0) {
                bots.add(id);
            }
        }
        return bots;
    }

    public List<Long> fetchOnlineBotsSafe() {
        try {
            return fetchBotList();
        } catch (Exception ignored) {
            return null;
        }
    }

    public Map<Long, String> fetchGroupPermissions() {
        String key = sessionKey;
        if (key == null) key = currentSession();
        JSONObject result = get("/groupList", key);
        Integer code = result.getInteger("code");
        if (isSessionOrBotRecoverable(code)) {
            key = refreshSession(key);
            result = get("/groupList", key);
        }
        check(result, "groupList");
        JSONArray data = result.getJSONArray("data");
        if (data == null) {
            throw new IllegalStateException("Mirai groupList returned no group array");
        }
        Map<Long, String> permissions = new LinkedHashMap<>();
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (item == null) continue;
            Long id = item.getLong("id");
            String permission = item.getString("permission");
            if (id != null && id > 0 && permission != null && !permission.isBlank()) {
                permissions.put(id, permission.strip().toUpperCase(Locale.ROOT));
            }
        }
        return permissions;
    }

    public Map<Long, String> fetchGroupPermissionsSafe() {
        try {
            return fetchGroupPermissions();
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized String sendGroupMessage(long group, JSONArray messageChain) {
        return send("/sendGroupMessage", group, messageChain);
    }

    public synchronized String sendFriendMessage(long qq, JSONArray messageChain) {
        return send("/sendFriendMessage", qq, messageChain);
    }

    public JSONArray fetchMessages(int count) {
        String key = sessionKey;
        if (key == null) key = currentSession();
        JSONObject result = fetch(count, key);
        Integer code = result.getInteger("code");
        if (isSessionOrBotRecoverable(code)) {
            key = refreshSession(key);
            result = fetch(count, key);
        }
        check(result, "fetchMessage");
        JSONArray events = result.getJSONArray("data");
        if (events == null) {
            throw new IllegalStateException("Mirai fetchMessage returned no event array");
        }
        return events;
    }

    private synchronized String currentSession() {
        ensureActive();
        if (sessionKey == null) connect(configuredBotQq, verifyKey, autoFollowOnlineBot);
        return sessionKey;
    }

    private synchronized String refreshSession(String expired) {
        ensureActive();
        if (closed) throw new IllegalStateException("Mirai client is closed");
        if (sessionKey == null || (expired != null && expired.equals(sessionKey))) {
            sessionKey = null;
            activeBotQq = 0;
            autoFollowed = false;
            connect(configuredBotQq, verifyKey, autoFollowOnlineBot);
        }
        return sessionKey;
    }

    private JSONObject fetch(int count, String key) {
        return get("/fetchMessage?count=" + Math.max(1, Math.min(100, count)), key);
    }

    public JSONObject checkConnection() {
        String key = sessionKey;
        if (key == null) key = currentSession();
        JSONObject session = get("/sessionInfo", key);
        Integer code = session.getInteger("code");
        if (isSessionOrBotRecoverable(code)) {
            key = refreshSession(key);
            session = get("/sessionInfo", key);
        }
        check(session, "sessionInfo");
        JSONObject data = session.getJSONObject("data");
        JSONObject bot = data == null ? null : data.getJSONObject("qq");
        long boundQq = activeBotQq;
        if (bot == null || boundQq <= 0 || !Long.valueOf(boundQq).equals(bot.getLong("id"))) {
            throw new IllegalStateException("Mirai session is not bound to the configured Bot");
        }
        JSONObject profile = get("/botProfile", key);
        Integer profileCode = profile.getInteger("code");
        if (autoFollowOnlineBot && isSessionOrBotRecoverable(profileCode)) {
            key = refreshSession(key);
            boundQq = activeBotQq;
            profile = get("/botProfile", key);
        }
        if (profile.containsKey("code")) check(profile, "botProfile");
        if (!(profile.get("nickname") instanceof String)) {
            throw new IllegalStateException("Mirai botProfile returned no profile");
        }
        JSONObject about = get("/about", key);
        check(about, "about");
        return new JSONObject().fluentPut("id", boundQq).fluentPut("version", about.getJSONObject("data").getString("version"));
    }

    public String fetchVersion() {
        ensureActive();
        JSONObject about = http.getForObject(baseUrl + "/about", JSONObject.class);
        if (about == null) {
            throw new IllegalStateException("Mirai returned an empty response for /about");
        }
        check(about, "about");
        JSONObject data = about.getJSONObject("data");
        String version = data == null ? null : data.getString("version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Mirai about returned no version");
        }
        return version;
    }

    private JSONObject get(String path, String key) {
        ensureActive();
        HttpHeaders headers = new HttpHeaders();
        headers.set("sessionKey", key);
        JSONObject result = http.exchange(baseUrl + path,
            HttpMethod.GET, new HttpEntity<>(headers), JSONObject.class).getBody();
        if (result == null) {
            throw new IllegalStateException("Mirai returned an empty response for " + path);
        }
        return result;
    }

    @Override
    public synchronized void close() {
        closed = true;
        String key = sessionKey;
        long qq = activeBotQq > 0 ? activeBotQq : configuredBotQq;
        sessionKey = null;
        activeBotQq = 0;
        autoFollowed = false;
        if (key != null && qq > 0) {
            check(post("/release", Map.of("sessionKey", key, "qq", qq)), "release");
        }
    }

    private String send(String path, long target, JSONArray messageChain) {
        ensureActive();
        if (sessionKey == null) {
            connect(configuredBotQq, verifyKey, autoFollowOnlineBot);
        }
        JSONObject result = post(path, Map.of("sessionKey", sessionKey, "target", target, "messageChain", messageChain));
        Integer code = result.getInteger("code");
        if (shouldRefreshBeforeRetrySend(code)) {
            ensureActive();
            sessionKey = null;
            activeBotQq = 0;
            autoFollowed = false;
            connect(configuredBotQq, verifyKey, autoFollowOnlineBot);
            result = post(path, Map.of("sessionKey", sessionKey, "target", target, "messageChain", messageChain));
        }
        check(result, path);
        return result.getString("messageId");
    }

    private boolean shouldRefreshBeforeRetrySend(Integer code) {
        if (isSessionOrBotRecoverable(code)) {
            return true;
        }
        if (autoFollowOnlineBot && code != null && code == 5 && activeBotQq > 0) {
            List<Long> onlineBots = fetchOnlineBotsSafe();
            return onlineBots != null && onlineBots.size() == 1 && !onlineBots.contains(activeBotQq);
        }
        return false;
    }

    private static boolean isSessionOrBotRecoverable(Integer code) {
        return code != null && (code == 2 || code == 3 || code == 4);
    }

    private JSONObject post(String path, Map<String, ?> body) {
        ensureActive();
        JSONObject result = http.postForObject(baseUrl + path, body, JSONObject.class);
        if (result == null) {
            throw new IllegalStateException("Mirai returned an empty response for " + path);
        }
        return result;
    }

    private void check(JSONObject result, String operation) {
        Integer code = result.getInteger("code");
        if (code == null || code != 0) {
            throw new MiraiApiException(operation, code, result.getString("msg"));
        }
    }

    public static String describeCode(int code) {
        return switch (code) {
            case 1 -> "verifyKey 校验失败，请核对 starbot.adapter.mirai.verify-key 与 mirai-api-http 配置";
            case 2 -> "目标 Bot 未在 Mirai 在线，请检查 Mirai 登录状态";
            case 3 -> "Mirai 会话已失效或不存在";
            case 4 -> "Mirai 会话未认证或未绑定";
            case 5 -> "发送目标群或好友不存在";
            case 6 -> "指定文件不存在";
            case 10 -> "Bot 无权限执行该操作";
            case 20 -> "Bot 被禁言，无法发送消息";
            case 30 -> "消息过长，发送被拒绝";
            case 400 -> "Mirai 请求参数错误或接口不支持";
            case 500 -> "Mirai 服务端内部错误（code=500）";
            default -> "Mirai 返回错误码 " + code;
        };
    }

    public static String describeFailure(Throwable error) {
        if (error == null) return "未知异常";
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            if (root instanceof ConnectException || root instanceof SocketTimeoutException
                || root instanceof UnknownHostException || root instanceof HttpTimeoutException) {
                break;
            }
            root = root.getCause();
        }
        if (root instanceof ConnectException) {
            return "无法连接到 Mirai HTTP 服务，请检查 Mirai 是否已启动及 host/port 配置";
        }
        if (root instanceof SocketTimeoutException || root instanceof HttpTimeoutException) {
            return "连接 Mirai 服务超时";
        }
        if (root instanceof UnknownHostException) {
            return "无法解析 Mirai 主机地址";
        }
        if (error instanceof ResourceAccessException) {
            return "无法访问 Mirai HTTP 服务，请检查网络及 host/port 配置";
        }
        if (error instanceof CancellationException) {
            return "请求已取消或超时";
        }
        if (error instanceof MiraiApiException apiError) {
            if (apiError.isAtAllLimited()) {
                return "今日 @全体成员 次数已达上限";
            }
            if (apiError.isGroupChatLimited()) {
                return "群聊发言受限，消息被服务端拒绝";
            }
            if (apiError.isLimitedMessaging()) {
                return "账号触发消息风控限流，发送被拒绝";
            }
            if (apiError.getCode() >= 0) {
                return describeCode(apiError.getCode());
            }
        }
        String msg = error.getMessage();
        if (msg != null) {
            if (msg.contains("AT_ALL_LIMITED")) {
                return "今日 @全体成员 次数已达上限";
            }
            if (msg.contains("GROUP_CHAT_LIMITED")) {
                return "群聊发言受限，消息被服务端拒绝";
            }
            if (msg.contains("LIMITED_MESSAGING")) {
                return "账号触发消息风控限流，发送被拒绝";
            }
            if (msg.contains("Mirai has no online bot")) {
                return "Mirai 当前没有在线 Bot，请先在 Mirai 控制台登录账号";
            }
            if (msg.contains("Mirai has multiple online bots")) {
                return "Mirai 当前有多个在线 Bot，请在 application.yml 中指定目标 starbot.adapter.mirai.bot-qq";
            }
            if (msg.contains("Mirai verifyKey must be configured")) {
                return "未配置有效的 starbot.adapter.mirai.verify-key";
            }
            if (msg.contains("Mirai QQ and verifyKey must be configured")) {
                return "未配置有效的 starbot.adapter.mirai.bot-qq 或 verify-key";
            }
            if (msg.contains("Mirai client is closed")) {
                return "Mirai 客户端已关闭";
            }
            if (msg.contains("Mirai session is not bound")) {
                return "Mirai 会话未绑定到目标 Bot";
            }
            int idx = msg.indexOf("code=");
            if (idx >= 0) {
                int start = idx + 5;
                int end = start;
                while (end < msg.length() && (Character.isDigit(msg.charAt(end)) || (end == start && msg.charAt(end) == '-'))) {
                    end++;
                }
                if (end > start) {
                    try {
                        int code = Integer.parseInt(msg.substring(start, end));
                        return describeCode(code);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return error.getClass().getSimpleName();
    }

    static <T> T beforeDeadline(long deadlineNanos, Supplier<T> action) {
        Long previous = DEADLINE.get();
        DEADLINE.set(deadlineNanos);
        try {
            ensureActive();
            return action.get();
        } finally {
            if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous);
        }
    }

    private static void ensureActive() {
        Long deadline = DEADLINE.get();
        if (Thread.currentThread().isInterrupted() || (deadline != null && System.nanoTime() - deadline >= 0)) {
            throw new CancellationException("Mirai request cancelled or deadline exceeded");
        }
    }
}
