package com.starlwr.bot.adapter.mirai;

import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.alibaba.fastjson2.JSON;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.util.HttpUtil;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MiraiAdapterServiceTest {
    private HttpServer server;
    private MiraiAdapterProperties properties;
    private MiraiAdapterService service;
    private HttpServer bridgeServer;
    private MockMvc mvc;
    private StarBotSenderService nativeSenders;
    private StarBotMessageSender coreSender;
    private ThreadPoolTaskExecutor requestWorkers;
    private StarBotCoreProperties coreProperties;
    private MiraiController controller;
    private final List<Long> receivedAt = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendCode = new AtomicInteger();
    private final AtomicReference<String> incoming = new AtomicReference<>("[]");
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger backendDelay = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String result = switch (path) {
                case "/verify" -> "{\"code\":0,\"session\":\"test-session\"}";
                case "/bind", "/release" -> "{\"code\":0,\"msg\":\"success\"}";
                case "/fetchMessage" -> "{\"code\":0,\"msg\":\"\",\"data\":" + incoming.getAndSet("[]") + "}";
                case "/sendGroupMessage", "/sendFriendMessage" -> {
                    receivedAt.add(System.nanoTime());
                    bodies.add(request);
                    try { Thread.sleep(backendDelay.get()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    yield "{\"code\":" + sendCode.get() + ",\"msg\":\"test-result\",\"messageId\":123}";
                }
                default -> "{\"code\":400,\"msg\":\"unexpected path\"}";
            };
            byte[] body = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties = new MiraiAdapterProperties();
        properties.setBotQq(123456789L);
        properties.setDelay(0);
        properties.setCommandMode("extended");
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        service = new MiraiAdapterService(properties, new MiraiMessageConverter(), client);
        bridgeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        coreProperties = new StarBotCoreProperties();
        nativeSenders = new StarBotSenderService(coreProperties);
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        WebServer webServer = mock(WebServer.class);
        when(context.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(bridgeServer.getAddress().getPort());
        controller = new MiraiController(properties, service, nativeSenders, context, coreProperties);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        controller.onApplicationReady();
        bridgeServer.createContext("/mirai/send", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var result = mvc.perform(post("/mirai/send").contentType("application/json")
                    .header("Authorization", exchange.getRequestHeaders().getFirst("Authorization"))
                    .content(body)).andReturn().getResponse();
                byte[] response = result.getContentAsByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.getStatus(), response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception e) {
                throw new java.io.IOException(e);
            } finally {
                exchange.close();
            }
        });
        bridgeServer.start();
        requestWorkers = new ThreadPoolTaskExecutor();
        requestWorkers.initialize();
        coreSender = new StarBotMessageSender(new HttpUtil(requestWorkers, new RestTemplate(), coreProperties), nativeSenders);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (coreSender != null) {
            var executor = StarBotMessageSender.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((java.util.concurrent.ExecutorService) executor.get(coreSender)).shutdownNow();
        }
        if (requestWorkers != null) requestWorkers.shutdown();
        if (controller != null) controller.close();
        if ((Object) service instanceof AutoCloseable closeable) {
            closeable.close();
        }
        server.stop(0);
        if (bridgeServer != null) bridgeServer.stop(0);
    }

    @Test
    void ignoresMessagesForAnotherPlatform() {
        Message message = Message.create("qq-onebot", PushTargetType.GROUP, 987654321L, "hello").get(0);
        coreSender.send(message);
        assertTrue(bodies.isEmpty());
    }

    @Test
    void registersNativeSenderAndRejectsUnauthenticatedHttpRequests() throws Exception {
        var sender = nativeSenders.getSender("mirai").orElseThrow();
        assertTrue(sender.getUrl().endsWith("/mirai/send"));
        var response = mvc.perform(post("/mirai/send").contentType("application/json")
            .content("{\"platform\":\"mirai\",\"type\":1,\"num\":987654321,\"content\":\"hello\"}"))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertNotEquals(0, JSON.parseObject(response.getContentAsString()).getIntValue("code"));
        assertTrue(bodies.isEmpty());
    }

    @Test
    void rejectsInvalidPlatformAndTargetTypeBeforeCallingMirai() throws Exception {
        String authorization = "Bearer " + nativeSenders.getSender("mirai").orElseThrow().getToken();
        for (String payload : List.of(
            "{\"platform\":\"other\",\"type\":1,\"num\":987654321,\"content\":\"hello\"}",
            "{\"platform\":\"mirai\",\"type\":9,\"num\":987654321,\"content\":\"hello\"}")) {
            var response = mvc.perform(post("/mirai/send").header("Authorization", authorization)
                .contentType("application/json").content(payload)).andReturn().getResponse();
            assertNotEquals(0, JSON.parseObject(response.getContentAsString()).getIntValue("code"));
        }
        assertTrue(bodies.isEmpty());
    }

    @Test
    void slowMiraiRequestReturnsFailureBeforeOuterCoreTimeout() throws Exception {
        coreProperties.getNetwork().setReadTimeout(1);
        backendDelay.set(800);
        CountDownLatch failure = new CountDownLatch(1);
        Message message = message();
        message.addOnFailureCallback(failure::countDown);
        coreSender.send(message);
        assertTrue(failure.await(1, TimeUnit.SECONDS), "Core must receive a nonzero result instead of losing callbacks to outer HTTP timeout");
        assertNotNull(message.getCompleteTime());
    }

    @Test
    void coreNextSplittingPreservesMiraiDeliveryOrder() throws Exception {
        CountDownLatch sent = new CountDownLatch(2);
        for (Message message : Message.create("mirai", PushTargetType.GROUP, 987654321L, "one{next}two")) {
            message.addOnSuccessCallback(sent::countDown);
            coreSender.send(message);
        }
        assertTrue(sent.await(2, TimeUnit.SECONDS));
        assertEquals("one", JSON.parseObject(bodies.get(0)).getJSONArray("messageChain").getJSONObject(0).getString("text"));
        assertEquals("two", JSON.parseObject(bodies.get(1)).getJSONArray("messageChain").getJSONObject(0).getString("text"));
    }

    @Test
    void polledGroupCommandReachesExistingHttpSender() throws Exception {
        PushTarget target = new PushTarget();
        target.setPlatform("mirai");
        target.setType(PushTargetType.GROUP);
        target.setNum(987654321L);
        target.setEnabled(true);
        PushUser user = new PushUser();
        user.setUid(111L);
        user.setPlatform("bilibili");
        user.setEnabled(true);
        user.getTargets().add(target);
        AbstractDataSource dataSource = new AbstractDataSource(null, null, null) {
            @Override public void load() { }
            @Override public List<PushUser> getAllUsers() { return List.of(user); }
            @Override public java.util.Optional<PushUser> getUser(String platform, Long uid) { return java.util.Optional.of(user); }
        };
        var event = JSON.parseObject("""
            {"type":"GroupMessage","sender":{"id":333333333,"group":{"id":987654321}},
            "messageChain":[{"type":"Source","id":12},{"type":"Plain","text":"status"}]}
            """);
        event.getJSONArray("messageChain").getJSONObject(0).put("time", java.time.Instant.now().getEpochSecond());
        incoming.set("[" + event.toJSONString() + "]");
        try (MiraiGroupMessageService receiver = new MiraiGroupMessageService(properties, service, dataSource, coreSender)) {
            receiver.poll();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (bodies.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, bodies.size());
            var request = JSON.parseObject(bodies.get(0));
            assertEquals(987654321L, request.getLongValue("target"));
            assertTrue(request.getJSONArray("messageChain").getJSONObject(0).getString("text").contains("本群订阅：1"));
            receiver.poll();
            assertEquals(1, bodies.size());
        }
    }

    @Test
    void hotReloadMovesCommandAuthorizationToNewGroup() throws Exception {
        var registry = new com.starlwr.bot.core.datasource.DataSourceServiceRegistry(List.of()) {
            @Override public java.util.Optional<com.starlwr.bot.core.service.DataSourceService> getDataSourceService(String platform) {
                return java.util.Optional.of(user -> { });
            }
        };
        AbstractDataSource dataSource = new AbstractDataSource(event -> { }, registry,
            new com.starlwr.bot.core.service.StarBotEventHandlerService(null)) {
            @Override public void load() { }
        };
        dataSource.add(new java.util.ArrayList<>(List.of(pushUser(987654321L))));
        dataSource.update(new java.util.ArrayList<>(List.of(pushUser(555555555L))));
        var oldGroup = JSON.parseObject("""
            {"type":"GroupMessage","sender":{"id":333333333,"group":{"id":987654321}},
            "messageChain":[{"type":"Source","id":21},{"type":"Plain","text":"status"}]}
            """);
        oldGroup.getJSONArray("messageChain").getJSONObject(0).put("time", java.time.Instant.now().getEpochSecond());
        var newGroup = JSON.parseObject(oldGroup.toJSONString());
        newGroup.getJSONObject("sender").getJSONObject("group").put("id", 555555555L);
        incoming.set("[" + oldGroup.toJSONString() + "," + newGroup.toJSONString() + "]");
        try (MiraiGroupMessageService receiver = new MiraiGroupMessageService(properties, service, dataSource, coreSender)) {
            receiver.poll();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (bodies.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, bodies.size());
            assertEquals(555555555L, JSON.parseObject(bodies.get(0)).getLongValue("target"));
        }
    }

    private PushUser pushUser(long group) {
        PushTarget target = new PushTarget();
        target.setPlatform("mirai");
        target.setType(PushTargetType.GROUP);
        target.setNum(group);
        target.setEnabled(true);
        PushUser user = new PushUser();
        user.setUid(111L);
        user.setPlatform("bilibili");
        user.setUname("Example");
        user.setEnabled(true);
        user.getTargets().add(target);
        return user;
    }

    @Test
    void successfulSendSetsIdAndCompletionTimeAndRunsCallbacks() throws Exception {
        Message message = message();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch success = new CountDownLatch(1);
        message.addOnCompleteCallback(() -> callbacks.add("complete:" + message.getId()));
        message.addOnSuccessCallback(() -> { callbacks.add("success"); success.countDown(); });
        message.addOnFailureCallback(() -> callbacks.add("failure"));
        coreSender.send(message);
        assertTrue(success.await(2, TimeUnit.SECONDS), "success callback must run");
        assertEquals("123", message.getId());
        assertNotNull(message.getCompleteTime());
        assertEquals(List.of("complete:null", "success"), callbacks);
    }

    @Test
    void beforeSendVetoPreventsHttpDelivery() throws Exception {
        Message message = message();
        CountDownLatch intercepted = new CountDownLatch(1);
        message.addOnBeforeSendInterceptor(m -> { intercepted.countDown(); return false; });
        coreSender.send(message);
        assertTrue(intercepted.await(2, TimeUnit.SECONDS), "before-send interceptor must run");
        assertTrue(receivedAt.isEmpty());
        assertNull(message.getCompleteTime());
    }

    @Test
    void failedSendRunsFailureCallbacksAndWorkerProcessesNextMessage() throws Exception {
        sendCode.set(20);
        Message first = message();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);
        first.addOnCompleteCallback(() -> callbacks.add("complete"));
        first.addOnFailureCallback(() -> { callbacks.add("failure"); failed.countDown(); });
        first.addOnSuccessCallback(() -> callbacks.add("success"));
        assertDoesNotThrow(() -> coreSender.send(first));
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertNotNull(first.getCompleteTime());
        assertNull(first.getId());
        assertEquals(List.of("complete", "failure"), callbacks);
        sendCode.set(0);
        CountDownLatch success = new CountDownLatch(1);
        Message next = message();
        next.addOnSuccessCallback(success::countDown);
        coreSender.send(next);
        assertTrue(success.await(2, TimeUnit.SECONDS));
        assertEquals(2, receivedAt.size());
    }

    @Test
    void serialQueueEnforcesConfiguredMessageInterval() throws Exception {
        properties.setDelay(300);
        nativeSenders.getSender("mirai").orElseThrow().setDelay(300);
        CountDownLatch sent = new CountDownLatch(2);
        Message first = message();
        Message second = message();
        first.addOnSuccessCallback(sent::countDown);
        second.addOnSuccessCallback(sent::countDown);
        coreSender.send(first);
        coreSender.send(second);
        assertTrue(sent.await(3, TimeUnit.SECONDS));
        assertEquals(2, receivedAt.size());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(receivedAt.get(1) - receivedAt.get(0)) >= 280,
                "messages must respect the configured 300 ms delay");
    }

    private Message message() {
        return Message.create("mirai", PushTargetType.GROUP, 987654321L, "hello").get(0);
    }
}
