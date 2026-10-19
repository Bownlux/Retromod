/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.RetromodVersion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Tells a resource converter about one mod and the host it is translated for. */
public final class MigrationContext {

    /** A mod loader, as a source the mod was built for or a host it is translated to. */
    public enum Loader {
        FABRIC, QUILT, FORGE, NEOFORGE, UNKNOWN;

        /** Parses a loader name such as {@code neoforge}; anything unrecognized is UNKNOWN. */
        public static Loader of(String name) {
            if (name == null) return UNKNOWN;
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "fabric" -> FABRIC;
                case "quilt" -> QUILT;
                case "forge" -> FORGE;
                case "neoforge" -> NEOFORGE;
                default -> UNKNOWN;
            };
        }

        /** The loader a transform writes for: NeoForge on a NeoForge host or CLI target, else the mod's. */
        public static Loader forTransform(String modLoaderType) {
            // The offline paths keep the mod's own loader, and the runtime paths share it with their host.
            return com.retromod.util.McReflect.isNeoForge() ? NEOFORGE : of(modLoaderType);
        }

        /** The loader a bundled jar runs on: its parent's host, since a library may carry no metadata. */
        public static Loader forNestedTransform(boolean forgeFamilyHost, Loader declared) {
            if (forgeFamilyHost) return com.retromod.util.McReflect.isNeoForge() ? NEOFORGE : FORGE;
            // A Quilt jar keeps Quilt on a Fabric-family host, as the runtime path does.
            return declared == QUILT ? QUILT : FABRIC;
        }

        /** The loader running this game, for packs, which are built for no loader of their own. */
        public static Loader host() {
            if (com.retromod.util.McReflect.isNeoForge()) return NEOFORGE;
            if (com.retromod.util.McReflect.isForge()) return FORGE;
            if (com.retromod.util.McReflect.isFabric()) return FABRIC;
            return UNKNOWN;
        }

        /** Fabric and Quilt read Fabric's metadata and resource conditions. */
        public boolean isFabricFamily() {
            return this == FABRIC || this == QUILT;
        }

