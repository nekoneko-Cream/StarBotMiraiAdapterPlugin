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

    private void respond(HttpExchange exchange, String response) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getRequestURI().getPath() + " " + body);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
