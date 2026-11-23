#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mvn -B -Dtest=ApplicationTest -Ddemo=true test
