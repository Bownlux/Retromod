/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.common.LegacyDispenserBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 projectile dispense behavior, after the SRG remap, against the 1.21 dispenser API. */
class LegacyDispenserBridgeTest {

    private static final String REBASED_BASE = "com/retromod/generated/LegacyAbstractProjectileDispenseBehavior";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void aProjectileBehaviorExtendsTheGeneratedBaseAndReadsTheRecord() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, true);

        ClassNode behavior = node(transformer.transformClass(legacyBehavior(), "test/DartBehavior.class"));

        assertEquals(PROJECTILE_BASE, behavior.superName, "the removed base must be supplied");
        MethodNode execute = behavior.methods.stream().filter(m -> m.name.equals("execute")).findFirst().orElseThrow();
        assertTrue(execute.desc.contains("L" + SOURCE + ";"), "the record must replace the interface: " + execute.desc);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : execute.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(SOURCE)) {
                calls.add(call.name);
                assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(), "a record accessor is a virtual call");
                assertFalse(call.itf, "the record is not an interface");
            }
        }
        assertEquals(List.of("level", "pos"), calls, "the get-prefixed getters must reach the accessors");
    }

    @Test
    void theGeneratedBaseIsStructurallyValid() throws Exception {
        ClassNode base = node(generateProjectileBase(DIRECTION_PROPERTY));

        assertNotEquals(0, base.access & Opcodes.ACC_ABSTRACT, "getProjectile stays the subclass's job");
        for (MethodNode method : base.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(base.name, method);
            }
        }
        assertTrue(base.methods.stream().anyMatch(m -> m.name.equals("getProjectile")
                && m.desc.equals(GET_PROJECTILE_DESC)), "subclasses override getProjectile by this shape");
    }

    @Test
    void theBaseReadsFacingWithTheHostsPropertyType() {
        for (String facing : List.of(DIRECTION_PROPERTY, ENUM_PROPERTY)) {
            ClassNode base = node(generateProjectileBase(facing));
            MethodNode execute = base.methods.stream().filter(m -> m.name.equals("execute")).findFirst().orElseThrow();
            boolean readsFacing = java.util.Arrays.stream(execute.instructions.toArray())
                    .anyMatch(insn -> insn instanceof org.objectweb.asm.tree.FieldInsnNode field
                            && field.name.equals("FACING") && field.desc.equals(facing));
            assertTrue(readsFacing, "1.21.2 retyped FACING, so the read must use the host's type " + facing);
        }
    }

    @Test
    void withoutTheRemovalOnlyTheRecordIsBridged() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, false);

        ClassNode behavior = node(transformer.transformClass(legacyBehavior(), "test/DartBehavior.class"));

        assertEquals(OLD_PROJECTILE_BASE, behavior.superName, "a host that keeps the base keeps it");
    }

    @Test
    void gettersAreBridgedOn26x() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyDispenserBridge.register(transformer);

        // Forge and NeoForge calls name the old interface; Fabric calls name the record after the remap.
        for (String owner : List.of(OLD_SOURCE, SOURCE)) {
            ClassNode behavior = node(transformer.transformClass(getterBehavior(owner), "test/GetterBehavior.class"));
            MethodNode execute = behavior.methods.stream().filter(m -> m.name.equals("execute")).findFirst().orElseThrow();
            assertEquals(List.of("level", "pos", "state"), recordCalls(execute),
                    "26.x still has only the record accessors, so calls through " + owner + " must reach them");
        }
        assertFalse(transformer.getSyntheticClasses().containsKey(PROJECTILE_BASE),
                "from 26.1 the removed base is left to RemovedBaseToParentBridge");
        assertNotEquals(PROJECTILE_BASE, transformer.getClassRedirects().get(OLD_PROJECTILE_BASE));
    }

    @Test
    void aHandlerCapturingTheOldImplementationNamesTheRecord() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyDispenserBridge.register(transformer);

        // Farmer's Delight 1.20.1's cutting board handler captures dispenseFrom's BlockSourceImpl local.
        ClassNode mixin = node(transformer.transformClass(implementationCapture(),
                "test/CuttingBoardDispenserMixin.class"));

        MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("onDispense")).findFirst().orElseThrow();
        assertEquals("(L" + SOURCE + ";)V", handler.desc,
                "Mixin rejects a handler whose parameter type the host lacks, and 26.2 has no BlockSourceImpl");
        assertEquals(List.of("state"), recordCalls(handler), "getBlockState must reach the record accessor");
    }

    @Test
    void theRebaseKeepsTheRemovedBaseOn26x() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyDispenserBridge.register(transformer);
        RemovedBaseToParentBridge.register(transformer);

        ClassNode behavior = node(transformer.transformClass(legacyBehavior(), "test/DartBehavior.class"));

        assertEquals(REBASED_BASE, behavior.superName, "the 26.x rebase must own the removed base");
        MethodNode execute = behavior.methods.stream().filter(m -> m.name.equals("execute")).findFirst().orElseThrow();
        assertEquals(List.of("level", "pos"), recordCalls(execute), "the getters must still reach the accessors");
    }

    @Test
    void fabricRegistersItOnceItsModsAreMojangNamed() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertEquals(SOURCE, transformer.getClassRedirects().get(OLD_SOURCE));

        // An intermediary host never sees the Mojang name, so the bridge stays off there.
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(OLD_SOURCE));
    }

    @Test
    void aFabricGetterCallReachesTheRecordOn26x() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        IntermediaryToMojangMapper.applyTo(transformer);

        ClassNode behavior = node(transformer.transformClass(intermediaryBehavior(), "test/FabricBehavior.class"));

        MethodNode execute = behavior.methods.stream().filter(m -> m.name.equals("execute")).findFirst().orElseThrow();
        assertEquals(List.of("level"), recordCalls(execute),
                "class_2342.method_10207 lands on the record as getLevel, which 26.x lacks");
    }

    @Test
    void a121HostStillGetsTheFiringBase() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyDispenserBridge.register(transformer);

        assertTrue(transformer.getSyntheticClasses().containsKey(PROJECTILE_BASE),
                "1.21.x keeps the generated base that fires the projectile");
        assertEquals(PROJECTILE_BASE, transformer.getClassRedirects().get(OLD_PROJECTILE_BASE));
    }

    /** The names of the record calls in a method, each checked to be a virtual class call. */
    private static List<String> recordCalls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(SOURCE)) {
                calls.add(call.name);
                assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode(), "a record accessor is a virtual call: " + call.name);
                assertFalse(call.itf, "the record is not an interface: " + call.name);
            }
        }
        return calls;
    }

    /** A plain dispense behavior that reads the level, position and state through the given owner. */
    private static byte[] getterBehavior(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/GetterBehavior", null, DEFAULT_BEHAVIOR, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute",
                "(L" + owner + ";Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
                null, null);
        mv.visitCode();
        for (String[] getter : GETTERS) {
            if (getter[0].equals("getEntity")) continue;
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, getter[0], "()" + getter[1], true);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A handler that takes 1.20.1's BlockSourceImpl and reads its block state. */
    private static byte[] implementationCapture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/CuttingBoardDispenserMixin", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onDispense", "(L" + OLD_SOURCE_IMPL + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_SOURCE_IMPL, "getBlockState",
                "()Lnet/minecraft/world/level/block/state/BlockState;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Fabric 1.20.1 behavior in intermediary names that reads the dispenser's level. */
    private static byte[] intermediaryBehavior() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/FabricBehavior", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute", "(Lnet/minecraft/class_2342;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/class_2342", "method_10207",
                "()Lnet/minecraft/class_3218;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A dispense behavior that reads the level and position from the old interface. */
    private static byte[] legacyBehavior() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/DartBehavior", null, OLD_PROJECTILE_BASE, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "execute",
                "(L" + OLD_SOURCE + ";Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD_SOURCE, "getLevel",
                "()Lnet/minecraft/server/level/ServerLevel;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD_SOURCE, "getPos", "()Lnet/minecraft/core/BlockPos;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
