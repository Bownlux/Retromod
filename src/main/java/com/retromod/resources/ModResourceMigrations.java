/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.shim.common.LegacyBannerPatternBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Runs every resource converter, in order, on a mod's resources. */
public final class ModResourceMigrations {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");

    // The runtime loaders, the CLI, the AOT compiler and their nested-jar loops all run this one
    // list, so a fix reaches every path. The order matters, as each step below says.
    private static final ModResourceMigrations STANDARD = new ModResourceMigrations(List.of(
            // 1.12 identifier case, so every later step sees lowercase ids.
            new LegacyIdentifierCase(),
            // 1.12 language files, so the key category names step can read the JSON they become.
            new LegacyLangMigrator(),
            // Data layout, so every later step sees data at the path the host reads.
            new ModDataLayoutMigrator(),
            // Forge 1.12 recipe features, then 1.12 item ids, in the copies the layout step made,
            // so the content rewrites see the target's item ids in 1.20.1 shapes.
            new LegacyForgeRecipeMigrator(),
            new LegacyItemFlattening(),
            // Pre-1.20 structure and smithing data, before the content rewrites update it further.
            new LegacyStructureAndSmithingData(),
            // 1.12 and Forge v1 blockstates, before the model steps repair the models they generate.
            new LegacyBlockStateMigrator(),
            // Model parents, block entity models, vanilla texture references, atlas sources and
            // element fields, in that order, so the atlas sees every model in its final form and
            // item definitions read the repaired models.
            new LegacyModelParentMigrator(),
            new LegacyBuiltinEntityBlockModels(),
            new ModVanillaTextureReferences(),
            new ModTextureAtlasSources(),
            new ModelElementFieldMigrator(),
            // Banner pattern tags, from the constant patterns in the mod's transformed classes.
            new BannerPatternTags(),
            // Armor equipment assets, from the old armor textures and the 1.21.2 folder.
            new EquipmentAssetMigrator(),
            // Texture moves for resource packs the mod ships inside its jar.
            new NestedResourcePacks(),
            // A Forge mod's data under the names NeoForge and newer Forge read, before the content
            // rewrites that read the final keys and paths.
            new ForgeDataNamespaceMigrator(),
            // Data content rewrites, per file.
            new DataContent(),
            // Client item definitions (1.21.4), with old model overrides, spawn eggs and
            // code-rendered items translated.
            new ClientItemDefinitionMigrator(),
            // Key binding category names (1.21.9).
            new KeyCategoryNames(),
            // A mod's additions to vanilla tags that later releases renamed, copied after the
            // content rewrites so each copy is already in its final form.
            new VanillaTagFiles(),
            // The 26.3 worldgen folder moves, after the rewrites that read the old names.
            new WorldgenFolders(),
            // The root pack.mcmeta Forge 26.x needs before it loads the jar's assets and data.
            new ModPackMetadata(),
            // The load gate, so it judges copied data in its final form.
            new DataLoadGate(),
            // Recipe advancements for recipes the host will not load (26.3). This runs after the
            // gate, since a recipe the gate keeps out leaves the advancement that rewards it dangling.
            new DanglingRecipeAdvancements()));

    private final List<ModResourceConverter> converters;

    public ModResourceMigrations(List<ModResourceConverter> converters) {
        this.converters = List.copyOf(converters);
    }

    /** The converters every transform path runs, in order. */
    public static ModResourceMigrations standard() {
        return STANDARD;
    }

    public List<ModResourceConverter> converters() {
        return converters;
    }

    /** Runs each converter that applies over one mod or nested jar, and counts the files changed. */
    public int run(ResourceTree tree, MigrationContext context) throws IOException {
        // The run adds the namespaces of the jar's own folders and starts a fresh ledger.
        MigrationContext run = context.forRun(rootNamespaces(tree.names()));
        int changed = 0;
        for (ModResourceConverter converter : converters) {
            if (!converter.appliesTo(run)) continue;
            // An unsafe tree or a failed write throws, and the caller fails the transform rather
            // than ship a half-migrated mod. A count is files written, moved or removed.
            int count = converter.apply(tree, run);
            if (count > 0) {
                LOGGER.debug("{}: {} file(s) for {}", converter.name(), count, run);
            }
            changed += count;
        }
        return changed;
    }

    /** Runs the standard converters over a mod a runtime loader extracted. */
    public static int migrateDirectory(Path root, MigrationContext context) throws IOException {
        // A missing root changes nothing, as the data migration always treated it.
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        return standard().run(DirectoryResourceTree.open(root), context);
    }

