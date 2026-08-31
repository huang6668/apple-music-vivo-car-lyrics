package com.apple.android.music.player;

public final class ClusterLyricsPaginatorTest {
    public static void main(String[] args) {
        testPageBoundaries();
        testSurrogatePairs();
        testReadableBreaks();
        testTimestampAllocation();
        testTimestampEdgeCases();
        testUnorderedInput();
        System.out.println("ClusterLyricsPaginator tests passed");
    }

    private static void testPageBoundaries() {
        assertPageLengths(repeat('a', 20), 20);
        assertPageLengths(repeat('a', 21), 20, 1);
        assertPageLengths(repeat('a', 41), 20, 20, 1);
        assertPageLengths(repeat('\u4e00', 20), 20);
        assertPageLengths(repeat('\u4e00', 21), 20, 1);
    }

    private static void testSurrogatePairs() {
        String emoji = "\ud83d\ude00";
        assertPageLengths(repeat('a', 18) + emoji, 20);
        assertPageLengths(repeat('a', 19) + emoji, 19, 2);
        assertPageLengths(emoji + emoji + emoji + emoji + emoji + emoji
                + emoji + emoji + emoji + emoji + emoji, 20, 2);
    }

    private static void testReadableBreaks() {
        assertPages("hello world from an instrument cluster",
                "hello world from an", "instrument cluster");
        assertPageLengths("abcdefghijklmnopqrstu", 20, 1);
        assertPages("1234567890,abcdefghij-klmnop", "1234567890,", "abcdefghij-klmnop");
        assertPages("1234567890\uff0cabcdefghij\u7532\u4e59\u4e19",
                "1234567890\uff0c", "abcdefghij\u7532\u4e59\u4e19");
        assertPages("  hello\t\nworld   ", "hello world");
    }

    private static void testTimestampAllocation() {
        assertTimes(result(new long[]{1000L, 5000L}, new String[]{repeat('a', 21), "next"}, 0L),
                1000L, 3000L, 5000L);
        assertTimes(result(new long[]{1000L, 5000L}, new String[]{repeat('a', 41), "next"}, 0L),
                1000L, 2333L, 3666L, 5000L);
        assertTimes(result(new long[]{0L, 20000L}, new String[]{repeat('a', 21), "next"}, 0L),
                0L, 2200L, 20000L);
        assertTimes(result(new long[]{9000L}, new String[]{repeat('a', 41)}, 15000L),
                9000L, 11000L, 13000L);
        ClusterLyricsPaginator.Result unknownDuration = result(
                new long[]{9000L}, new String[]{repeat('a', 41)}, 0L);
        assertTimes(unknownDuration, 9000L, 11200L, 13400L);
        assertTrue(unknownDuration.whole.contains("[00:11.200]"), "millisecond LRC timestamp missing");
    }

    private static void testTimestampEdgeCases() {
        ClusterLyricsPaginator.Result tinyInterval = result(
                new long[]{1000L, 1001L}, new String[]{repeat('a', 41), "next"}, 0L);
        assertTimes(tinyInterval, 1000L, 1001L, 1002L, 1003L);
        assertStrictlyIncreasing(tinyInterval);

        ClusterLyricsPaginator.Result duplicateStarts = result(
                new long[]{1000L, 1000L, 1001L},
                new String[]{"first", "second", "third"}, 0L);
        assertTimes(duplicateStarts, 1000L, 1001L, 1002L);
        assertStrictlyIncreasing(duplicateStarts);

        ClusterLyricsPaginator.Result saturated = result(
                new long[]{Long.MAX_VALUE - 1L}, new String[]{repeat('a', 41)}, Long.MAX_VALUE);
        assertTimes(saturated, Long.MAX_VALUE - 1L, Long.MAX_VALUE, Long.MAX_VALUE);
        assertNonDecreasing(saturated);
    }

    private static void testUnorderedInput() {
        ClusterLyricsPaginator.Result unordered = result(
                new long[]{5000L, 1000L, 3000L},
                new String[]{"late", "early", "middle"}, 7000L);
        assertTimes(unordered, 1000L, 3000L, 5000L);
        assertTexts(unordered, "early", "middle", "late");
        assertStrictlyIncreasing(unordered);
    }

    private static ClusterLyricsPaginator.Result result(long[] times, String[] texts, long duration) {
        return ClusterLyricsPaginator.paginate(times, texts, duration);
    }

    private static void assertPageLengths(String input, int... lengths) {
        String[] pages = ClusterLyricsPaginator.splitPages(input);
        assertTrue(pages.length == lengths.length,
                "page count: expected " + lengths.length + " but was " + pages.length);
        for (int index = 0; index < pages.length; index++) {
            assertTrue(pages[index].length() == lengths[index],
                    "page " + index + " length: expected " + lengths[index]
                            + " but was " + pages[index].length());
            assertTrue(pages[index].length() <= ClusterLyricsPaginator.MAX_PAGE_UTF16,
                    "page exceeds cluster limit");
            assertValidSurrogates(pages[index]);
        }
    }

    private static void assertPages(String input, String... expected) {
        String[] pages = ClusterLyricsPaginator.splitPages(input);
        assertTrue(pages.length == expected.length,
                "page count: expected " + expected.length + " but was " + pages.length);
        for (int index = 0; index < pages.length; index++) {
            assertTrue(expected[index].equals(pages[index]),
                    "page " + index + ": expected '" + expected[index] + "' but was '"
                            + pages[index] + "'");
        }
    }

    private static void assertTimes(ClusterLyricsPaginator.Result result, long... expected) {
        assertTrue(result.times.length == expected.length,
                "time count: expected " + expected.length + " but was " + result.times.length);
        for (int index = 0; index < expected.length; index++) {
            assertTrue(result.times[index] == expected[index],
                    "time " + index + ": expected " + expected[index]
                            + " but was " + result.times[index]);
        }
    }

    private static void assertTexts(ClusterLyricsPaginator.Result result, String... expected) {
        assertTrue(result.texts.length == expected.length,
                "text count: expected " + expected.length + " but was " + result.texts.length);
        for (int index = 0; index < expected.length; index++) {
            assertTrue(expected[index].equals(result.texts[index]),
                    "text " + index + ": expected '" + expected[index] + "' but was '"
                            + result.texts[index] + "'");
        }
    }

    private static void assertStrictlyIncreasing(ClusterLyricsPaginator.Result result) {
        for (int index = 1; index < result.times.length; index++) {
            assertTrue(result.times[index] > result.times[index - 1],
                    "timestamps are not strictly increasing at index " + index);
        }
    }

    private static void assertNonDecreasing(ClusterLyricsPaginator.Result result) {
        for (int index = 1; index < result.times.length; index++) {
            assertTrue(result.times[index] >= result.times[index - 1],
                    "timestamps regress at index " + index);
        }
    }

    private static void assertValidSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                assertTrue(index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1)),
                        "orphan high surrogate");
                index++;
            } else {
                assertTrue(!Character.isLowSurrogate(unit), "orphan low surrogate");
            }
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
