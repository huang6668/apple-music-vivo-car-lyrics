package com.apple.android.music.player;

public final class ChineseConverterTest {
    public static void main(String[] args) {
        testEdgeCases();
        testTraditionalToSimplified();
        testAlreadySimplifiedAndAscii();
        testIdempotent();
        System.out.println("ChineseConverter tests passed");
    }

    private static void testEdgeCases() {
        check(ChineseConverter.toSimplified(null) == null, "null remains null");
        check("".equals(ChineseConverter.toSimplified("")), "empty remains empty");
    }

    private static void testAlreadySimplifiedAndAscii() {
        equal("Cheers!", ChineseConverter.toSimplified("Cheers!"));
        equal("Jay Chou", ChineseConverter.toSimplified("Jay Chou"));
        equal("我怎么哭了", ChineseConverter.toSimplified("我怎么哭了"));
        equal("晴天", ChineseConverter.toSimplified("晴天"));
    }

    private static void testTraditionalToSimplified() {
        equal("干啦 干啦 (feat. 阿信 & 任贤齐)",
                ChineseConverter.toSimplified("乾啦 乾啦 (feat. 阿信 & 任賢齊)"));
        equal("圣诞星 (feat. 杨瑞代)",
                ChineseConverter.toSimplified("聖誕星 (feat. 楊瑞代)"));
        equal("我不想改变世界 我只想不被世界改变",
                ChineseConverter.toSimplified("我不想改變世界 我只想不被世界改變"));
        equal("日落 (孙燕姿)",
                ChineseConverter.toSimplified("日落 (孫燕姿)"));
        equal("稻香 (周杰伦)",
                ChineseConverter.toSimplified("稻香 (周杰倫)"));
    }

    private static void testIdempotent() {
        String trad = "乾啦 乾啦 (feat. 阿信 & 任賢齊)";
        String simp1 = ChineseConverter.toSimplified(trad);
        String simp2 = ChineseConverter.toSimplified(simp1);
        equal(simp1, simp2);
    }

    private static void equal(String expected, String actual) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Expected '" + expected + "' but got '" + actual + "'");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
