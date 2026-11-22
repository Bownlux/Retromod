/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.embedded.LegacyBuiltinPacks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Fabric mod that registers a built-in data pack through Fabric API's old helper implementation, as Lootr does. */
class LegacyBuiltinPacksTest {

    private static final String OLD_OWNER = "net/fabricmc/fabric/impl/resource/loader/ResourceManagerHelperImpl";
    private static final String OLD_DESC = "(Lnet/minecraft/resources/Identifier;Ljava/lang/String;Lnet/fabricmc/loader/api/ModContainer;"
            + "Lnet/minecraft/network/chat/Component;Lnet/fabricmc/fabric/api/resource/ResourcePackActivationType;)Z";
    private static final String LOADER = "net/fabricmc/fabric/impl/resource/ResourceLoaderImpl";
    private static final String ACTIVATION = "net/fabricmc/fabric/api/resource/v1/pack/PackActivationType";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void theOldCallReachesTheBridge() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(caller(), "mod/Packs.class")).accept(node, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode method && method.name.equals("registerBuiltinResourcePack")) call = method;
        }
        assertEquals(LegacyBuiltinPacks.INTERNAL_NAME, call.owner);
    }

    @Test
    void thePackKeepsItsSubPathNameAndActivation() throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(LegacyBuiltinPacks.INTERNAL_NAME, bytes(LegacyBuiltinPacks.class));
        classes.putAll(renamed(Map.of(FakeLoader.class, LOADER, NewActivation.class, ACTIVATION)));
        ClassLoader loader = new DefiningLoader(classes);
        Class<?> bridge = loader.loadClass(LegacyBuiltinPacks.class.getName());

        Object registered = bridge.getMethod("registerBuiltinResourcePack", Object.class, String.class, Object.class, Object.class,
                Object.class).invoke(null, "lootr:default_disable", "datapacks/default_disable", "lootr", "Lootr Defaults",
                OldActivation.NORMAL);
        assertEquals(true, registered);
        Object[] seen = (Object[]) loader.loadClass(LOADER.replace('/', '.')).getField("last").get(null);
        assertEquals("datapacks/default_disable", seen[1], "the data pack keeps its sub path");
        assertEquals("Lootr Defaults", seen[3], "and its display name");
        assertEquals("NORMAL", ((Enum<?>) seen[4]).name());
        assertEquals(ACTIVATION.replace('/', '.'), seen[4].getClass().getName(), "as the new loader's activation type");
    }

    /** {@code ResourceManagerHelperImpl.registerBuiltinResourcePack(id, subPath, container, name, NORMAL)}, as Lootr compiled it. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Packs", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(Lnet/minecraft/resources/Identifier;"
                + "Lnet/fabricmc/loader/api/ModContainer;Lnet/minecraft/network/chat/Component;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("datapacks/default_disable");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(GETSTATIC, "net/fabricmc/fabric/api/resource/ResourcePackActivationType", "NORMAL",
                "Lnet/fabricmc/fabric/api/resource/ResourcePackActivationType;");
        mv.visitMethodInsn(INVOKESTATIC, OLD_OWNER, "registerBuiltinResourcePack", OLD_DESC, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Fabric API 26.x's resource loader, keeping the last registration. */
    public static final class FakeLoader {
        public static Object[] last;

        public static boolean registerBuiltinPack(Object id, String subPath, Object container, Object name, NewActivation type) {
            last = new Object[]{id, subPath, container, name, type};
            return true;
        }
    }

    /** The v1 activation type. */
    public enum NewActivation { NORMAL, DEFAULT_ENABLED, ALWAYS_ENABLED }

    /** The old activation type a mod still passes. */
    enum OldActivation { NORMAL }

    private static byte[] bytes(Class<?> type) throws Exception {
        try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    /** Test classes renamed onto host names, without the nesting attributes that tie them to this class. */
    private static Map<String, byte[]> renamed(Map<Class<?>, String> classes) throws Exception {
        Map<String, String> names = new HashMap<>();
        classes.forEach((type, name) -> names.put(type.getName().replace('.', '/'), name));
        Map<String, byte[]> out = new HashMap<>();
        for (Map.Entry<Class<?>, String> entry : classes.entrySet()) {
            ClassWriter writer = new ClassWriter(0);
            ClassVisitor flat = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public void visitInnerClass(String name, String outerName, String innerName, int access) {}
                @Override public void visitNestHost(String nestHost) {}
                @Override public void visitOuterClass(String owner, String name, String descriptor) {}
            };
            new ClassReader(bytes(entry.getKey())).accept(new ClassRemapper(flat, new SimpleRemapper(names)), 0);
            out.put(entry.getValue(), writer.toByteArray());
        }
        return out;
    }

    private static final class DefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        DefiningLoader(Map<String, byte[]> classes) {
            super(LegacyBuiltinPacksTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // The bridge and the stand-ins come from here, so the bridge finds the stand-in loader.
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }
    }
}
