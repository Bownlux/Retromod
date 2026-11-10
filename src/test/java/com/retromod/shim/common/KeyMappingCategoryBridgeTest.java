/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old String-category keybinds reach RetroKeyMapping on every host from 1.21.9, Forge's included. */
class KeyMappingCategoryBridgeTest {

    private static final String POLY = "com/retromod/polyfill/minecraft/RetroKeyMapping";
    private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
    private static final String STRING = "Ljava/lang/String;";
    private static final String TYPE = "Lcom/mojang/blaze3d/platform/InputConstants$Type;";
    private static final String KEY = "Lcom/mojang/blaze3d/platform/InputConstants$Key;";
    private static final String FORGE_SETTINGS = "Lnet/minecraftforge/client/settings/";
    private static final String NEO_SETTINGS = "Lnet/neoforged/neoforge/client/settings/";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("each Forge conflict-context keybind is rebuilt by RetroKeyMapping")
    @CsvSource({"createConflict", "createConflictKey", "createConflictModifier",
            "createConflictModifierKey"})
    void forgeConflictFormsAreBridged(String factory) {
        Common_1_21_11_to_26_1_ClassMoves.registerKeyMappingBridges26x(transformer);
        List<String> out = trace(transformer.transformClass(
                constructs(conflictDescriptor(FORGE_SETTINGS, factory)), "test/mod/Keys"));

        assertTrue(out.contains("call " + POLY + "." + factory),
                "Forge's String-category " + factory + " form is gone from 1.21.9: " + out);
        assertTrue(out.contains("checkcast " + KEY_MAPPING), "the factory returns Object: " + out);
        assertFalse(out.contains("new " + KEY_MAPPING), "no half-built keybind may stay: " + out);
        assertFalse(out.contains("call " + KEY_MAPPING + ".<init>"), out.toString());
    }

    @ParameterizedTest(name = "{0} on {1}")
    @DisplayName("the shim host gate bridges String-category keybinds from 1.21.9")
    @CsvSource({"forge,1.21.8,false", "forge,1.21.9,true", "forge,1.21.11,true", "forge,26.2,true",
            "forge,26.3,true", "neoforge,1.21.8,false", "neoforge,1.21.9,true", "neoforge,26.3,true"})
    void hostGateDecidesTheBridge(String loader, String host, boolean bridged) {
        loadShimsFor(loader, host);
        String settings = loader.equals("forge") ? FORGE_SETTINGS : NEO_SETTINGS;
        String[] descriptors = {
            "(" + STRING + "I" + STRING + ")V",
            "(" + STRING + TYPE + "I" + STRING + ")V",
            conflictDescriptor(settings, "createConflict"),
        };

        for (String descriptor : descriptors) {
            List<String> out = trace(transformer.transformClass(constructs(descriptor), "test/mod/Keys"));
            boolean reachesPoly = out.stream().anyMatch(s -> s.startsWith("call " + POLY + ".create"));
            assertEquals(bridged, reachesPoly, loader + " " + host + " " + descriptor + ": " + out);
            assertEquals(!bridged, out.contains("call " + KEY_MAPPING + ".<init>"),
                    loader + " " + host + " " + descriptor + ": " + out);
        }
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the 1.21.9 keybind bridge leaves 1.21.x quad accessors alone")
    @CsvSource({"forge", "neoforge"})
    void bakedQuadAccessorsStayOn1219(String loader) {
        loadShimsFor(loader, "1.21.9");
        String quad = "net/minecraft/client/renderer/block/model/BakedQuad";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Quads", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "tint", "(L" + quad + ";)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, quad, "tintIndex", "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> out = trace(transformer.transformClass(cw.toByteArray(), "test/mod/Quads"));
        assertTrue(out.contains("call " + quad + ".tintIndex"),
                loader + " 1.21.9 still has BakedQuad.tintIndex(); the 26.1 quad bridge must not run: " + out);
        assertFalse(out.contains("call " + LegacyBakedQuadSynthetic.INTERNAL + ".tintIndex"), out.toString());
    }

    /** The old String-category conflict constructor that {@code factory} rebuilds. */
    private static String conflictDescriptor(String settings, String factory) {
        String context = settings + "IKeyConflictContext;";
        String modifier = settings + "KeyModifier;";
        return switch (factory) {
            case "createConflict" -> "(" + STRING + context + TYPE + "I" + STRING + ")V";
            case "createConflictKey" -> "(" + STRING + context + KEY + STRING + ")V";
            case "createConflictModifier" -> "(" + STRING + context + modifier + TYPE + "I" + STRING + ")V";
            case "createConflictModifierKey" -> "(" + STRING + context + modifier + KEY + STRING + ")V";
            default -> throw new IllegalArgumentException(factory);
        };
    }

    /** Registers what a host of this version registers, through the entry points' loader filter and gate. */
    private void loadShimsFor(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        // RetromodNeoForge also takes Forge shims, for Forge mods it runs.
        List<String> loaderTypes = loader.equals("forge")
                ? List.of("forge", "common") : List.of("neoforge", "forge", "common");
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (!loaderTypes.contains(shim.getModLoaderType())) continue;
            if (ShimRegistry.isAvailableOnHost(shim, host)) shim.registerRedirects(transformer);
        }
    }

    /** A method that runs {@code new KeyMapping(...)} with the given constructor and null arguments. */
    private static byte[] constructs(String desc) {
        return constructs(desc, mv -> {
            for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(desc)) {
                mv.visitInsn(arg.getSort() == org.objectweb.asm.Type.INT ? ICONST_0 : ACONST_NULL);
            }
        });
    }

    private static byte[] constructs(String desc, Consumer<MethodVisitor> args) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Keys", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setup", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, KEY_MAPPING);
        mv.visitInsn(DUP);
        args.accept(mv);
        mv.visitMethodInsn(INVOKESPECIAL, KEY_MAPPING, "<init>", desc, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> trace(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) out.add("call " + c.owner + "." + c.name);
                if (insn instanceof TypeInsnNode t && t.getOpcode() == NEW) out.add("new " + t.desc);
                if (insn instanceof TypeInsnNode t && t.getOpcode() == CHECKCAST) out.add("checkcast " + t.desc);
            }
        }
        return out;
    }
}
