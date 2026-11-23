package dev.ezmad.health;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationTest {
    @Test
    void pollsTwoLocalEndpointsAndServesOnlySummaries() throws Exception {
        var fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/private-path-ok", exchange -> reply(exchange, 200));
        fixture.createContext("/private-path-down", exchange -> reply(exchange, 503));
        fixture.start();
        try {
            String base = "http://127.0.0.1:" + fixture.getAddress().getPort();
            try (var context = SpringApplication.run(HealthMonitorApplication.class,
                    "--spring.main.banner-mode=off", "--server.port=0", "--monitor.interval=1s",
                    "--monitor.timeout=2s", "--monitor.history-size=2",
                    "--monitor.endpoints[0].name=sample-up", "--monitor.endpoints[0].url=" + base + "/private-path-ok",
                    "--monitor.endpoints[1].name=sample-down", "--monitor.endpoints[1].url=" + base + "/private-path-down");
                 var client = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build()) {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/checks"))
                        .timeout(Duration.ofSeconds(2)).build();
                var response = awaitChecks(client, request);
                assertEquals(200, response.statusCode());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                String json = response.body();
                assertTrue(json.contains("\"name\":\"sample-up\""));
                assertTrue(json.contains("\"name\":\"sample-down\""));
                assertTrue(json.contains("\"outcome\":\"UP\""));
                assertTrue(json.contains("\"outcome\":\"HTTP_ERROR\""));
                assertTrue(json.contains("\"httpStatus\":200"));
                assertTrue(json.contains("\"httpStatus\":503"));
                assertTrue(json.contains("\"latencyMs\":"));
                assertFalse(json.contains("fixture-private"));
                assertFalse(json.contains("private-path"));
                assertFalse(json.contains(base));
                rejectsForeignHost(port);

                if (Boolean.getBoolean("demo")) {
                    System.out.println("Isolated demo results: " + json);
                }
            }
        } finally {
            fixture.stop(0);
        }
    }

    private static HttpResponse<String> awaitChecks(HttpClient client, HttpRequest request) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        HttpResponse<String> response;
        do {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("\"httpStatus\":200") && response.body().contains("\"httpStatus\":503")) {
                return response;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return response;
    }

    private static void rejectsForeignHost(int port) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(("GET /checks HTTP/1.1\r\nHost: example.test\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            var text = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(text.startsWith("HTTP/1.1 400"));
            assertFalse(text.contains("sample-up"));
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status) throws java.io.IOException {
        try (exchange) {
            byte[] body = "fixture-private-body".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Example", "fixture-private-header");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
