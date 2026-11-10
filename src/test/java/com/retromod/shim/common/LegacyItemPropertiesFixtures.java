/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.loaderapi.LoaderApiFixtures;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Old mods that register item properties, compiled against each loader's old API, with a model whose overrides read them. */
public final class LegacyItemPropertiesFixtures {

    /** The mod's own class, which registers three properties and reads one back. */
    public static final String PROPS = "oldmod/Props";

    public enum Loader { FORGE_1201, NEOFORGE_1211, FABRIC_1211 }

    private LegacyItemPropertiesFixtures() {}

    /** A mod jar with the props class and a gem whose model overrides read two of them, nesting a library with the same. */
    public static byte[] modJar(Loader loader, Path work) throws Exception {
        Map<String, byte[]> classes = compile(work, loader);
        Map<String, byte[]> lib = files(loader, "fixturelib", classes, false);
        Map<String, byte[]> mod = files(loader, "gemmod", classes, true);
        mod.put(nestedPath(loader), LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    public static String nestedPath(Loader loader) {
        return loader == Loader.FABRIC_1211 ? "META-INF/jars/lib.jar" : "META-INF/jarjar/lib.jar";
    }

    private static Map<String, byte[]> files(Loader loader, String modId, Map<String, byte[]> classes, boolean nests) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        switch (loader) {
            case FORGE_1201 -> files.put("META-INF/mods.toml", forgeToml(modId));
            case NEOFORGE_1211 -> files.put("META-INF/neoforge.mods.toml", neoForgeToml(modId));
            case FABRIC_1211 -> files.put("fabric.mod.json", fabricMetadata(modId, nests));
        }
        // Each jar ships the props class in its own package, so the nested copy is told apart.
        String own = modId.equals("gemmod") ? PROPS : "fixturelib/Props";
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            if (!entry.getKey().startsWith("oldmod/")) continue;
            String name = own.substring(0, own.lastIndexOf('/')) + entry.getKey().substring("oldmod".length());
            files.put(name + ".class", relocate(entry.getValue(), "oldmod/", own.substring(0, own.lastIndexOf('/') + 1)));
        }
        // An unprefixed predicate names Minecraft's namespace, as new ResourceLocation("cooking") did.
        files.put("assets/" + modId + "/models/item/gem.json", text("{\"parent\":\"minecraft:item/generated\",\"textures\":"
                + "{\"layer0\":\"" + modId + ":item/gem\"},\"overrides\":["
                + "{\"predicate\":{\"" + modId + ":charge\":0.5},\"model\":\"" + modId + ":item/gem_charged\"},"
                + "{\"predicate\":{\"cooking\":1},\"model\":\"" + modId + ":item/gem_cooking\"}]}"));
        for (String variant : List.of("gem_charged", "gem_cooking")) {
            files.put("assets/" + modId + "/models/item/" + variant + ".json", text("{\"parent\":\"minecraft:item/generated\","
                    + "\"textures\":{\"layer0\":\"" + modId + ":item/" + variant + "\"}}"));
        }
        return files;
    }

