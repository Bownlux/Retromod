/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Function;

/** Sends Forge's {@code RecipeBookType.create(String)} to a rebuilt helper on Forge-family hosts where that call no longer adds a type. */
public final class LegacyRecipeBookTypeBridge {

    static final String TYPE = "net/minecraft/world/inventory/RecipeBookType";
    static final String SETTINGS = "net/minecraft/stats/RecipeBookSettings";
    static final String CREATE = "(Ljava/lang/String;)L" + TYPE + ";";
    static final String HELPER = RebuiltApiClasses.ROOT + "forge/common/LegacyRecipeBookTypes";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    /** The first NeoForge version checked to lack {@code create}: 1.21.11, as do 26.1, 26.2 and 26.3. */
    static final String NEOFORGE_FIRST_CHECKED_VERSION = "1.21.11";

    private LegacyRecipeBookTypeBridge() {}

    /** Forge 26.2 keeps a {@code create} whose setup throws; offline, a Forge target from 26.2. */
    public static void registerForge(RetromodTransformer transformer) {
        if (forgeCannotAddType(ClassResourceInspector::read)) register(transformer);
    }

    /** NeoForge adds book types by enum extension and has no {@code create}. Offline, a NeoForge target from 1.21.11. */
    public static void registerNeoForge(RetromodTransformer transformer) {
        if (neoForgeLacksCreate(ClassResourceInspector::read)) register(transformer);
    }

    static boolean forgeCannotAddType(Function<String, ClassNode> host) {
        if (!ForgeHostShape.matches(List.of(TYPE), List.of(), ForgeHostShape.FIRST_CHECKED_VERSION, host)) return false;
        // Forge 26.2's settings have a field per vanilla type only. A host whose settings keep a map
        // for added types can honor its own create.
        ClassNode settings = host.apply(SETTINGS);
        return settings == null || !keepsAddedTypes(settings);
    }

    static boolean neoForgeLacksCreate(Function<String, ClassNode> host) {
        if (!McReflect.isNeoForge()) return false;
        ClassNode type = host.apply(TYPE);
        if (type == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, NEOFORGE_FIRST_CHECKED_VERSION) >= 0;
        }
        for (MethodNode method : type.methods) {
            if (method.name.equals("create") && method.desc.equals(CREATE)) return false;
        }
        return true;
    }

    private static boolean keepsAddedTypes(ClassNode settings) {
        for (FieldNode field : settings.fields) {
            if ((field.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0 && field.desc.equals("Ljava/util/Map;")) return true;
        }
        return false;
    }

    private static void register(RetromodTransformer transformer) {
        RebuiltApiClasses.registerTree(transformer, HELPER);
        SyntheticEmbedder.registerClassResource(transformer, LEGACY_LOG, LegacyLog.class);
        transformer.registerMethodRedirect(TYPE, "create", CREATE, HELPER, "create", CREATE);
    }
}
