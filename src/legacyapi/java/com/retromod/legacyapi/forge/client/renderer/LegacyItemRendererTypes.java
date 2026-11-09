/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.renderer;

import com.mojang.serialization.MapCodec;
import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRendererCalls;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRendererModel;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/** Adds {@code <namespace>:retromod_legacy_item} to Minecraft's item model types for each mod that installed this copy. */
final class LegacyItemRendererTypes {

    private static final Set<String> TYPED = ConcurrentHashMap.newKeySet();

    private LegacyItemRendererTypes() {}

    /** Adds the type for each namespace that has none yet, before the first resource reload reads item definitions. */
    static void addTypes() {
        // This copy's item models find a stack's renderer through Forge's client extensions.
        LegacyItemRendererCalls.useExtensions(IClientItemExtensions::of);
        for (String namespace : LegacyClientRenderBootstrap.namespaces()) {
            if (!TYPED.add(namespace)) continue;
            try {
                ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemModel.Unbaked>> ids = modelTypes();
                // Mods may construct in parallel, and every copy locks the same host object.
                synchronized (ids) {
                    ids.put(Identifier.fromNamespaceAndPath(namespace, LegacyItemRenderers.MODEL_TYPE), LegacyItemRendererModel.newType());
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                // Retromod's item definitions for this mod name the type, so the game reads those items without it.
                LegacyLog.warn("Retromod did not add the item model type " + namespace + ":" + LegacyItemRenderers.MODEL_TYPE
                        + ", which its item definitions for the old item renderers of " + namespace + " name: " + failure);
            }
        }
    }

    // Minecraft keeps its item model type ids in a private static field. Its jar has no module descriptor, so its packages
    // are open to reflection; the field is found by its type, so a rename does not lose it.
    @SuppressWarnings("unchecked")
    private static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemModel.Unbaked>> modelTypes()
            throws ReflectiveOperationException {
        for (Field field : ItemModels.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ExtraCodecs.LateBoundIdMapper.class) {
                field.setAccessible(true);
                return (ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends ItemModel.Unbaked>>) field.get(null);
            }
        }
        throw new NoSuchFieldException("ItemModels keeps no item model type ids");
    }
}
