/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_26_2_to_26_3;
import com.retromod.shim.forge.Forge_26_2_to_26_3;
import com.retromod.shim.neoforge.NeoForge_26_2_to_26_3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.LegacyDestroyParticlesBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A block built before 26.3 that breaks with Lootr's pot and shulker effects: its calls, its override and its accessor's callers. */
class LegacyDestroyParticlesBridgeTest {

    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String POS = "net/minecraft/core/BlockPos";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void everyLoaderSendsTheOldCallToTheEntityForm() {
        for (VersionShim shim : List.of(new Forge_26_2_to_26_3(), new NeoForge_26_2_to_26_3(), new Fabric_26_2_to_26_3())) {
            RetromodTransformer transformer = target("26.3");
            shim.registerRedirects(transformer);
            assertEquals(List.of(BLOCK + "." + NEW_NAME + NEW_DESC), blockCalls(transformer), shim.getShimName());
        }
        RetromodTransformer older = target("26.2");
        LegacyDestroyParticlesBridge.register(older);
        assertEquals(List.of(BLOCK + "." + OLD_NAME + OLD_DESC), blockCalls(older), "26.2 still has the old method");
    }

    @Test
    void anOldOverrideStillRunsAndSeesThePlayer() throws Exception {
        byte[] bridged = LegacyDestroyParticlesBridge.apply(modBlock(), (child, ancestor) -> true, true);
        Map<String, byte[]> classes = new HashMap<>(Map.of("mod/PotBlock", bridged));
        for (String name : List.of(LEVEL, POS, STATE, ENTITY)) classes.put(name, empty(name, "java/lang/Object"));
        classes.put(PLAYER, empty(PLAYER, ENTITY));
        classes.put(BLOCK, hostBlock());
        ClassLoader loader = new DefiningLoader(classes);

        Class<?> block = loader.loadClass("mod.PotBlock");
        Object pot = block.getConstructor().newInstance();
        Object player = loader.loadClass(PLAYER.replace('/', '.')).getConstructor().newInstance();
        Object cat = loader.loadClass(ENTITY.replace('/', '.')).getConstructor().newInstance();
        var spawn = block.getMethod(NEW_NAME, loader.loadClass(LEVEL.replace('/', '.')), loader.loadClass(ENTITY.replace('/', '.')),
                loader.loadClass(POS.replace('/', '.')), loader.loadClass(STATE.replace('/', '.')));

        Class<?> host = loader.loadClass(BLOCK.replace('/', '.'));
        spawn.invoke(pot, null, player, null, null);
        assertSame(player, block.getField("seen").get(null), "the host's call reaches the old override with the player");
        assertEquals(0, host.getField("defaults").get(null));

        // An explosion passes no entity and a mob passes itself; the old override never ran for those.
        block.getField("seen").set(null, null);
        spawn.invoke(pot, null, cat, null, null);
        spawn.invoke(pot, null, null, null, null);
        assertNull(block.getField("seen").get(null), "the old override still runs only when a player breaks the block");
        assertEquals(2, host.getField("defaults").get(null), "the default effect runs instead");
    }

    @Test
    void callsThroughARetypedInvokerTakeTheEntityForm() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Breaker", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "smash",
                "(Lmod/AccessorMixinBlock;L" + LEVEL + ";L" + PLAYER + ";L" + POS + ";L" + STATE + ";)V", null, null);
        mv.visitCode();
        for (int slot = 0; slot < 5; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKEINTERFACE, "mod/AccessorMixinBlock", "lootr$spawnDestroyParticles", OLD_DESC, true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(LegacyDestroyParticlesBridge.apply(cw.toByteArray(), (child, ancestor) -> false, true)).accept(node, 0);
        MethodInsnNode call = (MethodInsnNode) java.util.Arrays.stream(node.methods.get(0).instructions.toArray())
                .filter(insn -> insn instanceof MethodInsnNode).findFirst().orElseThrow();
        assertEquals(NEW_DESC, call.desc, "the caller matches the invoker the mixin repair retyped");
    }

    private static RetromodTransformer target(String version) {
        RetromodVersion.TARGET_MC_VERSION = version;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        return transformer;
    }

    /** The calls on Block a mod's {@code block.spawnDestroyParticles(level, player, pos, state)} makes after the transform. */
    private static List<String> blockCalls(RetromodTransformer transformer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Breaks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "smash", "(L" + BLOCK + ";L" + LEVEL + ";L" + PLAYER + ";L" + POS
                + ";L" + STATE + ";)V", null, null);
        mv.visitCode();
        for (int slot = 0; slot < 5; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK, OLD_NAME, OLD_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/Breaks.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(BLOCK)) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** Lootr's pot: it overrides the old method and records the player it was given. */
    private static byte[] modBlock() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/PotBlock", null, BLOCK, null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "seen", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, BLOCK, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor override = cw.visitMethod(ACC_PROTECTED, OLD_NAME, OLD_DESC, null, null);
        override.visitCode();
        override.visitVarInsn(ALOAD, 2);
        override.visitFieldInsn(PUTSTATIC, "mod/PotBlock", "seen", "Ljava/lang/Object;");
        override.visitInsn(RETURN);
        override.visitMaxs(0, 0);
        override.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 26.3's Block, reduced to the method the host calls when a block breaks. */
    private static byte[] hostBlock() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, BLOCK, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "defaults", "I", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor spawn = cw.visitMethod(ACC_PUBLIC, NEW_NAME, NEW_DESC, null, null);
        spawn.visitCode();
        spawn.visitFieldInsn(GETSTATIC, BLOCK, "defaults", "I");
        spawn.visitInsn(ICONST_1);
        spawn.visitInsn(IADD);
        spawn.visitFieldInsn(PUTSTATIC, BLOCK, "defaults", "I");
        spawn.visitInsn(RETURN);
        spawn.visitMaxs(0, 0);
        spawn.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] empty(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class DefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        DefiningLoader(Map<String, byte[]> classes) {
            super(ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
