/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.Legacy1212MemberBridge.LevelConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.21.2 dropped the profiler supplier from Level's constructor. Create's virtual render world,
 * Moonlight's fake level and Iceberg's entity collector still passed it, so building them failed
 * with {@code NoSuchMethodError} on 26.3, and 16 mods' ServerLevel and ClientLevel mixins carried
 * the same call in their constructors.
 */
class Legacy1212LevelConstructorTest {

    private static final LevelConstructor MOJANG = Legacy1212MemberBridge.MOJANG_LEVEL;

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a fake level's super call reaches the new constructor with every other argument in place")
    void fakeLevelBuildsOnTheNewConstructor() throws Exception {
        RetromodTransformer transformer = bridged("26.3", MOJANG);

        byte[] fakeLevel = transformer.transformClass(fakeLevel(MOJANG, "mod/FakeLevel"), "mod/FakeLevel");

        assertEquals(MOJANG.newDesc(), superCall(fakeLevel, MOJANG.owner()).desc);
        HostStubs stubs = new HostStubs().add(level(MOJANG)).add("mod/FakeLevel", fakeLevel);
        Object level = stubs.load("mod/FakeLevel").getConstructor().newInstance();
        Class<?> type = level.getClass().getSuperclass();
        assertEquals(true, type.getField("clientSide").get(level));
        assertEquals(false, type.getField("debug").get(level));
        assertEquals(123456789L, type.getField("seed").get(level));
        assertEquals(1000000, type.getField("updates").get(level));
        assertEquals("net.minecraft.world.level.storage.WritableLevelData",
                type.getField("data").get(level).getClass().getName(), "the arguments before the supplier stay in place");
        assertEquals("net.minecraft.core.Holder", type.getField("holder").get(level).getClass().getName());
    }

    @Test
    @DisplayName("a host that still takes the supplier keeps the call")
    void hostWithTheOldConstructorIsLeftAlone() {
        ClassNode before = new ClassNode();
        before.methods.add(new MethodNode(Opcodes.ACC_PROTECTED, "<init>", MOJANG.oldDesc(), null, null));
        ClassNode after = new ClassNode();
        after.methods.add(new MethodNode(Opcodes.ACC_PROTECTED, "<init>", MOJANG.newDesc(), null, null));
        assertFalse(Legacy1212MemberBridge.replacedIn(before, "<init>", MOJANG.oldDesc(), "<init>", MOJANG.newDesc()));
        assertTrue(Legacy1212MemberBridge.replacedIn(after, "<init>", MOJANG.oldDesc(), "<init>", MOJANG.newDesc()));

        RetromodTransformer transformer = bridged("1.21.1", MOJANG);
        byte[] fakeLevel = transformer.transformClass(fakeLevel(MOJANG, "mod/FakeLevel"), "mod/FakeLevel");

        assertEquals(MOJANG.oldDesc(), superCall(fakeLevel, MOJANG.owner()).desc);
    }

    @Test
    @DisplayName("a Fabric host from 1.21.2 to 1.21.11 gets the same rewrite in intermediary names")
    void intermediaryForFabricBefore26() {
        LevelConstructor intermediary = Legacy1212MemberBridge.INTERMEDIARY_LEVEL;
        RetromodVersion.TARGET_MC_VERSION = "1.21.8";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);

        byte[] fakeLevel = transformer.transformClass(fakeLevel(intermediary, "mod/FakeWorld"), "mod/FakeWorld");

        assertEquals(intermediary.newDesc(), superCall(fakeLevel, intermediary.owner()).desc);
    }

    @Test
    @DisplayName("NeoForge, Forge and Fabric on 26.x register it from their 1.21.2 shims")
    void everyLoaderRegistersIt() {
        for (com.retromod.core.VersionShim shim : java.util.List.of(new com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2(),
                new com.retromod.shim.forge.Forge_1_21_1_to_1_21_2(),
                new com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2())) {
            RetromodVersion.TARGET_MC_VERSION = "26.3";
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);

            byte[] fakeLevel = transformer.transformClass(fakeLevel(MOJANG, "mod/FakeLevel"), "mod/FakeLevel");

            assertEquals(MOJANG.newDesc(), superCall(fakeLevel, MOJANG.owner()).desc, shim.getShimName());
        }
    }

    private static RetromodTransformer bridged(String host, LevelConstructor level) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Legacy1212MemberBridge.registerLevelConstructor(transformer, level);
        return transformer;
    }

    /** {@code FakeLevel() { super(new WritableLevelData(), ..., profilerSupplier, true, false, 123456789L, 1000000); }} */
    private static byte[] fakeLevel(LevelConstructor level, String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, level.owner(), null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        Type[] args = Type.getArgumentTypes(level.oldDesc());
        for (int i = 0; i < 4; i++) {
            init.visitTypeInsn(Opcodes.NEW, args[i].getInternalName());
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, args[i].getInternalName(), "<init>", "()V", false);
        }
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitInsn(Opcodes.ICONST_1);
        init.visitInsn(Opcodes.ICONST_0);
        init.visitLdcInsn(123456789L);
        init.visitLdcInsn(1000000);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, level.owner(), "<init>", level.oldDesc(), false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Level with only the 1.21.2 constructor, which records its arguments in public fields. */
    private static byte[] level(LevelConstructor level) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, level.owner(), null, "java/lang/Object", null);
        String[][] fields = {{"data", "Ljava/lang/Object;"}, {"key", "Ljava/lang/Object;"},
                {"access", "Ljava/lang/Object;"}, {"holder", "Ljava/lang/Object;"}, {"clientSide", "Z"},
                {"debug", "Z"}, {"seed", "J"}, {"updates", "I"}};
        for (String[] field : fields) cw.visitField(Opcodes.ACC_PUBLIC, field[0], field[1], null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", level.newDesc(), null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int slot = 1;
        for (String[] field : fields) {
            Type type = Type.getType(field[1]);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
            init.visitFieldInsn(Opcodes.PUTFIELD, level.owner(), field[0], field[1]);
            slot += type.getSize();
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode superCall(byte[] classBytes, String owner) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals("<init>")) {
                    return call;
                }
            }
        }
        throw new AssertionError("no super call to " + owner);
    }
}
