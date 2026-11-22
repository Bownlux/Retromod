/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Old block hooks, server members and target predicates that 1.21.2 to 26.3 changed keep working. */
class LegacyServer263BridgesTest {

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Old block hook overrides get their current form, and their super calls reach the current hook")
    void blockHooks() throws Exception {
        Set<String> flags = Set.of(LegacyBlockOverridesAdapter.NEIGHBOR_CHANGED, LegacyBlockOverridesAdapter.ON_REMOVE,
                LegacyBlockOverridesAdapter.CLONE_STACK, LegacyBlockOverridesAdapter.FALL_ON, LegacyBlockOverridesAdapter.ENTITY_INSIDE);
        ClassNode repaired = read(LegacyBlockOverridesAdapter.apply(oldBlock(), flags));

        assertNotNull(method(repaired, "affectNeighborsAfterRemoval", LegacyBlockOverridesAdapter.NEW_REMOVE));
        assertNotNull(method(repaired, "neighborChanged", LegacyBlockOverridesAdapter.NEW_NEIGHBOR));
        assertNotNull(method(repaired, "getCloneItemStack", LegacyBlockOverridesAdapter.NEW_CLONE));
        assertNotNull(method(repaired, "fallOn", LegacyBlockOverridesAdapter.NEW_FALL));
        assertNotNull(method(repaired, "entityInside", LegacyBlockOverridesAdapter.NEW_INSIDE));
        List<String> removeCalls = calls(method(repaired, "onRemove", LegacyBlockOverridesAdapter.OLD_REMOVE));
        assertTrue(removeCalls.contains(BLOCK + ".affectNeighborsAfterRemoval" + LegacyBlockOverridesAdapter.NEW_REMOVE), removeCalls.toString());
        assertTrue(calls(method(repaired, "fallOn", LegacyBlockOverridesAdapter.OLD_FALL))
                .contains(BLOCK + ".fallOn" + LegacyBlockOverridesAdapter.NEW_FALL));
        for (MethodNode m : repaired.methods) new Analyzer<>(new BasicVerifier()).analyze(repaired.name, m);
    }

    @Test
    @DisplayName("An old entityInside super call passes on the applier the host gave the bridge")
    void entityInsideKeepsTheApplier() throws Exception {
        ClassNode repaired = read(LegacyBlockOverridesAdapter.apply(oldBlock(), Set.of(LegacyBlockOverridesAdapter.ENTITY_INSIDE)));
        String helper = LegacyBlockOverridesAdapter.INSIDE_HELPER;
        List<String> bridge = calls(method(repaired, "entityInside", LegacyBlockOverridesAdapter.NEW_INSIDE));
        assertEquals(helper + ".enter(Ljava/lang/Object;)Ljava/lang/Object;", bridge.get(0), bridge.toString());
        assertTrue(bridge.contains(helper + ".exit(Ljava/lang/Object;)V"), bridge.toString());
        List<String> old = calls(method(repaired, "entityInside", LegacyBlockOverridesAdapter.OLD_INSIDE));
        assertEquals(List.of(helper + ".current()Lnet/minecraft/world/entity/InsideBlockEffectApplier;",
                BLOCK + ".entityInside" + LegacyBlockOverridesAdapter.NEW_INSIDE), old);
        ClassNode slot = read(LegacyBlockOverridesAdapter.generateInsideHelper());
        for (MethodNode m : slot.methods) new Analyzer<>(new BasicVerifier()).analyze(slot.name, m);
    }

    @Test
    @DisplayName("Without the host's change no rule runs, so a newer mod is left alone")
    void noFlagsNoChange() {
        byte[] block = oldBlock();
        assertSame(block, LegacyBlockOverridesAdapter.apply(block, Set.of()));
    }

