/*
 * Retromod - Forge mod-file locator for the Retromod-owned mods subfolder.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.locator;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;

/**
 * Forge counterpart of {@link RetromodModLocator} (#78): discovers jars in
 * {@code mods/Retromod/} so Retromod and its transformed {@code *-retromod.jar} outputs
 * can ship as CurseForge pack overrides instead of top-level {@code mods/} jars.
 *
 * <p>Not service-registered in Retromod's main jar: Forge's {@code ModDirTransformerDiscoverer}
 * claims any {@code mods/} jar declaring the {@code IModLocator} service onto the early
 * service/transformer layer, where it loads before mod discovery and is never scanned as a
 * {@code @Mod}, so shipping the service stopped {@code RetromodForge} from ever running. The class
 * stays for self-hash uniformity and a possible future locator-only stub jar, but is unwired; until
 * then {@code mods/Retromod/} loading is NeoForge-only and Forge users place jars in {@code mods/}.
 *
 * <p>Reflectively delegates to Forge's own {@code ModsFolderLocator(Path, String)} (reachable via
 * {@code setAccessible} since fmlloader is an automatic module) rather than reimplementing the
 * internal {@code createMod}/{@code ModFileFactory} path. Soft-fails to an empty list if that class
 * moves or reflection is blocked.
 *
 * <p>Self-contained (JDK + SLF4J + the SPI only) so it can ride in the minimal
 * CurseForge "Retromod Loader" stub jar.
 */
public final class RetromodForgeModLocator implements IModLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Locator");

    /** Subfolder of {@code mods/} this locator scans. */
    public static final String SUBFOLDER = "Retromod";

    /** Optional absolute-path override (mainly for testing); see {@link RetromodModLocator}. */
    public static final String OVERRIDE_PROPERTY = "retromod.modfolder";

    @Override
    public List<?> scanMods() {
        final Path folder;
        try {
            folder = resolveModFolder();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not find the Retromod mods folder, so Forge will not load "
                    + "transformed mods from it", e);
            return Collections.emptyList();
        }
        if (folder == null || !Files.isDirectory(folder) || !hasJars(folder)) {
            return Collections.emptyList();
        }
        try {
            // Forge must create the IModFile objects so its normal discovery rules still apply.
            Class<?> mfl = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModsFolderLocator");
            Constructor<?> ctor = mfl.getDeclaredConstructor(Path.class, String.class);
            ctor.setAccessible(true);
            Object inner = ctor.newInstance(folder, "Retromod");
            Object result = mfl.getMethod("scanMods").invoke(inner);
            List<?> list = (result instanceof List) ? (List<?>) result : Collections.emptyList();
            LOGGER.info("Added {} transformed mod {} from {} to Forge discovery",
                    list.size(), list.size() == 1 ? "jar" : "jars", folder);
            return list;
        } catch (Throwable t) {
            LOGGER.warn("Forge could not scan transformed mods in {}. The mods in that folder "
                    + "will not load: {}", folder, t.toString());
            return Collections.emptyList();
        }
    }

    /** {@code retromod.modfolder} override, else Forge's {@code FMLPaths.MODSDIR}/Retromod. */
    private static Path resolveModFolder() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        try {
            Class<?> fmlPaths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
            Object modsDir = fmlPaths.getField("MODSDIR").get(null);
            Path mods = (Path) fmlPaths.getMethod("get").invoke(modsDir);
            return mods.resolve(SUBFOLDER);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasJars(Path folder) {
        try (Stream<Path> s = Files.list(folder)) {
            return s.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"));
        } catch (Exception e) {
            return false;
        }
    }

    // IModProvider: the returned files belong to the inner ModsFolderLocator, so Forge
    // calls these on it, not us; trivial impls suffice.

    @Override
    public String name() {
        return "retromod folder locator";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }

    @Override
    public boolean isValid(IModFile modFile) {
        return true;
    }

    @Override
    public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
    }
}
