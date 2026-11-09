/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The tag keys 26.1, 26.2 and 26.3 renamed read under their new names, which the fuzzy resolver no longer guesses. */
class LegacyTagKeyRenamesTest {

    private static final String BLOCK_TAGS = "net/minecraft/tags/BlockTags";
    private static final String ITEM_TAGS = "net/minecraft/tags/ItemTags";
    private static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";
    private static final String BLOCK_TAG = "Lnet/minecraft/tags/TagKey<Lnet/minecraft/world/level/block/Block;>;";
    private static final String ITEM_TAG = "Lnet/minecraft/tags/TagKey<Lnet/minecraft/world/item/Item;>;";
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    /** Each renamed tag key: its class, old name and new name, as the tag files were renamed. */
    private static final List<String[]> TAGS = List.of(
            new String[]{BLOCK_TAGS, "BAMBOO_PLANTABLE_ON", "SUPPORTS_BAMBOO"},
            new String[]{BLOCK_TAGS, "BIG_DRIPLEAF_PLACEABLE", "SUPPORTS_BIG_DRIPLEAF"},
            new String[]{BLOCK_TAGS, "DRY_VEGETATION_MAY_PLACE_ON", "SUPPORTS_DRY_VEGETATION"},
            new String[]{BLOCK_TAGS, "MUSHROOM_GROW_BLOCK", "OVERRIDES_MUSHROOM_LIGHT_REQUIREMENT"},
            new String[]{BLOCK_TAGS, "SMALL_DRIPLEAF_PLACEABLE", "SUPPORTS_SMALL_DRIPLEAF"},
            new String[]{BLOCK_TAGS, "SNOW_LAYER_CAN_SURVIVE_ON", "SUPPORT_OVERRIDE_SNOW_LAYER"},
            new String[]{BLOCK_TAGS, "SNOW_LAYER_CANNOT_SURVIVE_ON", "CANNOT_SUPPORT_SNOW_LAYER"},
            new String[]{ITEM_TAGS, "DYEABLE", "CAULDRON_CAN_REMOVE_DYE"},
            new String[]{BLOCK_TAGS, "CONCRETE_POWDER", "CONCRETE_POWDERS"},
            new String[]{BLOCK_TAGS, "CONVERTABLE_TO_MUD", "CONVERTIBLE_TO_MUD"});

    @TempDir
    Path dir;

    @Test
    @DisplayName("A host with the new tag keys gets the renames; a host that still has the old ones keeps the reads")
    void renamedTagKeysReadTheNewNames() throws Exception {
        RetromodTransformer renamed = indexed("renamed.jar", false);
        LegacyMemberRenames.register(renamed, rename -> LegacyMemberRenames.hostRenamed(rename, renamed.getFuzzyResolver()));
        assertEquals(TAGS.stream().map(tag -> tag[2]).toList(), reads(renamed.transformClass(reader(), "mod/Tags")));

        RetromodTransformer older = indexed("older.jar", true);
        LegacyMemberRenames.register(older, rename -> LegacyMemberRenames.hostRenamed(rename, older.getFuzzyResolver()));
        assertEquals(TAGS.stream().map(tag -> tag[1]).toList(), reads(older.transformClass(reader(), "mod/Tags")));
    }

    @Test
    @DisplayName("Each old tag key on 1.21.1 has the type argument of its new name on 26.1.2, 26.2 and 26.3")
    void typeArgumentsMatchOnTheRealHosts() throws Exception {
        Path neoForm = HOME.resolve(".gradle/caches/neoformruntime/intermediate_results/"
                + "rename_b19be917e8c598bb694e648908d4873a9f96dd70_output.jar");
        Path clients = HOME.resolve("Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft");
        List<Path> hosts = List.of(clients.resolve("26.1.2/minecraft-26.1.2-client.jar"),
                clients.resolve("26.2/minecraft-26.2-client.jar"), clients.resolve("26.3/minecraft-26.3-client.jar"));
        Assumptions.assumeTrue(Files.isRegularFile(neoForm) && hosts.stream().allMatch(Files::isRegularFile),
                "no local 1.21.1 and 26.x jars");
        for (String[] tag : TAGS) {
            assertNotNull(signature(hosts.get(2), tag[0], tag[2]), tag[2] + " on 26.3");
            // DRY_VEGETATION_MAY_PLACE_ON came after 1.21.1, so there is nothing to compare it with here.
            String old = signature(neoForm, tag[0], tag[1]);
            if (old == null) continue;
            for (Path host : hosts) {
                // CONCRETE_POWDERS and CONVERTIBLE_TO_MUD arrive in 26.2 and 26.3.
                String renamed = signature(host, tag[0], tag[2]);
                if (renamed != null) assertEquals(old, renamed, tag[1] + " -> " + tag[2] + " on " + host.getFileName());
            }
        }
    }

    // A transformer indexing a host whose tag classes have each new key, and the old ones too when withOld.
    private RetromodTransformer indexed(String name, boolean withOld) throws Exception {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String owner : List.of(BLOCK_TAGS, ITEM_TAGS)) {
                ClassWriter cw = new ClassWriter(0);
                cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
                String signature = owner.equals(BLOCK_TAGS) ? BLOCK_TAG : ITEM_TAG;
                for (String[] tag : TAGS) {
                    if (!tag[0].equals(owner)) continue;
                    for (String field : withOld ? List.of(tag[1], tag[2]) : List.of(tag[2])) {
                        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field, TAG_KEY, signature, null)
                                .visitEnd();
                    }
                }
                cw.visitEnd();
                out.putNextEntry(new JarEntry(owner + ".class"));
                out.write(cw.toByteArray());
                out.closeEntry();
            }
        }
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(jar);
        return transformer;
    }

    /** A mod built before 26.1 reading every old tag key. */
    private static byte[] reader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "mod/Tags", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()V", null, null);
        mv.visitCode();
        for (String[] tag : TAGS) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, tag[0], tag[1], TAG_KEY);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> reads(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return Arrays.stream(node.methods.get(0).instructions.toArray())
                .filter(insn -> insn instanceof FieldInsnNode).map(insn -> ((FieldInsnNode) insn).name).toList();
    }

    /** The field's Signature in {@code jar}, or null when the class does not declare it. */
    private static String signature(Path jar, String owner, String field) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(file.getJarEntry(owner + ".class")).readAllBytes())
                    .accept(node, ClassReader.SKIP_CODE);
            return node.fields.stream().filter(f -> f.name.equals(field)).map((FieldNode f) -> f.signature)
                    .findFirst().orElse(null);
        }
    }
}
