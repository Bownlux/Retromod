/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.core.ModStateProvider;

/** Holds an old model event back until Forge has finished common setup, which old listeners such as Patchouli's wait for. */
final class LegacySetupGate {

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long MAX_WAIT_NANOS = TimeUnit.MINUTES.toNanos(10);

    private LegacySetupGate() {}

    /**
     * Whether common setup is done, waiting for it off {@code gameThread}. The game's own thread never waits, and a
     * failed or stuck setup answers false, so the caller posts nothing rather than block the game.
     */
    static boolean awaitCommonSetup(Thread gameThread) {
        if (commonSetupDone()) return true;
        if (Thread.currentThread() == gameThread) return false;
        long deadline = System.nanoTime() + MAX_WAIT_NANOS;
        // A managed block lets a worker pool start another thread meanwhile, so setup work on that pool still runs.
        ForkJoinPool.ManagedBlocker setup = new ForkJoinPool.ManagedBlocker() {
            @Override
            public boolean block() {
                LockSupport.parkNanos(POLL_NANOS);
                return isReleasable();
            }

            @Override
            public boolean isReleasable() {
                return commonSetupDone() || !loadingValid() || System.nanoTime() > deadline;
            }
        };
        try {
            ForkJoinPool.managedBlock(setup);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (commonSetupDone()) return true;
        if (loadingValid()) LegacyLog.warn("Forge's common setup did not finish in 10 minutes, so Retromod did not ask old mods for"
                + " their extra models during this resource reload");
        return false;
    }

    /** Whether Forge has finished common setup; polled, because a setup that fails never reports done. */
    static boolean commonSetupDone() {
        try {
            return ModLoader.hasCompletedState(ModStateProvider.COMMON_SETUP);
        } catch (LinkageError noModLoader) {
            // Without FML's loading states there is no setup to wait for.
            return true;
        }
    }

    private static boolean loadingValid() {
        try {
            return ModLoader.isLoadingStateValid();
        } catch (LinkageError noModLoader) {
            return true;
        }
    }
}
