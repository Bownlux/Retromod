/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** On Fabric 26.x both the old and the 1.21.5 sand intermediary ids map to SandBlock; only the old one may move on. */
class FabricSandBlockVersionLimitTest {

    private static final String SAND = "net/minecraft/world/level/block/SandBlock";
    private static final String COLORED = "net/minecraft/world/level/block/ColoredFallingBlock";

    @AfterEach
    void reset() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    void aNewerModsSandBlockStaysSandBlock() {
        assertEquals(SAND, superAfterTransform("1.21.11", "net/minecraft/class_10737"),
                "1.21.5 brought SandBlock back, with its own desert sounds");
    }

    @Test
    void anOlderModsSandBlockBecomesColoredFallingBlock() {
        assertEquals(COLORED, superAfterTransform("1.20.1", "net/minecraft/class_2468"),
                "1.20.3 folded the old SandBlock into ColoredFallingBlock");
    }

    @Test
    void aNewerModsSandBlockSurvivesTheEmbed(@TempDir Path dir) throws Exception {
        assertEquals(SAND, superAfterEmbed(dir, "1.21.11", "net/minecraft/class_10737"),
                "the stand-in embed FabricModTransformer runs must not move SandBlock either");
    }

    @Test
    void anOlderModsSandBlockStillMovesThroughTheEmbed(@TempDir Path dir) throws Exception {
        assertEquals(COLORED, superAfterEmbed(dir, "1.20.1", "net/minecraft/class_2468"));
    }

    private static final String HELPER = "com/retromod/generated/TestSandHelper";

    /** The superclass after the class pass and the stand-in embed, for a mod that also calls a stand-in. */
    private static String superAfterEmbed(Path dir, String modVersion, String intermediarySand)
            throws Exception {
        byte[] block = transformedRedSand(modVersion, intermediarySand);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        // A stand-in the mod calls is what makes the embed rewrite the mod's classes.
        transformer.registerSyntheticClass(HELPER, helperClass(HELPER, null));
        Files.createDirectories(dir.resolve("mod"));
        Files.write(dir.resolve("mod/RedSand.class"), block);
        Files.write(dir.resolve("mod/UsesHelper.class"), helperClass("mod/UsesHelper", HELPER));

        SyntheticEmbedder.embed(dir, "sandmod", transformer);

        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(dir.resolve("mod/RedSand.class"))).accept(node, 0);
        return node.superName;
    }

    /** A class with a static {@code touch()}, which calls {@code callee.touch()} when one is given. */
    private static byte[] helperClass(String name, String callee) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "touch", "()V", null, null);
        m.visitCode();
        if (callee != null) m.visitMethodInsn(Opcodes.INVOKESTATIC, callee, "touch", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The superclass a mod block extending {@code intermediarySand} ends with. */
    private static String superAfterTransform(String modVersion, String intermediarySand) {
        ClassNode node = new ClassNode();
        new ClassReader(transformedRedSand(modVersion, intermediarySand)).accept(node, 0);
        return node.superName;
    }

    /** A mod block extending {@code intermediarySand}, after the class pass for a mod built for {@code modVersion}. */
    private static byte[] transformedRedSand(String modVersion, String intermediarySand) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_20_2_to_1_20_3().registerRedirects(transformer);
        // The bundled 26.x table maps both ids to the Mojang name.
        transformer.registerClassRedirect("net/minecraft/class_10737", SAND);
        transformer.registerClassRedirect("net/minecraft/class_2468", SAND);
        transformer.setModSourceVersion(modVersion);

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/RedSand", null, intermediarySand, null);
        cw.visitEnd();
        return transformer.transformClass(cw.toByteArray(), "mod/RedSand.class");
    }
}
