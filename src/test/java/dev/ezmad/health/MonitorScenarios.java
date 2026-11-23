package dev.ezmad.health;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

final class MonitorScenarios {
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (var method : List.of(
                new Scenario("classifies 2xx and non-2xx, keeps newest results, omits URL and body", MonitorScenarios::history),
                new Scenario("rejects unsafe targets and invalid configuration", MonitorScenarios::validation),
                new Scenario("rejects redirects without connecting to their destination", MonitorScenarios::redirect),
                new Scenario("times out while waiting for response headers", MonitorScenarios::timeout),
                new Scenario("records promptly when response body stalls", MonitorScenarios::headersOnly),
                new Scenario("reports connection failure without an HTTP status", MonitorScenarios::unreachable))) {
            method.run().check();
            System.out.println("PASS " + method.name());
            passed++;
        }
        System.out.println("Monitor core: " + passed + " scenarios passed");
    }

    private static void history() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/private-path-marker", exchange -> {
                int status = calls.incrementAndGet() == 1 ? 200 : 503;
                byte[] body = "private-body-marker".getBytes(StandardCharsets.UTF_8);
                try (exchange) {
                    exchange.getResponseHeaders().set("X-Test", "private-header-marker");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            try (var monitor = new Monitor(List.of(fixture.target("localhost", "/private-path-marker")),
                    Duration.ofSeconds(2), 2)) {
                expect(monitor.snapshot().getFirst().checks().isEmpty(), "initial history");
                monitor.runChecks();
                var first = latest(monitor);
                expect(first.outcome() == Monitor.Outcome.UP && first.httpStatus() == 200, "200 result");
                monitor.runChecks();
                monitor.runChecks();
                var checks = monitor.snapshot().getFirst().checks();
                expect(checks.size() == 2 && !checks.contains(first), "oldest sample evicted");
                expect(checks.getFirst().httpStatus() == 503 && checks.getFirst().outcome() == Monitor.Outcome.HTTP_ERROR,
                        "503 result");
                expect(!checks.getFirst().checkedAt().isBefore(checks.getLast().checkedAt()), "newest first");
                expect(checks.getFirst().latencyMs() >= 0, "non-negative latency");
                expect(!monitor.snapshot().toString().contains("private-"), "snapshot contains no fixture markers");
            }
        }
    }

    private static void validation() {
        for (String invalid : List.of("https://localhost:80/", "http://example.test:80/",
                "http://localhost.evil.test:80/", "http://127.0.0.2:80/", "http://0.0.0.0:80/",
                "http://foo@localhost:80/", "http://localhost:80/?secret", "http://localhost:80/#frag",
                "http://localhost/", "http://localhost:0/", "http://localhost:65536/", "http://[::ffff:127.0.0.1]:80/",
                "/relative")) {
            rejects(() -> new Monitor.Target("sample", URI.create(invalid)));
        }
        var local = new Monitor.Target("sample", URI.create("http://LOCALHOST:9000/test%20path"));
        expect(local.uri().toString().equals("http://127.0.0.1:9000/test%20path"), "localhost is pinned");
        expect(new Monitor.Target("v6", URI.create("http://[::1]:9000")).uri().getHost().equals("[::1]"),
                "IPv6 loopback accepted");
        rejects(() -> new Monitor.Target("space name", local.uri()));
        rejects(() -> new Monitor(List.of(), Duration.ofSeconds(1), 2));
        rejects(() -> new Monitor(List.of(local, local), Duration.ofSeconds(1), 2));
        rejects(() -> new Monitor(List.of(local), Duration.ofMillis(20), 2));
        rejects(() -> new Monitor(List.of(local), Duration.ofSeconds(1), 101));
    }

    private static void redirect() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/redirect", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location", "/sink");
                    exchange.sendResponseHeaders(302, -1);
                }
            });
            fixture.server.createContext("/sink", exchange -> {
                calls.incrementAndGet();
                exchange.close();
            });
            try (var monitor = fixture.monitor("/redirect", Duration.ofSeconds(2))) {
                monitor.runChecks();
                var result = latest(monitor);
                expect(result.outcome() == Monitor.Outcome.HTTP_ERROR && result.httpStatus() == 302, "redirect result");
                expect(calls.get() == 0, "redirect destination untouched");
            }
        }
    }

    private static void timeout() throws Exception {
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/slow", exchange -> {
                try (exchange) {
                    await(release);
                }
            });
            try (var monitor = fixture.monitor("/slow", Duration.ofMillis(300))) {
                monitor.runChecks();
                var result = latest(monitor);
                expect(result.outcome() == Monitor.Outcome.TIMEOUT && result.httpStatus() == null, "timeout result");
            } finally {
                release.countDown();
            }
        }
    }

    private static void headersOnly() throws Exception {
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.server.createContext("/stall", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 30);
                    exchange.getResponseBody().write(new byte[] {1});
                    exchange.getResponseBody().flush();
                    await(release);
                }
            });
            try (var monitor = fixture.monitor("/stall", Duration.ofSeconds(2))) {
                monitor.runChecks();
                expect(latest(monitor).outcome() == Monitor.Outcome.UP, "does not wait for response body");
            } finally {
                release.countDown();
            }
        }
    }

    private static void unreachable() throws Exception {
        try (var fixture = new Fixture()) {
            // Terminate the connection before HTTP headers, including any client retry.
            fixture.server.createContext("/disconnect", exchange -> exchange.close());
            try (var monitor = fixture.monitor("/disconnect", Duration.ofSeconds(2))) {
                monitor.runChecks();
                var result = latest(monitor);
                expect(result.outcome() == Monitor.Outcome.UNREACHABLE && result.httpStatus() == null,
                        "expected a transport failure, got " + result.outcome());
            }
        }
    }

    private static Monitor.Result latest(Monitor monitor) {
        return monitor.snapshot().getFirst().checks().getFirst();
    }

    private static void await(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid configuration accepted");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface Check {
        void check() throws Exception;
    }

    private record Scenario(String name, Check run) {}

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        private Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.start();
        }

        private Monitor.Target target(String host, String path) {
            return new Monitor.Target("sample", URI.create("http://" + host + ":" + server.getAddress().getPort() + path));
        }

        private Monitor monitor(String path, Duration timeout) {
            return new Monitor(List.of(target("127.0.0.1", path)), timeout, 2);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