    /** Whether a streaming path holds this entry back for the resource migration. */
    public static boolean isMigratedResource(String entryName) {
        // Classes, loader metadata and everything under META-INF/ stay in the path's own loop,
        // which also keeps the manifest first in the jar.
        if (entryName == null || entryName.endsWith(".class") || entryName.startsWith("META-INF/")
                || entryName.equals("fabric.mod.json") || entryName.equals("quilt.mod.json")) {
            return false;
        }
        // So do refmaps, access wideners, class tweakers and mixin configs.
        String lower = entryName.toLowerCase(java.util.Locale.ROOT);
        if ((lower.endsWith(".json") && lower.contains("refmap"))
                || lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker")
                || entryName.endsWith(".mixins.json") || entryName.endsWith("mixin.json")) {
            return false;
        }
        // The loops treat any JSON naming "mixin" as a mixin config, but under assets/ and data/
        // that is game data, such as the "mixing" recipes several mods ship.
        boolean gameResource = entryName.startsWith("assets/") || entryName.startsWith("data/");
        if (!gameResource && entryName.contains("mixin") && entryName.endsWith(".json")) {
            return false;
        }
        // Assets, data, pack metadata and other plain files are held back. A name that is not in
        // canonical form stays in the loop as it is.
        try {
            ResourceTree.checkName(entryName);
            return true;
        } catch (IOException notCanonical) {
            return false;
        }
    }

