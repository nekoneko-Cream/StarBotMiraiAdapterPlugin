package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;

/** Minimal client for Mirai API HTTP 2.x. */
public final class MiraiApiClient implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final String baseUrl;
    private final RestTemplate http;
    private volatile String sessionKey;
    private boolean closed;
    private long botQq;
    private String verifyKey;

    public MiraiApiClient(String baseUrl, RestTemplate http) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = http;
    }

    public synchronized void connect(long botQq, String verifyKey) {
        ensureActive();
        if (closed) throw new IllegalStateException("Mirai client is closed");
        if (botQq <= 0 || verifyKey == null || verifyKey.isBlank()) {
            throw new IllegalArgumentException("Mirai QQ and verifyKey must be configured");
        }
        this.botQq = botQq;
        this.verifyKey = verifyKey;
        JSONObject verified = post("/verify", Map.of("verifyKey", verifyKey));
        check(verified, "verify");
        String key = verified.getString("session");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Mirai verify returned no session key");
        }
        JSONObject bound = post("/bind", Map.of("sessionKey", key, "qq", botQq));
        check(bound, "bind");
        this.sessionKey = key;
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
        if (code != null && (code == 3 || code == 4)) {
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
        if (sessionKey == null) connect(botQq, verifyKey);
        return sessionKey;
    }

    private synchronized String refreshSession(String expired) {
        ensureActive();
        if (closed) throw new IllegalStateException("Mirai client is closed");
        if (sessionKey == null || expired.equals(sessionKey)) {
            sessionKey = null;
            connect(botQq, verifyKey);
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
        if (code != null && (code == 3 || code == 4)) {
            key = refreshSession(key);
            session = get("/sessionInfo", key);
        }
        check(session, "sessionInfo");
        JSONObject data = session.getJSONObject("data");
        JSONObject bot = data == null ? null : data.getJSONObject("qq");
        if (bot == null || !Long.valueOf(botQq).equals(bot.getLong("id"))) {
            throw new IllegalStateException("Mirai session is not bound to the configured Bot");
        }
        JSONObject profile = get("/botProfile", key);
        if (profile.containsKey("code")) check(profile, "botProfile");
        if (!(profile.get("nickname") instanceof String)) {
            throw new IllegalStateException("Mirai botProfile returned no profile");
        }
        JSONObject about = get("/about", key);
        check(about, "about");
        return new JSONObject().fluentPut("id", botQq).fluentPut("version", about.getJSONObject("data").getString("version"));
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
        if (sessionKey != null) {
            String key = sessionKey;
            sessionKey = null;
            check(post("/release", Map.of("sessionKey", key, "qq", botQq)), "release");
        }
    }

    private String send(String path, long target, JSONArray messageChain) {
        ensureActive();
        if (sessionKey == null) {
            connect(botQq, verifyKey);
        }
        JSONObject result = post(path, Map.of("sessionKey", sessionKey, "target", target, "messageChain", messageChain));
        Integer code = result.getInteger("code");
        if (code != null && (code == 3 || code == 4)) {
            ensureActive();
            sessionKey = null;
            connect(botQq, verifyKey);
            result = post(path, Map.of("sessionKey", sessionKey, "target", target, "messageChain", messageChain));
        }
        check(result, path);
        return result.getString("messageId");
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
            throw new IllegalStateException("Mirai " + operation + " failed: code=" + code + ", msg=" + result.getString("msg"));
        }
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
