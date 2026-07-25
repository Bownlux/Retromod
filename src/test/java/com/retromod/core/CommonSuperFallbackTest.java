package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Covers the {@code getCommonSuperClass} fallback used when ASM can't resolve a type during
 * {@code COMPUTE_FRAMES}. Two opposite failures have to be threaded:
 * <ul>
 *   <li>{@code #94} (forge-config-api-port {@code ConfigTracker}): merging two exception types
 *       must stay {@code Throwable}, not collapse to {@code Object}, or a catch-handler join fails
 *       verification.
 *   <li>jade {@code CommonProxy.lambda$loadComplete$5}: merging a {@code Throwable} with a plain
 *       non-exception ({@code ModMetadata}, at a trailing {@code return} that both the try's
 *       {@code goto} and the catch's rethrow fall into) must be {@code Object}, not
 *       over-generalized to {@code Throwable}, or a predecessor providing {@code ModMetadata}
 *       fails the frame check with a {@code VerifyError} at load.
 * </ul>
 * The distinguisher is whether an operand is provably a Throwable, provably not one, or
 * unresolvable (a JiJ/mod class); the unresolvable case leans on the {@code *Exception} naming
 * convention.
 */
class CommonSuperFallbackTest {

    @Test
    void throwableMergedWithUnresolvableExceptionBecomesThrowableNotObject() {
        // ConfigTracker (#94): a JDK exception merged with a JiJ-bundled exception the transformer
        // can't resolve, but whose name follows the convention.
        assertEquals("java/lang/Throwable",
                RetromodTransformer.commonSuperFallback("java/io/IOException", "com/example/JijParsingException"));
        // order-independent
        assertEquals("java/lang/Throwable",
                RetromodTransformer.commonSuperFallback("com/example/JijParsingException", "java/io/IOException"));
    }

    @Test
    void twoThrowableSubtypesMergeToThrowable() {
        assertEquals("java/lang/Throwable",
                RetromodTransformer.commonSuperFallback("java/lang/RuntimeException", "java/lang/Error"));
    }

    @Test
    void throwableMergedWithProvableNonExceptionBecomesObject() {
        // A guaranteed-loadable JDK non-exception: provably not a Throwable -> Object.
        assertEquals("java/lang/Object",
                RetromodTransformer.commonSuperFallback("java/lang/Throwable", "java/util/HashMap"));
        assertEquals("java/lang/Object",
                RetromodTransformer.commonSuperFallback("java/util/HashMap", "java/lang/Throwable"));
    }

    @Test
    void throwableMergedWithModMetadataBecomesObject() {
        // The real jade CommonProxy shape: Throwable (catch, local 3) merged with Fabric's
        // ModMetadata (try, local 3) at a shared trailing return. Object either way: if the
        // Fabric loader is on the classpath it is a provable non-Throwable, and if it isn't the
        // unresolvable name carries no exception hint, so both paths land on Object (never the
        // over-specific Throwable that produced the VerifyError).
        assertEquals("java/lang/Object",
                RetromodTransformer.commonSuperFallback(
                        "java/lang/Throwable", "net/fabricmc/loader/api/metadata/ModMetadata"));
    }

    @Test
    void throwableMergedWithUnresolvableNonExceptionNameBecomesObject() {
        // Unresolvable AND not exception-named: the safe merge with a Throwable is Object. This is
        // the jade shape for a mod value whose bytes we can't read (name carries no exception hint).
        assertEquals("java/lang/Object",
                RetromodTransformer.commonSuperFallback("java/lang/Throwable", "com/example/ModMetadata"));
        assertEquals("java/lang/Object",
                RetromodTransformer.commonSuperFallback("com/example/ModMetadata", "java/lang/Throwable"));
    }

    @Test
    void twoUnresolvableExceptionNamedTypesMergeToThrowable() {
        // Both unresolvable but both exception-named: almost certainly a catch-handler join of two
        // JiJ exceptions -> Throwable (same spirit as #94).
        assertEquals("java/lang/Throwable",
                RetromodTransformer.commonSuperFallback("com/example/FooException", "com/example/BarError"));
    }

    @Test
    void nonThrowableMergesStayObject() {
        // Neither operand is (or can be shown to be) a Throwable, so Object.
        assertEquals("java/lang/Object",
                RetromodTransformer.commonSuperFallback("java/lang/String", "com/example/Unknown"));
        assertEquals("java/lang/Object",
                RetromodTransformer.commonSuperFallback("com/example/UnknownA", "com/example/UnknownB"));
    }
}
