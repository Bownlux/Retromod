/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.api.forge.ForgeRegistryApiShim;
import com.retromod.util.McReflect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.HashSet;
import java.util.Set;

/**
 * #87 registry-id bridge: MC 26.x requires the registry id on Block/Item {@code Properties} before the
 * object is built, but Forge's {@code DeferredRegister.register(String, Supplier)} builds it inside the
 * supplier with no id. These tests prove the transform routes registration + Properties creation through
 * the {@link RegistryIdBridgeSynthetic} so a thread-local can stamp the id.
 */
class RegistryIdBridgeTest {

    @AfterEach
    void reset() {
        McReflect.setForceNeoForge(false);
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void generatedBridgeHasTheExpectedShape() {
        byte[] bytes = RegistryIdBridgeSynthetic.generate();
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);

        assertTrue(cn.interfaces.contains("java/util/function/Function"),
                "the bridge must implement Function so it can wrap the registration supplier");
        Set<String> methods = new HashSet<>();
        cn.methods.forEach(m -> methods.add(m.name));
        for (String required : new String[]{"register", "apply", "applyBlock", "applyItem",
                "blockOf", "blockOfFullCopy", "blockOfLegacyCopy", "itemProps"}) {
            assertTrue(methods.contains(required), "bridge missing method: " + required);
        }
    }

    /** A class whose {@code <clinit>} does {@code BLOCKS.register("x", () -> null)} and calls Properties.of(). */
    private static byte[] registrarFixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Reg", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                "(Lnet/neoforged/neoforge/registries/DeferredRegister;Ljava/util/function/Supplier;)V", null, null);
        mv.visitCode();
        // dr.register("thing", supplier)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("thing");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/neoforged/neoforge/registries/DeferredRegister",
                "register", "(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/neoforged/neoforge/registries/DeferredHolder;", false);
        mv.visitInsn(Opcodes.POP);
        // BlockBehaviour.Properties.of()
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/world/level/block/state/BlockBehaviour$Properties",
                "of", "()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;", false);
        mv.visitInsn(Opcodes.POP);
        // new net/minecraft/world/item/Item$Properties()
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/world/item/Item$Properties");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/world/item/Item$Properties", "<init>", "()V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void registrationAndPropsAreRoutedThroughTheBridgeOn26x() {
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new ForgeRegistryApiShim().registerRedirects(t);

        byte[] out = t.transformClass(registrarFixture(), "com/example/Reg.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(text.contains(RegistryIdBridgeSynthetic.INTERNAL),
                "registration/Properties calls must be routed through the RegistryIdBridge");
        assertFalse(text.contains("net/neoforged/neoforge/registries/DeferredRegisterregister")
                        || containsCall(out, "net/neoforged/neoforge/registries/DeferredRegister", "register",
                                "(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/neoforged/neoforge/registries/DeferredHolder;"),
                "no raw DeferredRegister.register(String,Supplier) call may survive");
    }

    /** {@code DeferredRegister.createBlocks(...)} returns the Blocks subclass, whose register is typed. */
    private static byte[] typedRegistrarFixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/TypedReg", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                "(Lnet/neoforged/neoforge/registries/DeferredRegister$Blocks;"
                        + "Lnet/neoforged/neoforge/registries/DeferredRegister$Items;"
                        + "Lnet/neoforged/bus/api/IEventBus;Ljava/util/function/Supplier;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("thing");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLOCKS_REGISTER_OWNER, "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)"
                        + "Lnet/neoforged/neoforge/registries/DeferredBlock;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("thing");
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEMS_REGISTER_OWNER, "register",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)"
                        + "Lnet/neoforged/neoforge/registries/DeferredItem;", false);
        mv.visitInsn(Opcodes.POP);
        // The event-bus overload shares the name and must be left alone.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLOCKS_REGISTER_OWNER, "register",
                "(Lnet/neoforged/bus/api/IEventBus;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String BLOCKS_REGISTER_OWNER =
            "net/neoforged/neoforge/registries/DeferredRegister$Blocks";
    private static final String ITEMS_REGISTER_OWNER =
            "net/neoforged/neoforge/registries/DeferredRegister$Items";

    @Test
    void typedDeferredRegisterSubclassesAreBridged() {
        // #285: DeferredRegister.createBlocks(...) hands back the Blocks subclass, so the call site
        // names DeferredRegister$Blocks.register and returns DeferredBlock. Keyed only on the base
        // class, the id bridge never matched and the block was built with no id.
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new ForgeRegistryApiShim().registerRedirects(t);

        byte[] out = t.transformClass(typedRegistrarFixture(), "com/example/TypedReg.class");

        assertTrue(containsCall(out, RegistryIdBridgeSynthetic.INTERNAL, "register",
                        RegistryIdBridgeSynthetic.REGISTER_DESC),
                "the typed register must route through the id bridge");
        for (String owner : new String[]{BLOCKS_REGISTER_OWNER, ITEMS_REGISTER_OWNER}) {
            String returned = owner.endsWith("Blocks")
                    ? "Lnet/neoforged/neoforge/registries/DeferredBlock;"
                    : "Lnet/neoforged/neoforge/registries/DeferredItem;";
            assertFalse(containsCall(out, owner, "register",
                            "(Ljava/lang/String;Ljava/util/function/Supplier;)" + returned),
                    "no raw typed register(String,Supplier) may survive on " + owner);
        }
        for (String cast : new String[]{"net/neoforged/neoforge/registries/DeferredBlock",
                                        "net/neoforged/neoforge/registries/DeferredItem"}) {
            assertTrue(containsCheckCast(out, cast),
                    "the widened DeferredHolder return must be cast back to " + cast);
        }
        assertTrue(containsCall(out, BLOCKS_REGISTER_OWNER, "register",
                        "(Lnet/neoforged/bus/api/IEventBus;)V"),
                "the event-bus overload shares the name and must be left alone");
    }

    /** True if the class bytes contain a CHECKCAST to the given type. */
    private static boolean containsCheckCast(byte[] classBytes, String type) {
        boolean[] found = {false};
        new ClassReader(classBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int op, String t) {
                        if (op == Opcodes.CHECKCAST && t.equals(type)) found[0] = true;
                    }
                };
            }
        }, 0);
        return found[0];
    }

    @Test
    void bridgeIsNotAppliedOnPre26Hosts() {
        // Pre-1.21.3 has no Properties.setId; the id bridge must not be wired there.
        McReflect.setForceNeoForge(true);
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new ForgeRegistryApiShim().registerRedirects(t);

        byte[] out = t.transformClass(registrarFixture(), "com/example/Reg.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(RegistryIdBridgeSynthetic.INTERNAL),
                "on a pre-26 host the registry-id bridge must be gated off");
    }

    /** True if the class bytes contain an INVOKEVIRTUAL/STATIC to owner.name with the given descriptor. */
    private static boolean containsCall(byte[] classBytes, String owner, String name, String desc) {
        boolean[] found = {false};
        new ClassReader(classBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String o, String mn, String md, boolean itf) {
                        if (o.equals(owner) && mn.equals(name) && md.equals(desc)) found[0] = true;
                    }
                };
            }
        }, 0);
        return found[0];
    }
}
