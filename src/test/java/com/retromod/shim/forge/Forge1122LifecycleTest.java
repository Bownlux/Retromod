/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The pre-1.13 FML lifecycle (#103/#108/#117): a 1.12.2 mod's {@code @Mod(modid=...)} must
 * become the modern value shape (or FML never registers it), and its {@code @Mod.EventHandler}
 * setup methods must actually run (nothing on a modern loader ever calls them). Shapes are
 * verified against the real forge-1.12.2-14.23.5.2859-universal jar.
 */
class Forge1122LifecycleTest {

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
    }

    /** A 1.12.2 mod main class: old @Mod attributes + a preInit @Mod.EventHandler. */
    private static byte[] legacyModClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/OldMod", null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        av.visit("modid", "oldmod");
        av.visit("name", "Old Mod");
        av.visit("version", "1.0");
        av.visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor pre = cw.visitMethod(ACC_PUBLIC, "preInit",
                "(Lnet/minecraftforge/fml/common/event/FMLPreInitializationEvent;)V", null, null);
        pre.visitAnnotation("Lnet/minecraftforge/fml/common/Mod$EventHandler;", true).visitEnd();
        pre.visitCode();
        pre.visitInsn(RETURN);
        pre.visitMaxs(0, 0);
        pre.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * #120: a real 1.12.2 @Mod carries modid + name + version + dependencies (e.g. Scape and
     * Run Parasites' SRPMain). Modern Forge's @Mod has ONLY value(); it reads value() = null
     * from the old shape and dies "mods.toml missing metadata for modid null". The upgrade must
     * collapse the whole annotation to @Mod(value=modid), no matter how many old elements it had.
     */
    @Test
    @DisplayName("#120: @Mod(modid,name,version,dependencies) collapses to @Mod(value=modid)")
    void fullLegacyModAnnotationCollapsesToValue() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/SRPMain", null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        av.visit("modid", "srparasites");
        av.visit("name", "Scape and Run Parasites");
        av.visit("version", "1.10.7");
        av.visit("dependencies", "before:jeid@[2.2.5,)");
        av.visitEnd();
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode(); c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN); c.visitMaxs(0, 0); c.visitEnd();
        cw.visitEnd();

        byte[] out = Forge1122LifecycleSynthetics.upgradeLegacyModClass(cw.toByteArray());
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        AnnotationNode mod = cn.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lnet/minecraftforge/fml/common/Mod;"))
                .findFirst().orElseThrow();
        assertEquals(2, mod.values.size(),
                "every old element must be dropped, leaving only value: " + mod.values);
        assertEquals("value", mod.values.get(0));
        assertEquals("srparasites", mod.values.get(1),
                "value must be the modid so modern Forge reads a real id, not null");
    }

    @Test
    @DisplayName("@Mod(modid=...) becomes @Mod(value) and the ctor gains the lifecycle call")
    void modClassIsUpgraded() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);

        // the normal pipeline: transform (event param types redirect to the stand-ins),
        // then the upgrade pass (annotation + ctor wiring)
        byte[] transformed = t.transformClass(legacyModClass(), "test/OldMod.class");
        byte[] out = Forge1122LifecycleSynthetics.upgradeLegacyModClass(transformed);

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);

        AnnotationNode mod = cn.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lnet/minecraftforge/fml/common/Mod;"))
                .findFirst().orElseThrow();
        assertEquals(2, mod.values.size(), "old attributes must be gone: " + mod.values);
        assertEquals("value", mod.values.get(0));
        assertEquals("oldmod", mod.values.get(1));

        MethodNode init = cn.methods.stream().filter(m -> m.name.equals("<init>"))
                .findFirst().orElseThrow();
        boolean fires = false;
        for (AbstractInsnNode in : init.instructions.toArray()) {
            if (in instanceof MethodInsnNode mi
                    && mi.owner.equals(Forge1122LifecycleSynthetics.BRIDGE)
                    && mi.name.equals("fire")) {
                fires = true;
            }
        }
        assertTrue(fires, "ctor must call the lifecycle bridge");

        // the handler's parameter type must now be the embedded stand-in
        MethodNode pre = cn.methods.stream().filter(m -> m.name.equals("preInit"))
                .findFirst().orElseThrow();
        assertTrue(pre.desc.contains(Forge1122LifecycleSynthetics.PRE_EVENT),
                "handler param must be redirected to the stand-in event: " + pre.desc);
    }

    @Test
    @DisplayName("a MODERN @Mod(value) class passes through untouched")
    void modernModUntouched() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.register(t);

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/ModernMod", null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        av.visit("value", "modernmod");
        av.visitEnd();
        cw.visitEnd();
        byte[] in = cw.toByteArray();
        assertSame(in, Forge1122LifecycleSynthetics.upgradeLegacyModClass(in),
                "a value-shaped @Mod has no modid attribute and must be untouched");
    }

    @Test
    @DisplayName("inactive gate: without the 1.12.2 shim the pass is a no-op")
    void inactiveGate() {
        byte[] in = legacyModClass();
        assertSame(in, Forge1122LifecycleSynthetics.upgradeLegacyModClass(in),
                "the pass must not run unless the 1.12.2 chain registered");
    }

    /** Loads generated classes so the reflective bridge can actually run. */
    private static final class MapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        MapClassLoader(Map<String, byte[]> classes) {
            super(Forge1122LifecycleTest.class.getClassLoader());
            this.classes = classes;
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = classes.get(name);
            if (b == null) throw new ClassNotFoundException(name);
            return defineClass(name, b, 0, b.length);
        }
    }

    @Test
    @DisplayName("functional: fire() constructs the events and invokes the handlers in order")
    void bridgeActuallyFires() throws Exception {
        // a tiny mod class recording which handlers ran (compiled by hand against the stand-ins)
        String pre = Forge1122LifecycleSynthetics.PRE_EVENT;
        String init = Forge1122LifecycleSynthetics.INIT_EVENT;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/FireMod", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "log", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
        for (String[] h : new String[][]{{"onPre", pre, "P"}, {"onInit", init, "I"}}) {
            MethodVisitor m = cw.visitMethod(ACC_PUBLIC, h[0], "(L" + h[1] + ";)V", null, null);
            m.visitCode();
            m.visitFieldInsn(GETSTATIC, "test/FireMod", "log", "Ljava/lang/String;");
            m.visitLdcInsn(h[2]);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false);
            m.visitFieldInsn(PUTSTATIC, "test/FireMod", "log", "Ljava/lang/String;");
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        cw.visitEnd();

        Map<String, byte[]> defs = new HashMap<>();
        defs.put("test.FireMod", cw.toByteArray());
        defs.put(Forge1122LifecycleSynthetics.BRIDGE.replace('/', '.'),
                Forge1122LifecycleSynthetics.generateBridge());
        defs.put(pre.replace('/', '.'), Forge1122LifecycleSynthetics.generateEvent(pre, true));
        defs.put(init.replace('/', '.'), Forge1122LifecycleSynthetics.generateEvent(init, false));
        defs.put(Forge1122LifecycleSynthetics.POST_EVENT.replace('/', '.'),
                Forge1122LifecycleSynthetics.generateEvent(
                        Forge1122LifecycleSynthetics.POST_EVENT, false));

        MapClassLoader cl = new MapClassLoader(defs);
        Class<?> modClass = Class.forName("test.FireMod", true, cl);
        modClass.getField("log").set(null, "");
        Object mod = modClass.getDeclaredConstructor().newInstance();
        Class<?> bridge = Class.forName(
                Forge1122LifecycleSynthetics.BRIDGE.replace('/', '.'), true, cl);
        Method fire = bridge.getMethod("fire", Object.class, String.class);
        fire.invoke(null, mod, "firemod");
        assertEquals("PI", modClass.getField("log").get(null),
                "handlers must run in pre -> init order with constructed events");

        // and the pre-init event surface works (the config-file name carries the modid)
        Class<?> preEv = Class.forName(pre.replace('/', '.'), true, cl);
        Object ev = preEv.getDeclaredConstructor(String.class).newInstance("firemod");
        Object file = preEv.getMethod("getSuggestedConfigurationFile").invoke(ev);
        assertTrue(file.toString().endsWith("firemod.cfg"),
                "suggested config file must be config/<modid>.cfg, got " + file);
    }
}
