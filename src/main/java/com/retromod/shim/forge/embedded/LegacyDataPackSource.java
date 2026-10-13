/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The pack half of {@link LegacyWorldgenStore} on 1.20.5 and newer, whose pack API took a location
 * record and a selection config. Reflective on Mojang names, which those hosts run under on every
 * loader, because Retromod is not compiled against Minecraft.
 */
public final class LegacyDataPackSource {
    private LegacyDataPackSource() {}

    /** A {@code RepositorySource} that offers {@code root} as a required server data pack at the top. */
    public static Object create(String packId, Path root) {
        try {
            ClassLoader loader = LegacyDataPackSource.class.getClassLoader();
            Class<?> source = Class.forName("net.minecraft.server.packs.repository.RepositorySource", false, loader);
            return Proxy.newProxyInstance(source.getClassLoader(), new Class<?>[]{source}, (proxy, method, args) -> {
                if (method.getName().equals("loadPacks") && args != null && args.length == 1) {
                    @SuppressWarnings("unchecked")
                    Consumer<Object> sink = (Consumer<Object>) args[0];
                    Object pack = pack(loader, packId, root);
                    if (pack != null) sink.accept(pack);
                    return null;
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Retromod legacy worldgen pack " + packId;
                    default -> null;
                };
            });
        } catch (ReflectiveOperationException | LinkageError e) {
            System.out.println("[Retromod] Legacy worldgen: this Minecraft version's pack API is not bridged: " + e);
            return null;
        }
    }

    /** The major server data pack format of the running game. */
    public static int packFormat() {
        try {
            ClassLoader loader = LegacyDataPackSource.class.getClassLoader();
            Object version = Class.forName("net.minecraft.SharedConstants", true, loader)
                    .getMethod("getCurrentVersion").invoke(null);
            Class<?> packType = Class.forName("net.minecraft.server.packs.PackType", true, loader);
            try {
                Object format = version.getClass().getMethod("packVersion", packType).invoke(version, serverData(loader));
                return (Integer) format.getClass().getMethod("major").invoke(format);
            } catch (NoSuchMethodException beforeMajorMinor) {
                // 1.20.5 to 1.21.11 report the format as a plain number.
                return (Integer) version.getClass().getMethod("getPackVersion", packType)
                        .invoke(version, serverData(loader));
            }
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new IllegalStateException("Retromod could not read this game's data pack format", e);
        }
    }

    private static Object pack(ClassLoader loader, String packId, Path root) throws ReflectiveOperationException {
        Class<?> component = Class.forName("net.minecraft.network.chat.Component", true, loader);
        Class<?> packSource = Class.forName("net.minecraft.server.packs.repository.PackSource", true, loader);
        Class<?> locationInfo = Class.forName("net.minecraft.server.packs.PackLocationInfo", true, loader);
        Class<?> selection = Class.forName("net.minecraft.server.packs.PackSelectionConfig", true, loader);
        Class<?> position = Class.forName("net.minecraft.server.packs.repository.Pack$Position", true, loader);
        Class<?> packClass = Class.forName("net.minecraft.server.packs.repository.Pack", true, loader);
        Class<?> resources = Class.forName("net.minecraft.server.packs.repository.Pack$ResourcesSupplier", true, loader);
        Class<?> packType = Class.forName("net.minecraft.server.packs.PackType", true, loader);
        Class<?> pathSupplier = Class.forName("net.minecraft.server.packs.PathPackResources$PathResourcesSupplier",
                true, loader);

        Object title = component.getMethod("literal", String.class).invoke(null, "Retromod legacy worldgen");
        Object info = locationInfo.getConstructor(String.class, component, packSource, Optional.class)
                .newInstance(packId, title, packSource.getField("BUILT_IN").get(null), Optional.empty());
        Object top = position.getField("TOP").get(null);
        Object config = selection.getConstructor(boolean.class, position, boolean.class).newInstance(true, top, false);
        Object supplier = pathSupplier.getConstructor(Path.class).newInstance(root);
        return packClass.getMethod("readMetaAndCreate", locationInfo, resources, packType, selection)
                .invoke(null, info, supplier, serverData(loader), config);
    }

    private static Object serverData(ClassLoader loader) throws ReflectiveOperationException {
        return Class.forName("net.minecraft.server.packs.PackType", true, loader).getField("SERVER_DATA").get(null);
    }
}
