/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Sends the old {@code ModelManager.getAtlas(id)} to the game's atlas manager, which holds the atlases since 1.21.11. */
public final class LegacyModelAtlasBridge {

    static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/client/resources/model/LegacyModelAtlases";
    static final String ATLAS = "Lnet/minecraft/client/renderer/texture/TextureAtlas;";
    static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    // A NeoForge or Forge mod names the id by its pre-1.21.11 name, which the class redirect renames.
    static final String RESOURCE_LOCATION = "Lnet/minecraft/resources/ResourceLocation;";

    private LegacyModelAtlasBridge() {}

    /** Registers the redirect where ModelManager lost getAtlas and the client has an atlas manager; offline, the shim's host gate decides. */
    public static void register(RetromodTransformer t) {
        if (!hostMatches(ClassResourceInspector.read(MODEL_MANAGER), ClassResourceInspector.read(MINECRAFT))) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        String manager = "L" + MODEL_MANAGER + ";";
        for (String id : new String[]{IDENTIFIER, RESOURCE_LOCATION}) {
            t.registerMethodRedirect(MODEL_MANAGER, "getAtlas", "(" + id + ")" + ATLAS, HELPER, "getAtlas",
                    "(" + manager + IDENTIFIER + ")" + ATLAS, true);
        }
    }

    /** Whether the host lacks ModelManager.getAtlas and has Minecraft.getAtlasManager; with neither class readable, true. */
    static boolean hostMatches(ClassNode modelManager, ClassNode minecraft) {
        if (modelManager == null && minecraft == null) return true;
        if (modelManager == null || minecraft == null) return false;
        return !hasMethod(modelManager, "getAtlas", "(" + IDENTIFIER + ")" + ATLAS)
                && !hasMethod(modelManager, "getAtlas", "(" + RESOURCE_LOCATION + ")" + ATLAS)
                && hasMethodNamed(minecraft, "getAtlasManager");
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasMethodNamed(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }
}
