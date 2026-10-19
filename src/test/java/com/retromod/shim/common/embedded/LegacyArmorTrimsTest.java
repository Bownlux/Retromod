/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The atlas {@link LegacyArmorTrims} rebuilds on 26.3 has to hold every sprite the removed 26.2
 * atlas held, under the same name, and every host member the helper reaches by reflection has to
 * exist on the host with the descriptor it names.
 */
class LegacyArmorTrimsTest {

    @Test
    @DisplayName("vanilla trim palettes keep their old suffixes; any other palette is named by its id")
    void permutationNames() {
        assertEquals("iron", LegacyArmorTrims.permutationName("minecraft:trim/iron"));
        assertEquals("iron_darker", LegacyArmorTrims.permutationName("minecraft:trim/iron_darker"));
        assertEquals("examplemod.trim.ruby", LegacyArmorTrims.permutationName("examplemod:trim/ruby"));
        assertEquals("minecraft.trim_base", LegacyArmorTrims.permutationName("minecraft:trim_base"));
    }

    @Test
    @DisplayName("each base palette recolors its textures with every other palette of its size")
    void definitionGroupsByBasePalette() {
        Map<String, List<String>> textures = Map.of(
                "minecraft:trim_base", List.of("minecraft:trims/entity/humanoid/coast",
                        "minecraft:trims/entity/humanoid_leggings/coast"));
        Map<String, Integer> sizes = new TreeMap<>(Map.of(
                "minecraft:trim_base", 8, "minecraft:trim/iron", 8, "minecraft:trim/iron_darker", 8,
                "examplemod:trim/ruby", 8, "examplemod:sky", 4));

        JsonArray sources = JsonParser.parseString(LegacyArmorTrims.definitionJson(textures, sizes))
                .getAsJsonObject().getAsJsonArray("sources");
        JsonObject paletted = sources.get(0).getAsJsonObject();
        assertEquals("minecraft:paletted_permutations", paletted.get("type").getAsString());
        assertEquals("minecraft:trim_base", paletted.get("palette_key").getAsString());
        assertEquals(Set.of("iron", "iron_darker", "examplemod.trim.ruby"),
                paletted.getAsJsonObject("permutations").keySet(),
                "the base palette itself and a palette of another size are left out");
        assertEquals("minecraft:trim/iron_darker",
                paletted.getAsJsonObject("permutations").get("iron_darker").getAsString());

        JsonObject unrecolored = sources.get(1).getAsJsonObject();
        assertEquals("minecraft:directory", unrecolored.get("type").getAsString());
        assertEquals("trims/entity", unrecolored.get("source").getAsString());
        assertEquals("trims/entity/", unrecolored.get("prefix").getAsString());
    }

