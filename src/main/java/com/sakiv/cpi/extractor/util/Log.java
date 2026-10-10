package com.sakiv.cpi.extractor.util;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal stderr logger (SLF4J-style {@code {}} placeholders) so the CLI needs no
 * logging framework. stdout is left free for results.
 */
public final class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static volatile Level threshold = Level.INFO;
    private static volatile PrintStream out = System.err;

    private Log() {}

    /** Kept for call-site symmetry with SLF4J's LoggerFactory.getLogger(Class). */
    public static Log get(Class<?> owner) {
        return INSTANCE;
    }

    private static final Log INSTANCE = new Log();

    public static void setLevel(Level level) { threshold = level; }
    public static Level getLevel() { return threshold; }
    public static void setOutput(PrintStream stream) { out = stream; }

    public boolean isDebugEnabled() { return threshold == Level.DEBUG; }

    public void debug(String message, Object... args) { log(Level.DEBUG, message, args); }
    public void info(String message, Object... args) { log(Level.INFO, message, args); }
    public void warn(String message, Object... args) { log(Level.WARN, message, args); }
    public void error(String message, Object... args) { log(Level.ERROR, message, args); }

    private void log(Level level, String message, Object[] args) {
        if (level.compareTo(threshold) < 0) return;

        StringBuilder sb = new StringBuilder(message.length() + 32);
        sb.append(LocalTime.now().format(TIME)).append(" [").append(level).append("] ");
        int argIndex = 0;
        int from = 0;
        int at;
        while (argIndex < args.length && (at = message.indexOf("{}", from)) >= 0) {
            sb.append(message, from, at).append(args[argIndex++]);
            from = at + 2;
        }
        sb.append(message, from, message.length());

        // A trailing Throwable without a placeholder is printed as a stack trace in debug mode.
        Throwable trailing = argIndex < args.length && args[args.length - 1] instanceof Throwable t ? t : null;
        PrintStream stream = out;
        synchronized (Log.class) {
            stream.println(sb);
            if (trailing != null && threshold == Level.DEBUG) {
                trailing.printStackTrace(stream);
            }
        }
    }
}
