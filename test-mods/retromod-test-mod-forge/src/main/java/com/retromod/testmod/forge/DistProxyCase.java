/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * A side proxy picked with {@code DistExecutor} in a static initializer, as Alex's Mobs builds its
 * {@code PROXY}. Forge 26.2 removed {@code DistExecutor}. Kept out of the mod class, so a missing
 * class fails only its own check.
 */
final class DistProxyCase {

    private static int clientCalls;
    private static int serverCalls;

    // Runs while this class initializes, the way an @Mod class's PROXY field does.
    @SuppressWarnings("deprecation")
    static final String PROXY = DistExecutor.runForDist(
            () -> () -> {
                clientCalls++;
                return "client";
            },
            () -> () -> {
                serverCalls++;
                return "server";
            });

    private DistProxyCase() {}

    /** The static initializer picked this side's proxy and never asked the other side for one. */
    static boolean pickedThisSide() {
        boolean client = FMLEnvironment.dist == Dist.CLIENT;
        return PROXY.equals(client ? "client" : "server")
                && clientCalls == (client ? 1 : 0) && serverCalls == (client ? 0 : 1);
    }

    /** The run and call forms, safe ones included, run only on the side they name. */
    static boolean runsOnlyOnTheNamedSide() throws Exception {
        Dist here = FMLEnvironment.dist;
        Dist other = here == Dist.CLIENT ? Dist.DEDICATED_SERVER : Dist.CLIENT;
        int[] runs = new int[2];
        DistExecutor.safeRunWhenOn(here, () -> Sides::noteRun);
        DistExecutor.unsafeRunWhenOn(here, () -> () -> runs[0]++);
        DistExecutor.unsafeRunWhenOn(other, () -> () -> runs[1]++);
        String called = DistExecutor.unsafeCallWhenOn(here, () -> () -> "called");
        String skipped = DistExecutor.unsafeCallWhenOn(other, () -> () -> "called");
        String safe = DistExecutor.safeRunForDist(() -> Sides::clientName, () -> Sides::serverName);
        return Sides.noted == 1 && runs[0] == 1 && runs[1] == 0 && "called".equals(called) && skipped == null
                && safe.equals(here == Dist.CLIENT ? "client" : "server");
    }

    // Safe referents name a method of another class, as a mod's ClientProxy::new does.
    static final class Sides {
        static int noted;

        static void noteRun() {
            noted++;
        }

        static String clientName() {
            return "client";
        }

        static String serverName() {
            return "server";
        }
    }
}