    @Test
    @DisplayName("a base palette without a palette of its size adds no recolored source")
    void definitionWithoutMatchingPalettes() {
        JsonArray sources = JsonParser.parseString(LegacyArmorTrims.definitionJson(
                Map.of("minecraft:trim_base", List.of("minecraft:trims/entity/humanoid/coast")),
                Map.of("minecraft:trim_base", 8))).getAsJsonObject().getAsJsonArray("sources");
        assertEquals(1, sources.size());
        assertEquals("minecraft:directory", sources.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    @DisplayName("built from 26.3's own assets, the atlas holds every sprite of 26.2's armor trim atlas")
    void rebuiltAtlasCoversTheRemovedOne() throws Exception {
        Path v262 = clientJar("26.2");
        Path v263 = clientJar("26.3");
        Assumptions.assumeTrue(v262 != null && v263 != null, "the 26.2 and 26.3 client jars are present");

        Set<String> removed = new TreeSet<>();
        try (ZipFile old = new ZipFile(v262.toFile())) {
            JsonObject definition = readJson(old, "assets/minecraft/atlases/armor_trims.json").getAsJsonObject();
            for (JsonElement source : definition.getAsJsonArray("sources")) {
                removed.addAll(sprites(source.getAsJsonObject(), Set.of()));
            }
        }
        assertEquals(36 * 16, removed.size(), "26.2 stitched 36 trim textures in 16 palettes");

        Set<String> rebuilt = new TreeSet<>();
        try (ZipFile current = new ZipFile(v263.toFile())) {
            Map<String, List<String>> textures = trimTexturesByBasePalette(current);
            Set<String> unrecolored = new TreeSet<>();
            textures.values().forEach(unrecolored::addAll);
            String definition = LegacyArmorTrims.definitionJson(textures, paletteSizes(current));
            for (JsonElement source : JsonParser.parseString(definition).getAsJsonObject().getAsJsonArray("sources")) {
                rebuilt.addAll(sprites(source.getAsJsonObject(), unrecolored));
            }
            Set<String> missing = new TreeSet<>(removed);
            missing.removeAll(rebuilt);
            assertEquals(Set.of(), missing, "every old sprite name resolves in the rebuilt atlas");
            assertTrue(rebuilt.contains("minecraft:trims/entity/humanoid/coast"),
                    "the unrecolored texture is there for an override without a palette");
        }
    }

    @Test
    @DisplayName("every member the helper resolves exists on 26.3 with that descriptor and is public")
    void membersResolveOnTheHost() throws Exception {
        Path v263 = clientJar("26.3");
        Path dataFixer = library("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
        Assumptions.assumeTrue(v263 != null && dataFixer != null, "the 26.3 client jar and its DataFixerUpper are present");

        List<Path> jars = new ArrayList<>(List.of(v263, dataFixer));
        Path neoForge = library("net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar");
        try (Classes host = new Classes(jars)) {
            assertAllResolve(host, "26.3");
        }
        if (neoForge != null) {
            try (Classes host = new Classes(List.of(neoForge, dataFixer))) {
                assertAllResolve(host, "NeoForge's patched 26.3");
            }
        }
    }

    private static void assertAllResolve(Classes host, String label) throws Exception {
        List<String> unresolved = new ArrayList<>();
        for (String[] member : LegacyArmorTrims.MEMBERS) {
            if (!host.resolves(member)) unresolved.add(String.join(" ", member));
        }
        for (String[] method : LegacyArmorTrims.IMPLEMENTED) {
            if (!host.hasMethod(method[0], method[1], method[2], false)) unresolved.add("implements " + String.join(" ", method));
        }
        assertEquals(List.of(), unresolved, "members missing on " + label);
    }

    // --- 26.2 and 26.3 assets ---------------------------------------------------------------

    /**
     * The sprite names a source adds, as {@code namespace:path}. A directory source over the trim
     * textures adds {@code trimTextures} themselves.
     */
    private static Set<String> sprites(JsonObject source, Set<String> trimTextures) {
        Set<String> names = new TreeSet<>();
        String type = source.get("type").getAsString();
        if (type.equals("minecraft:paletted_permutations")) {
            for (JsonElement texture : source.getAsJsonArray("textures")) {
                for (String suffix : source.getAsJsonObject("permutations").keySet()) {
                    names.add(qualified(texture.getAsString()) + "_" + suffix);
                }
            }
        } else if (type.equals("minecraft:directory")) {
            assertEquals("trims/entity", source.get("source").getAsString());
            names.addAll(trimTextures);
        }
        return names;
    }

    private static String qualified(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** What the helper reads from resources: each trim texture under its metadata's base palette. */
    private static Map<String, List<String>> trimTexturesByBasePalette(ZipFile jar) throws Exception {
        Map<String, List<String>> grouped = new TreeMap<>();
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith("assets/") || !name.endsWith(".png")) continue;
            String namespace = name.substring("assets/".length(), name.indexOf('/', "assets/".length()));
            String path = name.substring(("assets/" + namespace + "/").length());
            if (!path.startsWith(LegacyArmorTrims.TRIM_TEXTURES + "/")) continue;
            ZipEntry metadata = jar.getEntry(name + ".mcmeta");
            if (metadata == null) continue;
            JsonObject palette = readJson(jar, metadata.getName()).getAsJsonObject().getAsJsonObject("palette");
            if (palette == null) continue;
            String texture = namespace + ":" + path.substring("textures/".length(), path.length() - ".png".length());
            grouped.computeIfAbsent(qualified(palette.get("base_palette").getAsString()), k -> new ArrayList<>()).add(texture);
        }
        return grouped;
    }

    /** Every palette under {@code textures/palettes}, with its color count. */
    private static Map<String, Integer> paletteSizes(ZipFile jar) throws Exception {
        Map<String, Integer> sizes = new TreeMap<>();
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith("assets/") || !name.endsWith(".png")) continue;
            String namespace = name.substring("assets/".length(), name.indexOf('/', "assets/".length()));
            String path = name.substring(("assets/" + namespace + "/").length());
            if (!path.startsWith("textures/palettes/")) continue;
            try (InputStream in = jar.getInputStream(jar.getEntry(name))) {
                BufferedImage image = ImageIO.read(in);
                sizes.put(namespace + ":" + path.substring("textures/palettes/".length(), path.length() - ".png".length()),
                        image.getWidth() * image.getHeight());
            }
        }
        return sizes;
    }