    @Test
    @DisplayName("The server members register only where the host changed them, and their helpers verify")
    void serverMembersFollowTheHost() throws Exception {
        Map<String, ClassNode> host = Map.of(
                "net/minecraft/world/Containers", type("net/minecraft/world/Containers"),
                "net/minecraft/world/entity/Entity", type("net/minecraft/world/entity/Entity",
                        new FieldNode(Opcodes.ACC_PUBLIC, "needsSync", "Z", null, null),
                        new FieldNode(Opcodes.ACC_PUBLIC, "fallDistance", "D", null, null)));
        LegacyServerApiBridge.register(transformer, host::get);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyServerApiBridge.HELPER));
        ClassNode helper = read(transformer.getSyntheticClasses().get(LegacyServerApiBridge.HELPER));
        for (String name : List.of("dropContentsOnDestroy", "needsSync", "setNeedsSync", "fallDistance", "setFallDistance")) {
            assertTrue(helper.methods.stream().anyMatch(m -> m.name.equals(name)), name);
        }
        assertFalse(helper.methods.stream().anyMatch(m -> m.name.equals("readVec3")), "no buffer class was readable");
        for (MethodNode m : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, m);
        for (MethodNode m : read(LegacyServerApiBridge.generateSmall(true, true)).methods) {
            new Analyzer<>(new BasicVerifier()).analyze(LegacyServerApiBridge.SMALL, m);
        }
    }

    @Test
    @DisplayName("A crafting remainder read while 26.3 loads recipes is built as a stack that waits for bound components")
    void remainderWaitsForComponents() throws Exception {
        for (boolean early : new boolean[] {false, true}) {
            ClassNode small = read(LegacyServerApiBridge.generateSmall(false, true, early));
            MethodNode remainder = small.methods.stream().filter(m -> m.name.equals("craftingRemainder")).findFirst().orElseThrow();
            new Analyzer<>(new BasicVerifier()).analyze(small.name, remainder);
            List<String> calls = new ArrayList<>();
            remainder.instructions.forEach(insn -> {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            });
            assertEquals(early, calls.contains(LegacyItemStackCodecsBridge.HELPER + ".create"), calls.toString());
            assertEquals(!early, calls.contains("net/minecraft/world/item/ItemStackTemplate.create"), calls.toString());
        }
    }

    @Test
    @DisplayName("On 1.21.5 to 1.21.11 a crafting remainder is read as the item's stack, and a host with neither shape gets no helper")
    void remainderFollowsTheHostShape() throws Exception {
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        assertEquals(LegacyServerApiBridge.RemainderShape.STACK,
                LegacyServerApiBridge.remainderShape(item("()" + stack)), "1.21.11 returns a stack");
        assertEquals(LegacyServerApiBridge.RemainderShape.TEMPLATE,
                LegacyServerApiBridge.remainderShape(item("()Lnet/minecraft/world/item/ItemStackTemplate;")), "26.x returns a template");
        assertNull(LegacyServerApiBridge.remainderShape(item("()V")));
        assertNull(LegacyServerApiBridge.remainderShape(null));

        ClassNode small = read(LegacyServerApiBridge.generateSmall(false, LegacyServerApiBridge.RemainderShape.STACK, false));
        for (MethodNode m : small.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(small.name, m);
            m.instructions.forEach(insn -> {
                if (insn instanceof MethodInsnNode call) {
                    assertFalse(call.desc.contains("ItemStackTemplate"), m.name + " calls the 26.x shape: " + call.desc);
                }
            });
        }
        assertEquals(3, small.methods.stream().filter(m -> m.name.contains("rafting")).count());
    }

    /** An Item class whose only remainder getter has {@code desc}. */
    private static ClassNode item(String desc) {
        ClassNode item = new ClassNode();
        item.name = "net/minecraft/world/item/Item";
        item.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getCraftingRemainder", desc, null, null));
        return item;
    }

    @Test
    @DisplayName("The drop helper compares the old and new blocks itself, since a 26.x state has no is(Block)")
    void dropHelperComparesBlocksDirectly() {
        ClassNode helper = read(LegacyServerApiBridge.generate(true, false, false, false, false, false, List.of()));
        MethodNode drop = helper.methods.stream().filter(m -> m.name.equals("dropContentsOnDestroy")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        drop.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        });
        assertFalse(calls.stream().anyMatch(c -> c.contains(".is(")), "no state.is(Block) call: " + calls);
        assertTrue(drop.instructions.toArray().length > 0
                && java.util.Arrays.stream(drop.instructions.toArray()).anyMatch(i -> i.getOpcode() == Opcodes.IF_ACMPEQ),
                "the blocks are compared by identity");
    }

    @Test
    @DisplayName("On a 26.2-shaped entity only hasImpulse moves, to needsSync; hurtMarked still exists there")
    void impulseOnlyWhereHurtMarkedRemains() {
        List<String> reads = entityFlagReads(new FieldNode(Opcodes.ACC_PUBLIC, "hurtMarked", "Z", null, null),
                new FieldNode(Opcodes.ACC_PUBLIC, "needsSync", "Z", null, null));
        assertEquals(List.of(LegacyServerApiBridge.HELPER + ".needsSync", ENTITY + ".hurtMarked"), reads);
    }

    @Test
    @DisplayName("On a 26.3-shaped entity hasImpulse reads needsSync and hurtMarked reads syncVelocity")
    void hurtMarkedFollowsSyncVelocity() throws Exception {
        List<String> reads = entityFlagReads(new FieldNode(Opcodes.ACC_PUBLIC, "needsSync", "Z", null, null),
                new FieldNode(Opcodes.ACC_PUBLIC, "syncVelocity", "Z", null, null));
        assertEquals(List.of(LegacyServerApiBridge.HELPER + ".needsSync", LegacyServerApiBridge.HELPER + ".syncVelocity"), reads);
        ClassNode helper = read(transformer.getSyntheticClasses().get(LegacyServerApiBridge.HELPER));
        assertNotNull(method(helper, "setSyncVelocity", "(Ljava/lang/Object;Z)V"));
        for (MethodNode m : helper.methods) new Analyzer<>(new BasicVerifier()).analyze(helper.name, m);
    }

    @Test
    @DisplayName("A target goal built with an entity predicate gets it wrapped as a selector")
    void targetPredicates() throws Exception {
        String old = "(Lnet/minecraft/world/entity/Mob;Ljava/lang/Class;ZLjava/util/function/Predicate;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Goals", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "(Lnet/minecraft/world/entity/Mob;Ljava/util/function/Predicate;)Ljava/lang/Object;", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, LegacyTargetSelectorBridge.GOAL);
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitLdcInsn(Type.getObjectType("net/minecraft/world/entity/player/Player"));
        m.visitInsn(Opcodes.ICONST_1);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyTargetSelectorBridge.GOAL, "<init>", old, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(method(read(LegacyTargetSelectorBridge.apply(cw.toByteArray())), "make", null));
        assertEquals(List.of(LegacyTargetSelectorBridge.WRAPPER + ".of(Ljava/util/function/Predicate;)L" + LegacyTargetSelectorBridge.SELECTOR + ";",
                LegacyTargetSelectorBridge.GOAL + ".<init>" + old.replace("Ljava/util/function/Predicate;)", "L" + LegacyTargetSelectorBridge.SELECTOR + ";)")), calls);
        ClassNode wrapper = read(LegacyTargetSelectorBridge.generate());
        for (MethodNode w : wrapper.methods) new Analyzer<>(new BasicVerifier()).analyze(wrapper.name, w);
    }

    /** Registers against an entity with {@code fields}, then lists what a mod's hasImpulse and hurtMarked reads became. */
    private List<String> entityFlagReads(FieldNode... fields) {
        LegacyServerApiBridge.register(transformer, Map.of(ENTITY, type(ENTITY, fields))::get);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Knockback", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "flags", "(L" + ENTITY + ";)Z", null, null);
        m.visitCode();
        for (String name : List.of("hasImpulse", "hurtMarked")) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, ENTITY, name, "Z");
            m.visitInsn(Opcodes.POP);
        }
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        List<String> reads = new ArrayList<>();
        method(read(transformer.transformClass(cw.toByteArray(), "test/mod/Knockback")), "flags", null).instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) reads.add(call.owner + "." + call.name);
            if (insn instanceof FieldInsnNode field) reads.add(field.owner + "." + field.name);
        });
        return reads;
    }

    /** A block with each old hook: onRemove, fallOn and entityInside also call their super form. */
    private static byte[] oldBlock() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/OldBlock", null, BLOCK, null);
        MethodVisitor remove = cw.visitMethod(Opcodes.ACC_PROTECTED, "onRemove", LegacyBlockOverridesAdapter.OLD_REMOVE, null, null);
        remove.visitCode();
        remove.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 4; slot++) remove.visitVarInsn(Opcodes.ALOAD, slot);
        remove.visitVarInsn(Opcodes.ILOAD, 5);
        remove.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "onRemove", LegacyBlockOverridesAdapter.OLD_REMOVE, false);
        remove.visitInsn(Opcodes.RETURN);
        remove.visitMaxs(0, 0);
        remove.visitEnd();
        MethodVisitor fall = cw.visitMethod(Opcodes.ACC_PUBLIC, "fallOn", LegacyBlockOverridesAdapter.OLD_FALL, null, null);
        fall.visitCode();
        fall.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 4; slot++) fall.visitVarInsn(Opcodes.ALOAD, slot);
        fall.visitVarInsn(Opcodes.FLOAD, 5);
        fall.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "fallOn", LegacyBlockOverridesAdapter.OLD_FALL, false);
        fall.visitInsn(Opcodes.RETURN);
        fall.visitMaxs(0, 0);
        fall.visitEnd();
        empty(cw, "neighborChanged", LegacyBlockOverridesAdapter.OLD_NEIGHBOR);
        MethodVisitor inside = cw.visitMethod(Opcodes.ACC_PUBLIC, "entityInside", LegacyBlockOverridesAdapter.OLD_INSIDE, null, null);
        inside.visitCode();
        inside.visitVarInsn(Opcodes.ALOAD, 0);
        for (int slot = 1; slot <= 4; slot++) inside.visitVarInsn(Opcodes.ALOAD, slot);
        inside.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "entityInside", LegacyBlockOverridesAdapter.OLD_INSIDE, false);
        inside.visitInsn(Opcodes.RETURN);
        inside.visitMaxs(0, 0);
        inside.visitEnd();
        MethodVisitor clone = cw.visitMethod(Opcodes.ACC_PUBLIC, "getCloneItemStack", LegacyBlockOverridesAdapter.OLD_CLONE, null, null);
        clone.visitCode();
        clone.visitInsn(Opcodes.ACONST_NULL);
        clone.visitInsn(Opcodes.ARETURN);
        clone.visitMaxs(0, 0);
        clone.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void empty(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PROTECTED, name, desc, null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static ClassNode type(String name, FieldNode... fields) {
        ClassNode node = new ClassNode();
        node.name = name;
        node.fields.addAll(List.of(fields));
        return node;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc))).findFirst().orElse(null);
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        method.instructions.forEach(insn -> { if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc); });
        return calls;
    }
}
