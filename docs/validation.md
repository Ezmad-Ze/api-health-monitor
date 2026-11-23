# Validation

November 23, 2026. The Java 21.0.13 core compiled on Linux and passed six local scenarios using real HTTP fixtures. They cover status classification, bounded history, omission of response data, invalid targets, redirects, header timeouts, stalled response bodies and connection failures.

The Spring Boot integration test exercises scheduled probes, the live JSON results endpoint and its Host-header check. The demo runs that test in isolation. The Maven toolchain and dependencies were unavailable in the local validation environment, so the Spring Boot build, integration test and demo await execution. Ubuntu and macOS CI jobs are configured and await publication.
