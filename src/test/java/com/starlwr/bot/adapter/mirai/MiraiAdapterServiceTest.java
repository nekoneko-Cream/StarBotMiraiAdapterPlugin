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
    private final List<Long> receivedAt = new CopyOnWriteArrayList<>();
    private final AtomicInteger sendCode = new AtomicInteger();
    private final AtomicReference<String> incoming = new AtomicReference<>("[]");
    private final List<String> bodies = new CopyOnWriteArrayList<>();

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
        MiraiApiClient client = new MiraiApiClient("http://127.0.0.1:" + server.getAddress().getPort(), new RestTemplate());
        client.connect(123456789L, "test-key");
        service = new MiraiAdapterService(properties, new MiraiMessageConverter(), client);
    }

    @AfterEach
    void tearDown() throws Exception {
        if ((Object) service instanceof AutoCloseable closeable) {
            closeable.close();
        }
        server.stop(0);
    }

    @Test
    void ignoresMessagesForAnotherPlatform() {
        Message message = Message.create("qq-onebot", PushTargetType.GROUP, 987654321L, "hello").get(0);
        assertFalse(service.shouldHandle(message));
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
        try (MiraiGroupMessageService receiver = new MiraiGroupMessageService(properties, service, dataSource)) {
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
        try (MiraiGroupMessageService receiver = new MiraiGroupMessageService(properties, service, dataSource)) {
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
        message.addOnCompleteCallback(() -> callbacks.add("complete"));
        message.addOnSuccessCallback(() -> { callbacks.add("success"); success.countDown(); });
        message.addOnFailureCallback(() -> callbacks.add("failure"));
        service.send(message);
        assertTrue(success.await(2, TimeUnit.SECONDS), "success callback must run");
        assertEquals("123", message.getId());
        assertNotNull(message.getCompleteTime());
        assertEquals(List.of("complete", "success"), callbacks);
    }

    @Test
    void beforeSendVetoPreventsHttpDelivery() throws Exception {
        Message message = message();
        CountDownLatch intercepted = new CountDownLatch(1);
        message.addOnBeforeSendInterceptor(m -> { intercepted.countDown(); return false; });
        service.send(message);
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
        assertDoesNotThrow(() -> service.send(first));
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertNotNull(first.getCompleteTime());
        assertNull(first.getId());
        assertEquals(List.of("complete", "failure"), callbacks);
        sendCode.set(0);
        CountDownLatch success = new CountDownLatch(1);
        Message next = message();
        next.addOnSuccessCallback(success::countDown);
        service.send(next);
        assertTrue(success.await(2, TimeUnit.SECONDS));
        assertEquals(2, receivedAt.size());
    }

    @Test
    void serialQueueEnforcesConfiguredMessageInterval() throws Exception {
        properties.setDelay(300);
        CountDownLatch sent = new CountDownLatch(2);
        Message first = message();
        Message second = message();
        first.addOnSuccessCallback(sent::countDown);
        second.addOnSuccessCallback(sent::countDown);
        service.send(first);
        service.send(second);
        assertTrue(sent.await(3, TimeUnit.SECONDS));
        assertEquals(2, receivedAt.size());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(receivedAt.get(1) - receivedAt.get(0)) >= 280,
                "messages must respect the configured 300 ms delay");
    }

    private Message message() {
        return Message.create("mirai", PushTargetType.GROUP, 987654321L, "hello").get(0);
    }
}
