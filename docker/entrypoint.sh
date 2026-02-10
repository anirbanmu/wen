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
  -XX:MaxRAM=256m \
  -XX:ActiveProcessorCount=1 \
  \
  -Xms48m \
  -Xmx48m \
  -XX:SoftMaxHeapSize=32m \
  \
  -XX:+UseZGC \
  -XX:+AlwaysPreTouch \
  -XX:ZAllocationSpikeTolerance=5 \
  -XX:+UseCompactObjectHeaders \
  \
  -XX:ReservedCodeCacheSize=16m \
  -XX:TieredStopAtLevel=1 \
  \
  -Dconfig="$CONFIG_PATH" \
  -jar wen.jar \
  "$@"
