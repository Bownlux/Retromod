/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** #366: a removed registry key must not be matched to a similarly named one, as BLOCK_TYPE was to BLOCK. */
class FuzzyIdentityFieldTest {

    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String DATA_COMPONENTS = "net/minecraft/core/component/DataComponents";
    private static final String COMPONENT_TYPE = "Lnet/minecraft/core/component/DataComponentType;";
    private static final String EFFECTS = "net/minecraft/world/effect/MobEffects";
    private static final String BREWING = "net/minecraft/world/item/alchemy/PotionBrewing";
    private static final String RAID = "net/minecraft/world/entity/raid/Raid";
    private static final String ITEM_TAGS = "net/minecraft/tags/ItemTags";
    private static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String LIST = "Ljava/util/List;";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";

    @TempDir
    Path dir;

    @Test
    @DisplayName("A missing registry key stays missing instead of becoming its neighbor")
    void registryKeysAreNotGuessed() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        assertNull(resolver.resolveField(REGISTRIES, "BLOCK_TYPE", KEY), "BLOCK_TYPE must not become BLOCK");
    }

    @Test
    @DisplayName("Other renamed constants are still matched")
    void otherConstantsStillResolve() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        FuzzyMethodResolver.FieldInfo match = resolver.resolveField(ATTRIBUTES, "GENERIC_MAX_HEALTH", HOLDER);
        assertNotNull(match, "a constant of an ordinary type keeps its fuzzy match");
        assertEquals("MAX_HEALTH", match.name());
    }

    @Test
    @DisplayName("A removed tag key is not matched to another tag of the same type, as ItemTags.TOOLS was to WOOL")
    void tagKeysAreNotGuessed() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        assertNull(resolver.resolveField(ITEM_TAGS, "TOOLS", TAG_KEY, true), "every ItemTags key is a TagKey<Item>");
        assertNull(resolver.resolveField(ITEM_TAGS, "TALL_FLOWERS", TAG_KEY, true));
    }

    @Test
    @DisplayName("A removed data component is not matched to a component of another value type, as HIDE_ADDITIONAL_TOOLTIP was to TOOL")
    void componentsOfOtherValueTypesAreNotGuessed() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        assertNull(resolver.resolveField(DATA_COMPONENTS, "HIDE_ADDITIONAL_TOOLTIP", COMPONENT_TYPE),
                "a DataComponentType<Unit> read as DataComponentType<Tool> fails with ClassCastException");
    }

    @Test
    @DisplayName("A rename stays matched where every field of that type on the class has the same type arguments")
    void uniformTypeArgumentsStillResolve() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        FuzzyMethodResolver.FieldInfo match = resolver.resolveField(EFFECTS, "MOVEMENT_SPEED", HOLDER);
        assertNotNull(match, "every MobEffects constant is a Holder<MobEffect>");
        assertEquals("SPEED", match.name());
    }

    @Test
    @DisplayName("The real 26.3 client: HIDE_ADDITIONAL_TOOLTIP stays missing, GENERIC_MAX_HEALTH still finds MAX_HEALTH")
    void realHost263() throws Exception {
        Path client = Path.of(System.getProperty("user.home"),
                "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(client), "no local 26.3 client jar");
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(client);
        assertNull(resolver.resolveField(DATA_COMPONENTS, "HIDE_ADDITIONAL_TOOLTIP", COMPONENT_TYPE));
        assertNull(resolver.resolveField(ITEM_TAGS, "TOOLS", TAG_KEY, true));
        assertNull(resolver.resolveField("net/minecraft/tags/BlockTags", "OVERWORLD_CARVER_REPLACEABLES", TAG_KEY, true));
        FuzzyMethodResolver.FieldInfo match = resolver.resolveField(ATTRIBUTES, "GENERIC_MAX_HEALTH", HOLDER);
        assertNotNull(match);
        assertEquals("MAX_HEALTH", match.name());
    }

    @Test
    @DisplayName("A static read never becomes an instance field read, as PotionBrewing.ALLOWED_CONTAINERS did with containers")
    void staticReadsKeepToStaticFields() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        assertNull(resolver.resolveField(BREWING, "ALLOWED_CONTAINERS", LIST, true), "GETSTATIC of an instance field");
        FuzzyMethodResolver.FieldInfo match = resolver.resolveField(BREWING, "OLD_CONTAINERS", LIST, false);
        assertNotNull(match, "an instance read still finds the instance field");
        assertEquals("containers", match.name());
    }

    @Test
    @DisplayName("A field read whose best guess is the wrong kind of field stays missing, as Raid.id did against RAID_TIMEOUT_TICKS")
    void aWrongKindBestGuessIsNotReplacedByTheNextOne() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        assertNull(resolver.resolveField(RAID, "id", "I", false),
                "raidOmenLevel scores no better than the static RAID_TIMEOUT_TICKS, so it is no rename");
    }

    @Test
    @DisplayName("ItemStack.ITEM_NON_AIR_CODEC, a Codec<Holder<Item>>, is never guessed as CODEC, a Codec<ItemStack>")
    void itemNonAirCodecIsNotGuessed() throws Exception {
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
        assertNull(resolver.resolveField(ITEM_STACK, "ITEM_NON_AIR_CODEC", CODEC, true));
    }

    private Path hostJar() throws Exception {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            write(out, REGISTRIES, constants(REGISTRIES, KEY, "BLOCK", "ITEM"));
            write(out, ATTRIBUTES, constants(ATTRIBUTES, HOLDER, "MAX_HEALTH", "ARMOR"));
            // A slice of 26.3's DataComponents, with each field's real Signature.
            write(out, DATA_COMPONENTS, typed(DATA_COMPONENTS, COMPONENT_TYPE, Map.of(
                    "UNBREAKABLE", "net/minecraft/util/Unit", "CUSTOM_NAME", "net/minecraft/network/chat/Component",
                    "TOOLTIP_DISPLAY", "net/minecraft/world/item/component/TooltipDisplay",
                    "TOOL", "net/minecraft/world/item/component/Tool", "GLIDER", "net/minecraft/util/Unit")));
            write(out, BREWING, brewing());
            write(out, ITEM_TAGS, typed(ITEM_TAGS, TAG_KEY, Map.of("WOOL", "net/minecraft/world/item/Item",
                    "FLOWERS", "net/minecraft/world/item/Item", "SMALL_FLOWERS", "net/minecraft/world/item/Item")));
            write(out, RAID, raid());
            write(out, ITEM_STACK, typed(ITEM_STACK, CODEC, Map.of("CODEC", ITEM_STACK)));
            write(out, EFFECTS, typed(EFFECTS, HOLDER, Map.of(
                    "SLOWNESS", "net/minecraft/world/effect/MobEffect", "SPEED", "net/minecraft/world/effect/MobEffect")));
        }
        return jar;
    }

    // 26.3's Raid: a static int and an instance int whose names both contain "id".
    private static byte[] raid() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RAID, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "RAID_TIMEOUT_TICKS", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "raidOmenLevel", "I", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // 1.21.2's PotionBrewing: the container list became an instance field.
    private static byte[] brewing() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BREWING, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "containers", LIST,
                "Ljava/util/List<Lnet/minecraft/world/item/crafting/Ingredient;>;", null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Static constants of an erased type, each with its own type argument in the Signature.
    private static byte[] typed(String owner, String descriptor, Map<String, String> typeArguments) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        String raw = descriptor.substring(0, descriptor.length() - 1);
        typeArguments.forEach((name, argument) -> cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                name, descriptor, raw + "<L" + argument + ";>;", null).visitEnd());
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void write(JarOutputStream out, String name, byte[] bytes) throws Exception {
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] constants(String owner, String descriptor, String... names) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        for (String name : names) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, name, descriptor, null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
