package com.example.mideditor.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 轻量日志：输出到「输出」面板，同时保留最近记录。 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final List<String> LINES = new CopyOnWriteArrayList<>();
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private Log() {
    }

    public static void info(String message) {
        publish("INFO", message);
    }

    public static void warn(String message) {
        publish("WARN", message);
    }

    public static void error(String message) {
        publish("ERROR", message);
    }

    private static void publish(String level, String message) {
        String line = LocalTime.now().format(TIME) + " [" + level + "] " + message;
        LINES.add(line);
        if (LINES.size() > 2000) {
            LINES.remove(0);
        }
        for (Consumer<String> listener : LISTENERS) {
            listener.accept(line);
        }
    }

    public static List<String> lines() {
        return List.copyOf(LINES);
    }

    public static void addListener(Consumer<String> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
    }
}
