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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.common.LegacyItemStackCodecsBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old recipe serializer's item stack codecs go to the rebuilt ones on 26.x hosts, and a 26.x mod keeps the host's. */
class LegacyItemStackCodecsBridgeTest {

    private static final String MOD = "mod/CookingRecipeSerializer";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    private static final List<String> OLD_FIELDS = List.of("CODEC", "STRICT_CODEC", "OPTIONAL_CODEC",
            "SINGLE_ITEM_CODEC", "STRICT_SINGLE_ITEM_CODEC", "SIMPLE_ITEM_CODEC");

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
    @ValueSource(strings = {"1.20.6", "1.21.1", "1.21.11"})
    @DisplayName("each old stack codec read becomes a call to the rebuilt codec")
    void oldModsReadTheRebuiltCodecs(String source) {
        registerOn("26.3");
        transformer.setModSourceVersion(source);

        ClassNode mod = transform(serializerClass());

        assertEquals(List.of(HELPER + ".codec()" + CODEC, HELPER + ".strict()" + CODEC, HELPER + ".optional()" + CODEC,
                HELPER + ".codec()" + CODEC, HELPER + ".strict()" + CODEC, HELPER + ".simple()" + CODEC), calls(mod, "codecs"));
        assertTrue(stackFieldReads(mod).isEmpty(), "no ItemStack codec field read is left: " + stackFieldReads(mod));
    }

    @ParameterizedTest(name = "a mod built for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("a mod built for late binding keeps the host's codecs")
    void newerModsKeepTheHostsCodecs(String source) {
        registerOn("26.3");
        transformer.setModSourceVersion(source);

        ClassNode mod = transform(serializerClass());

        assertTrue(calls(mod, "codecs").isEmpty());
        assertEquals(OLD_FIELDS, stackFieldReads(mod));
    }

    @ParameterizedTest(name = "a mod built for {0}")
    @ValueSource(strings = {"1.21.1", "26.1"})
    @DisplayName("an old mod's ItemCost.CODEC read goes to the rebuilt codec, and a 26.1 mod keeps the host's")
    void tradeCostCodec(String source) {
        registerOn("26.3");
        transformer.setModSourceVersion(source);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "codecs", "()V", null, null);
        m.visitCode();
        m.visitFieldInsn(GETSTATIC, LegacyItemStackCodecsBridge.ITEM_COST, "CODEC", CODEC);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        ClassNode mod = transform(cw.toByteArray());

        boolean old = source.equals("1.21.1");
        assertEquals(old ? List.of(LegacyItemStackCodecsBridge.COSTS + ".codec()" + CODEC) : List.of(), calls(mod, "codecs"));
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyItemStackCodecsBridge.COSTS));
    }

    @Test
    @DisplayName("a host that binds components with its items registers nothing")
    void earlyBindingHostIsLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyItemStackCodecsBridge.register26x(transformer);
        transformer.setModSourceVersion("1.21.1");

        assertEquals(OLD_FIELDS, stackFieldReads(transform(serializerClass())));
        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fabric", "forge", "neoforge"})
    @DisplayName("each loader's 26.1 shim registers the rebuilt codecs")
    void eachLoadersShimRegistersTheBridge(String loader) {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        VersionShim shim = switch (loader) {
            case "fabric" -> new Fabric_1_21_11_to_26_1();
            case "forge" -> new Forge_1_21_11_to_26_1();
            default -> new NeoForge_1_21_11_to_26_1();
        };
        shim.registerRedirects(transformer);
        transformer.setModSourceVersion("1.21.1");

        assertEquals(HELPER + ".codec()" + CODEC, calls(transform(serializerClass()), "codecs").get(0));
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER));
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEarlyItemStackBridge.HELPER),
                "the waiting stacks the codec builds come with it");
    }

    private void registerOn(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        LegacyItemStackCodecsBridge.register26x(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the rebuilt codecs are registered on " + host);
    }

    private ClassNode transform(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD + ".class")).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node, String method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, method).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static List<String> stackFieldReads(ClassNode node) {
        List<String> reads = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, "codecs").instructions) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(ITEM_STACK)) reads.add(field.name);
        }
        return reads;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    /** A serializer reading the 1.21.1 stack codecs, the first three as Farmer's Delight's cooking serializer does. */
    private static byte[] serializerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "codecs", "()V", null, null);
        m.visitCode();
        for (String field : OLD_FIELDS) {
            m.visitFieldInsn(GETSTATIC, ITEM_STACK, field, CODEC);
            m.visitInsn(POP);
        }
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
