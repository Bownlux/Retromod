/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.google.gson.JsonParser;
import com.retromod.shim.common.LegacyItemComponentValues;
import com.retromod.shim.neoforge.LegacyDataMapComponentBridge.Gate;
import com.retromod.shim.neoforge.LegacyDataMapComponentBridge.Kind;
import com.retromod.shim.neoforge.LegacyDataMapComponentBridge.MapFile;
import com.retromod.shim.neoforge.LegacyDataMapComponentBridge.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** NeoForge 26.3 dropped the fuel and compost data maps; translated mods' files still give their items components. */
class LegacyDataMapComponentBridgeTest {

    private static final Predicate<String> NO_TAGS = tag -> false;

    private static final String PROJECTE_FUELS = "{\"values\":{"
            + "\"projecte:aeternalis_fuel\":{\"burn_time\":102400},"
            + "\"projecte:aeternalis_fuel_block\":{\"burn_time\":921600},"
            + "\"projecte:alchemical_coal\":{\"burn_time\":6400}}}";

    @Test
    @DisplayName("ProjectE's fuels keep their burn times")
    void projectEFuelsResolve() {
        Table fuels = table(Kind.FUEL, PROJECTE_FUELS);

        assertEquals(6400, fuels.valueFor("projecte:alchemical_coal", NO_TAGS));
        assertEquals(921600, fuels.valueFor("projecte:aeternalis_fuel_block", NO_TAGS));
        assertNull(fuels.valueFor("projecte:dark_matter", NO_TAGS));
    }

    @Test
    @DisplayName("a bare value and the value wrapper both parse")
    void bareAndWrappedValuesParse() {
        Table fuels = table(Kind.FUEL, "{\"values\":{\"example:bare\":200,"
                + "\"example:wrapped\":{\"value\":{\"burn_time\":200},\"replace\":true}}}");

        assertEquals(200, fuels.valueFor("example:bare", NO_TAGS));
        assertEquals(200, fuels.valueFor("example:wrapped", NO_TAGS));
    }

    @Test
    @DisplayName("Biomes O' Plenty's compost chances take vanilla's nearest tiers")
    void compostChancesTakeTheNearestTier() {
        Table compost = table(Kind.COMPOST, "{\"values\":{\"biomesoplenty:bramble\":{\"chance\":0.3},"
                + "\"biomesoplenty:barley\":{\"chance\":0.5},\"biomesoplenty:blue_hydrangea\":{\"chance\":0.65}}}");

        assertEquals("compostable/low", tier(compost, "biomesoplenty:bramble"));
        assertEquals("compostable/low_medium", tier(compost, "biomesoplenty:barley"));
        assertEquals("compostable/medium", tier(compost, "biomesoplenty:blue_hydrangea"));
    }

    @Test
    @DisplayName("a tag entry applies to its items, and the same file's removals win")
    void farmersDelightCabinets() {
        Table fuels = table(Kind.FUEL, "{\"remove\":[\"farmersdelight:crimson_cabinet\"],"
                + "\"values\":{\"#farmersdelight:cabinets/wooden\":{\"burn_time\":300}}}");
        Predicate<String> woodenCabinets = tag -> tag.equals("farmersdelight:cabinets/wooden");

        assertEquals(300, fuels.valueFor("farmersdelight:oak_cabinet", woodenCabinets));
        assertNull(fuels.valueFor("farmersdelight:crimson_cabinet", woodenCabinets),
                "crimson cabinets are in the tag but removed, as NeoForge applied them");
    }

    @Test
    @DisplayName("a later file with replace: true clears what an earlier jar gave")
    void replaceClearsEarlierFiles() {
        Table fuels = table(Kind.FUEL, "{\"values\":{\"example:log\":{\"burn_time\":300}}}",
                "{\"replace\":true,\"values\":{\"example:plank\":{\"burn_time\":100}}}");

        assertNull(fuels.valueFor("example:log", NO_TAGS));
        assertEquals(100, fuels.valueFor("example:plank", NO_TAGS));
    }

