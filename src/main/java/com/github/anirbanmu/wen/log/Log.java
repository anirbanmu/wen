package com.github.anirbanmu.wen.log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class Log {
    private static final Logger logger = System.getLogger("wen");
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(4096);
    private static final OutputStream OUT = new FileOutputStream(FileDescriptor.out);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_INSTANT;

    static {
        Thread drainThread = Thread.ofVirtual().name("wen-log-drain").start(Log::drainLoop);

        // virtual threads are daemon, so need a hook to flush on exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            drainThread.interrupt();
            try {
                drainThread.join(1000);
            } catch (InterruptedException e) {
            }
        }));
    }

    private Log() {}

    public static void info(String evt, Object... kv) {
        log(Level.INFO, evt, kv);
    }

    public static void error(String evt, Throwable t, Object... kv) {
        log(Level.ERROR, evt, kv, t);
    }

    public static void error(String evt, Object... kv) {
        log(Level.ERROR, evt, kv);
    }

    public static void warn(String evt, Object... kv) {
        log(Level.WARNING, evt, kv);
    }

    public static void debug(String evt, Object... kv) {
        log(Level.DEBUG, evt, kv);
    }

    private static void drainLoop() {
        List<String> batch = new ArrayList<>(128);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    batch.add(QUEUE.take());
                    QUEUE.drainTo(batch, 127);
                    write(batch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("PANIC: LOGGING FAILED");
                    e.printStackTrace();
                    batch.clear();
                }
            }
        } finally {
            try {
                if (!batch.isEmpty()) {
                    write(batch);
                }
                while (!QUEUE.isEmpty()) {
                    batch.clear();
                    QUEUE.drainTo(batch, 128);
                    write(batch);
                }
            } catch (Exception e) {
                System.err.println("PANIC: FLUSH FAILED");
            }
        }
    }

    private static void write(List<String> batch) throws IOException {
        StringBuilder chunk = new StringBuilder(batch.size() * 128);
        for (String msg : batch) {
            chunk.append(msg).append('\n');
        }
        OUT.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
        batch.clear();
    }

    private static void log(Level level, String evt, Object[] kv, Throwable t) {
        if (!logger.isLoggable(level)) {
            return;
        }

        StringBuilder sb = new StringBuilder(128);
        TIME_FMT.formatTo(Instant.now().truncatedTo(ChronoUnit.MILLIS), sb);
        sb.append(" ").append(level.name());

        if (evt != null) {
            sb.append(" evt=").append(escape(evt));
        }

        if (kv != null && kv.length > 0) {
            for (int i = 0; i < kv.length; i += 2) {
                sb.append(" ").append(kv[i]).append("=")
                  .append(escape(String.valueOf((i + 1 < kv.length) ? kv[i + 1] : "null")));
            }
        }

        if (t != null) {
            sb.append(" err=").append(escape(t.getClass().getSimpleName()))
              .append(" msg=").append(escape(t.getMessage()));

            if (t.getStackTrace().length > 0) {
                 sb.append(" loc=").append(escape(t.getStackTrace()[0].toString()));
            }
        }

        QUEUE.offer(sb.toString());
    }

    private static void log(Level level, String evt, Object[] kv) {
        log(level, evt, kv, null);
    }

    private static String escape(String s) {
        if (s == null) {
            return "null";
        }

        if (s.isEmpty()) {
            return "\"\"";
        }

        boolean safe = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == '=' || c == '"') {
                safe = false;
                break;
            }
        }

        if (safe) {
            return s;
        }

        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
