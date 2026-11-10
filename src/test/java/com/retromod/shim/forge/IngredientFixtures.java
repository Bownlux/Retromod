/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Forge 1.20.1 ingredient classes as an old mod ships them, and a Forge 26.2 host with the shapes they reach. */
final class IngredientFixtures {

    static final String OLD = "com/retromod/shim/forge/oldingredient/";
    static final String HOST = "com/retromod/shim/forge/ingredienthost/";
    static final String ITEM_INGREDIENT = OLD + "ItemIngredient";
    static final String ITEM_SERIALIZER = ITEM_INGREDIENT + "$Serializer";
    static final String BROKEN_SERIALIZER = OLD + "BrokenSerializer";
    static final String DIRECT_INGREDIENT = OLD + "DirectIngredient";
    static final String CRAFTING_HELPER = ForgeRecipeConditionSynthetics.CRAFTING_HELPER;
    static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String REGISTER_EVENT = "net/minecraftforge/registries/RegisterEvent";
    static final String KEYS = "net/minecraftforge/registries/ForgeRegistries$Keys";
    static final String MOD_CONTAINER = "net/minecraftforge/fml/ModContainer";
    static final String MOD_LIST = "net/minecraftforge/fml/ModList";
    static final String MOD_LOADING_CONTEXT = "net/minecraftforge/fml/ModLoadingContext";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String DYNAMIC_OPS = "com/mojang/serialization/DynamicOps";
    static final String MAP_LIKE = "com/mojang/serialization/MapLike";
    static final String RECORD_BUILDER = "com/mojang/serialization/RecordBuilder";
    static final String DATA_RESULT = "com/mojang/serialization/DataResult";
    static final String JSON_OPS = "com/mojang/serialization/JsonOps";
    static final String PAIR = "com/mojang/datafixers/util/Pair";

    // Each stand-in under the host name it serves; the last two only name a compiled stub.
    private static final Map<String, String> HOST_NAMES = Map.ofEntries(
            Map.entry(HOST + "Identifier", IDENTIFIER),
            Map.entry(HOST + "ResourceKey", "net/minecraft/resources/ResourceKey"),
            Map.entry(HOST + "ForgeRegistries", "net/minecraftforge/registries/ForgeRegistries"),
            Map.entry(HOST + "ForgeRegistries$Keys", KEYS),
            Map.entry(HOST + "RegisterEvent", REGISTER_EVENT),
            Map.entry(HOST + "ModContainer", MOD_CONTAINER),
            Map.entry(HOST + "ModList", MOD_LIST),
            Map.entry(HOST + "ModLoadingContext", MOD_LOADING_CONTEXT),
            Map.entry(HOST + "Ingredient", INGREDIENT),
            Map.entry(HOST + "AbstractIngredient", LegacyIngredientSerializerBridge.HOST_ABSTRACT),
            Map.entry(HOST + "Pair", PAIR),
            Map.entry(HOST + "DynamicOps", DYNAMIC_OPS),
            Map.entry(HOST + "MapLike", MAP_LIKE),
            Map.entry(HOST + "DataResult", DATA_RESULT),
            Map.entry(HOST + "DataResult$Result", DATA_RESULT + "$Result"),
            Map.entry(HOST + "RecordBuilder", RECORD_BUILDER),
            Map.entry(HOST + "MapCodec", MAP_CODEC),
            Map.entry(HOST + "JsonOps", JSON_OPS),
            Map.entry(HOST + "IIngredientSerializer", LegacyIngredientSerializerBridge.HOST_SERIALIZER),
            Map.entry(HOST + "FriendlyByteBuf", "net/minecraft/network/FriendlyByteBuf"));
    private static final List<String> NAMED_ONLY = List.of(HOST + "IIngredientSerializer", HOST + "FriendlyByteBuf");
    // The test types the stand-ins name for EventBus 7 and FML.
    private static final Map<String, String> BUS_NAMES = Map.of(
            "com/retromod/shim/forge/eb7/BusGroup", EventBus7Host.BUS_GROUP,
            "com/retromod/shim/forge/eb7/EventBus", EventBus7Host.EVENT_BUS,
            "com/retromod/shim/forge/caphost/IModBusEvent", "net/minecraftforge/fml/event/IModBusEvent");
    // The old API under the Forge 1.20.1 and Minecraft names a mod compiled against.
    private static final Map<String, String> OLD_NAMES = Map.of(
            OLD + "OldIngredientSerializer", LegacyIngredientSerializerBridge.OLD_SERIALIZER,
            OLD + "OldAbstractIngredient", LegacyIngredientSerializerBridge.OLD_ABSTRACT,
            OLD + "OldValue", LegacyIngredientSerializerBridge.OLD_VALUE,
            OLD + "OldCraftingHelper", CRAFTING_HELPER);

    private IngredientFixtures() {}

    /** One old mod class, compiled here, as a Forge 1.20.1 jar would hold it. */
    static byte[] oldModClass(String testName) throws IOException {
        Map<String, String> names = new HashMap<>(HOST_NAMES);
        names.putAll(BUS_NAMES);
        names.putAll(OLD_NAMES);
        return CapabilityHostFixtures.remap(read(testName), new SimpleRemapper(names));
    }

    /** The old mod's classes, by name: the ingredient, its serializer and a serializer whose read throws. */
    static Map<String, byte[]> oldMod() throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (String name : List.of(ITEM_INGREDIENT, ITEM_SERIALIZER, BROKEN_SERIALIZER, DIRECT_INGREDIENT)) {
            classes.put(name, oldModClass(name));
        }
        return classes;
    }

    /** The stand-ins that behave like Forge 26.2 and DataFixerUpper, under their host names. */
    static void defineHost(EventBus7Host host) throws IOException {
        Map<String, String> names = new HashMap<>(HOST_NAMES);
        names.putAll(BUS_NAMES);
        Remapper remapper = new SimpleRemapper(names);
        for (Map.Entry<String, String> standIn : HOST_NAMES.entrySet()) {
            if (NAMED_ONLY.contains(standIn.getKey())) continue;
            host.define(standIn.getValue(), CapabilityHostFixtures.remap(read(standIn.getKey()), remapper));
        }
    }

    /** One translated mod: its classes, and each registered synthetic relocated under {@code prefix} as embedded. */
    static ClassLoader mod(ClassLoader host, RetromodTransformer transformer, String prefix,
            Map<String, byte[]> modClasses) {
        // The transform first: it grows the generated base a constructor for each super call it meets.
        Map<String, byte[]> transformed = new HashMap<>();
        modClasses.forEach((name, bytes) -> transformed.put(name, transformer.transformClass(bytes, name + ".class")));
        Map<String, byte[]> embedded = new HashMap<>(transformer.getSyntheticClasses());
        Remapper relocation = new Remapper() {
            @Override
            public String map(String internalName) {
                return embedded.containsKey(internalName) ? prefix + internalName : internalName;
            }
        };
        Map<String, byte[]> classes = new HashMap<>();
        embedded.forEach((name, bytes) -> classes.put(prefix + name, CapabilityHostFixtures.remap(bytes, relocation)));
        transformed.forEach((name, bytes) -> classes.put(name, CapabilityHostFixtures.remap(bytes, relocation)));
        return new LegacyEventRuntimeTest.Loader(host, classes);
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] read(String internalName) throws IOException {
        try (InputStream in = IngredientFixtures.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing test class " + internalName);
            return in.readAllBytes();
        }
    }
}