    private static JsonElement readJson(ZipFile jar, String entry) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(jar.getEntry(entry)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    private static Path clientJar(String version) {
        return library("com/mojang/minecraft/" + version + "/minecraft-" + version + "-client.jar");
    }

    private static Path library(String path) {
        Path jar = Path.of(System.getProperty("user.home", ""), "Library/Application Support/PrismLauncher/libraries", path);
        return Files.isRegularFile(jar) ? jar : null;
    }

    /** Class structure from a set of jars and the running JDK, for resolving members the way a lookup does. */
    private static final class Classes implements AutoCloseable {
        private final List<ZipFile> jars = new ArrayList<>();

        Classes(List<Path> paths) throws Exception {
            for (Path path : paths) jars.add(new ZipFile(path.toFile()));
        }

        ClassNode node(String owner) throws Exception {
            for (ZipFile jar : jars) {
                ZipEntry entry = jar.getEntry(owner + ".class");
                if (entry != null) return parse(jar.getInputStream(entry).readAllBytes());
            }
            try (InputStream in = Object.class.getModule().getResourceAsStream(owner + ".class")) {
                if (in != null) return parse(in.readAllBytes());
            }
            InputStream fromJdk = ClassLoader.getSystemResourceAsStream(owner + ".class");
            if (fromJdk != null) {
                try (fromJdk) {
                    return parse(fromJdk.readAllBytes());
                }
            }
            return null;
        }

        /** A public member of a public class, static or not as the kind says, with the exact descriptor. */
        boolean resolves(String[] member) throws Exception {
            ClassNode owner = node(member[1]);
            if (owner == null || (owner.access & Opcodes.ACC_PUBLIC) == 0) return false;
            switch (member[0]) {
                case "static field":
                    for (FieldNode field : owner.fields) {
                        if (field.name.equals(member[2]) && field.desc.equals(member[3])
                                && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) == (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) {
                            return true;
                        }
                    }
                    return false;
                case "constructor":
                    return declares(owner, "<init>", member[3], false);
                case "static":
                    return declares(owner, member[2], member[3], true);
                case "virtual":
                    return hasMethod(member[1], member[2], member[3], true);
                default:
                    throw new AssertionError("unknown kind " + member[0]);
            }
        }

        /** An instance method on the class or one of its supertypes, as a virtual lookup finds it. */
        boolean hasMethod(String owner, String name, String desc, boolean mustBePublic) throws Exception {
            ClassNode node = node(owner);
            if (node == null) return false;
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(desc) && (method.access & Opcodes.ACC_STATIC) == 0
                        && (!mustBePublic || (method.access & Opcodes.ACC_PUBLIC) != 0)) {
                    return true;
                }
            }
            if (node.superName != null && hasMethod(node.superName, name, desc, mustBePublic)) return true;
            for (String iface : node.interfaces) {
                if (hasMethod(iface, name, desc, mustBePublic)) return true;
            }
            return false;
        }

        private static boolean declares(ClassNode owner, String name, String desc, boolean isStatic) {
            for (MethodNode method : owner.methods) {
                if (method.name.equals(name) && method.desc.equals(desc) && (method.access & Opcodes.ACC_PUBLIC) != 0
                        && ((method.access & Opcodes.ACC_STATIC) != 0) == isStatic) {
                    return true;
                }
            }
            return false;
        }

        private static ClassNode parse(byte[] bytes) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }

        @Override
        public void close() throws Exception {
            for (ZipFile jar : jars) jar.close();
        }
    }
}
