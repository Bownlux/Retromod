/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Points a mod's models at vanilla textures under the names the host ships them as. */
public final class ModVanillaTextureReferences implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    /** The Flattening, which renamed the texture folders and most block and item textures. */
    static final String FLATTENING = "1.13";

    @Override
    public String name() {
        return "vanilla texture references in mod models";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        PackFormat source = SourcePackFormat.resource(context.sourceMcVersion());
        PackFormat target = PackFormat.resourceTargetOrNull(context.targetMcVersion());
        return source != null && target != null && source.compareTo(target) < 0;
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // A model that reuses a vanilla texture names it the way its own game did, such as
        // minecraft:blocks/planks_oak in 1.12 or minecraft:item/chain before 1.21.9. The host finds
        // nothing under that name and draws the missing texture.
        Renames renames = new Renames(context);
        int changed = 0;
        for (String name : ModModels.modelFiles(tree.names(), context.ownNamespaces())) {
            if (rewriteModel(tree, name, renames)) changed++;
        }
        // The OBJ material files beside the models name textures too.
        for (String name : ModModels.filesUnder(tree.names(), context.ownNamespaces(), "models/", ".mtl")) {
            if (rewriteMaterial(tree, name, renames)) changed++;
        }
        if (changed > 0) {
            LOGGER.info("Pointed {} model file(s) at the vanilla textures Minecraft {} ships", changed,
                    context.targetMcVersion());
        }
        if (renames.keptOnNewLayout > 0) {
            LOGGER.info("Kept {} vanilla texture reference(s) as written: Minecraft {} only has that art "
                    + "redrawn in another layout, so those faces show the missing texture",
                    renames.keptOnNewLayout, context.targetMcVersion());
        }
        return changed;
    }

    /** Renames the vanilla textures one model's slots name. */
    private static boolean rewriteModel(ResourceTree tree, String name, Renames renames) throws IOException {
        JsonObject model = ModModels.read(tree, name);
        if (model == null) return false;
        JsonObject textures = ModModels.textures(model);
        if (textures == null) return false;
        boolean changed = false;
        for (String slot : List.copyOf(textures.keySet())) {
            JsonElement value = textures.get(slot);
            String reference = ModModels.slotReference(value);
            if (reference == null || reference.startsWith("#")) continue;
            String rewritten = renames.apply(tree, reference);
            if (!rewritten.equals(reference)) {
                textures.add(slot, ModModels.withReference(value, rewritten));
                changed = true;
            }
        }
        if (changed) ModModels.write(tree, name, model);
        return changed;
    }

    /** Renames the vanilla textures one OBJ material file names. */
    private static boolean rewriteMaterial(ResourceTree tree, String name, Renames renames) throws IOException {
        // Latin-1 maps every byte to one character, so lines this leaves alone keep their exact bytes.
        String material = new String(tree.read(name), StandardCharsets.ISO_8859_1);
        String rewritten = ModModels.rewriteMaterialTextures(material, texture -> renames.apply(tree, texture));
        if (rewritten.equals(material)) return false;
        tree.write(name, rewritten.getBytes(StandardCharsets.ISO_8859_1));
        return true;
    }

    /** The vanilla texture renames between one mod's game and the host. */
    private static final class Renames {
        private final boolean flattening;
        private final PackFormat sourceFormat;
        private final PackFormat targetFormat;
        private final Map<String, String> flatteningReferences;
        private int keptOnNewLayout;

        Renames(MigrationContext context) {
            this.flattening = context.sourceBefore(FLATTENING) && context.targetAtLeast(FLATTENING);
            this.sourceFormat = SourcePackFormat.resource(context.sourceMcVersion());
            this.targetFormat = PackFormat.resourceTargetOrNull(context.targetMcVersion());
            this.flatteningReferences = flattening ? PackTextureReferences.flatteningReferenceRenames() : Map.of();
        }

        /** The reference as the host names it, written the way the mod wrote it. */
        String apply(ResourceTree tree, String reference) {
            String id = ModModels.qualify(reference);
            // Only minecraft references change. The mod's own textures keep their paths, since its
            // code may bind them by path.
            if (id == null || !ModModels.namespace(id).equals(ModModels.DEFAULT_NAMESPACE)) return reference;
            // A texture the jar ships under the old name is the mod's own art, not vanilla's, so a
            // mod's blocks/brick is never mistaken for vanilla's brick.
            if (tree.exists(ModModels.textureEntry(id))) return reference;
            // Run the same tables a resource pack's models go through: the 1.13 Flattening renames for
            // a mod built before 1.13, then each later move in texture-migrations.tsv that lies
            // between the mod's resource format and the host's.
            String path = ModModels.path(id);
            if (flattening) path = PackTextureReferences.rewriteReference(path, flatteningReferences);
            VersionedTexturePathMappings.Followed moved =
                    VersionedTexturePathMappings.follow(path + ".png", sourceFormat, targetFormat);
            // A move that ends on new art in another layout, such as the sign sheet 26.2 redrew as a
            // block sprite, keeps the old name: the model's coordinates would cut the wrong parts out.
            if (!moved.keepsLayout()) {
                keptOnNewLayout++;
                return reference;
            }
            path = withoutPng(moved.path());
            if (path.equals(ModModels.path(id))) return reference;
            return ModModels.sameForm(reference, ModModels.DEFAULT_NAMESPACE + ":" + path);
        }

        private static String withoutPng(String file) {
            return file.endsWith(".png") ? file.substring(0, file.length() - ".png".length()) : file;
        }
    }
}
