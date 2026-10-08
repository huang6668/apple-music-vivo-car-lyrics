import dev.amenhancer.compat.CatalogQueryMethod;
import java.lang.reflect.Method;
import java.util.Map;
import kotlin.coroutines.Continuation;

public final class CatalogQueryMethodTest {
    public static class Author {
        public Object B(String path, Map<?, ?> parameters, Continuation continuation) {
            return null;
        }
    }

    public static class Unknown {
        public Object x(String path, Map<?, ?> parameters, Continuation continuation) {
            return null;
        }
    }

    public static class Ambiguous {
        public Object x(String path, Map<?, ?> parameters, Continuation continuation) {
            return null;
        }

        public Object y(String path, Map<?, ?> parameters, Continuation continuation) {
            return null;
        }
    }

    public static class WrongParameters {
        public Object B(String path, Map<?, ?> parameters, Object continuation) {
            return null;
        }
    }

    public static class WrongReturn {
        public String B(String path, Map<?, ?> parameters, Continuation continuation) {
            return null;
        }
    }

    public static class WrongStatic {
        public static Object B(String path, Map<?, ?> parameters, Continuation continuation) {
            return null;
        }
    }

    public static class Inherited extends Author {}

    public static void main(String[] args) throws Exception {
        Method remapped = CatalogQueryMethod.resolve(s8.F.class, "B");
        if (!remapped.getName().equals("x")
                || !"catalog".equals(remapped.invoke(new s8.F(), "path", null, null))) {
            throw new AssertionError("r38 catalog mapping failed");
        }
        if (!CatalogQueryMethod.resolve(Author.class, "B").getName().equals("B")
                || CatalogQueryMethod.resolve(Inherited.class, "B").getDeclaringClass() != Author.class) {
            throw new AssertionError("Preferred method or inherited lookup changed");
        }
        // Apple Music 7 renames the catalog query again; a single method with the exact
        // signature on the class AM++ already located is accepted as the query.
        if (!CatalogQueryMethod.resolve(Unknown.class, "B").getName().equals("x")
                || !CatalogQueryMethod.resolve(s8.F.class, "unrecognized").getName().equals("x")) {
            throw new AssertionError("Unique signature fallback failed");
        }
        for (Class<?> clazz : new Class<?>[] {
                Ambiguous.class, WrongParameters.class, WrongReturn.class, WrongStatic.class}) {
            expectMissing(clazz, "B");
        }
        System.out.println("Catalog query compatibility tests passed");
    }

    private static void expectMissing(Class<?> clazz, String name) throws Exception {
        try {
            CatalogQueryMethod.resolve(clazz, name);
            throw new AssertionError("Unexpected catalog match: " + clazz.getName());
        } catch (NoSuchMethodException expected) {
            // Unknown variants must fail closed instead of invoking a similarly shaped method.
        }
    }
}
