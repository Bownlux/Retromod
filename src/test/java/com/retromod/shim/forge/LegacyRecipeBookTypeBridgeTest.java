/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodTransformer.MethodTarget;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Forge's RecipeBookType.create goes to the rebuilt helper only on Forge-family hosts that cannot add a book type. */
class LegacyRecipeBookTypeBridgeTest {

    private static final String TYPE_SETTINGS = "Lnet/minecraft/stats/RecipeBookSettings$TypeSettings;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedTarget;
    private boolean savedForge;
    private boolean savedNeoForge;

    @BeforeEach
    void save() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        savedForge = McReflect.isForceForge();
        savedNeoForge = McReflect.isForceNeoForge();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Forge 26.2: create exists, but the book settings keep only the four vanilla types")
    void forge262CannotAddType() {
        onForge();
        assertTrue(LegacyRecipeBookTypeBridge.forgeCannotAddType(host(withCreate(), fixedSettings())));
    }

    @Test
    @DisplayName("A Forge host whose book settings keep a map for added types keeps its own create")
    void forgeWithAMapKeepsCreate() {
        onForge();
        assertFalse(LegacyRecipeBookTypeBridge.forgeCannotAddType(host(withCreate(), mapSettings())));
    }

    @Test
    @DisplayName("Offline, a Forge target from 26.2 gets the helper and Forge 1.20.1 does not")
    void forgeOfflineFollowsTheTarget() {
        onForge();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyRecipeBookTypeBridge.forgeCannotAddType(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        assertFalse(LegacyRecipeBookTypeBridge.forgeCannotAddType(name -> null));
    }

    @Test
    @DisplayName("NeoForge 26.x has no create, since it adds book types by enum extension")
    void neoForgeWithoutCreate() {
        onNeoForge();
        assertTrue(LegacyRecipeBookTypeBridge.neoForgeLacksCreate(host(withoutCreate(), mapSettings())));
        assertFalse(LegacyRecipeBookTypeBridge.neoForgeLacksCreate(host(withCreate(), mapSettings())));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(LegacyRecipeBookTypeBridge.neoForgeLacksCreate(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        assertFalse(LegacyRecipeBookTypeBridge.neoForgeLacksCreate(name -> null));
    }

    @Test
    @DisplayName("Fabric and Quilt never take either bridge")
    void otherLoadersNever() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertFalse(LegacyRecipeBookTypeBridge.forgeCannotAddType(name -> null));
        assertFalse(LegacyRecipeBookTypeBridge.neoForgeLacksCreate(name -> null));
    }

    @Test
    @DisplayName("Registering on a Forge 26.2 target sends create to the rebuilt helper and adds it")
    void registersTheHelper() {
        onForge();
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.clearRedirectsForTesting();
        LegacyRecipeBookTypeBridge.registerForge(transformer);

        MethodTarget target = transformer.getMethodRedirects().get(
                new MethodKey(LegacyRecipeBookTypeBridge.TYPE, "create", LegacyRecipeBookTypeBridge.CREATE));
        assertNotNull(target);
        assertEquals(LegacyRecipeBookTypeBridge.HELPER, target.owner());
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyRecipeBookTypeBridge.HELPER),
                "the helper is added to each mod that calls it");
    }

    private static void onForge() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
    }

    private static void onNeoForge() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
    }

    /** A running host with the given book type and settings classes, and Forge's anchor class. */
    private static Function<String, ClassNode> host(ClassNode type, ClassNode settings) {
        Map<String, ClassNode> classes = Map.of(ForgeHostShape.ANCHOR, node(ForgeHostShape.ANCHOR),
                LegacyRecipeBookTypeBridge.TYPE, type, LegacyRecipeBookTypeBridge.SETTINGS, settings);
        return classes::get;
    }

    private static ClassNode withCreate() {
        ClassNode type = node(LegacyRecipeBookTypeBridge.TYPE);
        type.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                LegacyRecipeBookTypeBridge.CREATE, null, null));
        return type;
    }

    private static ClassNode withoutCreate() {
        return node(LegacyRecipeBookTypeBridge.TYPE);
    }

    /** Forge 26.2's settings: one field per vanilla type, and static codecs. */
    private static ClassNode fixedSettings() {
        ClassNode settings = node(LegacyRecipeBookTypeBridge.SETTINGS);
        for (String name : new String[]{"crafting", "furnace", "blastFurnace", "smoker"}) {
            settings.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, name, TYPE_SETTINGS, null, null));
        }
        settings.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MAP_CODEC",
                "Lcom/mojang/serialization/MapCodec;", null, null));
        return settings;
    }

    /** NeoForge's settings, and Forge 1.20.1's: a map that holds every type, added ones too. */
    private static ClassNode mapSettings() {
        ClassNode settings = fixedSettings();
        settings.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "moddedSettings", "Ljava/util/Map;", null, null));
        return settings;
    }

    private static ClassNode node(String name) {
        ClassNode node = new ClassNode();
        node.name = name;
        return node;
    }
}
