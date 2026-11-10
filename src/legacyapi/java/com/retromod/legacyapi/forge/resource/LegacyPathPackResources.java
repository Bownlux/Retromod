/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.resource;

import com.retromod.shim.common.embedded.LegacyLog;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

/** Forge 1.20.1's folder pack, which Forge 26.2 removed, rebuilt on the vanilla one. */
public class LegacyPathPackResources extends PathPackResources {

    private static volatile Field rootField;
    private static volatile boolean rootUnreadable;

    // Only the old constructor sets it. A pack built the vanilla way reads its files exactly as vanilla does.
    private final Path source;

    /** Forge 1.20.1's {@code PathPackResources(String packId, boolean isBuiltin, Path source)}. */
    public LegacyPathPackResources(String packId, boolean builtin, Path source) {
        super(location(packId, builtin), source);
        this.source = source;
    }

    /** The vanilla constructor, for a subclass of the vanilla class that the bridge rebased here too. */
    public LegacyPathPackResources(PackLocationInfo location, Path root) {
        super(location, root);
        this.source = null;
    }

    /** Vanilla 1.20.1's {@code PathPackResources(String name, Path root, boolean isBuiltin)}, for its old subclasses. */
    public LegacyPathPackResources(String name, Path root, boolean builtin) {
        super(location(name, builtin), root);
        this.source = null;
    }

    /** What {@code new PathPackResources(packId, builtin, source)} builds now. */
    public static PathPackResources create(String packId, boolean builtin, Path source) {
        return new LegacyPathPackResources(packId, builtin, source);
    }

    /** {@code getSource()} on any folder pack, including the ones Forge 26.2 builds for each mod. */
    public static Path sourceOf(PathPackResources pack) {
        if (pack instanceof LegacyPathPackResources legacy) return legacy.getSource();
        return rootOf(pack);
    }

    /** {@code super.getSource()} from an old override, which must not run that override again. */
    public static Path superSourceOf(PathPackResources pack) {
        if (pack instanceof LegacyPathPackResources legacy) return legacy.ownSource();
        return rootOf(pack);
    }

    // Forge named the pack by its id, and the flag chose between the built-in and default sources.
    private static PackLocationInfo location(String packId, boolean builtin) {
        PackSource packSource = builtin ? PackSource.BUILT_IN : PackSource.DEFAULT;
        return new PackLocationInfo(packId, Component.literal(packId), packSource, Optional.empty());
    }

    public Path getSource() {
        return ownSource();
    }

    private Path ownSource() {
        return source != null ? source : rootOf(this);
    }

    /** The file for these path segments; old subclasses override it to read from a jar or another folder. */
    protected Path resolve(String... paths) {
        Path path = getSource();
        for (String segment : paths) {
            path = path.resolve(segment);
        }
        return path;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... paths) {
        if (source == null) return super.getRootResource(paths);
        Path path = resolve(paths);
        return Files.exists(path) ? IoSupplier.create(path) : null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (source == null) return super.getResource(type, id);
        // Forge read lang files from assets for every pack type, so a server could translate.
        PackType folder = id.getPath().startsWith("lang/") ? PackType.CLIENT_RESOURCES : type;
        String[] parts = id.getPath().split("/");
        String[] segments = new String[parts.length + 2];
        segments[0] = folder.getDirectory();
        segments[1] = id.getNamespace();
        System.arraycopy(parts, 0, segments, 2, parts.length);
        // Through getRootResource, so a subclass that overrides it or resolve still decides.
        return getRootResource(segments);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, PackResources.ResourceOutput output) {
        if (source == null) {
            super.listResources(type, namespace, path, output);
            return;
        }
        PathPackResources.listResources(resolve(type.getDirectory()), namespace, path, output);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (source == null) return super.getNamespaces(type);
        return PathPackResources.getNamespaces(resolve(type.getDirectory()));
    }

    // Vanilla keeps the folder in a private field with no getter. Minecraft is an open automatic module on Forge 26.2 and 26.3.
    private static Path rootOf(PathPackResources pack) {
        Field field = rootField();
        if (field == null) return null;
        try {
            return (Path) field.get(pack);
        } catch (IllegalAccessException | RuntimeException failure) {
            return null;
        }
    }

    private static Field rootField() {
        Field field = rootField;
        if (field != null || rootUnreadable) return field;
        try {
            field = PathPackResources.class.getDeclaredField("root");
            field.setAccessible(true);
            rootField = field;
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            rootUnreadable = true;
            LegacyLog.warnOnce("path-pack-root", "An old mod asked a resource pack for its folder (getSource), but "
                    + "Retromod could not read it on this Minecraft (" + failure + "). The mod gets no folder for "
                    + "that pack and may skip it.");
            return null;
        }
    }
}
