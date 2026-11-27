/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.shim.OfflineRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 Fabric mod's {@code addCarver(GenerationStep.Carving.AIR, key)} on Fabric 26.3, through the real shim chain and the loader API runtime. */
class CarvingStepPathTest {

    private static final String HOST = "26.3";
    private static final String MOD_CLASS = "cavemod/CaveBiomes";
    private static final String CONTEXT = "net/fabricmc/fabric/api/biome/v1/BiomeModificationContext$GenerationSettingsContext";
    private static final String CALLS = "com/retromod/legacyapi/fabric/LegacyFabricApiCalls";
    private static final String STAND_IN = "com/retromod/legacyapi/minecraft/world/level/levelgen/LegacyCarvingStep";
    private static final String CARVING = "net/minecraft/world/level/levelgen/GenerationStep$Carving";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String BUILDER = "net/minecraft/world/level/biome/BiomeGenerationSettings$Builder";
    // The mod names Minecraft in intermediary, as every Fabric mod before 26.1 does.
    private static final String INTERMEDIARY_CARVING = "net/minecraft/class_2893$class_2894";
    private static final String INTERMEDIARY_KEY = "Lnet/minecraft/class_5321;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final boolean savedForge = McReflect.isForceForge();

    @BeforeEach
    void fabricHost() {
        RetromodVersion.TARGET_MC_VERSION = HOST;
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        // A 26.3 host keeps one carver list: its builder adds a carver by key, and the carving step is gone.
        ClassResourceInspector.setOfflineHost(hostWithOneCarverList());
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        ClassResourceInspector.setOfflineHost(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A 1.20.1 mod reads AIR from the vanilla stand-in and adds its carver through the loader API helper")
    void olderModReachesStandInAndHelper() {
        ClassNode out = translate("1.20.1");
        assertEquals(List.of(STAND_IN + ".AIR:L" + STAND_IN + ";"), fieldReads(out),
                "the removed carving step is read from the stand-in");
        assertEquals(List.of("INVOKESTATIC " + CALLS + ".addCarver(L" + CONTEXT + ";Ljava/lang/Object;" + KEY + ")V",
                        "INVOKEINTERFACE " + CONTEXT + ".addCarver(" + KEY + ")V"), calls(out),
                "the old call drops the step in the helper, and the one-list call stays");
        assertTrue(transformer.getSyntheticClasses().containsKey(CALLS), "the helper travels with the mod");
        assertTrue(transformer.getSyntheticClasses().containsKey(STAND_IN), "the stand-in travels with the mod");
    }

    @Test
    @DisplayName("A mod built for 1.21.4 is left alone, since 1.21.2 and newer never pass a carving step")
    void newerModIsLeftAlone() {
        ClassNode out = translate("1.21.4");
        assertEquals(List.of(CARVING + ".AIR:L" + CARVING + ";"), fieldReads(out));
        assertEquals(List.of("INVOKEINTERFACE " + CONTEXT + ".addCarver(L" + CARVING + ";" + KEY + ")V",
                "INVOKEINTERFACE " + CONTEXT + ".addCarver(" + KEY + ")V"), calls(out));
    }

    /** Registers what the CLI registers for a Fabric mod built for {@code source}, then translates the mod's class. */
    private ClassNode translate(String source) {
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion(source);
        ShimRegistry registry = new ShimRegistry();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) registry.register(shim);
        ModVersionInfo info = new ModVersionInfo("cavemod", "1.0", source, "fabric", "0.16.10",
                Set.of("cavemod"), Set.of("fabric-api"), false);
        List<VersionShim> chain = registry.findTranslationShims("fabric", source, HOST, false);
        OfflineRegistration.register(transformer, registry, info, HOST, chain);
        // The loader API runtime registers last, as the CLI does before it reads the jar; forced here as if it were on.
        LoaderApiRuntime.prepareForced(transformer, new LoaderApiContext("fabric", HOST,
                LoaderApiContext.TransformPath.CLI_TRANSFORM, source));
        byte[] out = transformer.transformClass(modClass(), MOD_CLASS);
        assertNotNull(out, "the mod class was transformed");
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        return node;
    }

    /** {@code context.addCarver(GenerationStep.Carving.AIR, key); context.addCarver(key);} in intermediary names. */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addCaves",
                "(L" + CONTEXT + ";" + INTERMEDIARY_KEY + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, INTERMEDIARY_CARVING, "field_13169", "L" + INTERMEDIARY_CARVING + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONTEXT, "addCarver",
                "(L" + INTERMEDIARY_CARVING + ";" + INTERMEDIARY_KEY + ")V", true);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CONTEXT, "addCarver", "(" + INTERMEDIARY_KEY + ")V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The host's biome builder with only the by-key carver adder, as {@code --host-jar} gives the CLI. */
    private static ClassLoader hostWithOneCarverList() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, BUILDER, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "addCarver", "(" + KEY + ")L" + BUILDER + ";", null, null).visitEnd();
        cw.visitEnd();
        byte[] builder = cw.toByteArray();
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String resource) {
                return resource.equals(BUILDER + ".class") ? new ByteArrayInputStream(builder) : null;
            }
        };
    }

    private static List<String> fieldReads(ClassNode node) {
        List<String> reads = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) reads.add(field.owner + "." + field.name + ":" + field.desc);
            }
        }
        return reads;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    String opcode = switch (call.getOpcode()) {
                        case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                        case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                        default -> "INVOKEVIRTUAL";
                    };
                    calls.add(opcode + " " + call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }
}
