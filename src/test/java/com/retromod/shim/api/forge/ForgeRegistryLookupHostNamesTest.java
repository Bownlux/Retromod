/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Forge registry lookups keyed on {@code ResourceLocation}, on hosts where later shims rename that
 * class and the registry's lookup methods. The redirects are registered with 1.20.1 names before
 * those renames, so the output must still name what the host has.
 */
class ForgeRegistryLookupHostNamesTest {

    private static final String FORGE_REGISTRY = "net/minecraftforge/registries/IForgeRegistry";
    private static final String LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "getValue(ResourceLocation) on {0} -> Registry.{1}{2}")
    @DisplayName("IForgeRegistry.getValue reaches the host's value lookup")
    @CsvSource({
        "1.21.1,  get,      (Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;",
        "1.21.2,  getValue, (Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;",
        "1.21.11, getValue, (Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;",
    })
    void getValueUsesTheHostLookup(String host, String name, String desc) {
        loadForgeShimsFor(host);
        MethodInsnNode call = lookupCall(transformer.transformClass(getValueCaller(), "test/Lookup"));
        assertEquals("net/minecraft/core/Registry", call.owner);
        assertEquals(name + desc, call.name + call.desc,
                "the Forge lookup must name the method this host's Registry declares");
    }

    /**
     * The Forge registry redirects first, as the Forge chain registers them, then the NeoForge and
     * common shims of this host, which carry the later renames. The registry shim's NeoForge gate
     * has no test seam, so its redirects are registered directly.
     */
    private void loadForgeShimsFor(String host) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
        new ForgeRegistryApiShim().registerRegistryRedirects(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            String loader = shim.getModLoaderType();
            if (!("neoforge".equals(loader) || "common".equals(loader))) continue;
            if (ShimRegistry.isAvailableOnHost(shim, host)) shim.registerRedirects(transformer);
        }
    }

    private static byte[] getValueCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Lookup", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "find",
                "(L" + FORGE_REGISTRY + ";" + LOCATION + ")Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, FORGE_REGISTRY, "getValue", "(" + LOCATION + ")Ljava/lang/Object;", true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode lookupCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }));
        assertEquals(1, calls.size(), "one lookup call: " + calls.stream().map(c -> c.owner + "." + c.name + c.desc).toList());
        return calls.get(0);
    }
}
