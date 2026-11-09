/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.fml;

import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forge 1.20.1's {@code DistExecutor}, which Forge 26.2 removed: runs code only on the side it names. */
public final class DistExecutor {

    private DistExecutor() {}

    public static <T> T callWhenOn(Dist dist, Supplier<Callable<T>> toRun) {
        return call(dist, toRun);
    }

    public static <T> T unsafeCallWhenOn(Dist dist, Supplier<Callable<T>> toRun) {
        return call(dist, toRun);
    }

    public static <T> T safeCallWhenOn(Dist dist, Supplier<SafeCallable<T>> toRun) {
        return call(dist, toRun);
    }

    public static void runWhenOn(Dist dist, Supplier<Runnable> toRun) {
        run(dist, toRun);
    }

    public static void unsafeRunWhenOn(Dist dist, Supplier<Runnable> toRun) {
        run(dist, toRun);
    }

    public static void safeRunWhenOn(Dist dist, Supplier<SafeRunnable> toRun) {
        run(dist, toRun);
    }

    public static <T> T runForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget) {
        return forDist(clientTarget, serverTarget);
    }

    public static <T> T unsafeRunForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget) {
        return forDist(clientTarget, serverTarget);
    }

    public static <T> T safeRunForDist(Supplier<SafeSupplier<T>> clientTarget, Supplier<SafeSupplier<T>> serverTarget) {
        return forDist(clientTarget, serverTarget);
    }

    // The supplier is asked only on its own side, so the other side's classes never load.
    private static void run(Dist dist, Supplier<? extends Runnable> toRun) {
        if (FMLEnvironment.dist == dist) {
            toRun.get().run();
        }
    }

    // Forge 1.20.1 threw any exception from the call wrapped in a RuntimeException.
    private static <T> T call(Dist dist, Supplier<? extends Callable<T>> toCall) {
        if (FMLEnvironment.dist == dist) {
            try {
                return toCall.get().call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    private static <T> T forDist(Supplier<? extends Supplier<T>> clientTarget,
            Supplier<? extends Supplier<T>> serverTarget) {
        Supplier<? extends Supplier<T>> chosen = FMLEnvironment.dist == Dist.CLIENT ? clientTarget : serverTarget;
        return chosen.get().get();
    }

    /** Marks a lambda that names the other side's classes only inside its body. */
    public interface SafeReferent {
    }

    public interface SafeRunnable extends SafeReferent, Runnable, Serializable {
    }

    public interface SafeCallable<T> extends SafeReferent, Callable<T>, Serializable {
    }

    public interface SafeSupplier<T> extends SafeReferent, Supplier<T>, Serializable {
    }
}
