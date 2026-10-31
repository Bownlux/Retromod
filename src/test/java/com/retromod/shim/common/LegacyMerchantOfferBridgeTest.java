/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A 1.20.1 mod's trade factory built {@code new TradeOffer(price, result, maxUses, xp, multiplier)},
 * which 1.20.5 replaced with item costs. On 26.2 and 26.3 the test mod's trade then failed with
 * {@code NoSuchMethodError: MerchantOffer.<init>(ItemStack, ItemStack, int, int, float)}.
 */
class LegacyMerchantOfferBridgeTest {

    private static final String OFFER = "net/minecraft/world/item/trading/MerchantOffer";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Each old item stack trade constructor becomes the helper's factory")
    void rewritesEveryOldConstructor() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyMerchantOfferBridge.register(transformer);

        for (LegacyMerchantOfferBridge.Shape shape : LegacyMerchantOfferBridge.SHAPES) {
            List<String> calls = calls(transformer.transformClass(trade(shape.oldDesc()), "mod/Trade"));
            assertEquals(List.of(LegacyMerchantOfferBridge.HELPER + ".create"), calls, shape.oldDesc());
        }
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyMerchantOfferBridge.HELPER));
    }

    @Test
    @DisplayName("A host before 1.20.5 keeps its own constructors")
    void olderHostsAreLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyMerchantOfferBridge.register(transformer);

        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyMerchantOfferBridge.HELPER));
        String first = LegacyMerchantOfferBridge.SHAPES.get(0).oldDesc();
        assertEquals(List.of(OFFER + ".<init>"), calls(transformer.transformClass(trade(first), "mod/Trade")));
    }

    @Test
    @DisplayName("The generated helper verifies")
    void helperVerifies() {
        new ClassReader(LegacyMerchantOfferBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    @ParameterizedTest(name = "in {0}")
    @ValueSource(strings = {"26.1.2", "26.3"})
    @DisplayName("Every constructor and member the helper calls is in the released client jar")
    void helperCallsExistOnTheHost(String version) throws Exception {
        Path jar = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries",
                "com/mojang/minecraft", version, "minecraft-" + version + "-client.jar");
        assumeTrue(Files.exists(jar), "no local " + version + " client jar; it is local test material");
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyMerchantOfferBridge.generate()).accept(helper, 0);
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (MethodNode method : helper.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/minecraft/")
                            && !declares(zip, call.owner, call.name, call.desc)) {
                        missing.add(call.owner + "." + call.name + call.desc);
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    /** {@code static Object make(...) { return new MerchantOffer(...); }} with the old constructor's arguments. */
    private static byte[] trade(String constructorDesc) {
        Type[] args = Type.getArgumentTypes(constructorDesc);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Trade", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make",
                Type.getMethodDescriptor(Type.getObjectType(OFFER), args), null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, OFFER);
        mv.visitInsn(Opcodes.DUP);
        int slot = 0;
        for (Type arg : args) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OFFER, "<init>", constructorDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static boolean declares(ZipFile zip, String owner, String name, String desc) throws Exception {
        var entry = zip.getEntry(owner + ".class");
        if (entry == null) return false;
        ClassNode node = new ClassNode();
        new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
    }
}
