/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** What the food helper writes, and the Minecraft members it reaches by reflection on the hosts it serves. */
class LegacyAnimalFoodTagsTest {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home"),
            "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft");

    @TempDir
    Path folder;

    @AfterEach
    void tearDown() {
        LegacyAnimalFoodTags.resetForTests();
    }

    @Test
    @DisplayName("each tag a mod added to is written under minecraft, merged with the host's tag and with other mods'")
    void writesEachTagWithTheAddedItems() throws IOException {
        LegacyAnimalFoodTags.addForTests("chicken_food", List.of("farmersdelight:rice", "farmersdelight:cabbage_seeds"));
        LegacyAnimalFoodTags.addForTests("parrot_food", List.of("farmersdelight:tomato_seeds"));
        Files.createDirectories(folder.resolve("data/minecraft/tags/item/stale"));

        LegacyAnimalFoodTags.writePack(folder, 94);

        assertEquals("{\"replace\":false,\"values\":[{\"id\":\"farmersdelight:cabbage_seeds\",\"required\":false},"
                        + "{\"id\":\"farmersdelight:rice\",\"required\":false}]}",
                Files.readString(folder.resolve("data/minecraft/tags/item/chicken_food.json")));
        assertTrue(Files.exists(folder.resolve("data/minecraft/tags/item/parrot_food.json")));
        assertFalse(Files.exists(folder.resolve("data/minecraft/tags/item/stale")), "an earlier session's files are cleared");
        assertTrue(Files.readString(folder.resolve("pack.mcmeta")).contains("\"pack_format\":94"));
    }

    @Test
    @DisplayName("1.20.5 and 1.20.6 read the plural tag folder")
    void pluralFolderBefore121() throws IOException {
        LegacyAnimalFoodTags.addForTests("pig_food", List.of("farmersdelight:tomato"));

        LegacyAnimalFoodTags.writePack(folder, 41);

        assertTrue(Files.exists(folder.resolve("data/minecraft/tags/items/pig_food.json")));
    }

    @Test
    @DisplayName("outside an embed the pack has the plain name; each mod's copy adds its own key")
    void packIdFollowsTheEmbed() {
        assertEquals("retromod_legacy_item_tags", LegacyAnimalFoodTags.packId());
    }

    @Test
    @DisplayName("every member the helper reaches by name is on the real 26.2 and 26.3 clients")
    void membersExistOnTheRealHosts() throws IOException {
        Path jar262 = LIBRARIES.resolve("26.2/minecraft-26.2-client.jar");
        Path jar263 = LIBRARIES.resolve("26.3/minecraft-26.3-client.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar262) && Files.isRegularFile(jar263),
                "Minecraft 26.2 and 26.3 are not installed in Prism's libraries");
        for (Path jar : List.of(jar262, jar263)) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (String tag : List.of("CHICKEN_FOOD", "PIG_FOOD", "CAT_FOOD", "PARROT_FOOD", "VILLAGER_PICKS_UP")) {
                    assertField(zip, "net/minecraft/tags/ItemTags", tag);
                }
                assertField(zip, "net/minecraft/core/registries/BuiltInRegistries", "ITEM");
                assertMethod(zip, "net/minecraft/world/item/crafting/Ingredient", "of",
                        "(Lnet/minecraft/core/HolderSet;)Lnet/minecraft/world/item/crafting/Ingredient;");
                assertMethod(zip, "net/minecraft/world/item/crafting/Ingredient", "items", "()Ljava/util/stream/Stream;");
                assertMethod(zip, "net/minecraft/core/HolderSet", "emptyNamed",
                        "(Lnet/minecraft/core/HolderOwner;Lnet/minecraft/tags/TagKey;)Lnet/minecraft/core/HolderSet$Named;");
                assertMethod(zip, "net/minecraft/core/HolderGetter", "get", "(Lnet/minecraft/tags/TagKey;)Ljava/util/Optional;");
                assertMethod(zip, "net/minecraft/core/Registry", "getKey", "(Ljava/lang/Object;)Lnet/minecraft/resources/Identifier;");
                assertMethod(zip, "net/minecraft/core/Holder$Reference", "is", "(Lnet/minecraft/tags/TagKey;)Z");
                assertMethod(zip, "net/minecraft/world/item/Item", "builtInRegistryHolder", "()Lnet/minecraft/core/Holder$Reference;");
            }
        }
        // The villager food component that replaced FOOD_POINTS, and the initializer list items are built from.
        try (ZipFile zip = new ZipFile(jar263.toFile())) {
            assertField(zip, "net/minecraft/core/registries/BuiltInRegistries", "DATA_COMPONENT_INITIALIZERS");
            assertField(zip, "net/minecraft/core/component/DataComponents", "VILLAGER_FOOD");
            assertMethod(zip, "net/minecraft/core/component/DataComponentInitializers", "add",
                    "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/component/DataComponentInitializers$Initializer;)V");
            assertMethod(zip, "net/minecraft/core/component/DataComponentInitializers$Initializer", "run",
                    "(Lnet/minecraft/core/component/DataComponentMap$Builder;Lnet/minecraft/core/HolderLookup$Provider;"
                            + "Lnet/minecraft/resources/ResourceKey;)V");
            assertMethod(zip, "net/minecraft/core/component/DataComponentMap$Builder", "set",
                    "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Lnet/minecraft/core/component/DataComponentMap$Builder;");
            assertMethod(zip, "net/minecraft/world/food/VillagerFood", "<init>", "(I)V");
            assertMethod(zip, "net/minecraft/core/Holder$Reference", "areComponentsBound", "()Z");
        }
    }

    private static void assertField(ZipFile zip, String owner, String name) throws IOException {
        for (FieldNode field : read(zip, owner).fields) if (field.name.equals(name)) return;
        fail(owner + "." + name + " is missing from " + zip.getName());
    }

    private static void assertMethod(ZipFile zip, String owner, String name, String desc) throws IOException {
        for (MethodNode method : read(zip, owner).methods) if (method.name.equals(name) && method.desc.equals(desc)) return;
        fail(owner + "." + name + desc + " is missing from " + zip.getName());
    }

    private static ClassNode read(ZipFile zip, String owner) throws IOException {
        ZipEntry entry = zip.getEntry(owner + ".class");
        assertNotNull(entry, owner + " is missing from " + zip.getName());
        ClassNode node = new ClassNode();
        try (InputStream in = zip.getInputStream(entry)) {
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
        }
        return node;
    }
}
