#!/bin/sh
set -e

CONFIG_PATH="/tmp/config.toml"

if [ -n "$WEN_CONFIG_B64" ]; then
  echo "$WEN_CONFIG_B64" | base64 -d > "$CONFIG_PATH"
else
  echo "ERROR: WEN_CONFIG_B64 env var is required" >&2
  exit 1
fi

exec java \
  -Xms128m \
  -Xmx128m \
  -XX:+UseZGC \
  -XX:+AlwaysPreTouch \
  -XX:ZAllocationSpikeTolerance=5 \
  -XX:SoftMaxHeapSize=90m \
  -XX:+UseCompactObjectHeaders \
  -Dconfig="$CONFIG_PATH" \
  -jar wen.jar \
  "$@"
