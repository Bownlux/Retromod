/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The scattered single removed members reach their rebuilt forms, and the world data's game rules the server's. */
class LegacyScatteredMembersBridgeTest {

    private static final String ROOT = LegacyScatteredMembersBridge.ROOT;
    private String savedVersion;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("each old member reaches its rebuilt form on a 26.3 host")
    void membersAreBridgedOn263() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyScatteredMembersBridge.register(t, name -> null);

        List<String> refs = refs(t.transformClass(oldCalls(), "test/scattered/Old"));

        assertTrue(refs.contains(LegacyScatteredMembersBridge.TEAM_COLORS + ".getColor(L" + LegacyScatteredMembersBridge.TEAM + ";)"
                + LegacyScatteredMembersBridge.FORMATTING), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyScatteredMembersBridge.PROFILES + ".properties(")), refs.toString());
        assertTrue(refs.contains(LegacyScatteredMembersBridge.OPTIONS + ".getCloudStatus" + LegacyScatteredMembersBridge.CLOUDS), refs.toString());
        assertTrue(refs.contains("F " + LegacyScatteredMembersBridge.RESULT + ".CONSUME"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyScatteredMembersBridge.HOVER_VALUES + ".getValue(")), refs.toString());
        assertTrue(refs.contains(LegacyScatteredMembersBridge.BLOCK_ENTITY_TYPES + ".getKey" + LegacyScatteredMembersBridge.GET_KEY), refs.toString());
        assertTrue(refs.contains(ROOT + "client/renderer/LegacyBlendCalls.enableBlend()V"), refs.toString());
        assertTrue(refs.contains(ROOT + "client/LegacyVideoModes.getRefreshRate(L" + LegacyScatteredMembersBridge.VIDEO_MODE + ";)I"), refs.toString());
        for (String name : new String[]{"getLookVector", "getUpVector", "getLeftVector"}) {
            assertTrue(refs.contains(ROOT + "client/LegacyCameraVectors." + name + "(L" + LegacyScatteredMembersBridge.CAMERA
                    + ";)Lorg/joml/Vector3f;"), name + " " + refs);
        }
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(ROOT + "client/renderer/block/LegacyBlockShades.getShade(")), refs.toString());
        assertEquals(2, refs.stream().filter(r -> r.startsWith(ROOT + "world/item/LegacyCraftingRemainders.getCraftingRemainder(")).count(),
                "the 1.21.1 name and Forge's spelling both reach the helper: " + refs);
    }

    @Test
    @DisplayName("offline for 26.2 the team color is bridged, since 26.2 already took team colors")
    void teamColorFrom262() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyScatteredMembersBridge.register(t, name -> null);

        List<String> refs = refs(t.transformClass(oldCalls(), "test/scattered/Old"));

        assertTrue(refs.contains(LegacyScatteredMembersBridge.TEAM_COLORS + ".getColor(L" + LegacyScatteredMembersBridge.TEAM + ";)"
                + LegacyScatteredMembersBridge.FORMATTING), refs.toString());
        assertTrue(refs.contains(ROOT + "client/renderer/LegacyBlendCalls262.enableBlend()V"), refs.toString());
    }

    @Test
    @DisplayName("a 26.2 host blends through the blaze3d state manager, and a host with the old members keeps them")
    void hostGates() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ClassNode team = withMethod("getColor", "()" + LegacyScatteredMembersBridge.FORMATTING);
        ClassNode options = withMethod("getCloudsType", LegacyScatteredMembersBridge.CLOUDS);
        LegacyScatteredMembersBridge.register(t, Map.of(LegacyScatteredMembersBridge.TEAM, team, LegacyScatteredMembersBridge.OPTIONS, options,
                LegacyScatteredMembersBridge.BLAZE_GL, new ClassNode())::get);

        List<String> refs = refs(t.transformClass(oldCalls(), "test/scattered/Old"));

        assertTrue(refs.contains(LegacyScatteredMembersBridge.TEAM + ".getColor()" + LegacyScatteredMembersBridge.FORMATTING), refs.toString());
        assertTrue(refs.contains(LegacyScatteredMembersBridge.OPTIONS + ".getCloudsType" + LegacyScatteredMembersBridge.CLOUDS), refs.toString());
        assertTrue(refs.contains(ROOT + "client/renderer/LegacyBlendCalls262.enableBlend()V"), refs.toString());
    }

    @Test
    @DisplayName("the old client and gameplay members reach their rebuilt forms on a 26.3 host")
    void clientAndGameplayMembersAreBridgedOn263() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyScatteredMembersBridge.register(t, name -> null);

        List<String> refs = refs(t.transformClass(oldClientAndGameplayCalls(), "test/scattered/OldClient"));

        assertTrue(refs.contains("net/minecraft/client/gui/screens/worldselection/WorldSelectionList.scrollAmount()D"), refs.toString());
        assertTrue(refs.contains(ROOT + "client/gui/LegacyWorldLists.getScreen(L" + LegacyScatteredMembersBridge.WORLD_LIST + ";)"
                + LegacyScatteredMembersBridge.SELECT_WORLD.substring(2)), refs.toString());
        assertTrue(refs.contains(ROOT + "client/renderer/entity/LegacyHitBoxes.shouldRenderHitBoxes(L" + LegacyScatteredMembersBridge.DISPATCHER
                + ";)Z"), refs.toString());
        assertTrue(refs.contains(ROOT + "client/renderer/entity/LegacyHitBoxes.setRenderHitBoxes(L" + LegacyScatteredMembersBridge.DISPATCHER
                + ";Z)V"), refs.toString());
        assertTrue(refs.contains(ROOT + "client/multiplayer/LegacyClientWeather.isRaining(L" + LegacyScatteredMembersBridge.LEVEL_DATA + ";)Z"),
                refs.toString());
        assertTrue(refs.contains(ROOT + "client/player/LegacyLocalPlayers.clientLevel(L" + LegacyScatteredMembersBridge.LOCAL_PLAYER + ";)"
                + LegacyScatteredMembersBridge.CLIENT_LEVEL), refs.toString());
        assertTrue(refs.contains(ROOT + "world/entity/player/LegacySweepAttacks.sweepAttack(L" + LegacyScatteredMembersBridge.PLAYER + ";)V"),
                refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(ROOT + "world/entity/LegacyEntityCommandSources.createCommandSourceStack(")),
                refs.toString());
        assertTrue(refs.contains(ROOT + "world/entity/LegacyVillagerBiomes.byBiome" + LegacyScatteredMembersBridge.BY_BIOME), refs.toString());
        assertTrue(refs.contains(ROOT + "world/item/LegacyPotionNames.getName" + LegacyScatteredMembersBridge.POTION_NAME), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("getScrollAmount") || r.startsWith("F ")), "no old read may survive: " + refs);
    }

    @Test
    @DisplayName("NeoForge 26.3's renamed container screen getters take the old calls, and a host that kept them is left alone")
    void containerPositionsFollowTheHost() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyScatteredMembersBridge.registerContainerPositions(t, withMethod("getLeftPos", "()I"));

        List<String> refs = refs(t.transformClass(oldContainerCalls(), "test/scattered/OldContainer"));

        String screen = LegacyScatteredMembersBridge.CONTAINER_SCREEN;
        assertEquals(List.of(screen + ".getLeftPos()I", screen + ".getTopPos()I", screen + ".getImageWidth()I", screen + ".getImageHeight()I"),
                refs);

        t.clearRedirectsForTesting();
        ClassNode forge = withMethod("getGuiLeft", "()I");
        forge.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getLeftPos", "()I", null, null));
        LegacyScatteredMembersBridge.registerContainerPositions(t, forge);
        assertEquals(screen + ".getGuiLeft()I", refs(t.transformClass(oldContainerCalls(), "test/scattered/OldContainer")).get(0));
    }

    private static byte[] oldContainerCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/scattered/OldContainer", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        for (String name : new String[]{"getGuiLeft", "getGuiTop", "getXSize", "getYSize"}) {
            call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.CONTAINER_SCREEN, name, "()I", 1);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] oldClientAndGameplayCalls() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/scattered/OldClient", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.WORLD_LIST, "getScrollAmount", "()D", 1);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.WORLD_LIST, "getScreen", LegacyScatteredMembersBridge.SELECT_WORLD, 1);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.DISPATCHER, "shouldRenderHitBoxes", "()Z", 1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.DISPATCHER, "setRenderHitBoxes", "(Z)V", false);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.LEVEL_DATA, "isRaining", "()Z", 1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.GETFIELD, LegacyScatteredMembersBridge.LOCAL_PLAYER, "clientLevel", LegacyScatteredMembersBridge.CLIENT_LEVEL);
        mv.visitInsn(Opcodes.POP);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.PLAYER, "sweepAttack", "()V", 1);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.ENTITY, "createCommandSourceStack", LegacyScatteredMembersBridge.SOURCE, 1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        call(mv, Opcodes.INVOKESTATIC, LegacyScatteredMembersBridge.VILLAGER_TYPE, "byBiome", LegacyScatteredMembersBridge.BY_BIOME, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitLdcInsn("item.minecraft.potion.effect.");
        call(mv, Opcodes.INVOKESTATIC, LegacyScatteredMembersBridge.POTION, "getName", LegacyScatteredMembersBridge.POTION_NAME, 0);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("server.getWorldData().getGameRules() reads the server's game rules")
    void worldDataGameRulesReadTheServer() {
        String server = "net/minecraft/client/server/IntegratedServer";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/scattered/Rules", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rules", "(L" + server + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, server, "getWorldData", "()L" + LegacyWorldGenOptionsAdapter.WORLD_DATA + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LegacyWorldGenOptionsAdapter.WORLD_DATA, "getGameRules",
                LegacyWorldGenOptionsAdapter.RULES_DESC, true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        writer.visitEnd();

        List<String> refs = refs(LegacyWorldGenOptionsAdapter.apply(writer.toByteArray(), false, true));

        assertEquals(List.of(server + ".getGameRules" + LegacyWorldGenOptionsAdapter.RULES_DESC), refs);
    }

    @Test
    @DisplayName("Sheets' material getters and maps reach the sprite names the host has, and ones the host dropped stay as they were")
    void sheetsMaterialsBecomeSprites() {
        String sheets = LegacyScatteredMembersBridge.SHEETS;
        String sprite = LegacyScatteredMembersBridge.SPRITE_ID;
        String holder = "(Lnet/minecraft/core/Holder;)";
        String wood = "(Lnet/minecraft/world/level/block/state/properties/WoodType;)";
        // 26.3 keeps the banner sprite and dropped the sign one along with the sign map.
        ClassNode host263 = withMethod("getBannerSprite", holder + sprite);
        host263.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getShieldSprite", holder + sprite, null, null));
        ClassNode host261 = withMethod("getSignSprite", wood + sprite);
        host261.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "SIGN_SPRITES", "Ljava/util/Map;", null, null));

        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/scattered/Sheets", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        call(mv, Opcodes.INVOKESTATIC, sheets, "getBannerMaterial", holder + sprite, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        call(mv, Opcodes.INVOKESTATIC, sheets, "getShieldMaterial", holder + sprite, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        call(mv, Opcodes.INVOKESTATIC, sheets, "getSignMaterial", wood + sprite, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, sheets, "SIGN_MATERIALS", "Ljava/util/Map;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        writer.visitEnd();
        byte[] old = writer.toByteArray();

        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyScatteredMembersBridge.registerSheetsSprites(t, host263);
        assertEquals(List.of(sheets + ".getBannerSprite" + holder + sprite, sheets + ".getShieldSprite" + holder + sprite,
                sheets + ".getSignMaterial" + wood + sprite, "F " + sheets + ".SIGN_MATERIALS"), refs(t.transformClass(old, "test/scattered/Sheets")));

        // Offline, a 26.1 or newer target renames only the getters every 26.x host keeps.
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        try {
            RetromodVersion.TARGET_MC_VERSION = "26.3";
            t.clearRedirectsForTesting();
            LegacyScatteredMembersBridge.registerSheetsSprites(t, null);
            assertEquals(List.of(sheets + ".getBannerSprite" + holder + sprite, sheets + ".getShieldSprite" + holder + sprite,
                    sheets + ".getSignMaterial" + wood + sprite, "F " + sheets + ".SIGN_MATERIALS"), refs(t.transformClass(old, "test/scattered/Sheets")));
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
        }

        t.clearRedirectsForTesting();
        LegacyScatteredMembersBridge.registerSheetsSprites(t, host261);
        List<String> on261 = refs(t.transformClass(old, "test/scattered/Sheets"));
        assertTrue(on261.contains(sheets + ".getSignSprite" + wood + sprite) && on261.contains("F " + sheets + ".SIGN_SPRITES"), on261.toString());
    }

    private static byte[] oldCalls() {
        String formatting = LegacyScatteredMembersBridge.FORMATTING;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/scattered/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.TEAM, "getColor", "()" + formatting, 1);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.PROFILE, "properties", LegacyScatteredMembersBridge.PROPERTIES, 1);
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.OPTIONS, "getCloudsType", LegacyScatteredMembersBridge.CLOUDS, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, LegacyScatteredMembersBridge.RESULT, "CONSUME_PARTIAL", "L" + LegacyScatteredMembersBridge.RESULT + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        call(mv, Opcodes.INVOKEINTERFACE, LegacyScatteredMembersBridge.HOVER, "getValue", LegacyScatteredMembersBridge.GET_VALUE, 1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        call(mv, Opcodes.INVOKESTATIC, LegacyScatteredMembersBridge.BLOCK_ENTITY_TYPE, "getKey", LegacyScatteredMembersBridge.GET_KEY, 0);
        for (String gl : new String[]{LegacyScatteredMembersBridge.PEARL_GL, LegacyScatteredMembersBridge.BLAZE_GL}) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, gl, "_enableBlend", "()V", false);
        }
        call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.VIDEO_MODE, "getRefreshRate", "()I", 1);
        for (String name : new String[]{"getLookVector", "getUpVector", "getLeftVector"}) {
            call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.CAMERA, name, "()Lorg/joml/Vector3f;", 1);
        }
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LegacyScatteredMembersBridge.TINT_GETTER, "getShade", LegacyScatteredMembersBridge.SHADE, true);
        mv.visitInsn(Opcodes.POP);
        for (String name : new String[]{"getCraftingRemainder", "getCraftingRemainingItem"}) {
            call(mv, Opcodes.INVOKEVIRTUAL, LegacyScatteredMembersBridge.ITEM, name, "()L" + LegacyScatteredMembersBridge.ITEM + ";", 1);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(6, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Pushes {@code receivers} nulls, calls, and pops a non-void result. */
    private static void call(MethodVisitor mv, int opcode, String owner, String name, String desc, int receivers) {
        for (int i = 0; i < receivers; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == Opcodes.INVOKEINTERFACE);
        if (!desc.endsWith(")V")) mv.visitInsn(Opcodes.POP);
    }

    private static ClassNode withMethod(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add("F " + field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
