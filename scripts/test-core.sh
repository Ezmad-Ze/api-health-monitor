#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -d "$work" \
    src/main/java/dev/ezmad/health/Monitor.java \
    src/test/java/dev/ezmad/health/MonitorScenarios.java
java -cp "$work" dev.ezmad.health.MonitorScenarios
