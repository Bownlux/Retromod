/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.13 and newer read only {@code lang/<code>.json}, and ask for keys built from registry ids. A
 * 1.12 mod's {@code .lang} files become JSON with every old key kept and the modern key added
 * beside each old one whose registry id can be shown.
 */
class LegacyLangMigratorTest {

    private static final LegacyLangMigrator CONVERTER = new LegacyLangMigrator();
    private static final String ITEM = "net/minecraft/item/Item";
    private static final String STRING = "Ljava/lang/String;";

    private static MigrationContext context(String source, String target, MapResourceTree tree,
                                            Map<String, byte[]> classes) throws IOException {
        return MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("oldmod"))
                .classes(classes.keySet(), classes::get).build()
                .forRun(ModResourceMigrations.rootNamespaces(tree.names()));
    }

    private static JsonObject convert(MapResourceTree tree, String target, Map<String, byte[]> classes)
            throws IOException {
        MigrationContext context = context("1.12.2", target, tree, classes);
        assertTrue(CONVERTER.appliesTo(context), "a 1.12 mod on " + target);
        CONVERTER.apply(tree, context);
        return json(tree, "assets/oldmod/lang/en_us.json");
    }

    private static JsonObject json(ResourceTree tree, String name) throws IOException {
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    private static String value(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : null;
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.13", "1.21.1", "26.3"})
    @DisplayName("en_US.lang becomes en_us.json with every old key, and the original stays")
    void langBecomesJson(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_US.lang", "# Old Mod\nitemGroup.oldmod=Old Mod\n"
                        + "item.ruby.name=Ruby\ntile.ruby_block.name=Block of Ruby\n"
                        + "tooltip.oldmod.power=Power: %d of %s\n",
                "assets/oldmod/models/item/ruby.json", "{}",
                "assets/oldmod/blockstates/ruby_block.json", "{}"));

        JsonObject lang = convert(tree, target, Map.of());

        assertEquals("Old Mod", value(lang, "itemGroup.oldmod"));
        assertEquals("Ruby", value(lang, "item.ruby.name"), "the mod's code still asks for old keys");
        assertEquals("Ruby", value(lang, "item.oldmod.ruby"));
        assertEquals("Block of Ruby", value(lang, "block.oldmod.ruby_block"));
        assertEquals("Power: %d of %s", value(lang, "tooltip.oldmod.power"),
                "newer games convert format codes themselves");
        assertTrue(tree.exists("assets/oldmod/lang/en_US.lang"));
    }

    @Test
    @DisplayName("A #PARSE_ESCAPES file is read as Java properties, and a byte order mark is dropped")
    void escapesAndByteOrderMark() throws IOException {
        MapResourceTree tree = new MapResourceTree(Map.of(
                "assets/oldmod/lang/en_us.lang", bytes("\uFEFF#PARSE_ESCAPES\n"
                        + "tip.oldmod.two=First line\\nSecond line\ntip.oldmod.accent=Caf\\u00e9\n"),
                "assets/oldmod/lang/fr_FR.lang", bytes("\uFEFFtip.oldmod.raw=Keeps \\n as written\n")));

        JsonObject english = convert(tree, "26.3", Map.of());
        JsonObject french = json(tree, "assets/oldmod/lang/fr_fr.json");

        assertEquals("First line\nSecond line", value(english, "tip.oldmod.two"));
        assertEquals("Café", value(english, "tip.oldmod.accent"));
        assertEquals("Keeps \\n as written", value(french, "tip.oldmod.raw"),
                "without the marker 1.12 kept backslashes as they were");
        assertFalse(english.keySet().stream().anyMatch(key -> key.startsWith("\uFEFF")));
    }

    @Test
    @DisplayName("A broken escape costs only its own line, and the mod's other languages still convert")
    void brokenEscapeKeepsTheLanguage() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "item.ruby.name=Ruby\n",
                "assets/oldmod/lang/de_de.lang", "#PARSE_ESCAPES\nitem.ruby.name=Rubin \\u00zz\n"
                        + "tip.oldmod.two=Erste\\nZweite\n",
                "assets/oldmod/models/item/ruby.json", "{}"));

        ModResourceMigrations.standard().run(tree, context("1.12.2", "26.3", tree, Map.of()));

        JsonObject german = json(tree, "assets/oldmod/lang/de_de.json");
        assertEquals("Ruby", value(json(tree, "assets/oldmod/lang/en_us.json"), "item.oldmod.ruby"));
        assertEquals("Rubin \\u00zz", value(german, "item.oldmod.ruby"),
                "the line with the broken escape keeps its text as written");
        assertEquals("Erste\nZweite", value(german, "tip.oldmod.two"),
                "every other line is still read with its escapes");
    }

    @Test
    @DisplayName("A JSON file the mod ships for a language, in any case, is never replaced")
    void shippedJsonWins() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "item.ruby.name=Ruby\n",
                "assets/oldmod/lang/en_us.json", "{\"item.oldmod.ruby\":\"Shipped Ruby\"}",
                "assets/oldmod/lang/de_DE.lang", "item.ruby.name=Rubin\n",
                "assets/oldmod/lang/de_DE.json", "{}"));

        assertEquals(0, CONVERTER.apply(tree, context("1.12.2", "26.3", tree, Map.of())));
        assertEquals("{\"item.oldmod.ruby\":\"Shipped Ruby\"}", text(tree.read("assets/oldmod/lang/en_us.json")));
        assertFalse(tree.exists("assets/oldmod/lang/de_de.json"));
    }

    @Test
    @DisplayName("A registry name and translation key set on one object pair the old key with the id")
    void classPairsKeyWithRegistryName() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "item.ruby_gem.name=Ruby\nitem.ruby.name=Unused Ruby\n",
                "assets/oldmod/models/item/ruby.json", "{}"));

        JsonObject lang = convert(tree, "26.3", Map.of("oldmod/Items.class", namedItemClass()));

        assertEquals("Ruby", value(lang, "item.oldmod.ruby"),
                "the pair from the classes wins over a key that only shares the id's spelling");
    }

    @Test
    @DisplayName("A base class that passes its argument to both setters pairs each constant its callers pass")
    void constructorArgumentIdentity() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "item.sapphire.name=Sapphire\n"));
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("oldmod/ItemBase.class", itemBaseClass());
        classes.put("oldmod/Gems.class", gemsClass());

        JsonObject lang = convert(tree, "26.3", classes);

        assertEquals("Sapphire", value(lang, "item.oldmod.sapphire"),
                "no asset shows this id; only new ItemBase(\"sapphire\") does");
    }

    @Test
    @DisplayName("An entity's key follows the id given to registerModEntity with its name")
    void entityFromRegisterModEntity() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "entity.oldmod.FrostGiant.name=Frost Giant\n"));

        JsonObject lang = convert(tree, "26.3", Map.of("oldmod/Entities.class", entityClass()));

        assertEquals("Frost Giant", value(lang, "entity.oldmod.frost_giant"));
    }

    @Test
    @DisplayName("A potion's name key pairs with its registry name, and brewed potion keys move to the item keys")
    void effectsAndPotions() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "potion.chill=Chill\n"
                        + "potion.effect.chill=Potion of Chill\nsplash_potion.effect.chill=Splash Potion of Chill\n"
                        + "lingering_potion.effect.chill=Lingering Potion of Chill\n"
                        + "tipped_arrow.effect.chill=Arrow of Chill\npotion.potency.4=V\n"));

        JsonObject lang = convert(tree, "26.3", Map.of("oldmod/Effects.class", potionClass()));

        assertEquals("Chill", value(lang, "effect.oldmod.frost"));
        assertEquals("Potion of Chill", value(lang, "item.minecraft.potion.effect.chill"));
        assertEquals("Splash Potion of Chill", value(lang, "item.minecraft.splash_potion.effect.chill"));
        assertEquals("Lingering Potion of Chill", value(lang, "item.minecraft.lingering_potion.effect.chill"));
        assertEquals("Arrow of Chill", value(lang, "item.minecraft.tipped_arrow.effect.chill"));
        assertNull(value(lang, "item.minecraft.potion.potency.4"), "potency names kept their key");
    }

    @Test
    @DisplayName("Names written with the mod id, a colon, or camel case match the id they name")
    void identityForms() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "item.oldmod:ruby.name=Ruby\n"
                        + "item.oldmod.emerald_shard.name=Emerald Shard\ntile.CrystalLamp.name=Crystal Lamp\n"
                        + "tile.oldmod_marble.name=Marble\nitem.sleeping_bag.white.name=White Sleeping Bag\n",
                "assets/oldmod/models/item/ruby.json", "{}",
                "assets/oldmod/models/item/emerald_shard.json", "{}",
                "assets/oldmod/blockstates/crystal_lamp.json", "{}",
                "assets/oldmod/blockstates/marble.json", "{}",
                "assets/oldmod/models/item/sleeping_bag.json", "{}"));

        JsonObject lang = convert(tree, "1.21.1", Map.of());

        assertEquals("Ruby", value(lang, "item.oldmod.ruby"));
        assertEquals("Emerald Shard", value(lang, "item.oldmod.emerald_shard"));
        assertEquals("Crystal Lamp", value(lang, "block.oldmod.crystal_lamp"));
        assertEquals("Marble", value(lang, "block.oldmod.marble"));
        assertNull(value(lang, "item.oldmod.sleeping_bag"),
                "a metadata variant's name is not the item's name");
        assertEquals(9, lang.size(), "five old keys and four modern ones: " + lang.keySet());
    }

    @Test
    @DisplayName("A modern key the .lang file already has is kept as written")
    void ownModernKeyWins() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "item.ruby.name=Old Ruby\nitem.oldmod.ruby=New Ruby\n",
                "assets/oldmod/models/item/ruby.json", "{}"));

        assertEquals("New Ruby", value(convert(tree, "26.3", Map.of()), "item.oldmod.ruby"));
    }

    @Test
    @DisplayName("Language files are written only in the mod's own namespaces")
    void neverInSharedNamespaces() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_us.lang", "item.ruby.name=Ruby\n",
                "assets/minecraft/lang/en_US.lang", "item.diamond.name=Shiny Diamond\n",
                "assets/othermod/lang/en_US.lang", "material.moonstone.name=Moonstone\n"));

        CONVERTER.apply(tree, context("1.12.2", "26.3", tree, Map.of()));

        assertFalse(tree.exists("assets/minecraft/lang/en_us.json"), "it would reach vanilla's keys");
        assertTrue(tree.exists("assets/othermod/lang/en_us.json"),
                "a folder the jar ships for another mod is its own to write in, and language files merge");
    }

    @Test
    @DisplayName("A 1.13 mod and a host that still reads .lang are left alone, and a second run changes nothing")
    void gatedAndIdempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_US.lang", "item.ruby.name=Ruby\n",
                "assets/oldmod/models/item/ruby.json", "{}"));

        assertFalse(CONVERTER.appliesTo(context("1.13", "26.3", tree, Map.of())));
        assertFalse(CONVERTER.appliesTo(context("1.12.2", "1.12.2", tree, Map.of())),
                "a 1.12 host reads the .lang file as it is");
        assertFalse(CONVERTER.appliesTo(MigrationContext.builder("26.3").build()));

        assertEquals(1, CONVERTER.apply(tree, context("1.12.2", "26.3", tree, Map.of())));
        byte[] first = tree.read("assets/oldmod/lang/en_us.json");
        assertEquals(0, CONVERTER.apply(tree, context("1.12.2", "26.3", tree, Map.of())));
        assertArrayEquals(first, tree.read("assets/oldmod/lang/en_us.json"));
    }

    @Test
    @DisplayName("An extracted directory, holding its own classes, comes out the same as an in-memory tree")
    void directoryAndMapAgree(@TempDir Path directory) throws IOException {
        Map<String, byte[]> resources = entries("assets/oldmod/lang/en_us.lang", "item.ruby_gem.name=Ruby\n");
        Map<String, byte[]> withClasses = new LinkedHashMap<>(resources);
        withClasses.put("oldmod/Items.class", namedItemClass());
        writeDirectory(directory, withClasses);
        MapResourceTree inMemory = new MapResourceTree(resources);

        CONVERTER.apply(DirectoryResourceTree.open(directory),
                context("1.12.2", "26.3", inMemory, Map.of()));
        CONVERTER.apply(inMemory, context("1.12.2", "26.3", inMemory,
                Map.of("oldmod/Items.class", namedItemClass())));

        assertEquals(text(inMemory.read("assets/oldmod/lang/en_us.json")),
                text(DirectoryResourceTree.open(directory).read("assets/oldmod/lang/en_us.json")));
        assertEquals("Ruby", value(json(inMemory, "assets/oldmod/lang/en_us.json"), "item.oldmod.ruby"));
    }

    @Test
    @DisplayName("On 26.3 a key binding category named in a .lang file keeps its name")
    void keyCategoryReadsTheConvertedFile() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/lang/en_US.lang", "key.categories.oldmod=Old Mod Keys\n"));
        MigrationContext context = MigrationContext.builder("26.3").sourceMcVersion("1.12.2")
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("oldmod"))
                .classes(List.of("oldmod/Keys.class"), name -> keyBindingClass()).build();

        ModResourceMigrations.standard().run(tree, context);

        String categories = text(tree.read("assets/" + LegacyKeyCategoryNames.NAMESPACE + "/lang/en_us.json"));
        assertTrue(categories.contains("Old Mod Keys"), categories);
    }

    // ------------------------------------------------------------------ classes as a 1.12 mod built them

    /** {@code new Item().setRegistryName("ruby").setTranslationKey("ruby_gem")}, by SRG name. */
    private static byte[] namedItemClass() {
        return staticMethodClass("oldmod/Items", method -> {
            method.visitTypeInsn(Opcodes.NEW, ITEM);
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM, "<init>", "()V", false);
            method.visitLdcInsn("ruby");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "setRegistryName",
                    "(" + STRING + ")Lnet/minecraftforge/registries/IForgeRegistryEntry;", false);
            method.visitTypeInsn(Opcodes.CHECKCAST, ITEM);
            method.visitLdcInsn("ruby_gem");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "func_77655_b", "(" + STRING + ")L" + ITEM + ";", false);
            method.visitInsn(Opcodes.POP);
        });
    }

    /** {@code ItemBase(String name) { setRegistryName(name); setTranslationKey(name); }} */
    private static byte[] itemBaseClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "oldmod/ItemBase", null, ITEM, null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + STRING + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM, "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "oldmod/ItemBase", "setRegistryName",
                "(" + STRING + ")Lnet/minecraftforge/registries/IForgeRegistryEntry;", false);
        init.visitInsn(Opcodes.POP);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "oldmod/ItemBase", "func_77655_b",
                "(" + STRING + ")L" + ITEM + ";", false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code new ItemBase("sapphire")}. */
    private static byte[] gemsClass() {
        return staticMethodClass("oldmod/Gems", method -> {
            method.visitTypeInsn(Opcodes.NEW, "oldmod/ItemBase");
            method.visitInsn(Opcodes.DUP);
            method.visitLdcInsn("sapphire");
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "oldmod/ItemBase", "<init>", "(" + STRING + ")V", false);
            method.visitInsn(Opcodes.POP);
        });
    }

    /** {@code EntityRegistry.registerModEntity(new ResourceLocation("oldmod", "frost_giant"), ..., "FrostGiant", ...)}. */
    private static byte[] entityClass() {
        String location = "net/minecraft/util/ResourceLocation";
        return staticMethodClass("oldmod/Entities", method -> {
            method.visitTypeInsn(Opcodes.NEW, location);
            method.visitInsn(Opcodes.DUP);
            method.visitLdcInsn("oldmod");
            method.visitLdcInsn("frost_giant");
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, location, "<init>", "(" + STRING + STRING + ")V", false);
            method.visitLdcInsn(Type.getObjectType("oldmod/EntityFrostGiant"));
            method.visitLdcInsn("FrostGiant");
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitIntInsn(Opcodes.BIPUSH, 64);
            method.visitInsn(Opcodes.ICONST_3);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/common/registry/EntityRegistry",
                    "registerModEntity", "(L" + location + ";Ljava/lang/Class;" + STRING + "ILjava/lang/Object;IIZ)V",
                    false);
        });
    }

    /** {@code new PotionFrost().setRegistryName("frost").setPotionName("potion.chill")}. */
    private static byte[] potionClass() {
        String potion = "net/minecraft/potion/Potion";
        return staticMethodClass("oldmod/Effects", method -> {
            method.visitTypeInsn(Opcodes.NEW, "oldmod/PotionFrost");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "oldmod/PotionFrost", "<init>", "()V", false);
            method.visitLdcInsn("frost");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "oldmod/PotionFrost", "setRegistryName",
                    "(" + STRING + ")Lnet/minecraftforge/registries/IForgeRegistryEntry;", false);
            method.visitTypeInsn(Opcodes.CHECKCAST, potion);
            method.visitLdcInsn("potion.chill");
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, potion, "func_76390_b", "(" + STRING + ")L" + potion + ";", false);
            method.visitInsn(Opcodes.POP);
        });
    }

    /** {@code new KeyMapping("key.oldmod.dash", 0, "key.categories.oldmod")}, as the class bridge leaves it. */
    private static byte[] keyBindingClass() {
        String keyMapping = "net/minecraft/client/KeyMapping";
        return staticMethodClass("oldmod/Keys", method -> {
            method.visitTypeInsn(Opcodes.NEW, keyMapping);
            method.visitInsn(Opcodes.DUP);
            method.visitLdcInsn("key.oldmod.dash");
            method.visitInsn(Opcodes.ICONST_0);
            method.visitLdcInsn("key.categories.oldmod");
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, keyMapping, "<init>", "(" + STRING + "I" + STRING + ")V", false);
            method.visitInsn(Opcodes.POP);
        });
    }

    private static byte[] staticMethodClass(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()V",
                null, null);
        method.visitCode();
        body.accept(method);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
