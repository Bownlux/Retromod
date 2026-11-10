/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

/** The extra models' client pack, which asks old mods for their models when a resource reload first reads its item models. */
final class LegacyExtraModelPack extends PathPackResources {

    private static final String ITEMS = "items";

    private final Path folder;
    // Set once this reload's item models are written, or known not to come; until then every reader waits for them.
    private volatile boolean done;

    private LegacyExtraModelPack(PackLocationInfo location, Path folder) {
        super(location, folder);
        this.folder = folder;
    }

    /** What the pack list keeps: it opens a new pack for each reload, so each reload asks the mods once. */
    static Pack.ResourcesSupplier supplier(Path folder) {
        Class<Pack.ResourcesSupplier> type = Pack.ResourcesSupplier.class;
        // 26.2 opens packs with openPrimary and openFull, 26.3 with openMetadata and openResources, so one proxy answers both.
        InvocationHandler opens = (proxy, method, args) -> open(proxy, method, args, folder);
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, opens));
    }

    private static Object open(Object proxy, Method method, Object[] args, Path folder) throws Throwable {
        return switch (method.getName()) {
            case "openPrimary", "openFull", "openMetadata" -> new LegacyExtraModelPack((PackLocationInfo) args[0], folder);
            case "openResources" -> Stream.of(new LegacyExtraModelPack((PackLocationInfo) args[0], folder));
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Retromod extra models in " + folder;
            default -> {
                if (!method.isDefault()) throw new UnsupportedOperationException("Retromod's extra model pack has no " + method);
                yield InvocationHandler.invokeDefault(proxy, method, args);
            }
        };
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        // The pack holds item models only, so nothing else is read or waited for.
        if (type != PackType.CLIENT_RESOURCES || !isItems(path)) return;
        askOnce();
        super.listResources(type, namespace, path, output);
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (type != PackType.CLIENT_RESOURCES || !isItems(id.getPath())) return null;
        askOnce();
        return super.getResource(type, id);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        Set<String> namespaces = new HashSet<>(super.getNamespaces(type));
        if (type != PackType.CLIENT_RESOURCES) return namespaces;
        // Minecraft asks before the mods are, so it is told every mod's namespace; the folder adds any other from last time.
        namespaces.add("minecraft");
        namespaces.addAll(LegacyModelEventHooks.modNamespaces());
        return namespaces;
    }

    private static boolean isItems(String path) {
        return path.equals(ITEMS) || path.startsWith(ITEMS + "/");
    }

    // The reload's item loader comes here on a worker thread, where waiting for Forge's common setup is safe.
    private void askOnce() {
        // The game's own thread never waits, not even for this lock, and leaves the asking to the reload's worker.
        if (done || LegacyModelEventHooks.onGameThreadBeforeSetup()) return;
        // The wait comes before the lock: a managed wait lets the worker pool add a thread, a blocked lock does not.
        boolean setupDone = LegacyModelEventHooks.awaitSetup();
        // The lock is held only while the event is posted and the models written, so a second reader sees them whole.
        synchronized (this) {
            if (done) return;
            if (setupDone) LegacyModelEventHooks.writeItemModels(folder);
            done = true;
        }
    }
}
