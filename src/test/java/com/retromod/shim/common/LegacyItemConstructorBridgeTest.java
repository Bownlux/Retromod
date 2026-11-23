/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;


import static org.junit.jupiter.api.Assertions.*;

/**
 * A 1.21.1 mod registers its sign, wall-mounted, potion and boat items in one method, so the first
 * reshaped constructor stopped everything after it from registering on 26.1.
 */
class LegacyItemConstructorBridgeTest {

    private static final String MOD_CLASS = "test/mod/ModItems";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old sign item constructor becomes the bridge factory on 26.1")
    void signItemGoesThroughTheFactory() {
        LegacyItemConstructorBridge.register(transformer, "26.1.2");

        MethodInsnNode call = constructionCall(transformedMaker(signMaker()));
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyItemConstructorBridge.INTERNAL, call.owner,
                "the sign item must be built by the bridge's factory");
        assertEquals("signItem", call.name);
    }

    @Test
    @DisplayName("an older host keeps the constructor it still has")
    void olderHostIsUntouched() {
        LegacyItemConstructorBridge.register(transformer, "1.21.4");

        MethodInsnNode call = constructionCall(transformedMaker(signMaker()));
        assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode());
        assertEquals(LegacyItemConstructorBridge.SIGN_ITEM, call.owner);
        assertTrue(transformer.getSyntheticClasses().isEmpty());
    }

    @Test
    @DisplayName("on 26.3 the rebuilt SignItem takes the old constructor, and the factories never name the removed class")
    void removedSignItemLeavesTheFactoryOut() {
        LegacyItemConstructorBridge.register(transformer, "26.3");

        MethodInsnNode call = constructionCall(transformedMaker(signMaker()));
        assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode(), "the rebuilt class keeps the old constructor");
        byte[] factories = transformer.getSyntheticClasses().get(LegacyItemConstructorBridge.INTERNAL);
        assertFalse(new String(factories, java.nio.charset.StandardCharsets.ISO_8859_1).contains(LegacyItemConstructorBridge.SIGN_ITEM),
                "a factory naming SignItem would not link on 26.3");
        assertVerifies(factories);
    }

    @Test
    @DisplayName("the oak boat a stand-in boat item places is read from EntityTypes from 26.2 on")
    void boatItemReadsTheOakBoatWhereTheHostKeepsIt() {
        assertEquals(LegacyItemConstructorBridge.ENTITY_TYPE, boatOwnerOn("26.1.2"), "26.1 still keeps them on EntityType");
        assertEquals(LegacyItemConstructorBridge.ENTITY_TYPES, boatOwnerOn("26.2"), "26.2 moved them to EntityTypes");
        assertEquals(LegacyItemConstructorBridge.ENTITY_TYPES, boatOwnerOn("26.3"));

        // A readable host decides by its own fields, whatever the version says.
        ClassNode moved = new ClassNode();
        moved.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "OAK_BOAT",
                "L" + LegacyItemConstructorBridge.ENTITY_TYPE + ";", null, null));
        assertEquals(LegacyItemConstructorBridge.ENTITY_TYPES, LegacyItemConstructorBridge.boatTypeOwner(moved, "26.1.2"));
        assertEquals(LegacyItemConstructorBridge.ENTITY_TYPE, LegacyItemConstructorBridge.boatTypeOwner(new ClassNode(), "26.3"));
    }

    /** Registers on {@code host} and returns the owner of every oak boat field the generated factory reads. */
    private String boatOwnerOn(String host) {
        transformer.clearRedirectsForTesting();
        LegacyItemConstructorBridge.register(transformer, host);
        ClassNode factories = new ClassNode();
        new ClassReader(transformer.getSyntheticClasses().get(LegacyItemConstructorBridge.INTERNAL)).accept(factories, 0);
        java.util.Set<String> owners = new java.util.TreeSet<>();
        for (MethodNode method : factories.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.contains("OAK")) owners.add(field.owner);
            }
        }
        assertEquals(1, owners.size(), "both boat fields share one owner: " + owners);
        return owners.iterator().next();
    }

    @Test
    @DisplayName("the generated factories and boat stand-in are valid classes")
    void generatedClassesVerify() {
        assertVerifies(LegacyItemConstructorBridge.generate());
        assertVerifies(LegacyItemConstructorBridge.generateBoatType());
    }

    @Test
    @DisplayName("a 1.21.1 subclass's super call to StandingAndWallBlockItem swaps its direction and properties")
    void standingItemSuperCallSwapsArguments() {
        LegacyItemConstructorBridge.register(transformer, "26.3");

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(standingSubclass(), MOD_CLASS)).accept(out, 0);
        MethodNode init = out.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        MethodInsnNode call = constructionCall(init);
        assertEquals(LegacyItemConstructorBridge.STANDING_ITEM, call.owner);
        assertEquals(LegacyItemConstructorBridge.NEW_STANDING_DESC, call.desc, "26.1 takes the direction before the properties");
        assertEquals(Opcodes.SWAP, call.getPrevious().getOpcode(), "the last two arguments trade places on the stack");
    }

    /** {@code class Bunting extends StandingAndWallBlockItem { Bunting(a, b, p, d) { super(a, b, p, d); } }}, as 1.21.1 compiled it. */
    private static byte[] standingSubclass() {
        String desc = LegacyItemConstructorBridge.OLD_STANDING_DESC;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                LegacyItemConstructorBridge.STANDING_ITEM, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyItemConstructorBridge.STANDING_ITEM, "<init>", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Structural checks only: Minecraft is not on the test classpath for a data-flow check. */
    private static void assertVerifies(byte[] bytes) {
        assertDoesNotThrow(() -> new ClassReader(bytes).accept(new CheckClassAdapter(null, false), 0),
                "the generated class must be well formed");
    }

    private MethodNode transformedMaker(byte[] bytes) {
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD_CLASS)).accept(out, 0);
        return out.methods.stream().filter(m -> m.name.equals("make")).findFirst().orElseThrow();
    }

    private static MethodInsnNode constructionCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        fail("the method must still build the item");
        return null;
    }

    /** {@code static Object make(Properties p, Block a, Block b) { return new SignItem(p, a, b); }} */
    private static byte[] signMaker() {
        String desc = LegacyItemConstructorBridge.OLD_SIGN_DESC;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                desc.replace(")V", ")Ljava/lang/Object;"), null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, LegacyItemConstructorBridge.SIGN_ITEM);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyItemConstructorBridge.SIGN_ITEM, "<init>",
                desc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("before 26.1 the wall-standing item is bridged where the host shows the new order, as 1.21.11 does")
    void standingItemFollowsTheHost() {
        ClassNode reordered = new ClassNode();
        reordered.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", LegacyItemConstructorBridge.NEW_STANDING_DESC, null, null));
        ClassNode old = new ClassNode();
        old.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", LegacyItemConstructorBridge.OLD_STANDING_DESC, null, null));
        assertTrue(LegacyItemConstructorBridge.standingItemReordered(reordered, "1.21.11"));
        assertFalse(LegacyItemConstructorBridge.standingItemReordered(old, "1.21.11"));
        assertTrue(LegacyItemConstructorBridge.standingItemReordered(null, "1.21.11"), "offline, 1.21.11 has the new order");
        assertFalse(LegacyItemConstructorBridge.standingItemReordered(null, "1.21.10"), "offline, an earlier host is not assumed");
        new ClassReader(LegacyItemConstructorBridge.generateStandingOnly()).accept(new CheckClassAdapter(new ClassWriter(0)), 0);
    }
}
