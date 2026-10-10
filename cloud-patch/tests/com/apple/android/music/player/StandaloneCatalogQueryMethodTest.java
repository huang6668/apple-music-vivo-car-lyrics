package com.apple.android.music.player;

import java.util.Map;
import kotlin.coroutines.Continuation;

public final class StandaloneCatalogQueryMethodTest {
    public static class Preferred {
        public Object B(String p, Map<?, ?> m, Continuation c) { return null; }
        public Object other(String p, Map<?, ?> m, Continuation c) { return null; }
    }
    public static class Inherited extends Preferred {}
    public static class Unique {
        public Object x(String p, Map<?, ?> m, Continuation c) { return null; }
    }
    public static class Override extends Unique {
        public Object x(String p, Map<?, ?> m, Continuation c) { return null; }
    }
    public static class Ambiguous extends Unique {
        public Object y(String p, Map<?, ?> m, Continuation c) { return null; }
    }
    public static class WrongReturn {
        public String F(String p, Map<?, ?> m, Continuation c) { return null; }
    }
    public static class WrongStatic {
        public static Object F(String p, Map<?, ?> m, Continuation c) { return null; }
    }
    public static class WrongParameters {
        public Object F(String p, Map<?, ?> m, Object c) { return null; }
    }
    public static class WrongMap {
        public Object F(String p, java.util.HashMap<?, ?> m, Continuation c) { return null; }
    }
    public static void main(String[] args) throws Exception {
        equal("F", CatalogQueryMethod.resolve(w9.Q.class, "unknown").getName());
        equal("F", CatalogQueryMethod.resolve(w9.a.class, "unknown").getName());
        equal("x", CatalogQueryMethod.resolve(s8.F.class, "unknown").getName());
        equal("v", CatalogQueryMethod.resolve(u8.E.class, "unknown").getName());
        equal("B", CatalogQueryMethod.resolve(Preferred.class, "B").getName());
        equal(Preferred.class, CatalogQueryMethod.resolve(Inherited.class, "B").getDeclaringClass());
        equal("x", CatalogQueryMethod.resolve(Unique.class, "unknown").getName());
        equal(Override.class, CatalogQueryMethod.resolve(Override.class, "unknown").getDeclaringClass());
        for (Class<?> c : new Class<?>[]{Ambiguous.class, Preferred.class, WrongReturn.class,
                WrongStatic.class, WrongParameters.class, WrongMap.class}) {
            try {
                CatalogQueryMethod.resolve(c, "unknown");
                throw new AssertionError("Unexpected catalog match: " + c.getName());
            } catch (NoSuchMethodException expected) {
            }
        }
        System.out.println("StandaloneCatalogQueryMethod tests passed");
    }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
