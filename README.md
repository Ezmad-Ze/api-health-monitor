# API Health Monitor

A Java 21 and Spring Boot tool for checking local development APIs. It answers whether an endpoint responds, what HTTP status it returns and how long it takes to receive response headers. Each endpoint keeps a bounded history in memory.

Use it after changing configuration, restarting a service or working on a local integration. The monitor reads status codes without interpreting response bodies. Configure only local GET endpoints that are safe to call repeatedly.

## Run it

With JDK 21 and Maven 3.9 installed:

```sh
mvn -B verify
java -jar target/api-health-monitor-0.1.0.jar \
  '--monitor.endpoints[0].name=dev-api' \
  '--monitor.endpoints[0].url=http://127.0.0.1:8080/actuator/health'
```

Use a health endpoint you have already enabled, or substitute any local path that can safely be polled. In another terminal:

```sh
curl --fail http://127.0.0.1:8099/checks
```

Example after one successful check:

```json
[
  {
    "name": "dev-api",
    "checks": [
      {"checkedAt": "2026-11-23T17:00:00Z", "outcome": "UP", "httpStatus": 200, "latencyMs": 12}
    ]
  }
]
```

An empty `checks` array means the endpoint has no completed probe yet. Results are newest first. `UP` represents a 2xx response, `HTTP_ERROR` any other status, `TIMEOUT` an expired deadline and `UNREACHABLE` a transport failure. Transport failures and timeouts have a null HTTP status. Latency includes time spent establishing a connection when a new connection is necessary.

## Configuration

| Setting | Default | Constraint |
| --- | --- | --- |
| `monitor.endpoints` | Required | 1–16 endpoints with unique names of up to 40 letters, digits, underscores or dashes |
| `monitor.interval` | `15s` | 1 second to 1 hour after the previous pass finishes |
| `monitor.timeout` | `1s` | 50 milliseconds to 10 seconds per probe |
| `monitor.history-size` | `20` | 1–100 results per endpoint |
| `server.port` | `8099` | Results server binds to IPv4 loopback |

Add endpoints with successive indexes, or set the same fields in an external Spring Boot YAML file. Targets must be HTTP URLs with an explicit port and a host of `127.0.0.1`, `localhost` or `[::1]`. The probe maps `localhost` to IPv4 loopback, disables proxies and never follows redirects. Credentials, query strings and fragments are rejected. Results include only configured names and check summaries. Use non-sensitive names.

## Demo and tests

```sh
sh scripts/test-core.sh
sh scripts/demo.sh
```

The first command uses only Java 21 and runs six scenarios against local fixtures. The second uses Maven and launches separate temporary fixture and Spring Boot listeners on ephemeral loopback ports, prints the results and closes both servers. See [architecture](docs/architecture.md), [validation](docs/validation.md) and [changelog](CHANGELOG.md).

This portfolio project was prepared with AI assistance. It uses fictional examples and local test fixtures.
