package com.github.anirbanmu.wen.log;

import jdk.jfr.consumer.RecordingStream;

public final class GcLog {
    private GcLog() {
    }

    public static void install() {
        RecordingStream rs = new RecordingStream();

        // alloc stalls
        rs.enable("jdk.ZAllocationStall").withoutThreshold();

        // STW pause phases
        rs.enable("jdk.GCPhasePause").withoutThreshold();

        rs.enable("jdk.GCHeapSummary");

        rs.onEvent("jdk.ZAllocationStall", event -> {
            String thread = event.getThread() != null ? event.getThread().getJavaName() : "unknown";
            Log.warn("gc.alloc_stall",
                "type", event.getString("type"),
                "thread", thread,
                "duration_ms", event.getDuration().toMillis());
        });

        rs.onEvent("jdk.GCPhasePause", event -> {
            Log.info("gc.pause",
                "name", event.getString("name"),
                "duration_us", event.getDuration().toNanos() / 1000);
        });

        rs.onEvent("jdk.GCHeapSummary", event -> {
            long used = event.getLong("heapUsed");
            long committed = event.getLong("heapSpace.committedSize");
            if (committed > 0) {
                long pct = (used * 100) / committed;
                Log.info("gc.heap",
                    "used_mb", used >> 20,
                    "committed_mb", committed >> 20,
                    "pct", pct);
            }
        });

        rs.startAsync();
        Log.info("gc.log.installed");
    }
}
