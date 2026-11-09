/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.SyntheticLibraries;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A library whose own event passes old color handlers through erased generics, and an addon that hands it lambdas,
 * shaped like GlitchCore and BiomesOPlenty: the addon's lambdas must be the library's copy of the rebuilt BlockColor.
 */
class LegacyColorLibraryLinkTest {

    private static final String OLD_BLOCK_COLOR = LegacyForgeColorBridge.OLD_BLOCK_COLOR;
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BASE_EVENT = "glitchcore/event/client/RegisterColorsEvent";
    private static final String BLOCK_EVENT = BASE_EVENT + "$Block";
    private static final String HANDLER = "glitchcore/forge/handlers/ColorsEventHandler";
    private static final String ADDON = "biomesoplenty/init/ModClient";
    private static final String REBUILT_BLOCK_COLOR = com.retromod.shim.common.LegacyColorClasses.BLOCK_COLOR;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, "26.2")) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An addon's block color lambda passed through a library's erased generic event is the library's BlockColor copy")
    void addonLambdaTakesTheLibrarysCopy(@TempDir Path dir) throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        Path library = new ForgeModTransformer("26.2").transformMod(write(dir, "glitchcore.jar", "glitchcore", libraryClasses()),
                Files.createDirectory(dir.resolve("lib")), libraries);
        assertNotNull(library);
        libraries.add(library);
        Path addon = new ForgeModTransformer("26.2").transformMod(write(dir, "biomesoplenty.jar", "biomesoplenty", addonClasses()),
                Files.createDirectory(dir.resolve("addon")), libraries);
        assertNotNull(addon);

        String libraryColor;
        try (JarFile jar = new JarFile(library.toFile())) {
            libraryColor = castType(method(jar, HANDLER, "forward"));
        }
        assertTrue(libraryColor.startsWith(SyntheticEmbedder.PREFIX) && libraryColor.endsWith(REBUILT_BLOCK_COLOR),
                "the library casts to its own embedded BlockColor: " + libraryColor);
        try (JarFile jar = new JarFile(addon.toFile())) {
            assertEquals(libraryColor, lambdaType(method(jar, ADDON, "registerBlockColors")),
                    "the addon's lambda must be the copy the library casts to, or the host's block color event fails");
            assertEquals("none", localCopy(jar), "the addon embeds no BlockColor of its own");
        }
    }

    // ---- fixtures ----

    private static Path write(Path dir, String name, String modId, Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml(modId));
        classes.forEach((className, bytes) -> entries.put(className + ".class", bytes));
        Path jar = dir.resolve(name);
        Files.write(jar, LegacyAutoSubscribePathParityTest.jar(entries));
        return jar;
    }

    /**
     * GlitchCore's shape: {@code RegisterColorsEvent<T, O>} with an erased {@code register(Object, Object[])},
     * {@code RegisterColorsEvent.Block extends RegisterColorsEvent<BlockColor, Block>}, and a Forge handler that
     * casts each color back to BlockColor before handing it to Forge's block color event.
     */
    private static Map<String, byte[]> libraryClasses() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassWriter base = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        base.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, BASE_EVENT, "<T:Ljava/lang/Object;O:Ljava/lang/Object;>Ljava/lang/Object;",
                "java/lang/Object", null);
        constructor(base, "java/lang/Object");
        MethodVisitor register = base.visitMethod(ACC_PUBLIC | ACC_VARARGS, "register", "(Ljava/lang/Object;[Ljava/lang/Object;)V",
                "(TT;[TO;)V", null);
        register.visitCode();
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        base.visitEnd();
        classes.put(BASE_EVENT, base.toByteArray());

        ClassWriter block = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        block.visit(V17, ACC_PUBLIC | ACC_SUPER, BLOCK_EVENT,
                "L" + BASE_EVENT + "<L" + OLD_BLOCK_COLOR + ";L" + BLOCK + ";>;", BASE_EVENT, null);
        constructor(block, BASE_EVENT);
        block.visitEnd();
        classes.put(BLOCK_EVENT, block.toByteArray());

        ClassWriter handler = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        handler.visit(V17, ACC_PUBLIC | ACC_SUPER, HANDLER, null, "java/lang/Object", null);
        MethodVisitor forward = handler.visitMethod(ACC_PUBLIC | ACC_STATIC, "forward",
                "(L" + LegacyForgeColorBridge.BLOCK_EVENT + ";Ljava/lang/Object;L" + BLOCK + ";)V", null, null);
        forward.visitCode();
        forward.visitVarInsn(ALOAD, 0);
        forward.visitVarInsn(ALOAD, 1);
        forward.visitTypeInsn(CHECKCAST, OLD_BLOCK_COLOR);
        forward.visitInsn(ICONST_1);
        forward.visitTypeInsn(ANEWARRAY, BLOCK);
        forward.visitInsn(DUP);
        forward.visitInsn(ICONST_0);
        forward.visitVarInsn(ALOAD, 2);
        forward.visitInsn(AASTORE);
        forward.visitMethodInsn(INVOKEVIRTUAL, LegacyForgeColorBridge.BLOCK_EVENT, "register",
                "(L" + OLD_BLOCK_COLOR + ";[L" + BLOCK + ";)V", false);
        forward.visitInsn(RETURN);
        forward.visitMaxs(0, 0);
        forward.visitEnd();
        handler.visitEnd();
        classes.put(HANDLER, handler.toByteArray());
        return classes;
    }

    /** BiomesOPlenty's shape: a block color lambda handed to the library event's erased register. */
    private static Map<String, byte[]> addonClasses() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, ADDON, null, "java/lang/Object", null);
        String sam = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                + "Lnet/minecraft/core/BlockPos;I)I";
        MethodVisitor colors = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerBlockColors", "(L" + BLOCK_EVENT + ";)V", null, null);
        colors.visitCode();
        colors.visitVarInsn(ALOAD, 0);
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        colors.visitInvokeDynamicInsn("getColor", "()L" + OLD_BLOCK_COLOR + ";", metafactory, Type.getType(sam),
                new Handle(H_INVOKESTATIC, ADDON, "foliage", sam, false), Type.getType(sam));
        colors.visitInsn(ICONST_0);
        colors.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        colors.visitMethodInsn(INVOKEVIRTUAL, BLOCK_EVENT, "register", "(Ljava/lang/Object;[Ljava/lang/Object;)V", false);
        colors.visitInsn(RETURN);
        colors.visitMaxs(0, 0);
        colors.visitEnd();
        MethodVisitor foliage = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "foliage", sam, null, null);
        foliage.visitCode();
        foliage.visitLdcInsn(0x48B518);
        foliage.visitInsn(IRETURN);
        foliage.visitMaxs(0, 0);
        foliage.visitEnd();
        cw.visitEnd();
        return Map.of(ADDON, cw.toByteArray());
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static MethodNode method(JarFile jar, String owner, String name) throws Exception {
        return LegacyForgeRenderBridgeTest.method(ModConstructorHooksTest.node(
                jar.getInputStream(jar.getJarEntry(owner + ".class")).readAllBytes()), name);
    }

    private static String castType(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == CHECKCAST) return type.desc;
        }
        throw new AssertionError("no cast in " + method.name);
    }

    private static String lambdaType(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return Type.getReturnType(indy.desc).getInternalName();
        }
        throw new AssertionError("no lambda in " + method.name);
    }

    // The entry an addon-owned BlockColor copy would have, found by its suffix.
    private static String localCopy(JarFile jar) {
        return jar.stream().map(java.util.jar.JarEntry::getName)
                .filter(name -> name.endsWith(REBUILT_BLOCK_COLOR + ".class")).findFirst().orElse("none");
    }
}
