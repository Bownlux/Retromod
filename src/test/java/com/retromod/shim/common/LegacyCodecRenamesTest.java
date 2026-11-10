/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Old codec fields read as the same-typed codec a later host keeps, which the fuzzy resolver no longer guesses. */
class LegacyCodecRenamesTest {

    private static final String CUSTOM_DATA = "net/minecraft/world/item/component/CustomData";
    private static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    private static final String EXTRA_CODECS = "net/minecraft/util/ExtraCodecs";
    private static final String ENCHANTMENTS = "net/minecraft/world/item/enchantment/ItemEnchantments";
    private static final String PROFILE = "com/mojang/authlib/GameProfile";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"),
            "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft");

    @TempDir
    Path dir;

    @Test
    @DisplayName("On a host with only the new names the old codecs read them; a 1.21.1 host that still has both keeps the reads")
    void oldCodecsReadTheMergedCodec() throws Exception {
        RetromodTransformer merged = indexed("merged.jar", false);
        LegacyMemberRenames.register(merged, rename -> LegacyMemberRenames.hostRenamed(rename, merged.getFuzzyResolver()));
        assertEquals(List.of(CUSTOM_DATA + ".CODEC", INGREDIENT + ".CODEC", EXTRA_CODECS + ".AUTHLIB_GAME_PROFILE",
                ENCHANTMENTS + ".CODEC"), reads(merged.transformClass(reader(), "mod/Codecs")));

        RetromodTransformer older = indexed("older.jar", true);
        LegacyMemberRenames.register(older, rename -> LegacyMemberRenames.hostRenamed(rename, older.getFuzzyResolver()));
        assertEquals(List.of(CUSTOM_DATA + ".CODEC_WITH_ID", INGREDIENT + ".CODEC_NONEMPTY", EXTRA_CODECS + ".GAME_PROFILE",
                ENCHANTMENTS + ".FULL_CODEC"), reads(older.transformClass(reader(), "mod/Codecs")));
    }

    @Test
    @DisplayName("The old codec on 1.21.1 and CODEC on 26.3 have the same type argument")
    void typeArgumentsMatchOnTheRealHosts() throws Exception {
        Path neoForm = Path.of(System.getProperty("user.home"), ".gradle/caches/neoformruntime/intermediate_results",
                "rename_b19be917e8c598bb694e648908d4873a9f96dd70_output.jar");
        Path host = LIBRARIES.resolve("26.3/minecraft-26.3-client.jar");
        Assumptions.assumeTrue(Files.isRegularFile(neoForm) && Files.isRegularFile(host), "no local 1.21.1 and 26.3 jars");
        assertEquals(signature(neoForm, CUSTOM_DATA, "CODEC_WITH_ID"), signature(host, CUSTOM_DATA, "CODEC"));
        assertEquals(signature(neoForm, INGREDIENT, "CODEC_NONEMPTY"), signature(host, INGREDIENT, "CODEC"));
        assertEquals(signature(neoForm, EXTRA_CODECS, "GAME_PROFILE"), signature(host, EXTRA_CODECS, "AUTHLIB_GAME_PROFILE"));
        assertEquals(signature(neoForm, ENCHANTMENTS, "FULL_CODEC"), signature(host, ENCHANTMENTS, "CODEC"));
    }

    // A transformer indexing a host with each new codec, and the old ones too when withOld.
    private RetromodTransformer indexed(String name, boolean withOld) throws Exception {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            write(out, CUSTOM_DATA, codecs(CUSTOM_DATA, CUSTOM_DATA, "CODEC", withOld ? "CODEC_WITH_ID" : null));
            write(out, INGREDIENT, codecs(INGREDIENT, INGREDIENT, "CODEC", withOld ? "CODEC_NONEMPTY" : null));
            write(out, EXTRA_CODECS, codecs(EXTRA_CODECS, PROFILE, "AUTHLIB_GAME_PROFILE", withOld ? "GAME_PROFILE" : null));
            write(out, ENCHANTMENTS, codecs(ENCHANTMENTS, ENCHANTMENTS, "CODEC", withOld ? "FULL_CODEC" : null));
        }
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(jar);
        return transformer;
    }

    private static byte[] codecs(String owner, String valueType, String newName, String oldName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        String signature = "Lcom/mojang/serialization/Codec<L" + valueType + ";>;";
        for (String name : oldName == null ? List.of(newName) : List.of(newName, oldName)) {
            FieldVisitor field = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name, CODEC, signature, null);
            field.visitEnd();
        }
        // A codec of another type, as both classes have on 26.3, so the fuzzy resolver declines to guess.
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "OTHER_CODEC", CODEC,
                "Lcom/mojang/serialization/Codec<Ljava/lang/String;>;", null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void write(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    /** A 1.21.1 mod reading the four old codecs. */
    private static byte[] reader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Codecs", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, CUSTOM_DATA, "CODEC_WITH_ID", CODEC);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, INGREDIENT, "CODEC_NONEMPTY", CODEC);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, EXTRA_CODECS, "GAME_PROFILE", CODEC);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ENCHANTMENTS, "FULL_CODEC", CODEC);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> reads(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return java.util.Arrays.stream(node.methods.get(0).instructions.toArray())
                .filter(insn -> insn instanceof org.objectweb.asm.tree.FieldInsnNode)
                .map(insn -> ((org.objectweb.asm.tree.FieldInsnNode) insn).owner + "." + ((org.objectweb.asm.tree.FieldInsnNode) insn).name)
                .toList();
    }

    private static String signature(Path jar, String owner, String field) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(file.getJarEntry(owner + ".class")).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node.fields.stream().filter(f -> f.name.equals(field)).map((FieldNode f) -> f.signature)
                    .findFirst().orElseThrow();
        }
    }
}
