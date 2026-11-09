/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.client.model.ForgeBlockModelData;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.ModelContext;

/** Bakes models an old model or geometry holds inside it, reading their parents the way Forge 1.20.1 resolved them. */
final class LegacyNestedModels {

    private static final int MAX_DEPTH = 32;
    private static final String BUILTIN = "builtin/";

    // Minecraft 26.2 only resolves the models block states and items name, so a parent only an old model names is
    // read here, once per reload.
    private static final Map<Identifier, Optional<UnbakedModel>> FILES = new ConcurrentHashMap<>();

    private LegacyNestedModels() {}

    /** Forgets the files read for the last reload. */
    static void clear() {
        FILES.clear();
    }

    /** What one nested model baked into: its quads and the display settings its parents left it. */
    record Baked(QuadCollection quads, boolean ambientOcclusion, Material.Baked particle, ItemTransforms transforms,
            UnbakedModel.GuiLight guiLight) {}

    /** Bakes {@code model} with its parents resolved, through Minecraft's own texture, geometry and display rules. */
    static Baked bake(UnbakedModel model, ModelBaker baker, ModelState state, String name) {
        ResolvedModel resolved = resolve(model, name, baker, 0);
        TextureSlots slots = resolved.getTopTextureSlots();
        QuadCollection quads = resolved.bakeTopGeometry(slots, baker, state);
        return new Baked(quads, resolved.getTopAmbientOcclusion(), resolved.resolveParticleMaterial(slots, baker),
                resolved.getTopTransforms(), resolved.getTopGuiLight());
    }

    private static ResolvedModel resolve(UnbakedModel model, String name, ModelBaker baker, int depth) {
        UnbakedModel own = model instanceof LegacyBlockModel legacy ? legacy.unwrap() : model;
        Identifier parent = own.parent();
        // A chain longer than any real one is a loop, which ends without a parent.
        ResolvedModel resolvedParent = parent == null || depth >= MAX_DEPTH ? null : resolveId(parent, baker, depth + 1);
        return new Resolved(own, resolvedParent, name);
    }

    private static ResolvedModel resolveId(Identifier id, ModelBaker baker, int depth) {
        // Special models such as builtin/generated have no file, and Minecraft always resolves them.
        if (id.getPath().startsWith(BUILTIN)) return baker.getModel(id);
        UnbakedModel file = read(id);
        if (file == null) return baker.getModel(Identifier.withDefaultNamespace(BUILTIN + "missing"));
        return resolve(file, id.toString(), baker, depth);
    }

    /** The model's texture slots, resolved through the parents Retromod can read, nearest model first. */
    static TextureSlots textures(UnbakedModel model, String name) {
        TextureSlots.Resolver resolver = new TextureSlots.Resolver();
        UnbakedModel current = model instanceof LegacyBlockModel legacy ? legacy.unwrap() : model;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current.textureSlots() != null) resolver.addLast(current.textureSlots());
            Identifier parent = current.parent();
            current = parent == null || parent.getPath().startsWith(BUILTIN) ? null : read(parent);
        }
        return resolver.resolve(() -> name);
    }

    /** The model file Minecraft would load for {@code id}, or null when no pack has one. */
    static UnbakedModel read(Identifier id) {
        // Parsed outside the map, since a nested loader may read another file while it parses.
        Optional<UnbakedModel> known = FILES.get(id);
        if (known == null) {
            Optional<UnbakedModel> loaded = load(id);
            known = FILES.putIfAbsent(id, loaded);
            if (known == null) known = loaded;
        }
        return known.orElse(null);
    }

    private static Optional<UnbakedModel> load(Identifier id) {
        Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "models/" + id.getPath() + ".json");
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
            if (resource.isEmpty()) {
                note(id, "no pack has it");
                return Optional.empty();
            }
            try (Reader reader = resource.get().openAsReader()) {
                return Optional.of(CuboidModel.fromStream(reader));
            }
        } catch (IOException | RuntimeException failure) {
            note(id, failure.toString());
            return Optional.empty();
        }
    }

    private static void note(Identifier id, String why) {
        LegacyLog.warnOnce("legacy nested model " + id, "An old model names the model " + id + ", which Retromod could not"
                + " read (" + why + "), so the missing model stands in for it.");
    }

    /** One model of a nested chain, whose texture, geometry and display rules Minecraft's own defaults resolve. */
    private record Resolved(UnbakedModel wrapped, ResolvedModel parent, String debugName) implements ResolvedModel {

        // Forge's default context walks the parents as models Minecraft discovered itself and casts each one, so a
        // parent only an old model names threw ClassCastException. This context holds the same Forge model data.
        @Override
        public IGeometryBakingContext getContext() {
            return new ModelContext(this, parent, forgeData(this), true);
        }
    }

    // The nearest model's Forge data (root transform, render types, part visibility), filled in from its parents
    // until every part is set.
    private static ForgeBlockModelData forgeData(ResolvedModel model) {
        ForgeBlockModelData data = null;
        for (ResolvedModel current = model; current != null && (data == null || !data.full()); current = current.parent()) {
            if (!(current.wrapped() instanceof CuboidModel cuboid)) continue;
            data = data == null ? cuboid.forgeData() : data.merge(cuboid.forgeData());
        }
        return data;
    }
}
