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

import static org.junit.jupiter.api.Assertions.assertTrue;

class MiraiApiClientTest {
    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", exchange -> respond(exchange, "{\"code\":0,\"session\":\"unverified\"}"));
        server.createContext("/bind", exchange -> respond(exchange, "{\"code\":0,\"msg\":\"success\"}"));
        server.createContext("/sendGroupMessage", exchange -> respond(exchange, "{\"code\":0,\"messageId\":123}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
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
