/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * NeoForge 26.3 removed the axe, hoe, pickaxe, shovel, sword, and shield constants from
 * {@code ItemAbilities}. The Forge test mod's {@code ToolActions.AXE_STRIP} case failed there with
 * {@code NoSuchFieldError}; the ability itself is still available by name.
 */
class LegacyItemAbilitiesBridgeTest {

    /** The fields NeoForge 26.3's ItemAbilities still declares. */
    private static final Set<String> HOST_26_3 = Set.of("SHEARS_DIG", "SHOVEL_DOUSE", "SWORD_SWEEP",
            "SHEARS_HARVEST", "SHEARS_REMOVE_ARMOR", "SHEARS_CARVE", "SHEARS_DISARM", "SHEARS_TRIM",
            "FISHING_ROD_CAST", "TRIDENT_THROW", "BRUSH_BRUSH", "FIRESTARTER_LIGHT", "SPYGLASS_SCOPE",
            "DEFAULT_SHEARS_ACTIONS", "DEFAULT_FISHING_ROD_ACTIONS", "DEFAULT_TRIDENT_ACTIONS",
            "DEFAULT_BRUSH_ACTIONS", "DEFAULT_FLINT_ACTIONS", "DEFAULT_FIRECHARGE_ACTIONS",
            "DEFAULT_SPYGLASS_ACTIONS");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a Forge mod's ToolActions.AXE_STRIP read becomes ItemAbility.get(\"axe_strip\") on 26.3")
    void removedConstantIsReadByName() {
        transformer.clearRedirectsForTesting();
        LegacyItemAbilitiesBridge.register(transformer, HOST_26_3);

        MethodNode read = onlyMethod(transformer.transformClass(
                modReading("net/minecraftforge/common/ToolActions", "AXE_STRIP",
                        "Lnet/minecraftforge/common/ToolAction;"), "test/Strip"));
        MethodInsnNode call = first(read, MethodInsnNode.class);
        assertEquals("AXE_STRIP", call.name);
        assertTrue(call.owner.endsWith("LegacyItemAbilities"), call.owner);
        assertTrue(Arrays.stream(read.instructions.toArray()).noneMatch(FieldInsnNode.class::isInstance),
                "no read of the removed field may remain");

        MethodNode helper = helperMethod(LegacyItemAbilitiesBridge.generateHelper(
                List.<String[]>of(new String[]{"AXE_STRIP", "axe_strip"}), List.of()), "AXE_STRIP");
        assertEquals("axe_strip", first(helper, LdcInsnNode.class).cst);
        assertEquals("get", first(helper, MethodInsnNode.class).name);
    }

    @Test
    @DisplayName("a constant the host still has, such as SHEARS_DIG, keeps its plain field read")
    void keptConstantIsUntouched() {
        transformer.clearRedirectsForTesting();
        LegacyItemAbilitiesBridge.register(transformer, HOST_26_3);

        MethodNode read = onlyMethod(transformer.transformClass(
                modReading("net/neoforged/neoforge/common/ItemAbilities", "SHEARS_DIG",
                        "Lnet/neoforged/neoforge/common/ItemAbility;"), "test/Shears"));
        FieldInsnNode field = first(read, FieldInsnNode.class);
        assertEquals("SHEARS_DIG", field.name);
    }

    @Test
    @DisplayName("on a 21.1 host that still has every constant, nothing is registered")
    void olderHostRegistersNothing() {
        transformer.clearRedirectsForTesting();
        LegacyItemAbilitiesBridge.register(transformer, Set.of("AXE_DIG", "PICKAXE_DIG", "SHOVEL_DIG",
                "HOE_DIG", "SWORD_DIG", "AXE_STRIP", "AXE_SCRAPE", "AXE_WAX_OFF", "SHOVEL_FLATTEN",
                "HOE_TILL", "SHIELD_BLOCK", "DEFAULT_AXE_ACTIONS", "DEFAULT_HOE_ACTIONS",
                "DEFAULT_SHOVEL_ACTIONS", "DEFAULT_PICKAXE_ACTIONS", "DEFAULT_SWORD_ACTIONS",
                "DEFAULT_SHIELD_ACTIONS"));

        MethodNode read = onlyMethod(transformer.transformClass(
                modReading("net/neoforged/neoforge/common/ItemAbilities", "AXE_STRIP",
                        "Lnet/neoforged/neoforge/common/ItemAbility;"), "test/Native"));
        assertEquals("AXE_STRIP", first(read, FieldInsnNode.class).name);
    }

    @Test
    @DisplayName("HOE_TILL keeps its historical name \"till\" and the shovel set keeps douse")
    void namesMatchWhatTheConstantsHeld() {
        byte[] helper = LegacyItemAbilitiesBridge.generateHelper(
                List.<String[]>of(new String[]{"HOE_TILL", "till"}),
                List.<String[]>of(new String[]{"DEFAULT_SHOVEL_ACTIONS", "shovel_dig", "shovel_flatten",
                        "shovel_douse"}));
        assertEquals("till", first(helperMethod(helper, "HOE_TILL"), LdcInsnNode.class).cst);
        List<Object> names = Arrays.stream(helperMethod(helper, "DEFAULT_SHOVEL_ACTIONS").instructions.toArray())
                .filter(LdcInsnNode.class::isInstance).map(insn -> ((LdcInsnNode) insn).cst)
                .filter(String.class::isInstance).toList();
        assertEquals(List.of("shovel_dig", "shovel_flatten", "shovel_douse"), names);
    }

    @Test
    @DisplayName("Forge 66 removed the same ToolActions, and a Forge mod reads them through ToolAction.get")
    void forgeToolActionsAreReadByName() {
        transformer.clearRedirectsForTesting();
        LegacyItemAbilitiesBridge.register(transformer, LegacyItemAbilitiesBridge.FORGE, Set.of(
                "SHEARS_DIG", "SWORD_SWEEP", "SHEARS_HARVEST", "SHEARS_CARVE", "SHEARS_DISARM",
                "FISHING_ROD_CAST", "DEFAULT_SHEARS_ACTIONS", "DEFAULT_FISHING_ROD_ACTIONS"));

        MethodNode read = onlyMethod(transformer.transformClass(
                modReading("net/minecraftforge/common/ToolActions", "AXE_STRIP",
                        "Lnet/minecraftforge/common/ToolAction;"), "test/ForgeStrip"));
        MethodInsnNode call = first(read, MethodInsnNode.class);
        assertEquals("AXE_STRIP", call.name);
        assertTrue(call.owner.endsWith("LegacyToolActions"), call.owner);

        MethodNode helper = helperMethod(LegacyItemAbilitiesBridge.generateHelper(LegacyItemAbilitiesBridge.FORGE,
                List.<String[]>of(new String[]{"AXE_STRIP", "axe_strip"}), List.of()), "AXE_STRIP");
        MethodInsnNode get = first(helper, MethodInsnNode.class);
        assertEquals("net/minecraftforge/common/ToolAction", get.owner);
        assertEquals("get", get.name);
    }

    private static byte[] modReading(String owner, String field, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Reader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, owner, field, desc);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode onlyMethod(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("read")).findFirst().orElseThrow();
    }

    private static MethodNode helperMethod(byte[] classBytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        return Arrays.stream(method.instructions.toArray()).filter(type::isInstance).map(type::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in " + method.name));
    }
}
