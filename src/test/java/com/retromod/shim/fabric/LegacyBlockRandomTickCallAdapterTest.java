/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.shim.fabric.embedded.LegacyBlockRandomTickBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Transform-level coverage for the legacy block random-tick state-cache refresh. */
class LegacyBlockRandomTickCallAdapterTest {

    private static final String ACCESSOR_OWNER =
            "virtuoel/permafrost/mixin/BlockAccessor";

    @Test
    @DisplayName("#228: proven random-tick accessor calls refresh every cached block state")
    void refreshesAfterVerifiedAccessorSetter() {
        byte[] accessor = accessorMixin();
        byte[] once = LegacyBlockRandomTickCallAdapter.apply(
                caller(), owner -> ACCESSOR_OWNER.equals(owner) ? accessor : null);
        byte[] twice = LegacyBlockRandomTickCallAdapter.apply(
                once, owner -> ACCESSOR_OWNER.equals(owner) ? accessor : null);

        MethodNode method = method(twice, "disableRandomTicks");
        MethodInsnNode setter = findCall(method, ACCESSOR_OWNER, "setRandomTicks");
        assertEquals(Opcodes.DUP2, meaningfulPrevious(setter).getOpcode());
        assertEquals(Opcodes.POP, meaningfulNext(setter).getOpcode());

        MethodInsnNode refresh = (MethodInsnNode) meaningfulNext(meaningfulNext(setter));
        assertEquals(Opcodes.INVOKESTATIC, refresh.getOpcode());
        assertEquals(LegacyBlockRandomTickBridge.INTERNAL_NAME, refresh.owner);
        assertEquals("refreshStates", refresh.name);
        assertEquals(1, countCalls(method,
                LegacyBlockRandomTickBridge.INTERNAL_NAME, "refreshStates"),
                "the adapter must be idempotent");
    }

    @Test
    @DisplayName("lookalike interface calls stay unchanged without accessor proof")
    void rejectsUnprovenLookalike() {
        byte[] input = caller();
        byte[] output = LegacyBlockRandomTickCallAdapter.apply(
                input, owner -> plainInterface());
        assertSame(input, output,
                "a matching method name without @Mixin and @Accessor facts must be untouched");
    }

    @Test
    @DisplayName("an accessor with multiple mixin targets is refused")
    void rejectsAccessorWithMultipleTargets() {
        byte[] input = caller();
        byte[] output = LegacyBlockRandomTickCallAdapter.apply(
                input,
                owner -> ACCESSOR_OWNER.equals(owner) ? accessorMixinWithTwoTargets() : null);

        assertSame(input, output,
                "the cache refresh requires one proven Block target");
    }

    @Test
    @DisplayName("a relocated embedded refresh call is not added twice")
    void recognizesRelocatedBridgeCall() {
        byte[] accessor = accessorMixin();
        byte[] transformed = LegacyBlockRandomTickCallAdapter.apply(
                callerWithRelocatedRefresh(),
                owner -> ACCESSOR_OWNER.equals(owner) ? accessor : null);

        MethodNode method = method(transformed, "disableRandomTicks");
        assertEquals(1, countCallsEndingWith(method,
                LegacyBlockRandomTickBridge.INTERNAL_NAME, "refreshStates"),
                "reprocessing an embedded mod must keep one cache refresh");
    }

    @Test
    @DisplayName("state refresh recomputes every possible state after the owner flag changes")
    void refreshesEveryPossibleState() {
        FakeBlock block = new FakeBlock();
        block.randomTicks = false;

        LegacyBlockRandomTickBridge.refreshStates(block);

        assertFalse(block.definition.states.get(0).randomTicks);
        assertFalse(block.definition.states.get(1).randomTicks);
        assertEquals(1, block.definition.states.get(0).refreshes);
        assertEquals(1, block.definition.states.get(1).refreshes);
    }

    private static byte[] accessorMixin() {
        return accessorMixin("net/minecraft/class_2248");
    }

    private static byte[] accessorMixinWithTwoTargets() {
        return accessorMixin(
                "net/minecraft/class_2248",
                "net/minecraft/world/level/material/Fluid");
    }

    private static byte[] accessorMixin(String... targetNames) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                ACCESSOR_OWNER, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        for (String targetName : targetNames) {
            targets.visit(null, Type.getObjectType(targetName));
        }
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor setter = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "setRandomTicks", "(Z)V", null, null);
        setter.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/gen/Accessor;", true).visitEnd();
        setter.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] plainInterface() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                ACCESSOR_OWNER, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "setRandomTicks", "(Z)V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "example/PermafrostCaller", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "disableRandomTicks", "(Ljava/lang/Object;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitTypeInsn(Opcodes.CHECKCAST, ACCESSOR_OWNER);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                ACCESSOR_OWNER, "setRandomTicks", "(Z)V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] callerWithRelocatedRefresh() {
        byte[] bytes = caller();
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);

        MethodNode target = node.methods.stream()
                .filter(candidate -> "disableRandomTicks".equals(candidate.name))
                .findFirst().orElseThrow();
        MethodInsnNode targetSetter = findCall(target, ACCESSOR_OWNER, "setRandomTicks");
        target.instructions.insertBefore(targetSetter, new InsnNode(Opcodes.DUP2));
        InsnList refresh = new InsnList();
        refresh.add(new InsnNode(Opcodes.POP));
        refresh.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/retromod/embedded/test/" + LegacyBlockRandomTickBridge.INTERNAL_NAME,
                "refreshStates", "(Ljava/lang/Object;)V", false));
        target.instructions.insert(targetSetter, refresh);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(method -> name.equals(method.name))
                .findFirst().orElseThrow();
    }

    private static MethodInsnNode findCall(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                return call;
            }
        }
        throw new AssertionError("expected method call was not found");
    }

    private static long countCalls(MethodNode method, String owner, String name) {
        long count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static long countCallsEndingWith(MethodNode method, String owner, String name) {
        long count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && (call.owner.equals(owner) || call.owner.endsWith("/" + owner))
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode meaningfulPrevious(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode meaningfulNext(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    public static final class FakeBlock {
        public boolean randomTicks = true;
        public final FakeStateDefinition definition = new FakeStateDefinition(this);

        public FakeStateDefinition getStateDefinition() {
            return definition;
        }
    }

    public static final class FakeStateDefinition {
        private final List<FakeState> states;

        FakeStateDefinition(FakeBlock block) {
            states = List.of(new FakeState(block), new FakeState(block));
        }

        public List<FakeState> getPossibleStates() {
            return states;
        }
    }

    public static final class FakeState {
        private final FakeBlock block;
        public boolean randomTicks = true;
        public int refreshes;

        FakeState(FakeBlock block) {
            this.block = block;
        }

        public void initCache() {
            randomTicks = block.randomTicks;
            refreshes++;
        }
    }
}
