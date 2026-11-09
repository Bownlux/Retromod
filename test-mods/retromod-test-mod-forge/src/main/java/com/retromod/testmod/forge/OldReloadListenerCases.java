/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Reload listeners of the 1.20.1 shape, which 1.21.2 and 1.21.9 changed: a class, a method reference and a lambda. */
final class OldReloadListenerCases {

    private static final String CLASS_CASE = "An old-shape reload listener class prepares and applies (client resource reload)";
    private static final String REFERENCE_CASE = "An old-shape method reference on the resource manager runs, as GeckoLib's"
            + " cache does (client resource reload)";
    private static final String LAMBDA_CASE = "An old-shape capturing lambda runs (client resource reload)";
    private static final String SERVER_CASE = "An old-shape server reload listener prepares and applies (data pack load)";

    private static final Set<String> finished = ConcurrentHashMap.newKeySet();
    private static boolean clientReported;
    private static boolean serverReported;

    private OldReloadListenerCases() {}

    /** The server's data reload, on both sides. */
    static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(OldReloadListenerCases::onAddReloadListeners);
            MinecraftForge.EVENT_BUS.addListener(OldReloadListenerCases::onServerTick);
        } catch (Throwable t) {
            log(SERVER_CASE, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** The client's resource reload: on the resource manager while the mod constructs, as GeckoLib does, and through Forge's event. */
    static void registerClient(IEventBus modBus) {
        try {
            modBus.addListener(OldReloadListenerCases::onClientReloadListeners);
            MinecraftForge.EVENT_BUS.addListener(OldReloadListenerCases::onClientTick);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.getResourceManager() instanceof ReloadableResourceManager manager) {
                manager.registerReloadListener(OldReloadListenerCases::reloadModels);
            } else {
                log(REFERENCE_CASE, "fail: no reloadable resource manager while the mod constructs");
            }
        } catch (Throwable t) {
            log("Old-shape client reload listeners register (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onClientReloadListeners(RegisterClientReloadListenersEvent event) {
        try {
            event.registerReloadListener(new ResourceCounter(CLASS_CASE, "textures"));
            String label = LAMBDA_CASE;
            event.registerReloadListener((barrier, resources, preparations, reloads, background, game) ->
                    barrier.wait(label).thenAcceptAsync(OldReloadListenerCases::ran, game));
        } catch (Throwable t) {
            log(CLASS_CASE, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResourceCounter(SERVER_CASE, "tags"));
    }

    // GeckoLibCache::reload's shape: a static method with the six old parameters.
    private static CompletableFuture<Void> reloadModels(PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resources, ProfilerFiller preparations, ProfilerFiller reloads, Executor background,
            Executor game) {
        return CompletableFuture.supplyAsync(() -> resources != null && preparations != null, background)
                .thenCompose(barrier::wait)
                .thenAcceptAsync(read -> {
                    if (read) ran(REFERENCE_CASE);
                    else log(REFERENCE_CASE, "fail: no resources or profiler");
                }, game);
    }

    private static void ran(String what) {
        if (finished.add(what)) log(what, "success");
    }

    // A case that never ran says so once the player is in a world, or once the server ticks.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || clientReported || Minecraft.getInstance().level == null) return;
        clientReported = true;
        for (String what : List.of(CLASS_CASE, REFERENCE_CASE, LAMBDA_CASE)) {
            if (!finished.contains(what)) log(what, "fail: never ran");
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || serverReported) return;
        serverReported = true;
        if (!finished.contains(SERVER_CASE)) log(SERVER_CASE, "fail: never ran");
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /** Lists a folder's json or png files off the game thread and applies the count on it, as model loaders do. */
    static final class ResourceCounter implements PreparableReloadListener {
        private final String what;
        private final String folder;

        ResourceCounter(String what, String folder) {
            this.what = what;
            this.folder = folder;
        }

        @Override
        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
            return CompletableFuture.supplyAsync(() -> {
                preparations.push("retromod_test_reload");
                int count = resources.listResources(folder,
                        path -> path.getPath().endsWith(".json") || path.getPath().endsWith(".png")).size();
                preparations.pop();
                return count;
            }, background).thenCompose(barrier::wait).thenAcceptAsync(count -> {
                if (count > 0) ran(what);
                else log(what, "fail: no files under " + folder);
            }, game);
        }
    }
}
