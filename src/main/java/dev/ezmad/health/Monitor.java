package dev.ezmad.health;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Monitor implements AutoCloseable {
    enum Outcome { UP, HTTP_ERROR, TIMEOUT, UNREACHABLE }

    record Target(String name, URI uri) {
        Target {
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,40}")) {
                throw new IllegalArgumentException("Endpoint name must use 1–40 letters, digits, dashes or underscores");
            }
            uri = loopback(uri);
        }
    }

    record Result(Instant checkedAt, Outcome outcome, Integer httpStatus, long latencyMs) {}
    record Endpoint(String name, List<Result> checks) {}

    private final Map<Target, Deque<Result>> history = new LinkedHashMap<>();
    private final HttpClient client;
    private final Duration timeout;
    private final int capacity;

    Monitor(List<Target> targets, Duration timeout, int capacity) {
        if (targets.isEmpty() || targets.size() > 16
                || targets.stream().map(Target::name).distinct().count() != targets.size()) {
            throw new IllegalArgumentException("Configure 1–16 endpoints with unique names");
        }
        if (timeout.compareTo(Duration.ofMillis(50)) < 0 || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Check timeout must be between 50ms and 10s");
        }
        if (capacity < 1 || capacity > 100) {
            throw new IllegalArgumentException("History size must be between 1 and 100");
        }
        this.timeout = timeout;
        this.capacity = capacity;
        targets.forEach(target -> history.put(target, new ArrayDeque<>()));
        client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    void runChecks() {
        for (Target target : history.keySet()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            Result result = check(target);
            synchronized (history) {
                Deque<Result> checks = history.get(target);
                checks.addFirst(result);
                if (checks.size() > capacity) {
                    checks.removeLast();
                }
            }
        }
    }

    List<Endpoint> snapshot() {
        synchronized (history) {
            return history.entrySet().stream()
                    .map(entry -> new Endpoint(entry.getKey().name(), List.copyOf(entry.getValue())))
                    .toList();
        }
    }

    private Result check(Target target) {
        Instant checkedAt = Instant.now();
        long start = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(target.uri()).timeout(timeout).GET().build();
        // Receive status and headers, then cancel the stream without consuming or storing the body.
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            long elapsed = elapsedMs(start);
            try (InputStream body = response.body()) {
                return new Result(checkedAt, status >= 200 && status < 300 ? Outcome.UP : Outcome.HTTP_ERROR,
                        status, elapsed);
            }
        } catch (TimeoutException e) {
            discard(pending);
            return new Result(checkedAt, Outcome.TIMEOUT, null, elapsedMs(start));
        } catch (ExecutionException e) {
            Outcome outcome = e.getCause() instanceof java.net.http.HttpTimeoutException
                    ? Outcome.TIMEOUT : Outcome.UNREACHABLE;
            return new Result(checkedAt, outcome, null, elapsedMs(start));
        } catch (InterruptedException e) {
            discard(pending);
            Thread.currentThread().interrupt();
            return new Result(checkedAt, Outcome.UNREACHABLE, null, elapsedMs(start));
        } catch (IOException e) {
            return new Result(checkedAt, Outcome.UNREACHABLE, null, elapsedMs(start));
        }
    }

    private static void discard(java.util.concurrent.CompletableFuture<HttpResponse<InputStream>> pending) {
        if (!pending.cancel(true)) {
            pending.thenAccept(response -> {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // Closing is best effort after the deadline or shutdown.
                }
            });
        }
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static URI loopback(URI uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() == null || !uri.getRawAuthority()
                        .matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\]):[0-9]{1,5}")
                || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Endpoint must be a loopback HTTP URL with an explicit port and no credentials, query or fragment");
        }
        String host = "localhost".equalsIgnoreCase(uri.getHost()) ? "127.0.0.1" : uri.getHost();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return URI.create("http://" + host + ":" + uri.getPort() + path);
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
