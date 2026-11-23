# Architecture

Spring Boot binds startup configuration into a list of names and URLs. The Java probe validates each URL and turns `localhost` into a literal loopback address. The results route accepts no target URL, so a request to that route cannot trigger a probe or choose where one goes.

A single scheduled worker checks configured endpoints in order. Each probe sends GET and waits for headers with a finite deadline. Once headers arrive, it closes the body stream. A slow endpoint can delay later checks by up to its configured deadline in normal operation. The schedule starts its delay after the pass ends, which prevents overlapping sweeps.

Each endpoint has a fixed-length deque. When a new result exceeds the limit, the oldest result is evicted. The read endpoint gets immutable snapshots under a short lock, so reading history doesn't wait on network I/O. The process discards history on restart.

The results server is local and unauthenticated. A Host-header check protects the results route against requests addressed to unrelated hostnames. Another process on the same machine can read the results. The setup is intended for developer smoke checks.