        /** Forge and NeoForge share FML, the toml metadata, and the jar-in-jar layout. */
        public boolean isForgeFamily() {
            return this == FORGE || this == NEOFORGE;
        }
    }

    /** Namespaces no mod owns, where a definition from one mod would shadow everyone else's. */
    public static final Set<String> SHARED_NAMESPACES = Set.of(
            // Vanilla's own assets, then the ones the loaders and the convention tags share.
            "minecraft", "realms", "c", "forge", "neoforge", "fabric", "quilt");

    // Every transform path builds one context per tree, so a converter sees the same facts whether
    // the mod came through a runtime loader, the CLI, an AOT compile, or a nested jar.
    private final String sourceMcVersion;
    private final String targetMcVersion;
    private final Loader targetLoader;
    private final Loader sourceLoader;
    private final Set<String> declaredNamespaces;
    private final Set<String> ownNamespaces;
    private final boolean nested;
    private final boolean equipmentAssets;
    private final boolean standalonePack;
    private final Collection<String> classNames;
    private final Function<String, byte[]> classReader;
    private final MigrationLedger ledger;

    private MigrationContext(Builder builder, Set<String> ownNamespaces, MigrationLedger ledger) {
        this.sourceMcVersion = builder.sourceMcVersion;
        this.targetMcVersion = builder.targetMcVersion;
        this.targetLoader = builder.targetLoader;
        this.sourceLoader = builder.sourceLoader;
        this.declaredNamespaces = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.declaredNamespaces));
        this.ownNamespaces = Collections.unmodifiableSet(ownNamespaces);
        this.nested = builder.nested;
        this.equipmentAssets = builder.equipmentAssets;
        this.standalonePack = builder.standalonePack;
        this.classNames = List.copyOf(builder.classNames);
        this.classReader = builder.classReader;
        this.ledger = ledger;
    }

    public static Builder builder(String targetMcVersion) {
        return new Builder(targetMcVersion);
    }

    /** The Minecraft version the mod was built for. Null means unknown, never old. */
    public String sourceMcVersion() {
        return sourceMcVersion;
    }

    public String targetMcVersion() {
        return targetMcVersion;
    }

    public Loader targetLoader() {
        return targetLoader;
    }

    public Loader sourceLoader() {
        return sourceLoader;
    }

    /** Where a converter may write: the declared mod ids plus the tree's assets and data namespaces. */
    public Set<String> ownNamespaces() {
        return ownNamespaces;
    }

    /** The mod ids the jar's metadata declares, without {@link #SHARED_NAMESPACES}. */
    public Set<String> declaredNamespaces() {
        return declaredNamespaces;
    }

    /** Whether this tree is a jar bundled inside another mod. */
    public boolean nested() {
        return nested;
    }

    /** Whether the armor bridge is active, so old armor textures need equipment assets. */
    public boolean equipmentAssets() {
        return equipmentAssets;
    }

    /** Whether this tree is a resource or data pack, which ships no code that reads its files by name. */
    public boolean standalonePack() {
        return standalonePack;
    }

    /** Class entries for streaming paths, whose tree holds no classes; empty for an extracted tree. */
    public Collection<String> classNames() {
        return classNames;
    }

    /** Reads one entry named by {@link #classNames()}, or returns null when it cannot. */
    public byte[] readClass(String name) {
        return classReader.apply(name);
    }

    /** Whether the source is known and older than {@code version}. Unknown is never old. */
    public boolean sourceBefore(String version) {
        return isVersion(sourceMcVersion) && RetromodVersion.compareMcVersions(sourceMcVersion, version) < 0;
    }

    /** Whether the host is {@code version} or newer. */
    public boolean targetAtLeast(String version) {
        return isVersion(targetMcVersion) && RetromodVersion.compareMcVersions(targetMcVersion, version) >= 0;
    }

    MigrationLedger ledger() {
        return ledger;
    }

    /** A copy with the namespaces found in the tree added to the declared ones, and a fresh ledger. */
    MigrationContext forRun(Collection<String> treeNamespaces) {
        // A folder can belong to another mod the jar adds data or models for, so these namespaces
        // say where a converter may write, not which items the mod registers.
        Set<String> namespaces = new LinkedHashSet<>(ownNamespaces);
        for (String namespace : treeNamespaces) {
            if (!SHARED_NAMESPACES.contains(namespace)) namespaces.add(namespace);
        }
        Builder copy = new Builder(targetMcVersion);
        copy.sourceMcVersion = sourceMcVersion;
        copy.targetLoader = targetLoader;
        copy.sourceLoader = sourceLoader;
        copy.declaredNamespaces.addAll(declaredNamespaces);
        copy.nested = nested;
        copy.equipmentAssets = equipmentAssets;
        copy.standalonePack = standalonePack;
        copy.classNames = classNames;
        copy.classReader = classReader;
        return new MigrationContext(copy, namespaces, new MigrationLedger());
    }

    private static boolean isVersion(String version) {
        return version != null && version.matches("\\d+\\.\\d+.*");
    }

    @Override
    public String toString() {
        return "MigrationContext[" + sourceLoader + " " + sourceMcVersion + " -> " + targetLoader
                + " " + targetMcVersion + (nested ? ", nested" : "") + "]";
    }

    /** Builds a context; only the target version is required. */
    public static final class Builder {
        private String sourceMcVersion;
        private final String targetMcVersion;
        private Loader targetLoader = Loader.UNKNOWN;
        private Loader sourceLoader = Loader.UNKNOWN;
        private final Set<String> declaredNamespaces = new LinkedHashSet<>();
        private boolean nested;
        private boolean equipmentAssets;
        private boolean standalonePack;
        private Collection<String> classNames = List.of();
        private Function<String, byte[]> classReader = name -> null;

        private Builder(String targetMcVersion) {
            this.targetMcVersion = Objects.requireNonNull(targetMcVersion, "targetMcVersion");
        }

        public Builder sourceMcVersion(String version) {
            this.sourceMcVersion = version;
            return this;
        }

        public Builder targetLoader(Loader loader) {
            this.targetLoader = loader == null ? Loader.UNKNOWN : loader;
            return this;
        }

        public Builder sourceLoader(Loader loader) {
            this.sourceLoader = loader == null ? Loader.UNKNOWN : loader;
            return this;
        }

        /** Mod ids from the jar's metadata; shared namespaces are ignored. */
        public Builder declaredNamespaces(Collection<String> namespaces) {
            for (String namespace : namespaces) {
                if (namespace != null && namespace.matches("[a-z0-9_.-]+")
                        && !SHARED_NAMESPACES.contains(namespace)) {
                    declaredNamespaces.add(namespace);
                }
            }
            return this;
        }

        /** Takes the source version, source loader and mod ids that {@link SourceVersionResolver} read. */
        public Builder source(SourceVersionResolver.Resolution resolution) {
            if (resolution == null) return this;
            sourceMcVersion(resolution.minecraftVersion());
            sourceLoader(resolution.loader());
            declaredNamespaces(resolution.modIds());
            return this;
        }

        public Builder nested(boolean nested) {
            this.nested = nested;
            return this;
        }

        public Builder equipmentAssets(boolean active) {
            this.equipmentAssets = active;
            return this;
        }

        public Builder standalonePack(boolean pack) {
            this.standalonePack = pack;
            return this;
        }

        /** Class entries for converters on paths whose tree holds only resources. */
        public Builder classes(Collection<String> names, Function<String, byte[]> reader) {
            this.classNames = new ArrayList<>(names);
            this.classReader = Objects.requireNonNull(reader, "reader");
            return this;
        }

        public MigrationContext build() {
            return new MigrationContext(this, new LinkedHashSet<>(declaredNamespaces),
                    new MigrationLedger());
        }
    }
}
