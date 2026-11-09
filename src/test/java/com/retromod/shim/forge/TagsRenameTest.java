/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 renamed most 1.20.1 tag constants to c: tags; old reads take the host field or keep their forge: id. */
class TagsRenameTest {

    private static final String ITEMS = LegacyForgeTagsBridge.ITEMS;
    private static final String BLOCKS = LegacyForgeTagsBridge.BLOCKS;
    private static final String TAG_KEY = LegacyForgeTagsBridge.TAG_KEY;
    private static final String MOD = "mod/Tags";
    private static final Path PRISM = Path.of(System.getProperty("user.home", ""),
            "Library/Application Support/PrismLauncher/libraries");

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path tempDir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Renamed constants read the host field, armor reads vanilla's, and stained glass keeps its forge: id")
    void readsFollowTheTable() {
        LegacyForgeTagsBridge.register(transformer);
        List<String> reads = reads(transformer.transformClass(mod(
                ITEMS + ".SHEARS", ITEMS + ".ARMORS_HELMETS", BLOCKS + ".STONE", ITEMS + ".STAINED_GLASS"), MOD));
        assertEquals(List.of(ITEMS + ".TOOLS_SHEAR", LegacyForgeTagsBridge.ITEM_TAGS + ".HEAD_ARMOR",
                BLOCKS + ".STONES", LegacyForgeTagsBridge.HELPER + ".item_STAINED_GLASS()"), reads);
    }

