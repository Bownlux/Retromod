/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.common.LegacyEarlyItemStackBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old mod's item stacks go to the late-bound helper on 26.x hosts, and a 26.x mod's stay the host's own. */
class LegacyEarlyItemStackBridgeTest {

    private static final String MOD = "mod/EarlyStacks";
    private static final String OWN_ITEM = "mod/OwnItem";
    private static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String PATCH = "net/minecraft/core/component/DataComponentPatch";
    private static final String L_STACK = "L" + ITEM_STACK + ";";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private Field resolverField;
    private FuzzyMethodResolver savedResolver;

    @BeforeEach
    void isolate() throws Exception {
        transformer.clearRedirectsForTesting();
        // No indexed jar, so the host gate answers from the target version each test sets.
        resolverField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        resolverField.setAccessible(true);
        savedResolver = (FuzzyMethodResolver) resolverField.get(transformer);
        resolverField.set(transformer, null);
    }

    @AfterEach
    void restore() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
        resolverField.set(transformer, savedResolver);
    }

    @ParameterizedTest(name = "a mod built for {0}")
    @ValueSource(strings = {"1.20.1", "1.21.1", "1.21.11"})
    @DisplayName("every old constructor, constructor reference and default instance goes to the helper")
    void oldModsReachTheHelper(String source) {
        registerOn("26.3");
        transformer.setModSourceVersion(source);

        ClassNode mod = transform(modClass(), MOD);

        assertEquals(List.of(HELPER + ".of(L" + ITEM_LIKE + ";)" + L_STACK,
                HELPER + ".of(L" + ITEM_LIKE + ";I)" + L_STACK,
                HELPER + ".of(L" + HOLDER + ";)" + L_STACK,
                HELPER + ".of(L" + HOLDER + ";I)" + L_STACK,
                HELPER + ".of(L" + HOLDER + ";IL" + PATCH + ";)" + L_STACK,
                HELPER + ".defaultInstance(L" + ITEM + ";)" + L_STACK), calls(mod, "build"));
        assertFalse(allocatesStack(mod), "no NEW ItemStack is left behind");
        assertEquals(HELPER + ".of(L" + ITEM_LIKE + ";)" + L_STACK, constructorReference(mod));
    }

    @Test
    @DisplayName("a mod whose version is unknown keeps the redirect, as it keeps every shim for its host")
    void unknownVersionReachesTheHelper() {
        registerOn("26.3");

        assertEquals(HELPER + ".of(L" + ITEM_LIKE + ";I)" + L_STACK, calls(transform(modClass(), MOD), "build").get(1));
    }

    @ParameterizedTest(name = "a mod built for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("a mod built for late binding keeps the host's constructors and default instance")
    void newerModsKeepTheHostsOwn(String source) {
        registerOn("26.3");
        transformer.setModSourceVersion(source);

        ClassNode mod = transform(modClass(), MOD);

        assertEquals(List.of(ITEM_STACK + ".<init>(L" + ITEM_LIKE + ";)V", ITEM_STACK + ".<init>(L" + ITEM_LIKE + ";I)V",
                ITEM_STACK + ".<init>(L" + HOLDER + ";)V", ITEM_STACK + ".<init>(L" + HOLDER + ";I)V",
                ITEM_STACK + ".<init>(L" + HOLDER + ";IL" + PATCH + ";)V",
                ITEM + ".getDefaultInstance()" + L_STACK), calls(mod, "build"));
        assertTrue(allocatesStack(mod));
        assertEquals(ITEM_STACK + ".<init>(L" + ITEM_LIKE + ";)V", constructorReference(mod));
    }

    @Test
    @DisplayName("an item's own default instance still calls Minecraft's through super, so the helper never calls itself")
    void superCallStaysOnTheHost() {
        registerOn("26.3");
        transformer.setModSourceVersion("1.20.1");

        ClassNode own = transform(ownItemClass(), OWN_ITEM);

        MethodInsnNode call = onlyCall(own, "getDefaultInstance");
        assertEquals(INVOKESPECIAL, call.getOpcode());
        assertEquals(ITEM + ".getDefaultInstance()" + L_STACK, call.owner + "." + call.name + call.desc);
    }

    @Test
    @DisplayName("a host that binds components with its items registers nothing")
    void earlyBindingHostIsLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyEarlyItemStackBridge.register26x(transformer);
        transformer.setModSourceVersion("1.20.1");

        assertTrue(allocatesStack(transform(modClass(), MOD)));
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fabric", "forge", "neoforge"})
    @DisplayName("each loader's 26.1 shim sends the stacks and the default instance to the helper")
    void eachLoadersShimRegistersTheBridge(String loader) {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        VersionShim shim = switch (loader) {
            case "fabric" -> new Fabric_1_21_11_to_26_1();
            case "forge" -> new Forge_1_21_11_to_26_1();
            default -> new NeoForge_1_21_11_to_26_1();
        };
        shim.registerRedirects(transformer);
        transformer.setModSourceVersion("1.20.1");

        List<String> calls = calls(transform(modClass(), MOD), "build");

        assertEquals(HELPER + ".of(L" + ITEM_LIKE + ";)" + L_STACK, calls.get(0));
        assertEquals(HELPER + ".defaultInstance(L" + ITEM + ";)" + L_STACK, calls.get(5),
                "the loader's own registration must not take the default instance back");
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER));
    }

    private void registerOn(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        LegacyEarlyItemStackBridge.register26x(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the helper is registered on " + host);
    }

    private ClassNode transform(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, name + ".class")).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node, String method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, method).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static MethodInsnNode onlyCall(ClassNode node, String method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, method).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(1, calls.size());
        return calls.get(0);
    }

    private static boolean allocatesStack(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof TypeInsnNode type && insn.getOpcode() == NEW && type.desc.equals(ITEM_STACK)) return true;
            }
        }
        return false;
    }

    /** What {@code ItemStack::new} in {@code reference()} points at. */
    private static String constructorReference(ClassNode node) {
        for (AbstractInsnNode insn : method(node, "reference").instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                Handle target = (Handle) indy.bsmArgs[1];
                return target.getOwner() + "." + target.getName() + target.getDesc();
            }
        }
        throw new AssertionError("no constructor reference");
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    /** {@code build} makes a stack each way an old mod does, and {@code reference} hands out {@code ItemStack::new}. */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "build",
                "(L" + ITEM_LIKE + ";L" + HOLDER + ";L" + PATCH + ";L" + ITEM + ";)V", null, null);
        mv.visitCode();
        newStack(mv, "(L" + ITEM_LIKE + ";)V", () -> mv.visitVarInsn(ALOAD, 0));
        newStack(mv, "(L" + ITEM_LIKE + ";I)V", () -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_4);
        });
        newStack(mv, "(L" + HOLDER + ";)V", () -> mv.visitVarInsn(ALOAD, 1));
        newStack(mv, "(L" + HOLDER + ";I)V", () -> {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_2);
        });
        newStack(mv, "(L" + HOLDER + ";IL" + PATCH + ";)V", () -> {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_3);
            mv.visitVarInsn(ALOAD, 2);
        });
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM, "getDefaultInstance", DEFAULT_INSTANCE, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor ref = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "reference", "()Ljava/util/function/Function;", null, null);
        ref.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        ref.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(H_NEWINVOKESPECIAL, ITEM_STACK, "<init>", "(L" + ITEM_LIKE + ";)V", false),
                Type.getType("(L" + ITEM_LIKE + ";)" + L_STACK));
        ref.visitInsn(ARETURN);
        ref.visitMaxs(0, 0);
        ref.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void newStack(MethodVisitor mv, String constructor, Runnable arguments) {
        mv.visitTypeInsn(NEW, ITEM_STACK);
        mv.visitInsn(DUP);
        arguments.run();
        mv.visitMethodInsn(INVOKESPECIAL, ITEM_STACK, "<init>", constructor, false);
        mv.visitInsn(POP);
    }

    /** An item of the mod's own whose default instance calls Minecraft's through super. */
    private static byte[] ownItemClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, OWN_ITEM, null, ITEM, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getDefaultInstance", DEFAULT_INSTANCE, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, ITEM, "getDefaultInstance", DEFAULT_INSTANCE, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
