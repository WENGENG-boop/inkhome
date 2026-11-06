package java.lang.invoke;

/** Compile-time stand-in: android.jar omits this class, and d8 desugars lambdas away. */
public final class LambdaMetafactory {
    public static CallSite metafactory(MethodHandles.Lookup a, String b, MethodType c, MethodType d, MethodHandle e, MethodType f) {
        return null;
    }
}