    /** The namespaces of the jar's own {@code assets/} and {@code data/} folders. */
    static Set<String> rootNamespaces(SortedSet<String> names) {
        Set<String> namespaces = new TreeSet<>();
        for (String name : names) {
            String rest;
            if (name.startsWith("assets/")) {
                rest = name.substring("assets/".length());
            } else if (name.startsWith("data/")) {
                rest = name.substring("data/".length());
            } else {
                continue;
            }
            // The namespace is the first folder, and only a valid one counts.
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String namespace = rest.substring(0, slash);
                if (namespace.matches("[a-z0-9_.-]+")) namespaces.add(namespace);
            }
        }
        return namespaces;
    }

    /** Runs {@link LegacyDataMigrations}: 1.18.2 jigsaw structures and pre-1.20 smithing recipes. */
    static final class LegacyStructureAndSmithingData implements ModResourceConverter {
        @Override
        public String name() {
            return "pre-1.20 structure and smithing data";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            return context.sourceBefore("1.20") && context.targetAtLeast("1.19");
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            return LegacyDataMigrations.migrateTree(tree, context.sourceMcVersion(),
                    context.targetMcVersion());
        }
    }

    /** Writes the pattern item tags a pre-1.19 Forge mod's banner patterns need. */
    static final class BannerPatternTags implements ModResourceConverter {
        @Override
        public String name() {
            return "banner pattern tags";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            // Only hosts that keep patterns in a built-in registry need the tags.
            return LegacyBannerPatternBridge.appliesTo(context.targetMcVersion());
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            // LegacyBannerPatternBridge.tagEntries reads the constant patterns in the mod's
            // classes, from the tree or, on a streaming path, from the context.
            Set<String> names = new LinkedHashSet<>(tree.names());
            names.addAll(context.classNames());
            Map<String, byte[]> tags = LegacyBannerPatternBridge.tagEntries(names,
                    name -> tree.exists(name) ? readQuietly(tree, name) : context.readClass(name));
            int written = 0;
            for (Map.Entry<String, byte[]> tag : tags.entrySet()) {
                if (tree.exists(tag.getKey())) continue; // the mod's own tag wins
                tree.write(tag.getKey(), tag.getValue());
                written++;
            }
            return written;
        }
    }

    /** Runs the {@link ResourcePackTransformer} texture steps on resource packs inside a mod's jar. */
    static final class NestedResourcePacks implements ModResourceConverter {
        @Override
        public String name() {
            return "nested resource pack textures";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            return PackFormat.resourceTargetOrNull(context.targetMcVersion()) != null;
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            // A mod registers such a pack from code, such as a programmer-art or compatibility
            // pack. Each one is a folder with its own pack.mcmeta and is migrated from the format
            // it declares, so the mod's own assets never go through the steps meant for packs
            // that reskin vanilla.
            PackFormat target = PackFormat.resourceTargetOrNull(context.targetMcVersion());
            SortedSet<String> names = tree.names();
            int changed = 0;
            for (String metadata : names) {
                if (!metadata.endsWith("/pack.mcmeta")) continue;
                String root = metadata.substring(0, metadata.length() - "pack.mcmeta".length());
                SortedSet<String> assets = names.subSet(root + "assets/",
                        root + "assets/" + Character.MAX_VALUE);
                if (assets.isEmpty()) continue;
                // A pack already at the host's format, or one that also declares it, is left as
                // it is, as a pack transform copies it unchanged.
                PackMetadata.DeclaredFormats declared = declaredFormats(tree, metadata);
                if (declared == null || declared.supports(target)
                        || declared.primary().compareTo(target) >= 0) {
                    continue;
                }
                changed += migratePack(tree, root, assets, declared.primary(),
                        context.targetMcVersion());
            }
            return changed;
        }

        /** The formats a pack.mcmeta declares, or null when it cannot be read. */
        private static PackMetadata.DeclaredFormats declaredFormats(ResourceTree tree, String metadata) {
            try {
                String content = new String(tree.read(metadata, PackArchive.MAX_METADATA_BYTES),
                        java.nio.charset.StandardCharsets.UTF_8);
                return PackMetadata.parse(content, metadata);
            } catch (IOException | RuntimeException unreadable) {
                return null; // an unreadable pack.mcmeta gives no source to migrate from
            }
        }

        /** Runs the pack steps on a scratch copy, because they move files on disk. */
        private static int migratePack(ResourceTree tree, String root, SortedSet<String> assets,
                                       PackFormat declared, String targetMcVersion)
                throws IOException {
            Path scratch = Files.createTempDirectory("retromod-nested-pack-");
            try {
                for (String name : assets) {
                    Path file = scratch.resolve(name.substring(root.length()));
                    Files.createDirectories(file.getParent());
                    Files.write(file, tree.read(name));
                }
                // Only vanilla textures move. The pack's other namespaces override the mod's own
                // assets at the paths the mod's models name.
                try {
                    new ResourcePackTransformer(targetMcVersion).migrateTextures(scratch, declared, true);
                } catch (IOException conflict) {
                    // A pack can ship both names of a renamed texture, as a multi-version pack
                    // does, and then the move has nowhere to go. The pack stays as shipped, which
                    // costs at most that pack's old textures, where failing here would cost the
                    // whole mod.
                    LOGGER.warn("Left the resource pack in {} unchanged: {}", root, conflict.getMessage());
                    return 0;
                }

                // Carry every change back, so a directory tree and an in-memory tree end up
                // identical. A file the steps moved away is deleted.
                DirectoryResourceTree migrated = DirectoryResourceTree.open(scratch);
                Set<String> kept = new java.util.HashSet<>();
                int changed = 0;
                for (String relative : migrated.names()) {
                    String name = root + relative;
                    kept.add(name);
                    byte[] bytes = migrated.read(relative);
                    if (!tree.exists(name) || !java.util.Arrays.equals(tree.read(name), bytes)) {
                        tree.write(name, bytes);
                        changed++;
                    }
                }
                for (String name : assets) {
                    if (!kept.contains(name) && tree.delete(name)) changed++;
                }
                return changed;
            } finally {
                PackArchive.deleteRecursivelyQuietly(scratch);
            }
        }
    }

    /** Runs {@link ModDataMigrator#migrate} over every data file, for the loader the context names. */
    static final class DataContent implements ModResourceConverter {
        @Override
        public String name() {
            return "data content";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            return true; // migrate() picks the rewrites for the target
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            // The source version keeps a rename from touching data written after it.
            return ModDataMigrator.migrateContent(tree, context.sourceMcVersion(),
                    context.targetMcVersion(), context.targetLoader());
        }
    }

    /** Removes recipe advancements left dangling, since 26.3 binds their recipes as registry references. */
    static final class DanglingRecipeAdvancements implements ModResourceConverter {
        @Override
        public String name() {
            return "dangling recipe advancements";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            return context.targetAtLeast("26.3");
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            return ModDataMigrator.removeDanglingRecipeAdvancements(tree, context.targetMcVersion());
        }
    }

    /** Runs {@link LegacyKeyCategoryNames}, which reads the category strings from the mod's classes. */
    static final class KeyCategoryNames implements ModResourceConverter {
        @Override
        public String name() {
            return "key category names";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            return LegacyKeyCategoryNames.appliesTo(context.targetMcVersion());
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            return LegacyKeyCategoryNames.writeTree(tree, context.targetMcVersion(),
                    context.classNames(), context::readClass);
        }
    }

    /** Moves configured features and carvers to their 26.3 folders ({@link Worldgen26_3Migrator}). */
    /** Runs {@link VanillaTagRenames#copyTagFiles}, which also writes the dye recipes 26.1 needs. */
    static final class VanillaTagFiles implements ModResourceConverter {
        @Override
        public String name() {
            return "renamed vanilla tags";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            return context.targetAtLeast(VanillaTagRenames.FIRST_RENAME);
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            return VanillaTagRenames.copyTagFiles(tree, context.sourceMcVersion(), context.targetMcVersion());
        }
    }

    static final class WorldgenFolders implements ModResourceConverter {
        @Override
        public String name() {
            return "26.3 worldgen folders";
        }

        @Override
        public boolean appliesTo(MigrationContext context) {
            return Worldgen26_3Migrator.appliesTo(context.targetMcVersion());
        }

        @Override
        public int apply(ResourceTree tree, MigrationContext context) throws IOException {
            return ModDataMigrator.moveRelocatedWorldgen(tree, context.targetMcVersion());
        }
    }

    /** A file's bytes, or null when it cannot be read. */
    private static byte[] readQuietly(ResourceTree tree, String name) {
        try {
            return tree.read(name);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Lists the converters for a log line, such as when a path reports what it ran. */
    @Override
    public String toString() {
        List<String> names = new ArrayList<>();
        for (ModResourceConverter converter : converters) names.add(converter.name());
        return "ModResourceMigrations" + names;
    }
}
