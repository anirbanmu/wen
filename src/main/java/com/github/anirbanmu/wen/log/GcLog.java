package com.github.anirbanmu.wen.log;

import jdk.jfr.consumer.RecordingStream;

public final class GcLog {
    private static final long WINDOW_MS = 60_000;
    private static final long PAUSE_ALERT_US = 100_000;

    private static volatile Stats stats = new Stats();

    private GcLog() {
    }

    private static final class Stats {
        long totalPauseUs;
        long maxPauseUs;
        int pauseCount;
    }

    public static void install() {
        RecordingStream rs = new RecordingStream();

        rs.enable("jdk.ZAllocationStall").withoutThreshold();
        rs.enable("jdk.GCPhasePause").withoutThreshold();

        rs.onEvent("jdk.ZAllocationStall", event -> {
            String thread = event.getThread() != null ? event.getThread().getJavaName() : "unknown";
            Log.warn("gc.alloc_stall",
                "type", event.getString("type"),
                "thread", thread,
                "duration_ms", event.getDuration().toMillis());
        });

        rs.onEvent("jdk.GCPhasePause", event -> {
            long us = event.getDuration().toNanos() / 1000;

            if (us >= PAUSE_ALERT_US) {
                Log.warn("gc.long_pause",
                    "name", event.getString("name"),
                    "duration_ms", us / 1000);
            }

            Stats s = stats;
            s.totalPauseUs += us;
            s.pauseCount++;
            if (us > s.maxPauseUs) {
                s.maxPauseUs = us;
            }
        });

        Thread.ofVirtual().name("gc-stats").start(() -> {
            while (true) {
                try {
                    Thread.sleep(WINDOW_MS);
                } catch (InterruptedException e) {
                    return;
                }
                Stats old = stats;
                stats = new Stats();
                report(old);
            }
        });

        rs.startAsync();
        Log.info("gc.log.installed");
    }

    private static void report(Stats s) {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long used = total - rt.freeMemory();
        long pct = total > 0 ? (used * 100) / total : -1;

        Log.info("gc.stats",
            "pauses", s.pauseCount,
            "total_pause_us", s.totalPauseUs,
            "max_pause_us", s.maxPauseUs,
            "heap_mb", used >> 20,
            "committed_mb", total >> 20,
            "heap_pct", pct);
    }
}