    @Test
    @DisplayName("A kept constant builds its tag from the old registry and forge: id")
    void keptConstantRebuildsItsId() throws Exception {
        LegacyForgeTagsBridge.register(transformer);
        ClassLoader host = new ForgeHostStandIns()
                .add("net/minecraft/resources/ResourceKey.java", """
                        package net.minecraft.resources;
                        public final class ResourceKey<T> {
                            private final String name;
                            public ResourceKey(String name) { this.name = name; }
                            public String toString() { return name; }
                        }""")
                .add("net/minecraft/core/registries/Registries.java", """
                        package net.minecraft.core.registries;
                        import net.minecraft.resources.ResourceKey;
                        public final class Registries {
                            public static final ResourceKey<Object> ITEM = new ResourceKey<>("item");
                            public static final ResourceKey<Object> BLOCK = new ResourceKey<>("block");
                        }""")
                .add("net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public final class Identifier {
                            private final String id;
                            private Identifier(String id) { this.id = id; }
                            public static Identifier fromNamespaceAndPath(String namespace, String path) {
                                return new Identifier(namespace + ":" + path);
                            }
                            public String toString() { return id; }
                        }""")
                .add("net/minecraft/tags/TagKey.java", """
                        package net.minecraft.tags;
                        public final class TagKey<T> {
                            private final String text;
                            private TagKey(String text) { this.text = text; }
                            public static <T> TagKey<T> create(net.minecraft.resources.ResourceKey<?> registry,
                                    net.minecraft.resources.Identifier id) {
                                return new TagKey<>(registry + " " + id);
                            }
                            public String toString() { return text; }
                        }""")
                .loader(tempDir, transformer, Map.of(MOD, transformer.transformClass(
                        mod(ITEMS + ".GLASS_PANES_RED", BLOCKS + ".CHORUS_ADDITIONALLY_GROWS_ON"), MOD)));
        Class<?> mod = host.loadClass("mod.Tags");
        assertEquals("item forge:glass_panes/red", mod.getMethod("read0").invoke(null).toString());
        assertEquals("block forge:chorus_additionally_grows_on", mod.getMethod("read1").invoke(null).toString());
    }

    @Test
    @DisplayName("NeoForge and Forge 1.21.11 targets register nothing, and the 26.3 removals wait for 26.3")
    void otherHostsAndVersions() {
        LegacyForgeTagsBridge.register(transformer);
        assertFalse(redirected(BLOCKS, "NEEDS_GOLD_TOOL"), "Forge 26.2 still has NEEDS_GOLD_TOOL");
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyForgeTagsBridge.register(transformer);
        assertTrue(redirected(BLOCKS, "NEEDS_GOLD_TOOL"), "Forge 26.3 removed NEEDS_GOLD_TOOL");

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyForgeTagsBridge.register(transformer);
        assertTrue(transformer.getFieldRedirects().isEmpty(), "1.21.11");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        LegacyForgeTagsBridge.register(transformer);
        assertTrue(transformer.getFieldRedirects().isEmpty(), "NeoForge");
    }

    @Test
    @DisplayName("Against the Forge 26.2 jars: every old name is gone, every target exists, and every kept id matches 1.20.1")
    void tableMatchesTheRealJars() throws Exception {
        Path universal = PRISM.resolve("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-universal.jar");
        Path client = PRISM.resolve("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar");
        Path old = PRISM.resolve("net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-universal.jar");
        Assumptions.assumeTrue(Files.isRegularFile(universal) && Files.isRegularFile(client) && Files.isRegularFile(old),
                "the Forge 26.2 and 1.20.1 jars are not in the local Prism library");
        checkHost("26.2", jarHost(universal, client), jarHost(old));

        // Forge 26.3 has no Prism install yet; point retromod.test.forge263 at its universal jar to check it.
        String forge263 = System.getProperty("retromod.test.forge263", "");
        Path vanilla263 = PRISM.resolve("com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
        if (!forge263.isEmpty() && Files.isRegularFile(vanilla263)) {
            transformer.clearRedirectsForTesting();
            checkHost("26.3", jarHost(Path.of(forge263), vanilla263), jarHost(old));
        }
    }

    /** Registers against a host read from real jars, then checks every row of the table against it. */
    private void checkHost(String version, Function<String, ClassNode> host, Function<String, ClassNode> forge1201) {
        RetromodVersion.TARGET_MC_VERSION = version;
        LegacyForgeTagsBridge.register(transformer, host);
        for (String[] rename : LegacyForgeTagsBridge.RENAMES) {
            String row = String.join(" ", rename) + " on " + version;
            assertTrue(hasField(forge1201, rename[0], rename[1]), "1.20.1 had " + row);
            assertFalse(hasField(host, rename[0], rename[1]), "the host lacks " + row);
            assertTrue(hasField(host, rename[2], rename[3]), "the host has the target of " + row);
            assertTrue(redirected(rename[0], rename[1]), "registered " + row);
        }
        Map<String, String> oldIds = forgeIds(forge1201.apply(ITEMS), "item_");
        oldIds.putAll(forgeIds(forge1201.apply(BLOCKS), "block_"));
        for (String[] kept : LegacyForgeTagsBridge.KEPT_IDS) {
            String row = String.join(" ", kept) + " on " + version;
            assertEquals(kept[2], oldIds.get(LegacyForgeTagsBridge.getterName(kept)), "1.20.1 id of " + row);
            boolean removed = RetromodVersion.compareMcVersions(version, kept[3]) >= 0;
            assertEquals(removed, !hasField(host, kept[0], kept[1]), "the host field matches the version of " + row);
            assertEquals(removed, redirected(kept[0], kept[1]), "registered " + row);
        }
    }

    private boolean redirected(String owner, String field) {
        return transformer.getFieldRedirects().keySet().stream().anyMatch(key -> key.owner().equals(owner) && key.name().equals(field));
    }

    private static boolean hasField(Function<String, ClassNode> host, String owner, String field) {
        ClassNode node = host.apply(owner);
        return node != null && node.fields.stream().anyMatch(f -> f.name.equals(field));
    }

    /** Field name to its {@code forge:} path, read from the old class's static initializer. */
    private static Map<String, String> forgeIds(ClassNode tags, String prefix) {
        Map<String, String> ids = new HashMap<>();
        MethodNode clinit = tags.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        String lastString = null;
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) lastString = text;
            if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && lastString != null) {
                ids.put(prefix + put.name, lastString);
                lastString = null;
            }
        }
        return ids;
    }

    /** A host that reads classes from jars, with Forge's anchor present so ForgeHostShape treats it as a running host. */
    private static Function<String, ClassNode> jarHost(Path... jars) {
        Map<String, ClassNode> cache = new HashMap<>();
        return name -> cache.computeIfAbsent(name, key -> {
            for (Path jar : jars) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    var entry = zip.getEntry(key + ".class");
                    if (entry == null) continue;
                    ClassNode node = new ClassNode();
                    try (var in = zip.getInputStream(entry)) {
                        new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
                    }
                    return node;
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return null;
        });
    }

    /** A mod whose {@code readN()} methods read the given {@code owner.FIELD} tag constants. */
    private static byte[] mod(String... fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        for (int i = 0; i < fields.length; i++) {
            String[] field = fields[i].split("\\.");
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read" + i, "()Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, field[0], field[1], TAG_KEY);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> reads(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> reads = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) reads.add(field.owner + "." + field.name);
                if (insn instanceof MethodInsnNode call) reads.add(call.owner + "." + call.name + "()");
            }
        }
        return reads;
    }
}
