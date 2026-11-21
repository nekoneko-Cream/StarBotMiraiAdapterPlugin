package com.starlwr.bot.adapter.mirai;

import com.alibaba.fastjson2.JSONArray;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class MiraiApiClientTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger fetchCount = new AtomicInteger();
    private final ExecutorService httpWorkers = Executors.newCachedThreadPool();
    private final AtomicLong sessionBot = new AtomicLong(123456789L);
    private final java.util.concurrent.atomic.AtomicReference<String> profile = new java.util.concurrent.atomic.AtomicReference<>(
        "{\"nickname\":\"Example\",\"email\":\"example@example.com\",\"age\":18,\"level\":1,\"sign\":\"hello\",\"sex\":\"UNKNOWN\"}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpWorkers);
        server.createContext("/verify", exchange -> respond(exchange, "{\"code\":0,\"session\":\"unverified\"}"));
        server.createContext("/bind", exchange -> respond(exchange, "{\"code\":0,\"msg\":\"success\"}"));
        server.createContext("/sendGroupMessage", exchange -> respond(exchange, "{\"code\":0,\"messageId\":123}"));
        server.createContext("/fetchMessage", exchange -> {
            assertEquals("unverified", exchange.getRequestHeaders().getFirst("sessionKey"));
            assertEquals("count=20", exchange.getRequestURI().getQuery());
            respond(exchange, fetchCount.getAndIncrement() == 0 ? "{\"code\":3,\"msg\":\"expired\"}" :
                "{\"code\":0,\"msg\":\"\",\"data\":[{\"type\":\"GroupMessage\",\"sender\":{\"id\":333333333},\"messageChain\":[]}]}" );
        });
        server.createContext("/release", exchange -> respond(exchange, "{\"code\":0,\"msg\":\"success\"}"));
        server.createContext("/sessionInfo", exchange -> respond(exchange,
            "{\"code\":0,\"data\":{\"qq\":{\"id\":" + sessionBot.get() + ",\"nickname\":\"Example\"}}}"));
        server.createContext("/about", exchange -> respond(exchange, "{\"code\":0,\"data\":{\"version\":\"2.10.0\"}}"));
        server.createContext("/botProfile", exchange -> respond(exchange, profile.get()));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        httpWorkers.shutdownNow();
    }

    @Test
    void authenticatesBindsAndSendsGroupMessage() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        MiraiApiClient client = new MiraiApiClient(baseUrl, new RestTemplate());

        client.connect(123456789L, "test-key");
        client.sendGroupMessage(987654321L, new JSONArray().fluentAdd(new com.alibaba.fastjson2.JSONObject().fluentPut("type", "Plain").fluentPut("text", "hello")));

        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/verify ") && x.contains("test-key")));
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/bind ") && x.contains("123456789")));
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/sendGroupMessage ") && x.contains("987654321") && x.contains("hello")));
    }

    @Test
    void fetchesEventsWithHeaderAndRebindsExpiredSession() {
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        JSONArray events = client.fetchMessages(20);
        assertEquals("GroupMessage", events.getJSONObject(0).getString("type"));
        assertEquals(2, requests.stream().filter(x -> x.startsWith("/verify ")).count());
        assertEquals(2, requests.stream().filter(x -> x.startsWith("/bind ")).count());
        client.close();
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/release ") && x.contains("123456789")));
        assertThrows(IllegalStateException.class, () -> client.fetchMessages(20));
    }

    @Test
    void slowSendDoesNotBlockReceivingNextGroupMessage() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finishSend = new CountDownLatch(1);
        server.removeContext("/sendGroupMessage");
        server.createContext("/sendGroupMessage", exchange -> {
            entered.countDown();
            try {
                finishSend.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, "{\"code\":0,\"messageId\":123}");
        });
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        fetchCount.set(1);
        CompletableFuture<String> send = CompletableFuture.supplyAsync(() -> client.sendGroupMessage(987654321L, new JSONArray()));
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            CompletableFuture<JSONArray> fetch = CompletableFuture.supplyAsync(() -> client.fetchMessages(20));
            assertEquals("GroupMessage", fetch.get(1, TimeUnit.SECONDS).getJSONObject(0).getString("type"));
        } finally {
            finishSend.countDown();
            send.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void connectionCheckValidatesBoundBotInsteadOfHttpAvailabilityOnly() {
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        assertEquals("2.10.0", client.checkConnection().getString("version"));
        profile.set("{\"code\":2,\"msg\":\"Bot does not exist\"}");
        assertThrows(IllegalStateException.class, client::checkConnection);
        sessionBot.set(999999999L);
        assertThrows(IllegalStateException.class, client::checkConnection);
    }

    @Test
    void cancelledSendWaitingForClientLockNeverStartsHttpRequest() throws Exception {
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        CountDownLatch starting = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            synchronized (client) {
                Future<?> task = worker.submit(() -> {
                    starting.countDown();
                    client.sendGroupMessage(987654321L, new JSONArray());
                });
                assertTrue(starting.await(1, TimeUnit.SECONDS));
                task.cancel(true);
            }
            worker.shutdown();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(requests.stream().noneMatch(request -> request.startsWith("/sendGroupMessage ")));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void cancelledInFlightSendDoesNotReauthenticateOnExpiredResponse() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        server.removeContext("/sendGroupMessage");
        server.createContext("/sendGroupMessage", exchange -> {
            entered.countDown();
            try { finish.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            respond(exchange, "{\"code\":3,\"msg\":\"expired\"}");
        });
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> client.sendGroupMessage(987654321L, new JSONArray()));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            task.cancel(true);
            finish.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, requests.stream().filter(request -> request.startsWith("/verify ")).count());
            assertEquals(1, requests.stream().filter(request -> request.startsWith("/sendGroupMessage ")).count());
        } finally {
            finish.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void autoFollowsSingleOnlineBotWhenConfiguredBotIsOmitted() {
        List<Long> boundQqs = new CopyOnWriteArrayList<>();
        server.removeContext("/bind");
        server.createContext("/bind", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("/bind " + body);
            long qq = com.alibaba.fastjson2.JSON.parseObject(body).getLongValue("qq");
            boundQqs.add(qq);
            respondRaw(exchange, qq == 222222222L ? "{\"code\":0,\"msg\":\"success\"}" : "{\"code\":2,\"msg\":\"Bot does not exist\"}");
        });
        server.createContext("/botList", exchange -> respond(exchange, "{\"code\":0,\"msg\":\"success\",\"data\":[222222222]}"));

        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(0L, "test-key", true);

        assertEquals(222222222L, client.getActiveBotQq());
        assertTrue(client.isAutoFollowed());
        assertEquals(List.of(222222222L), boundQqs);
        client.close();
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/release ") && x.contains("222222222")));
    }

    @Test
    void autoFollowsSingleOnlineBotWhenConfiguredBotIsOffline() {
        List<Long> boundQqs = new CopyOnWriteArrayList<>();
        server.removeContext("/bind");
        server.createContext("/bind", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("/bind " + body);
            long qq = com.alibaba.fastjson2.JSON.parseObject(body).getLongValue("qq");
            boundQqs.add(qq);
            respondRaw(exchange, qq == 222222222L ? "{\"code\":0,\"msg\":\"success\"}" : "{\"code\":2,\"msg\":\"Bot does not exist\"}");
        });
        server.createContext("/botList", exchange -> respond(exchange, "{\"code\":0,\"msg\":\"success\",\"data\":[222222222]}"));

        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key", true);

        assertEquals(222222222L, client.getActiveBotQq());
        assertTrue(client.isAutoFollowed());
        assertEquals(List.of(123456789L, 222222222L), boundQqs);
    }

    @Test
    void recoversFromLiveBotSwitchDuringFetchSendAndConnectionCheck() {
        AtomicLong onlineBot = new AtomicLong(123456789L);
        AtomicInteger sessionCounter = new AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<String, Long> sessionOwners = new java.util.concurrent.ConcurrentHashMap<>();

        server.removeContext("/verify");
        server.createContext("/verify", exchange -> {
            String key = "sess-" + sessionCounter.incrementAndGet();
            respond(exchange, "{\"code\":0,\"session\":\"" + key + "\"}");
        });
        server.removeContext("/bind");
        server.createContext("/bind", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("/bind " + body);
            var json = com.alibaba.fastjson2.JSON.parseObject(body);
            long qq = json.getLongValue("qq");
            String key = json.getString("sessionKey");
            if (qq == onlineBot.get()) {
                sessionOwners.put(key, qq);
                respondRaw(exchange, "{\"code\":0,\"msg\":\"success\"}");
            } else {
                respondRaw(exchange, "{\"code\":2,\"msg\":\"Bot does not exist\"}");
            }
        });
        server.createContext("/botList", exchange -> respond(exchange, "{\"code\":0,\"msg\":\"success\",\"data\":[" + onlineBot.get() + "]}"));
        server.removeContext("/fetchMessage");
        server.createContext("/fetchMessage", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("sessionKey");
            Long owner = sessionOwners.get(key);
            if (owner == null || owner != onlineBot.get()) {
                respond(exchange, "{\"code\":2,\"msg\":\"Bot does not exist\"}");
            } else {
                respond(exchange, "{\"code\":0,\"msg\":\"\",\"data\":[{\"type\":\"GroupMessage\",\"sender\":{\"id\":333333333},\"messageChain\":[]}]}");
            }
        });
        server.removeContext("/sendGroupMessage");
        server.createContext("/sendGroupMessage", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add("/sendGroupMessage " + body);
            String key = com.alibaba.fastjson2.JSON.parseObject(body).getString("sessionKey");
            Long owner = sessionOwners.get(key);
            if (owner == null || owner != onlineBot.get()) {
                respondRaw(exchange, "{\"code\":2,\"msg\":\"Bot does not exist\"}");
            } else {
                respondRaw(exchange, "{\"code\":0,\"messageId\":456}");
            }
        });
        server.removeContext("/sessionInfo");
        server.createContext("/sessionInfo", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("sessionKey");
            Long owner = sessionOwners.get(key);
            if (owner == null || owner != onlineBot.get()) {
                respond(exchange, "{\"code\":2,\"msg\":\"Bot does not exist\"}");
            } else {
                respond(exchange, "{\"code\":0,\"data\":{\"qq\":{\"id\":" + owner + ",\"nickname\":\"Switched\"}}}");
            }
        });

        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key", true);
        assertEquals(123456789L, client.getActiveBotQq());

        // Simulate /switch to a new QQ account while StarBot is running.
        onlineBot.set(333333333L);
        JSONArray events = client.fetchMessages(20);
        assertEquals(1, events.size());
        assertEquals(333333333L, client.getActiveBotQq());

        // Simulate a second /switch right before sending a group message.
        onlineBot.set(444444444L);
        assertEquals("456", client.sendGroupMessage(987654321L, new JSONArray()));
        assertEquals(444444444L, client.getActiveBotQq());

        // Simulate a third /switch right before checkConnection().
        onlineBot.set(555555555L);
        var info = client.checkConnection();
        assertEquals(555555555L, info.getLongValue("id"));
        assertEquals("2.10.0", info.getString("version"));
    }

    @Test
    void failsClearlyWhenNoOnlineBotOrMultipleOnlineBotsExist() {
        java.util.concurrent.atomic.AtomicReference<String> botListBody = new java.util.concurrent.atomic.AtomicReference<>("{\"code\":0,\"msg\":\"success\",\"data\":[]}");
        server.removeContext("/bind");
        server.createContext("/bind", exchange -> respond(exchange, "{\"code\":2,\"msg\":\"Bot does not exist\"}"));
        server.createContext("/botList", exchange -> respond(exchange, botListBody.get()));

        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        IllegalStateException noneErr = assertThrows(IllegalStateException.class, () -> client.connect(123456789L, "test-key", true));
        assertTrue(MiraiApiClient.describeFailure(noneErr).contains("没有在线 Bot"));

        botListBody.set("{\"code\":0,\"msg\":\"success\",\"data\":[111111,222222]}");
        IllegalStateException multiErr = assertThrows(IllegalStateException.class, () -> client.connect(123456789L, "test-key", true));
        assertTrue(MiraiApiClient.describeFailure(multiErr).contains("多个在线 Bot"));
    }

    @Test
    void strictModeDoesNotQueryBotListWhenAutoFollowDisabled() {
        server.removeContext("/bind");
        server.createContext("/bind", exchange -> respond(exchange, "{\"code\":2,\"msg\":\"Bot does not exist\"}"));
        server.createContext("/botList", exchange -> respond(exchange, "{\"code\":0,\"msg\":\"success\",\"data\":[222222222]}"));

        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        IllegalStateException err = assertThrows(IllegalStateException.class, () -> client.connect(123456789L, "test-key", false));
        assertTrue(MiraiApiClient.describeFailure(err).contains("未在 Mirai 在线"));
        assertTrue(requests.stream().noneMatch(r -> r.startsWith("/botList ")));
    }

    @Test
    void describeFailureMapsErrorsWithoutLeakingSecrets() {
        IllegalStateException verifyErr = new IllegalStateException("Mirai verify failed: code=1, msg=secret-verify-key-xyz");
        String desc = MiraiApiClient.describeFailure(verifyErr);
        assertTrue(desc.contains("verifyKey"));
        assertFalse(desc.contains("secret-verify-key-xyz"));

        RuntimeException connErr = new RuntimeException("wrap", new java.net.ConnectException("Connection refused: 127.0.0.1:7827"));
        assertTrue(MiraiApiClient.describeFailure(connErr).contains("无法连接到 Mirai HTTP 服务"));

        MiraiApiClient.MiraiApiException atAllLim = new MiraiApiClient.MiraiApiException("/sendGroupMessage", 500, "SendMessageFailedException: AT_ALL_LIMITED");
        assertTrue(atAllLim.isAtAllLimited());
        assertTrue(MiraiApiClient.describeFailure(atAllLim).contains("@全体成员 次数已达上限"));

        MiraiApiClient.MiraiApiException groupLim = new MiraiApiClient.MiraiApiException("/sendGroupMessage", 500, "SendMessageFailedException: GROUP_CHAT_LIMITED");
        assertTrue(groupLim.isGroupChatLimited());
        assertTrue(MiraiApiClient.describeFailure(groupLim).contains("群聊发言受限"));

        MiraiApiClient.MiraiApiException msgLim = new MiraiApiClient.MiraiApiException("/sendGroupMessage", 500, "SendMessageFailedException: LIMITED_MESSAGING");
        assertTrue(msgLim.isLimitedMessaging());
        assertTrue(MiraiApiClient.describeFailure(msgLim).contains("风控限流"));
    }

    @Test
    void fetchesGroupPermissionsMapFromGroupList() {
        server.createContext("/groupList", exchange -> respond(exchange,
            "{\"code\":0,\"data\":[{\"id\":111111,\"name\":\"G1\",\"permission\":\"OWNER\"},{\"id\":222222,\"name\":\"G2\",\"permission\":\"MEMBER\"}]}"));
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        var perms = client.fetchGroupPermissions();
        assertEquals("OWNER", perms.get(111111L));
        assertEquals("MEMBER", perms.get(222222L));
    }

    private void respond(HttpExchange exchange, String response) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getRequestURI().getPath() + " " + body);
        respondRaw(exchange, response);
    }

    private void respondRaw(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
