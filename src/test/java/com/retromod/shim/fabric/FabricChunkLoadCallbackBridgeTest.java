/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.embedded.LegacyChunkEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Lootr's chunk load listener, written for 1.21.1's two-argument callback, on 26.x Fabric API's three-argument one. */
class FabricChunkLoadCallbackBridgeTest {

    private static final String LOAD = "net/fabricmc/fabric/api/event/lifecycle/v1/ServerChunkEvents$Load";
    private static final String LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    private static final String OLD_DESC = "(L" + LEVEL + ";L" + CHUNK + ";)V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    void theHostsCallReachesTheOldListener() throws Exception {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        // A plain bridge, so it works with the loader API runtime off, as in every release build.
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
        Map<String, byte[]> classes = new HashMap<>(transformer.getSyntheticClasses());
        classes.put("mod/Events", transformer.transformClass(oldListener(), "mod/Events.class"));
        classes.put(LEVEL, empty(LEVEL));
        classes.put(CHUNK, empty(CHUNK));
        classes.put(LOAD, hostCallback());
        ClassLoader loader = new DefiningLoader(classes);

        Class<?> mod = loader.loadClass("mod.Events");
        Object listener = mod.getMethod("listener").invoke(null);
        Class<?> load = loader.loadClass(LOAD.replace('/', '.'));
        assertTrue(load.isInstance(listener), "the old lambda is handed to the host as its own callback");
        Object level = loader.loadClass(LEVEL.replace('/', '.')).getConstructor().newInstance();
        Object chunk = loader.loadClass(CHUNK.replace('/', '.')).getConstructor().newInstance();
        load.getMethod("onChunkLoad", level.getClass(), chunk.getClass(), boolean.class).invoke(listener, level, chunk, true);
        assertSame(level, mod.getField("level").get(null), "the listener runs with the host's level");
        assertSame(chunk, mod.getField("chunk").get(null), "and its chunk");

        // An old direct call, such as on Fabric API's own invoker, which is a lambda too.
        mod.getField("level").set(null, null);
        loader.loadClass(LegacyChunkEvents.class.getName()).getMethod("onChunkLoad", Object.class, Object.class, Object.class)
                .invoke(null, listener, level, chunk);
        assertSame(level, mod.getField("level").get(null), "a two-argument call reaches a lambda listener");
    }

    /** {@code ServerChunkEvents.CHUNK_LOAD.register(Events::loaded)} as compiled for 1.21.1, reduced to building the lambda. */
    private static byte[] oldListener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/Events", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "level", "Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "chunk", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor listener = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "listener", "()Ljava/lang/Object;", null, null);
        listener.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        listener.visitInvokeDynamicInsn("onChunkLoad", "()L" + LOAD + ";", metafactory, Type.getType(OLD_DESC),
                new Handle(H_INVOKESTATIC, "mod/Events", "loaded", OLD_DESC, false), Type.getType(OLD_DESC));
        listener.visitInsn(ARETURN);
        listener.visitMaxs(0, 0);
        listener.visitEnd();
        MethodVisitor loaded = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "loaded", OLD_DESC, null, null);
        loaded.visitCode();
        loaded.visitVarInsn(ALOAD, 0);
        loaded.visitFieldInsn(PUTSTATIC, "mod/Events", "level", "Ljava/lang/Object;");
        loaded.visitVarInsn(ALOAD, 1);
        loaded.visitFieldInsn(PUTSTATIC, "mod/Events", "chunk", "Ljava/lang/Object;");
        loaded.visitInsn(RETURN);
        loaded.visitMaxs(0, 0);
        loaded.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 26.x Fabric API's callback, which also says whether the chunk was just generated. */
    private static byte[] hostCallback() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, LOAD, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "onChunkLoad", "(L" + LEVEL + ";L" + CHUNK + ";Z)V", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] empty(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class DefiningLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        DefiningLoader(Map<String, byte[]> classes) {
            super(FabricChunkLoadCallbackBridgeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // Minecraft and Fabric API stand-ins, the mod and its bridges come from here.
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }
    }
}
