package com.apple.android.music.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ClusterLyricsPaginator {
    static final int MAX_PAGE_UTF16 = 20;
    static final long TARGET_PAGE_MS = 2200L;
    private static final long DEFAULT_LAST_LINE_MS = 5000L;

    static final class Result {
        final long[] times;
        final String[] texts;
        final String whole;

        Result(long[] times, String[] texts, String whole) {
            this.times = times;
            this.texts = texts;
            this.whole = whole;
        }
    }

    private ClusterLyricsPaginator() {
    }

    static Result paginate(long[] sourceTimes, String[] sourceTexts, long duration) {
        int count = Math.min(sourceTimes == null ? 0 : sourceTimes.length,
                sourceTexts == null ? 0 : sourceTexts.length);
        if (count == 0) {
            return new Result(new long[0], new String[0], "");
        }

        List<Integer> order = new ArrayList<Integer>(count);
        for (int index = 0; index < count; index++) {
            order.add(Integer.valueOf(index));
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer left, Integer right) {
                long leftTime = normalizedTime(sourceTimes[left.intValue()]);
                long rightTime = normalizedTime(sourceTimes[right.intValue()]);
                if (leftTime < rightTime) {
                    return -1;
                }
                if (leftTime > rightTime) {
                    return 1;
                }
                return left.intValue() < right.intValue() ? -1
                        : (left.intValue() == right.intValue() ? 0 : 1);
            }
        });

        List<Long> pageTimes = new ArrayList<Long>();
        List<String> pageTexts = new ArrayList<String>();
        for (int orderIndex = 0; orderIndex < count; orderIndex++) {
            int index = order.get(orderIndex).intValue();
            String[] pages = splitPages(sourceTexts[index]);
            if (pages.length == 0) {
                continue;
            }

            long start = normalizedTime(sourceTimes[index]);
            long end = nextBoundary(sourceTimes, order, orderIndex, start, duration, pages.length);
            long span = Math.max(1L, end - start);
            long targetWindow = Math.max(1L, pages.length * TARGET_PAGE_MS);
            long window = Math.min(span, targetWindow);

            for (int page = 0; page < pages.length; page++) {
                long time = saturatedAdd(start, distributedOffset(window, page, pages.length));
                if (time >= end) {
                    time = end - 1L;
                }
                time = Math.max(start, time);
                // Preserve every page when its source interval is too short by spilling the
                // minimum 1 ms needed to keep the global timeline ordered.
                if (!pageTimes.isEmpty()) {
                    long previous = pageTimes.get(pageTimes.size() - 1).longValue();
                    if (time <= previous) {
                        time = previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1L;
                    }
                }
                pageTimes.add(Long.valueOf(time));
                pageTexts.add(pages[page]);
            }
        }

        long[] times = new long[pageTimes.size()];
        String[] texts = pageTexts.toArray(new String[pageTexts.size()]);
        StringBuilder lrc = new StringBuilder();
        for (int index = 0; index < times.length; index++) {
            times[index] = pageTimes.get(index).longValue();
            lrc.append(formatTime(times[index])).append(texts[index]);
            if (index + 1 < times.length) {
                lrc.append('\n');
            }
        }
        return new Result(times, texts, lrc.toString());
    }

    static Result paginate(long[] sourceTimes, List<String> sourceTexts, long duration) {
        if (sourceTexts == null) {
            return paginate(sourceTimes, (String[]) null, duration);
        }
        return paginate(sourceTimes,
                sourceTexts.toArray(new String[sourceTexts.size()]), duration);
    }

    static String[] splitPages(String text) {
        String normalized = normalizeWhitespace(text);
        if (normalized.isEmpty()) {
            return new String[0];
        }

        List<String> pages = new ArrayList<String>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + MAX_PAGE_UTF16);
            if (hardEnd < normalized.length()
                    && hardEnd > start
                    && Character.isHighSurrogate(normalized.charAt(hardEnd - 1))
                    && Character.isLowSurrogate(normalized.charAt(hardEnd))) {
                hardEnd--;
            }

            int end = hardEnd;
            if (hardEnd < normalized.length()) {
                int softEnd = findSoftEnd(normalized, start, hardEnd);
                if (softEnd > start) {
                    end = softEnd;
                }
            }

            String page = normalized.substring(start, end).trim();
            if (page.isEmpty()) {
                end = hardEnd;
                page = normalized.substring(start, end).trim();
            }
            if (!page.isEmpty()) {
                pages.add(page);
            }

            start = end;
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                start++;
            }
        }
        return pages.toArray(new String[pages.size()]);
    }

    private static int findSoftEnd(String text, int start, int hardEnd) {
        int minimum = start + MAX_PAGE_UTF16 / 2;
        for (int index = hardEnd - 1; index >= minimum; index--) {
            char value = text.charAt(index);
            if (Character.isWhitespace(value)) {
                return index;
            }
            if (isBreakPunctuation(value)) {
                return index + 1;
            }
        }
        return -1;
    }

    private static boolean isBreakPunctuation(char value) {
        return value == ',' || value == '.' || value == ';' || value == ':'
                || value == '!' || value == '?' || value == '/' || value == '-'
                || value == '\u3001' || value == '\u3002' || value == '\uff0c'
                || value == '\uff1b' || value == '\uff1a' || value == '\uff01'
                || value == '\uff1f';
    }

    private static String normalizeWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString().trim();
    }

    private static long nextBoundary(long[] sourceTimes, List<Integer> order, int orderIndex,
                                     long start, long duration, int pageCount) {
        for (int next = orderIndex + 1; next < order.size(); next++) {
            long candidate = normalizedTime(sourceTimes[order.get(next).intValue()]);
            if (candidate > start) {
                return candidate;
            }
        }
        long safeDuration = normalizedTime(duration);
        if (safeDuration > start) {
            return safeDuration;
        }
        long fallback = Math.max(DEFAULT_LAST_LINE_MS, pageCount * TARGET_PAGE_MS);
        return saturatedAdd(start, fallback);
    }

    private static long normalizedTime(long millis) {
        return Math.max(0L, millis);
    }

    private static long distributedOffset(long window, int page, int pageCount) {
        long quotient = window / pageCount;
        long remainder = window % pageCount;
        return quotient * page + (remainder * page) / pageCount;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static String formatTime(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60000L;
        long seconds = (safe % 60000L) / 1000L;
        long milliseconds = safe % 1000L;
        return String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, milliseconds);
    }
}
