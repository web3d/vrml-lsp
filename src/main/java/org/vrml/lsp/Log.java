package org.vrml.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal logger that never touches {@code System.out}.
 *
 * <p> stdout is the JSON-RPC channel; a single stray {@code System.out.println}
 * from anywhere in the process corrupts the stream and the client starts
 * reporting framing errors far from the cause, so logging is centralised here
 * and only here.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static Path file;

    private Log() {
    }

    /** Redirect to a file when {@code -Dvrml.lsp.log=<path>} is set; keep stderr too. */
    public static synchronized void init() {
        String path = System.getProperty("vrml.lsp.log");
        if (path == null || path.isBlank()) {
            return;
        }
        file = Path.of(path);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            write("---- vrml-lsp log start ----");
        } catch (IOException e) {
            System.err.println("vrml-lsp: cannot open log file " + path + ": " + e);
            file = null;
        }
    }

    public static void info(String msg) {
        emit("INFO ", msg, null);
    }

    public static void warn(String msg) {
        emit("WARN ", msg, null);
    }

    public static void error(String msg, Throwable t) {
        emit("ERROR", msg, t);
    }

    private static void emit(String level, String msg, Throwable t) {
        String line = LocalTime.now().format(TS) + " " + level + " " + msg;
        System.err.println(line);
        if (t != null) {
            t.printStackTrace(System.err);
        }
        if (file != null) {
            write(line);
            if (t != null) {
                var sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                for (String s : sw.toString().split("\n")) {
                    write("      " + s);
                }
            }
        }
    }

    private static void write(String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("vrml-lsp: log write failed: " + e);
        }
    }
}
