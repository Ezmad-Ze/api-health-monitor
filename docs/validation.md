# Validation

On November 23, 2026, the core compiled on Linux with Java 21.0.13 and passed six scenarios against real local HTTP fixtures. They cover status classification, bounded history, omission of response data, invalid targets, redirects, header timeouts, stalled response bodies and transport failures. The transport-failure fixture closes an accepted connection before sending response headers, including on any retry.

The Spring Boot integration test exercises scheduled probes, the live JSON results endpoint and its Host-header check. The isolated demo runs that test and prints the results. Maven and its dependencies were unavailable in the local validation environment. The [GitHub Actions workflow](https://github.com/Ezmad-Ze/api-health-monitor/actions/workflows/ci.yml) runs Maven verification and the demo with Java 21 on Ubuntu and macOS. Check the run for the commit you plan to use.