    private static byte[] relocate(byte[] bytes, String from, String to) {
        if (from.equals(to)) return bytes;
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.commons.ClassRemapper(writer,
                new org.objectweb.asm.commons.Remapper() {
                    @Override
                    public String map(String name) {
                        return name.startsWith(from) ? to + name.substring(from.length()) : name;
                    }
                }), 0);
        return writer.toByteArray();
    }

    static byte[] forgeToml(String modId) {
        return text("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n");
    }

    static byte[] neoForgeToml(String modId) {
        return text("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId + "\"\nversion=\"1.0\"\n"
                + "[[dependencies." + modId + "]]\nmodId=\"neoforge\"\ntype=\"required\"\nversionRange=\"[21.1.95,)\"\nordering=\"NONE\"\n"
                + "side=\"BOTH\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\ntype=\"required\"\nversionRange=\"[1.21.1,1.21.2)\"\n"
                + "ordering=\"NONE\"\nside=\"BOTH\"\n");
    }

    static byte[] fabricMetadata(String modId, boolean nests) {
        return text("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                + "\"depends\":{\"minecraft\":\"~1.21.1\",\"fabricloader\":\">=0.15.0\"}"
                + (nests ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}");
    }

    /** The props class compiled against the loader's old API; the API stand-ins themselves are left out. */
    public static Map<String, byte[]> compile(Path work, Loader loader) throws Exception {
        Map<String, String> sources = loader == Loader.FABRIC_1211 ? fabricSources() : mojangSources(loader);
        Path src = work.resolve("src-" + loader);
        Path out = work.resolve("out-" + loader);
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the old mod");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the old mod must compile");
        Map<String, byte[]> classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    // Forge 1.20.1 registers through its public ItemPropertyFunction overload; NeoForge 1.21.1 mods also call the clamped one.
    private static Map<String, String> mojangSources(Loader loader) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/minecraft/world/item/Item.java", "package net.minecraft.world.item; public class Item {}");
        sources.put("net/minecraft/world/item/ItemStack.java",
                "package net.minecraft.world.item; public class ItemStack { public Item getItem() { return null; } }");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel {}");
        sources.put("net/minecraft/world/entity/LivingEntity.java", "package net.minecraft.world.entity; public class LivingEntity {}");
        sources.put("net/minecraft/resources/ResourceLocation.java", "package net.minecraft.resources; public class ResourceLocation {}");
        String args = "net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level,"
                + " net.minecraft.world.entity.LivingEntity entity, int seed";
        sources.put("net/minecraft/client/renderer/item/ItemPropertyFunction.java", "package net.minecraft.client.renderer.item;"
                + " public interface ItemPropertyFunction { float call(" + args + "); }");
        sources.put("net/minecraft/client/renderer/item/ClampedItemPropertyFunction.java", "package net.minecraft.client.renderer.item;"
                + " public interface ClampedItemPropertyFunction extends ItemPropertyFunction {"
                + " default float call(" + args + ") { return Math.max(0, Math.min(1, unclampedCall(stack, level, entity, seed))); }"
                + " float unclampedCall(" + args + "); }");
        String getProperty = loader == Loader.FORGE_1201 ? "net.minecraft.world.item.Item item" : "net.minecraft.world.item.ItemStack stack";
        sources.put("net/minecraft/client/renderer/item/ItemProperties.java", "package net.minecraft.client.renderer.item;"
                + " import net.minecraft.resources.ResourceLocation; import net.minecraft.world.item.Item;"
                + " public class ItemProperties {"
                + " public static void register(Item item, ResourceLocation id, ItemPropertyFunction f) {}"
                + " public static void register(Item item, ResourceLocation id, ClampedItemPropertyFunction f) {}"
                + " public static ItemPropertyFunction registerGeneric(ResourceLocation id, ItemPropertyFunction f) { return f; }"
                + " public static ItemPropertyFunction getProperty(" + getProperty + ", ResourceLocation id) { return null; } }");
        String clampedCall = loader == Loader.FORGE_1201
                // Forge 1.20.1's clamped register is private, so its mods passed a clamped lambda to the public one.
                ? "ItemProperties.register(item, clamped, (ItemPropertyFunction) (ClampedItemPropertyFunction) (s, l, e, seed) -> 3F);"
                : "ItemProperties.register(item, clamped, (ClampedItemPropertyFunction) (s, l, e, seed) -> 3F);";
        String lookup = loader == Loader.FORGE_1201 ? "stack.getItem()" : "stack";
        sources.put("oldmod/Props.java", "package oldmod;"
                + " import net.minecraft.client.renderer.item.*; import net.minecraft.resources.ResourceLocation;"
                + " import net.minecraft.world.item.*;"
                + " public final class Props {"
                + " public static void register(Item item, ResourceLocation charge, ResourceLocation clamped, ResourceLocation generic) {"
                + "  ItemProperties.register(item, charge, (ItemPropertyFunction) (s, l, e, seed) -> 0.75F);"
                + "  " + clampedCall
                + "  ItemProperties.registerGeneric(generic, (s, l, e, seed) -> e == null ? -2F : 2F); }"
                + " public static float read(ItemStack stack, ResourceLocation id) {"
                + "  ItemPropertyFunction f = ItemProperties.getProperty(" + lookup + ", id);"
                + "  return f == null ? Float.NaN : f.call(stack, null, null, 0); } }");
        return sources;
    }

    // Fabric mods name everything by intermediary: class_5272 ItemProperties, class_1800 and class_6395 its function types.
    private static Map<String, String> fabricSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("net/minecraft/class_1792.java", "package net.minecraft; public class class_1792 {}");
        sources.put("net/minecraft/class_1799.java", "package net.minecraft; public class class_1799 {}");
        sources.put("net/minecraft/class_638.java", "package net.minecraft; public class class_638 {}");
        sources.put("net/minecraft/class_1309.java", "package net.minecraft; public class class_1309 {}");
        sources.put("net/minecraft/class_2960.java", "package net.minecraft; public class class_2960 {}");
        String args = "class_1799 stack, class_638 level, class_1309 entity, int seed";
        sources.put("net/minecraft/class_1800.java", "package net.minecraft; public interface class_1800 { float call(" + args + "); }");
        sources.put("net/minecraft/class_6395.java", "package net.minecraft; public interface class_6395 extends class_1800 {"
                + " default float call(" + args + ") { return Math.max(0, Math.min(1, unclampedCall(stack, level, entity, seed))); }"
                + " float unclampedCall(" + args + "); }");
        sources.put("net/minecraft/class_5272.java", "package net.minecraft; public class class_5272 {"
                + " public static void method_27879(class_1792 item, class_2960 id, class_6395 f) {}"
                + " public static class_6395 method_27881(class_2960 id, class_6395 f) { return f; }"
                + " public static class_1800 method_27878(class_1799 stack, class_2960 id) { return null; } }");
        sources.put("net/fabricmc/fabric/api/object/builder/v1/client/model/FabricModelPredicateProviderRegistry.java",
                "package net.fabricmc.fabric.api.object.builder.v1.client.model; import net.minecraft.*;"
                        + " public final class FabricModelPredicateProviderRegistry {"
                        + " public static void register(class_1792 item, class_2960 id, class_6395 f) {}"
                        + " public static void register(class_2960 id, class_6395 f) {} }");
        sources.put("oldmod/Props.java", "package oldmod; import net.minecraft.*;"
                + " import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;"
                + " public final class Props {"
                + " public static void register(class_1792 item, class_2960 charge, class_2960 clamped, class_2960 generic) {"
                + "  class_5272.method_27879(item, charge, (s, l, e, seed) -> 0.75F);"
                + "  FabricModelPredicateProviderRegistry.register(item, clamped, (s, l, e, seed) -> 3F);"
                + "  class_5272.method_27881(generic, (s, l, e, seed) -> e == null ? -2F : 2F); }"
                + " public static float read(class_1799 stack, class_2960 id) {"
                + "  class_1800 f = class_5272.method_27878(stack, id);"
                + "  return f == null ? Float.NaN : f.call(stack, null, null, 0); } }");
        return sources;
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
