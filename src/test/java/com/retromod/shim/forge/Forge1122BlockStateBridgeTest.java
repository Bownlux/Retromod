/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The 1.12.2 {@code IBlockState} and {@code BlockStateContainer} idioms on the host block state. */
class Forge1122BlockStateBridgeTest {

    private static final String STATE = Forge1122BlockStateBridge.BLOCK_STATE;
    private static final String PROPERTY = Forge1122BlockStateBridge.PROPERTY;
    private static final String DEFINITION = Forge1122BlockStateBridge.STATE_DEFINITION;
    private static final String BLOCK = Forge1122BlockStateBridge.BLOCK;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    private static RetromodTransformer bridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122BlockStateBridge.register(t);
        return t;
    }

    @Test
    void containerBuiltUnderItsLegacyNameReachesTheFactory() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // The real chain: the 1.13 shim maps the container to an intermediate name first.
        t.registerClassRedirect("net/minecraft/block/state/BlockStateContainer", "net/minecraft/state/StateContainer");
        t.registerClassRedirect("net/minecraft/state/StateContainer", DEFINITION);
        t.registerClassRedirect("net/minecraft/block/properties/IProperty", PROPERTY);
        t.registerClassRedirect("net/minecraft/block/Block", BLOCK);
        Forge1122BlockStateBridge.register(t);
        String container = "net/minecraft/block/state/BlockStateContainer";
        String property = "net/minecraft/block/properties/IProperty";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Mirror", null, "net/minecraft/block/Block", null);
        MethodVisitor create = cw.visitMethod(ACC_PROTECTED, "func_180661_e", "()L" + container + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, container);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitInsn(ICONST_0);
        create.visitTypeInsn(ANEWARRAY, property);
        create.visitMethodInsn(INVOKESPECIAL, container, "<init>",
                "(Lnet/minecraft/block/Block;[L" + property + ";)V", false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();

        List<String> names = calls(method(node(t.transformClass(cw.toByteArray(), "test/Mirror.class")),
                "func_180661_e")).stream().map(mi -> mi.owner + "." + mi.name).toList();
        assertEquals(List.of(Forge1122BlockStateBridge.FACTORY + ".container"), names,
                "the host has no StateDefinition(Block, Property[]) constructor");
    }

    /**
     * A remapped 1.12 block: its constructor sets the default state with
     * {@code getBaseState().withProperty(POWERED, false)} and reads a property back, both as the
     * interface calls 1.12 compiled, and {@code createBlockState()} returns
     * {@code new BlockStateContainer(this, POWERED)}.
     */
    private static byte[] legacyBlock() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Lamp", null, BLOCK, null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "POWERED", "L" + PROPERTY + ";", null, null).visitEnd();

        MethodVisitor set = cw.visitMethod(ACC_PUBLIC, "lit", "(L" + STATE + ";)L" + STATE + ";", null, null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 1);
        set.visitFieldInsn(GETSTATIC, "test/Lamp", "POWERED", "L" + PROPERTY + ";");
        set.visitInsn(ICONST_1);
        set.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        set.visitMethodInsn(INVOKEINTERFACE, STATE, "func_177226_a",
                "(L" + PROPERTY + ";Ljava/lang/Comparable;)L" + STATE + ";", true);
        set.visitVarInsn(ALOAD, 1);
        set.visitFieldInsn(GETSTATIC, "test/Lamp", "POWERED", "L" + PROPERTY + ";");
        set.visitMethodInsn(INVOKEINTERFACE, STATE, "getValue",
                "(L" + PROPERTY + ";)Ljava/lang/Comparable;", true);
        set.visitInsn(POP);
        set.visitInsn(ARETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        MethodVisitor create = cw.visitMethod(ACC_PROTECTED, "func_180661_e", "()L" + DEFINITION + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, DEFINITION);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitInsn(ICONST_1);
        create.visitTypeInsn(ANEWARRAY, PROPERTY);
        create.visitInsn(DUP);
        create.visitInsn(ICONST_0);
        create.visitFieldInsn(GETSTATIC, "test/Lamp", "POWERED", "L" + PROPERTY + ";");
        create.visitInsn(AASTORE);
        create.visitMethodInsn(INVOKESPECIAL, DEFINITION, "<init>", "(L" + BLOCK + ";[L" + PROPERTY + ";)V", false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static List<MethodInsnNode> calls(MethodNode m) {
        return Arrays.stream(m.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

    private static MethodNode method(ClassNode cn, String name) {
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void stateCallsBecomeVirtualCallsOnTheDeclaringHostClass() {
        ClassNode cn = node(bridged().transformClass(legacyBlock(), "test/Lamp.class"));
        MethodNode lit = method(cn, "lit");

        MethodInsnNode withProperty = calls(lit).stream()
                .filter(mi -> mi.name.equals("setValue")).findFirst().orElseThrow(
                        () -> new AssertionError("withProperty must become the host's setValue"));
        assertEquals(INVOKEVIRTUAL, withProperty.getOpcode(), "BlockState is a class on every host");
        assertFalse(withProperty.itf);
        assertEquals(Forge1122BlockStateBridge.STATE_HOLDER, withProperty.owner,
                "the declaring owner lets the Forge 1.20.1 SRG rename find setValue");
        AbstractInsnNode cast = withProperty.getNext();
        assertTrue(cast instanceof TypeInsnNode t && t.getOpcode() == CHECKCAST && t.desc.equals(STATE),
                "the generic Object result is cast back to the block state");

        MethodInsnNode getValue = calls(lit).stream()
                .filter(mi -> mi.name.equals("getValue")).findFirst().orElseThrow();
        assertEquals(INVOKEVIRTUAL, getValue.getOpcode(),
                "a kept name still needs the class opcode, or the JVM throws IncompatibleClassChangeError");
        assertFalse(getValue.itf);
    }

    @Test
    void stateContainerAddsItsPropertiesThroughTheHostOverride() {
        RetromodTransformer t = bridged();
        byte[] remapped = t.transformClass(legacyBlock(), "test/Lamp.class");
        ClassNode cn = node(Forge1122BlockStateBridge.upgradeBlockClass(remapped, "createBlockStateDefinition"));

        MethodInsnNode container = calls(method(cn, "func_180661_e")).stream()
                .filter(mi -> mi.name.equals("container")).findFirst().orElseThrow(
                        () -> new AssertionError("new BlockStateContainer must call the factory"));
        assertEquals(INVOKESTATIC, container.getOpcode());

        MethodNode override = cn.methods.stream()
                .filter(m -> m.name.equals("createBlockStateDefinition")
                        && m.desc.equals(Forge1122BlockStateBridge.DEFINITION_DESC))
                .findFirst().orElseThrow(() -> new AssertionError("the host override must be added"));
        assertEquals(List.of("defineLegacyStates"), calls(override).stream().map(mi -> mi.name).toList(),
                "the override runs createBlockState() while the host builder is open");
        assertTrue(Arrays.stream(override.instructions.toArray()).anyMatch(in ->
                        in instanceof org.objectweb.asm.tree.LdcInsnNode ldc && "func_180661_e".equals(ldc.cst)),
                "the helper is told which method is the block's createBlockState");

        ClassNode factory = node(Forge1122BlockStateBridge.factoryBytes());
        assertTrue(factory.methods.stream().anyMatch(m -> m.name.equals(container.name)
                        && m.desc.equals(container.desc)),
                "the rewritten container call must link against the generated factory");
    }

    @Test
    void directionPropertyFilterTakesTheGuavaPredicateAsAJdkOne() {
        String direction = Forge1122BlockStateBridge.DIRECTION_PROPERTY;
        String previous = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        try {
            RetromodTransformer t = bridged();
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Facing", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitLdcInsn("facing");
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, direction, "create",
                    "(Ljava/lang/String;Lcom/google/common/base/Predicate;)L" + direction + ";", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            cw.visitEnd();

            MethodInsnNode create = calls(method(node(t.transformClass(cw.toByteArray(), "test/Facing.class")),
                    "<clinit>")).get(0);
            assertEquals("(Ljava/lang/String;Ljava/util/function/Predicate;)L" + direction + ";", create.desc,
                    "Guava's Predicate extends the JDK Predicate the host factory takes");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = previous;
        }
    }

    @Test
    void forgeContainerBuilderCollectsListedPropertiesAndIgnoresUnlistedOnes() {
        RetromodTransformer t = bridged();
        String oldBuilder = "net/minecraft/block/state/BlockStateContainer$Builder";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Elevator", null, BLOCK, null);
        MethodVisitor create = cw.visitMethod(ACC_PROTECTED, "func_180661_e", "()L" + DEFINITION + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, oldBuilder);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKESPECIAL, oldBuilder, "<init>", "(L" + BLOCK + ";)V", false);
        create.visitInsn(ICONST_0);
        create.visitTypeInsn(ANEWARRAY, "net/minecraftforge/common/property/IUnlistedProperty");
        create.visitMethodInsn(INVOKEVIRTUAL, oldBuilder, "add",
                "([Lnet/minecraftforge/common/property/IUnlistedProperty;)L" + oldBuilder + ";", false);
        create.visitMethodInsn(INVOKEVIRTUAL, oldBuilder, "build", "()L" + DEFINITION + ";", false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        cw.visitEnd();

        ClassNode standIn = node(Forge1122BlockStateBridge.containerBuilderBytes());
        MethodNode transformed = method(node(t.transformClass(cw.toByteArray(), "test/Elevator.class")),
                "func_180661_e");
        TypeInsnNode array = Arrays.stream(transformed.instructions.toArray())
                .filter(in -> in.getOpcode() == ANEWARRAY).map(TypeInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals(Forge1122BlockStateBridge.UNLISTED, array.desc,
                "the unlisted property array must name a loadable type, or createBlockState fails before add");
        assertTrue((node(t.getSyntheticClasses().get(Forge1122BlockStateBridge.UNLISTED)).access & ACC_INTERFACE) != 0,
                "mods implement IUnlistedProperty, so the stand-in must be an interface");
        for (MethodInsnNode call : calls(transformed)) {
            assertEquals(Forge1122BlockStateBridge.CONTAINER_BUILDER, call.owner);
            assertTrue(standIn.methods.stream().anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc)),
                    call.name + call.desc + " must link against the builder stand-in");
        }
    }

    @Test
    void blocksWithoutAStateContainerAndUpgradedBlocksAreLeftAlone() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Plain", null, BLOCK, null);
        cw.visitEnd();
        byte[] plain = cw.toByteArray();
        assertSame(plain, Forge1122BlockStateBridge.upgradeBlockClass(plain, "createBlockStateDefinition"));

        byte[] once = Forge1122BlockStateBridge.upgradeBlockClass(
                bridged().transformClass(legacyBlock(), "test/Lamp.class"), "m_7926_");
        assertSame(once, Forge1122BlockStateBridge.upgradeBlockClass(once, "m_7926_"),
                "a block that already has the override must not get a second one");
    }
}
