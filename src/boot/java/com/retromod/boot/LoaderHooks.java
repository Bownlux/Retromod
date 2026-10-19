/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** Calls loader classes by reflection, so one bootstrap class is not tied to one loader build. */
public final class LoaderHooks {

    /** {@code FMLPaths} on Forge and on NeoForge 1.20.1. */
    public static final String FORGE_FML_PATHS = "net.minecraftforge.fml.loading.FMLPaths";

    /** {@code FMLPaths} on NeoForge 20.2 and newer. */
    public static final String NEOFORGE_FML_PATHS = "net.neoforged.fml.loading.FMLPaths";

    /** Where {@code ModDirTransformerDiscoverer} lives on Forge and NeoForge 1.20.1. */
    public static final String FORGE_FML_LOADING = "net.minecraftforge.fml.loading";

    /** The SPI package that holds {@code Environment} on Forge and NeoForge 1.20.1. */
    public static final String FORGE_SPI = "net.minecraftforge.forgespi";

    /** The SPI package that holds {@code Environment} on NeoForge 20.2 to 20.4. */
    public static final String NEOFORGE_SPI = "net.neoforged.neoforgespi";

    private LoaderHooks() {}

    /** Reads one {@code FMLPaths} constant, such as {@code GAMEDIR} or {@code MODSDIR}. */
    public static Path fmlPath(String fmlPathsClass, String constant)
            throws ReflectiveOperationException {
        Class<?> paths = Class.forName(fmlPathsClass, true, LoaderHooks.class.getClassLoader());
        Object value = paths.getField(constant).get(null);
        return (Path) paths.getMethod("get").invoke(value);
    }

    /** Builds FML's own folder locator for {@code folder}, an {@code IModLocator} of the host's SPI. */
    public static Object buildFolderLocator(String spiPackage, Path folder, String name)
            throws ReflectiveOperationException {
        // FML only accepts its own ModFile instances from a locator, and the factory behind
        // Environment.Keys.MODDIRECTORYFACTORY is how it creates them for a folder.
        ClassLoader loader = LoaderHooks.class.getClassLoader();
        Class<?> keys = Class.forName(spiPackage + ".Environment$Keys", true, loader);
        Object key = ((Supplier<?>) keys.getField("MODDIRECTORYFACTORY").get(null)).get();

        // Ask modlauncher's environment for the factory FML registers before any locator runs. It
        // is public API, and these are plain calls: nothing is copied from the loaders.
        Class<?> launcherClass = Class.forName("cpw.mods.modlauncher.Launcher", true, loader);
        Class<?> environmentType = Class.forName("cpw.mods.modlauncher.api.IEnvironment",
                false, loader);
        Class<?> keyType = Class.forName("cpw.mods.modlauncher.api.TypesafeMap$Key",
                false, loader);
        Object launcher = launcherClass.getField("INSTANCE").get(null);
        Object environment = launcherClass.getMethod("environment").invoke(launcher);
        Optional<?> factory = (Optional<?>) environmentType.getMethod("getProperty", keyType)
                .invoke(environment, key);
        if (factory.isEmpty()) {
            throw new IllegalStateException("the loader has not registered its mod folder "
                    + "factory yet");
        }
        Class<?> factoryType = Class.forName(spiPackage
                + ".locating.IModDirectoryLocatorFactory", false, loader);
        return factoryType.getMethod("build", Path.class, String.class)
                .invoke(factory.get(), folder, name);
    }

    /**
     * Keeps files in {@code mods/} out of FML's own scan on Forge and NeoForge 1.20.1 to 20.4.
     *
     * @param fmlLoadingPackage {@code net.minecraftforge.fml.loading} or
     *                          {@code net.neoforged.fml.loading}
     * @param paths             paths exactly as {@code Files.list(MODSDIR)} returns them
     */
    @SuppressWarnings("unchecked")
    public static void hideFromModsFolder(String fmlLoadingPackage, java.util.List<Path> paths)
            throws ReflectiveOperationException {
        if (paths.isEmpty()) return;
        // These loaders have no public way to do this. Their scan skips every path in
        // ModDirTransformerDiscoverer.allExcluded(), which maps the private static list found.
        // Checked in fmlloader 1.20.1-47.4.26 and 26.3-66.0.12 and NeoForge FML 47.2.2.
        ClassLoader loader = LoaderHooks.class.getClassLoader();
        Class<?> discoverer = Class.forName(fmlLoadingPackage + ".ModDirTransformerDiscoverer",
                false, loader);
        java.lang.reflect.Field found = discoverer.getDeclaredField("found");
        found.setAccessible(true);
        java.util.List<Object> list = (java.util.List<Object>) found.get(null);
        // The list is only appended to, never reassigned, and the scan reads it after this
        // bootstrap's locator ran, so adding a NamedPath for each file is enough.
        Class<?> namedPath = Class.forName("cpw.mods.modlauncher.api.NamedPath", false, loader);
        java.lang.reflect.Constructor<?> create = namedPath.getConstructor(String.class,
                Path[].class);
        for (Path path : paths) {
            list.add(create.newInstance("retromod-hidden:" + path.getFileName(),
                    (Object) new Path[] {path}));
        }
    }

    /** The cause worth reporting from a failed reflective call. */
    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
