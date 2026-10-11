package com.undyingdominion.tui;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps the most recent log lines for the dashboard. While the dashboard owns the terminal, anything
 * written to System.out or System.err lands here instead of scribbling over the screen.
 */
public class LogBuffer {

    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();

    public LogBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String line) {
        String clean = line.replace("\t", "    ").replaceAll("\\p{Cntrl}", "");
        if (clean.isBlank()) return;
        lines.addLast(clean);
        while (lines.size() > capacity) lines.removeFirst();
    }

    /** The newest {@code count} lines, oldest first. */
    public synchronized List<String> tail(int count) {
        List<String> all = new ArrayList<>(lines);
        return all.subList(Math.max(0, all.size() - count), all.size());
    }

    /** A stream that splits what is written to it into lines in this buffer. */
    public PrintStream stream() {
        return new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                if (b == '\n') {
                    add(pending.toString(StandardCharsets.UTF_8));
                    pending.reset();
                } else if (b != '\r') {
                    pending.write(b);
                }
            }
        }, true, StandardCharsets.UTF_8);
    }
}
