package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;

/** Minimal client for Mirai API HTTP 2.x. */
public final class MiraiApiClient implements AutoCloseable {
    private final String baseUrl;
    private final RestTemplate http;
    private String sessionKey;
    private long botQq;
    private String verifyKey;

    public MiraiApiClient(String baseUrl, RestTemplate http) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = http;
    }

    public synchronized void connect(long botQq, String verifyKey) {
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

    public synchronized JSONArray fetchMessages(int count) {
        if (sessionKey == null) {
            connect(botQq, verifyKey);
        }
        JSONObject result = fetch(count);
        Integer code = result.getInteger("code");
        if (code != null && (code == 3 || code == 4)) {
            sessionKey = null;
            connect(botQq, verifyKey);
            result = fetch(count);
        }
        check(result, "fetchMessage");
        JSONArray events = result.getJSONArray("data");
        if (events == null) {
            throw new IllegalStateException("Mirai fetchMessage returned no event array");
        }
        return events;
    }

    private JSONObject fetch(int count) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("sessionKey", sessionKey);
        JSONObject result = http.exchange(baseUrl + "/fetchMessage?count=" + Math.max(1, Math.min(100, count)),
            HttpMethod.GET, new HttpEntity<>(headers), JSONObject.class).getBody();
        if (result == null) {
            throw new IllegalStateException("Mirai returned an empty response for fetchMessage");
        }
        return result;
    }

    @Override
    public synchronized void close() {
        if (sessionKey != null) {
            String key = sessionKey;
            sessionKey = null;
            check(post("/release", Map.of("sessionKey", key, "qq", botQq)), "release");
        }
    }

    private String send(String path, long target, JSONArray messageChain) {
        if (sessionKey == null) {
            connect(botQq, verifyKey);
        }
        JSONObject result = post(path, Map.of("sessionKey", sessionKey, "target", target, "messageChain", messageChain));
        Integer code = result.getInteger("code");
        if (code != null && (code == 3 || code == 4)) {
            sessionKey = null;
            connect(botQq, verifyKey);
            result = post(path, Map.of("sessionKey", sessionKey, "target", target, "messageChain", messageChain));
        }
        check(result, path);
        return result.getString("messageId");
    }

    private JSONObject post(String path, Map<String, ?> body) {
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
}
