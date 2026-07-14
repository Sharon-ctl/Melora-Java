package com.discord.musicbot.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KaraokeManager {
    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})[\\.:](\\d{1,3})\\](.*)");

    public static class LrcLine {
        public final long timestampMs;
        public final String text;

        public LrcLine(long timestampMs, String text) {
            this.timestampMs = timestampMs;
            this.text = text;
        }
    }

    public static List<LrcLine> parseLrc(String lrc) {
        List<LrcLine> lines = new ArrayList<>();
        if (lrc == null || lrc.isBlank()) return lines;

        for (String line : lrc.split("\n")) {
            Matcher m = LRC_PATTERN.matcher(line.trim());
            if (m.matches()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = Long.parseLong(m.group(3));
                if (m.group(3).length() == 1) {
                    ms *= 100;
                } else if (m.group(3).length() == 2) {
                    ms *= 10;
                }
                long timestamp = (min * 60 * 1000) + (sec * 1000) + ms;
                String text = m.group(4).trim();
                lines.add(new LrcLine(timestamp, text));
            }
        }
        lines.sort(java.util.Comparator.comparingLong(l -> l.timestampMs));
        return lines;
    }

    public static int getActiveLineIndex(List<LrcLine> lines, long currentPositionMs) {
        if (lines == null || lines.isEmpty()) return -1;

        int activeIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (currentPositionMs >= lines.get(i).timestampMs) {
                activeIdx = i;
            } else {
                break;
            }
        }
        return activeIdx;
    }

    public static List<LrcLine> getActiveLineWindow(List<LrcLine> lines, int activeIndex, int windowBefore, int windowAfter) {
        if (lines == null || lines.isEmpty()) return new ArrayList<>();
        int start = Math.max(0, activeIndex - windowBefore);
        int end = Math.min(lines.size() - 1, Math.max(0, activeIndex) + windowAfter);
        return new ArrayList<>(lines.subList(start, end + 1));
    }

    public static String getActiveLine(List<LrcLine> lines, long currentPositionMs) {
        int idx = getActiveLineIndex(lines, currentPositionMs);
        if (idx >= 0 && idx < lines.size()) {
            return lines.get(idx).text;
        } else if (lines != null && !lines.isEmpty()) {
            return lines.get(0).text;
        }
        return null;
    }
}
