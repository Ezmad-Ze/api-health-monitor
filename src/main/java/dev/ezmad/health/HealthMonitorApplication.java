package dev.ezmad.health;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableConfigurationProperties(HealthMonitorApplication.Settings.class)
public class HealthMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthMonitorApplication.class, args);
    }

    @ConfigurationProperties("monitor")
    public record Settings(List<ConfiguredEndpoint> endpoints, Duration timeout, Duration interval, int historySize) {
        public record ConfiguredEndpoint(String name, URI url) {}
    }

    @Bean(destroyMethod = "close")
    Monitor monitor(Settings settings) {
        var endpoints = settings.endpoints() == null ? List.<Settings.ConfiguredEndpoint>of() : settings.endpoints();
        return new Monitor(endpoints.stream().map(entry -> new Monitor.Target(entry.name(), entry.url())).toList(),
                settings.timeout(), settings.historySize());
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService polling(Monitor monitor, Settings settings) {
        var interval = settings.interval();
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Check interval must be between 1s and 1h");
        }
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(monitor::runChecks, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        return executor;
    }

    @Bean
    WebServerFactoryCustomizer<ConfigurableWebServerFactory> loopbackServer() throws UnknownHostException {
        var address = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        return new LoopbackBinding(address);
    }

    private record LoopbackBinding(InetAddress address)
            implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public void customize(ConfigurableWebServerFactory factory) {
            factory.setAddress(address);
        }
    }

    @RestController
    static class ResultsController {
        private final Monitor monitor;

        ResultsController(Monitor monitor) {
            this.monitor = monitor;
        }

        @GetMapping("/checks")
        ResponseEntity<List<Monitor.Endpoint>> results(HttpServletRequest request) {
            String host = request.getHeader("Host");
            if (host == null || !host.matches("(?i)(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?")) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(monitor.snapshot());
        }
    }
}