    @Test
    @DisplayName("conditional entries and conditional files are skipped")
    void conditionsAreSkipped() {
        Table fuels = table(Kind.FUEL, "{\"values\":{\"example:kept\":{\"burn_time\":50},"
                        + "\"example:conditional\":{\"value\":{\"burn_time\":50},"
                        + "\"neoforge:conditions\":[{\"type\":\"neoforge:mod_loaded\",\"modid\":\"x\"}]}}}");

        assertEquals(50, fuels.valueFor("example:kept", NO_TAGS));
        assertNull(fuels.valueFor("example:conditional", NO_TAGS));
        assertNull(LegacyDataMapComponentBridge.parse(JsonParser.parseString(
                "{\"neoforge:conditions\":[],\"values\":{\"example:any\":5}}"), Kind.FUEL));
    }

    @Test
    @DisplayName("only neoforge's own map files in translated jars are read")
    void onlyTranslatedNeoForgeFilesAreRead(@TempDir Path directory) throws IOException {
        Path translated = jar(directory.resolve("translated.jar"), true, Map.of(
                LegacyDataMapComponentBridge.FUEL_FILE, PROJECTE_FUELS,
                "data/othermod/data_maps/item/furnace_fuels.json", "{\"values\":{\"othermod:x\":5}}"));
        Path otherOnly = jar(directory.resolve("other.jar"), true, Map.of(
                "data/othermod/data_maps/item/furnace_fuels.json", "{\"values\":{\"othermod:x\":5}}"));
        Path untranslated = jar(directory.resolve("native.jar"), false, Map.of(
                LegacyDataMapComponentBridge.FUEL_FILE, PROJECTE_FUELS));

        Map<Kind, Table> tables = LegacyDataMapComponentBridge.collect(
                List.of(translated, otherOnly, untranslated), new Gate(true, true));

        Table fuels = tables.get(Kind.FUEL);
        assertEquals(6400, fuels.valueFor("projecte:alchemical_coal", NO_TAGS));
        assertNull(fuels.valueFor("othermod:x", NO_TAGS), "another namespace's map is that mod's own type");
        assertFalse(tables.containsKey(Kind.COMPOST));
        assertTrue(LegacyDataMapComponentBridge.readJar(untranslated).isEmpty(),
                "a jar Retromod did not translate is left to the host");
    }

    @Test
    @DisplayName("only a host without the data map type but with the component is bridged")
    void gateFollowsTheHostShape() {
        ClassNode components = classWithFields(LegacyDataMapComponentBridge.DATA_COMPONENTS,
                "COOKING_FUEL", "COMPOSTABLE");
        ClassNode furnaceFuel = classWithFields(LegacyDataMapComponentBridge.FURNACE_FUEL_TYPE);
        ClassNode compostable = classWithFields(LegacyDataMapComponentBridge.COMPOSTABLE_TYPE);

        Gate neoForge262 = Gate.probe(host(components, furnaceFuel, compostable));
        Gate noComponent = Gate.probe(host(classWithFields(LegacyDataMapComponentBridge.DATA_COMPONENTS)));
        Gate neoForge263 = Gate.probe(host(components));

        assertFalse(neoForge262.any(), "26.2 still reads both maps itself");
        assertFalse(noComponent.any(), "a host without the components has nothing to set");
        assertTrue(neoForge263.fuel() && neoForge263.compost(), "26.3 lost both maps");
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    private static Table table(Kind kind, String... files) {
        List<MapFile> parsed = new ArrayList<>();
        for (String file : files) parsed.add(LegacyDataMapComponentBridge.parse(JsonParser.parseString(file), kind));
        return new Table(parsed);
    }

    private static String tier(Table compost, String item) {
        return LegacyItemComponentValues.nearestTier(compost.valueFor(item, NO_TAGS).floatValue());
    }

    private static java.util.function.Function<String, ClassNode> host(ClassNode... classes) {
        return name -> {
            for (ClassNode node : classes) {
                if (node.name.equals(name)) return node;
            }
            return null;
        };
    }

    private static ClassNode classWithFields(String name, String... fields) {
        ClassNode node = new ClassNode();
        node.name = name;
        for (String field : fields) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field,
                    "Lnet/minecraft/core/component/DataComponentType;", null, null));
        }
        return node;
    }

    private static Path jar(Path file, boolean translated, Map<String, String> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (translated) manifest.getMainAttributes().putValue("Retromod-Transformed", "true");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
