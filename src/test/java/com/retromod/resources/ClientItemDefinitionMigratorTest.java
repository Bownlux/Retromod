/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Old item models become 1.21.4 client item definitions that still do what the model did: bows
 * animate, custom model data picks a variant, spawn eggs keep their colors, and an item drawn by
 * code shows its icon instead of the missing model.
 */
class ClientItemDefinitionMigratorTest {

    private static final String BOW = "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oldmod:item/ruby_bow\"},"
            + "\"overrides\":["
            + "{\"predicate\":{\"pulling\":1},\"model\":\"oldmod:item/ruby_bow_pulling_0\"},"
            + "{\"predicate\":{\"pulling\":1,\"pull\":0.65},\"model\":\"oldmod:item/ruby_bow_pulling_1\"},"
            + "{\"predicate\":{\"pulling\":1,\"pull\":0.9},\"model\":\"oldmod:item/ruby_bow_pulling_2\"}]}";

    private static final String EGG = "{\"parent\":\"minecraft:item/template_spawn_egg\"}";

    private static MapResourceTree run(String source, String target, Map<String, byte[]> files) throws IOException {
        MapResourceTree tree = new MapResourceTree(files);
        MigrationContext context = MigrationContext.builder(target).sourceMcVersion(source)
                .targetLoader(MigrationContext.Loader.NEOFORGE).declaredNamespaces(List.of("oldmod")).build();
        new ModResourceMigrations(List.of(new ClientItemDefinitionMigrator())).run(tree, context);
        return tree;
    }

    private static JsonObject definition(MapResourceTree tree, String item) throws IOException {
        String[] id = item.split(":");
        String name = "assets/" + id[0] + "/items/" + id[1] + ".json";
        assertTrue(tree.exists(name), "a definition for " + item);
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject().getAsJsonObject("model");
    }

    private static JsonObject plain(String model) {
        JsonObject leaf = new JsonObject();
        leaf.addProperty("type", "minecraft:model");
        leaf.addProperty("model", model);
        return leaf;
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.21.4", "26.3"})
    @DisplayName("A bow's pull overrides become vanilla's bow shape: using_item, then use_duration")
    void bowAnimates(String target) throws IOException {
        MapResourceTree tree = run("1.20.1", target, entries("assets/oldmod/models/item/ruby_bow.json", BOW));

        JsonObject model = definition(tree, "oldmod:ruby_bow");
        assertEquals("minecraft:condition", model.get("type").getAsString());
        assertEquals("minecraft:using_item", model.get("property").getAsString());
        assertEquals(plain("oldmod:item/ruby_bow"), model.get("on_false"));
        JsonObject drawing = model.getAsJsonObject("on_true");
        assertEquals("minecraft:range_dispatch", drawing.get("type").getAsString());
        assertEquals("minecraft:use_duration", drawing.get("property").getAsString());
        assertEquals(0.05, drawing.get("scale").getAsDouble(), 1e-9, "old pull counted twentieths of a second");
        assertEquals(plain("oldmod:item/ruby_bow_pulling_0"), drawing.get("fallback"));
        JsonArray entries = drawing.getAsJsonArray("entries");
        assertEquals(2, entries.size());
        assertEquals(0.65, entries.get(0).getAsJsonObject().get("threshold").getAsDouble(), 1e-9);
        assertEquals(plain("oldmod:item/ruby_bow_pulling_2"), entries.get(1).getAsJsonObject().get("model"));
    }

    @Test
    @DisplayName("Custom model data overrides become a range dispatch, the last matching override winning as before")
    void customModelDataPicksVariants() throws IOException {
        MapResourceTree tree = run("1.18.2", "26.3", entries("assets/oldmod/models/item/badge.json",
                "{\"parent\":\"item/generated\",\"overrides\":["
                        + "{\"predicate\":{\"custom_model_data\":2},\"model\":\"oldmod:item/badge_gold\"},"
                        + "{\"predicate\":{\"custom_model_data\":1},\"model\":\"oldmod:item/badge_silver\"}]}"));

        JsonObject model = definition(tree, "oldmod:badge");
        assertEquals("minecraft:custom_model_data", model.get("property").getAsString());
        JsonArray entries = model.getAsJsonArray("entries");
        // At 2 both overrides match and the later one, silver, wins; so only threshold 1 remains.
        assertEquals(1, entries.size(), model.toString());
        assertEquals(plain("oldmod:item/badge_silver"), entries.get(0).getAsJsonObject().get("model"));
        assertEquals(plain("oldmod:item/badge"), model.get("fallback"));
    }

    @Test
    @DisplayName("A crossbow's charge overrides become a charge_type select, and its pull the crossbow's own property")
    void crossbowCharges() throws IOException {
        MapResourceTree tree = run("1.18.2", "26.3", entries("assets/oldmod/models/item/sling.json",
                "{\"parent\":\"item/generated\",\"overrides\":["
                        + "{\"predicate\":{\"pulling\":1},\"model\":\"oldmod:item/sling_pulling\"},"
                        + "{\"predicate\":{\"pulling\":1,\"pull\":0.5},\"model\":\"oldmod:item/sling_drawn\"},"
                        + "{\"predicate\":{\"charged\":1},\"model\":\"oldmod:item/sling_arrow\"},"
                        + "{\"predicate\":{\"charged\":1,\"firework\":1},\"model\":\"oldmod:item/sling_rocket\"}]}"));

        String model = definition(tree, "oldmod:sling").toString();
        assertTrue(model.contains("\"property\":\"minecraft:charge_type\""), model);
        assertTrue(model.contains("\"when\":\"rocket\""), model);
        assertTrue(model.contains("\"property\":\"minecraft:crossbow/pull\""), model);
        assertFalse(model.contains("use_duration"), model);
    }

    @Test
    @DisplayName("Properties only the mod's code computed, such as a compass angle or a mod's variant, keep the plain model")
    void modPropertiesKeepThePlainModel() throws IOException {
        MapResourceTree tree = run("1.12.2", "26.3", entries(
                "assets/oldmod/models/item/biome_compass.json",
                "{\"parent\":\"item/generated\",\"overrides\":[{\"predicate\":{\"angle\":0.5},"
                        + "\"model\":\"oldmod:item/biome_compass_16\"}]}",
                "assets/oldmod/models/item/net.json",
                "{\"parent\":\"item/generated\",\"overrides\":[{\"predicate\":{\"variant\":1},"
                        + "\"model\":\"oldmod:item/net_full\"}]}"));

        assertEquals(plain("oldmod:item/biome_compass"), definition(tree, "oldmod:biome_compass"),
                "vanilla's compass would point at spawn, not where the mod's needle pointed");
        assertEquals(plain("oldmod:item/net"), definition(tree, "oldmod:net"));
    }

    @Test
    @DisplayName("A mod built for 1.21.4 or later keeps the plain definition, since its own version ignored overrides")
    void newerSourceKeepsPlainDefinition() throws IOException {
        MapResourceTree tree = run("1.21.4", "26.3", entries("assets/oldmod/models/item/ruby_bow.json", BOW));

        assertEquals(plain("oldmod:item/ruby_bow"), definition(tree, "oldmod:ruby_bow"));
    }

    @Test
    @DisplayName("On 26.3 a template spawn egg gets Retromod's egg model, tinted with the colors from its constructor")
    void spawnEggKeepsItsColors() throws IOException {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/models/item/ruby_golem_spawn_egg.json", EGG,
                "assets/oldmod/models/item/ghost_spawn_egg.json", EGG));
        mod.put("oldmod/ModItems.class", lambdaRegisteredEgg("oldmod/ModItems", "ruby_golem_spawn_egg",
                0x3B6B2D, 0x9C2B2B));

        MapResourceTree tree = run("1.20.1", "26.3", mod);

        JsonObject egg = definition(tree, "oldmod:ruby_golem_spawn_egg");
        assertEquals("oldmod:item/retromod/spawn_egg/ruby_golem_spawn_egg", egg.get("model").getAsString());
        JsonArray tints = egg.getAsJsonArray("tints");
        assertEquals(0xFF3B6B2D, tints.get(0).getAsJsonObject().get("value").getAsInt());
        assertEquals(0xFF9C2B2B, tints.get(1).getAsJsonObject().get("value").getAsInt());
        String generated = text(tree.read("assets/oldmod/models/item/retromod/spawn_egg/ruby_golem_spawn_egg.json"));
        assertTrue(generated.contains("retromod_compat:item/spawn_egg_base"), generated);
        assertEquals(text(EGG.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                text(tree.read("assets/oldmod/models/item/ruby_golem_spawn_egg.json")), "the mod's model is kept");

        JsonArray neutral = definition(tree, "oldmod:ghost_spawn_egg").getAsJsonArray("tints");
        assertEquals(ClientItemDefinitionMigrator.NEUTRAL_EGG[0], neutral.get(0).getAsJsonObject().get("value").getAsInt(),
                "an egg its code does not pair gets the neutral colors, never another egg's");
        assertEgg(tree.read(SpawnEggTextures.BASE_FILE));
        assertEgg(tree.read(SpawnEggTextures.SPOTS_FILE));
    }

    @Test
    @DisplayName("A Fabric egg stored in a field is paired by the field's name")
    void fieldStoredEggIsPairedByName() throws IOException {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/models/item/moth_spawn_egg.json", EGG));
        mod.put("oldmod/Items.class", fieldStoredEgg("oldmod/Items", "MOTH_SPAWN_EGG", 0xECEBEB, 0xFFEC5B));

        JsonArray tints = definition(run("1.20.1", "26.3", mod), "oldmod:moth_spawn_egg").getAsJsonArray("tints");

        assertEquals(0xFFECEBEB, tints.get(0).getAsJsonObject().get("value").getAsInt());
    }

    @Test
    @DisplayName("An egg the class pass moved onto Retromod's spawn egg factory keeps its colors")
    void factoryBuiltEggKeepsItsColors() throws IOException {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/models/item/moth_spawn_egg.json", EGG));
        mod.put("oldmod/Items.class", factoryStoredEgg("oldmod/Items", "MOTH_SPAWN_EGG", 0xECEBEB, 0xFFEC5B));

        JsonArray tints = definition(run("1.20.1", "26.3", mod), "oldmod:moth_spawn_egg").getAsJsonArray("tints");

        assertEquals(0xFFECEBEB, tints.get(0).getAsJsonObject().get("value").getAsInt());
        assertEquals(0xFFFFEC5B, tints.get(1).getAsJsonObject().get("value").getAsInt());
    }

    @Test
    @DisplayName("On 1.21.4 the template still exists, so the egg keeps its own model and gains its tints")
    void spawnEggOn1214KeepsTheTemplate() throws IOException {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/models/item/ruby_golem_spawn_egg.json", EGG));
        mod.put("oldmod/ModItems.class", lambdaRegisteredEgg("oldmod/ModItems", "ruby_golem_spawn_egg",
                0x3B6B2D, 0x9C2B2B));

        MapResourceTree tree = run("1.20.1", "1.21.4", mod);

        JsonObject egg = definition(tree, "oldmod:ruby_golem_spawn_egg");
        assertEquals("oldmod:item/ruby_golem_spawn_egg", egg.get("model").getAsString());
        assertEquals(0xFF3B6B2D, egg.getAsJsonArray("tints").get(0).getAsJsonObject().get("value").getAsInt());
        assertFalse(tree.exists(SpawnEggTextures.BASE_FILE));
    }

    @Test
    @DisplayName("On 26.3 an egg whose jar ships its own item texture shows that texture, untinted, as vanilla eggs do")
    void spawnEggWithItsOwnTexture() throws IOException {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/models/item/moth_spawn_egg.json", EGG,
                "assets/oldmod/textures/item/moth_spawn_egg.png", "png"));
        mod.put("oldmod/Items.class", fieldStoredEgg("oldmod/Items", "MOTH_SPAWN_EGG", 0xECEBEB, 0xFFEC5B));

        MapResourceTree tree = run("1.20.1", "26.3", mod);

        JsonObject egg = definition(tree, "oldmod:moth_spawn_egg");
        assertEquals(plain("oldmod:item/retromod/spawn_egg_texture/moth_spawn_egg"), egg,
                "a full-color texture tinted with the egg colors would come out wrong");
        assertTrue(text(tree.read("assets/oldmod/models/item/retromod/spawn_egg_texture/moth_spawn_egg.json"))
                .contains("\"layer0\": \"oldmod:item/moth_spawn_egg\""));
        assertFalse(tree.exists(SpawnEggTextures.BASE_FILE), "Retromod's egg art is not needed");
    }

    @Test
    @DisplayName("On 1.21.4 a pre-1.21.4 mod's untinted egg definition gains the colors its code gave the egg")
    void shippedEggDefinitionOn1214GainsItsColors() throws IOException {
        String shipped = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"oldmod:item/moth_spawn_egg\"}}";
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/models/item/moth_spawn_egg.json", EGG,
                "assets/oldmod/items/moth_spawn_egg.json", shipped));
        mod.put("oldmod/Items.class", fieldStoredEgg("oldmod/Items", "MOTH_SPAWN_EGG", 0xECEBEB, 0xFFEC5B));

        JsonObject egg = definition(run("1.21.1", "1.21.4", mod), "oldmod:moth_spawn_egg");

        assertEquals("oldmod:item/moth_spawn_egg", egg.get("model").getAsString(), "the template still exists");
        assertEquals(0xFFECEBEB, egg.getAsJsonArray("tints").get(0).getAsJsonObject().get("value").getAsInt());
        assertEquals(shipped, text(run("1.21.4", "1.21.4", mod).read("assets/oldmod/items/moth_spawn_egg.json")),
                "a 1.21.4 mod saw its own definition, so it is kept");
    }

    @Test
    @DisplayName("An override whose model only code could draw shows the item's own model in that state")
    void codeRenderedOverrideFallsBackToTheBaseModel() throws IOException {
        MapResourceTree tree = run("1.20.1", "26.3", entries(
                "assets/oldmod/models/item/gun.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oldmod:item/gun\"},\"overrides\":["
                        + "{\"predicate\":{\"pulling\":1},\"model\":\"oldmod:item/gun_aim\"},"
                        + "{\"predicate\":{\"damaged\":1},\"model\":\"oldmod:item/gun_worn\"}]}",
                "assets/oldmod/textures/item/gun.png", "png",
                "assets/oldmod/models/item/gun_aim.json", "{\"parent\":\"builtin/entity\"}",
                "assets/oldmod/models/item/gun_worn.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oldmod:item/gun_worn\"}}"));

        String model = definition(tree, "oldmod:gun").toString();
        assertFalse(model.contains("oldmod:item/gun_aim"), "gun_aim can only show the missing model: " + model);
        assertFalse(model.contains("minecraft:using_item"), "both branches would show the same model: " + model);
        assertTrue(model.contains("oldmod:item/gun_worn"), model);
    }

    @Test
    @DisplayName("A particle that other items also use is no icon, so an item never looks like another one")
    void sharedParticleIsNoIcon() throws IOException {
        String critter = "{\"parent\":\"builtin/entity\",\"textures\":{\"particle\":\"oldmod:item/net_icon\"}}";
        MapResourceTree tree = run("1.20.1", "26.3", entries(
                "assets/oldmod/models/item/butterfly.json", critter,
                "assets/oldmod/models/item/moth.json", critter,
                "assets/oldmod/models/item/net.json",
                "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oldmod:item/net_icon\"}}",
                "assets/oldmod/textures/item/net_icon.png", "png"));

        assertFalse(tree.exists("assets/oldmod/items/butterfly.json"), "the butterfly would look like the net");
        assertFalse(tree.exists("assets/oldmod/items/moth.json"));
        assertEquals(plain("oldmod:item/net"), definition(tree, "oldmod:net"));
    }

    @Test
    @DisplayName("An item drawn through builtin/entity shows its old icon; without one it gets no definition")
    void codeRenderedItems() throws IOException {
        MapResourceTree tree = run("1.20.1", "26.3", entries(
                "assets/oldmod/models/item/statue.json",
                "{\"parent\":\"builtin/entity\",\"textures\":{\"layer0\":\"oldmod:item/statue_icon\"}}",
                "assets/oldmod/textures/item/statue_icon.png", "png",
                "assets/oldmod/models/item/chest.json", "{\"parent\":\"builtin/entity\"}"));

        assertEquals(plain("oldmod:item/retromod/builtin_entity/statue"), definition(tree, "oldmod:statue"));
        assertTrue(text(tree.read("assets/oldmod/models/item/retromod/builtin_entity/statue.json"))
                .contains("\"layer0\": \"oldmod:item/statue_icon\""));
        assertFalse(tree.exists("assets/oldmod/items/chest.json"),
                "a definition could only show the missing model");
    }

    @Test
    @DisplayName("An icon texture the jar does not ship is no icon, as MCreator's namespace-less particles are")
    void missingIconTextureGivesNoDefinition() throws IOException {
        MapResourceTree tree = run("1.20.1", "26.3", entries(
                "assets/oldmod/models/item/poster.json",
                "{\"parent\":\"builtin/entity\",\"textures\":{\"particle\":\"item/poster\"}}",
                "assets/oldmod/textures/block/poster.png", "png"));

        assertFalse(tree.exists("assets/oldmod/items/poster.json"));
        assertFalse(tree.exists("assets/oldmod/models/item/retromod/builtin_entity/poster.json"));
    }

    @Test
    @DisplayName("A shipped map_color tint is dropped on 26.3 and kept on 26.2, which still registers it")
    void mapColorTint() throws IOException {
        String shipped = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"oldmod:item/atlas\",\"tints\":["
                + "{\"type\":\"minecraft:constant\",\"value\":-1},{\"type\":\"minecraft:map_color\",\"default\":0}]}}";
        Map<String, byte[]> mod = entries("assets/oldmod/models/item/atlas.json", "{\"parent\":\"item/generated\"}",
                "assets/oldmod/items/atlas.json", shipped);

        JsonArray tints = definition(run("1.21.4", "26.3", mod), "oldmod:atlas").getAsJsonArray("tints");
        assertEquals(1, tints.size());
        assertEquals(shipped, text(run("1.21.4", "26.2", mod).read("assets/oldmod/items/atlas.json")));
    }

    @Test
    @DisplayName("A shipped definition with a model type no host registers is replaced by what its model gets here")
    void unknownModelTypeIsReplaced() throws IOException {
        MapResourceTree tree = run("1.21.1", "1.21.11", entries(
                "assets/oldmod/models/item/net.json",
                "{\"parent\":\"builtin/entity\",\"textures\":{\"particle\":\"oldmod:item/net_icon\"}}",
                "assets/oldmod/textures/item/net_icon.png", "png",
                "assets/oldmod/items/net.json",
                "{\"model\":{\"type\":\"minecraft:no_data_special\",\"model\":\"oldmod:item/net\"}}"));

        assertEquals(plain("oldmod:item/retromod/builtin_entity/net"), definition(tree, "oldmod:net"));
    }

    @Test
    @DisplayName("A 1.21.4 definition naming vanilla's egg template gets the egg model in the item's own namespace")
    void shippedTemplateEggUsesTheItemNamespace() throws IOException {
        MapResourceTree tree = run("1.21.4", "26.3", entries("assets/oldmod/items/frog_spawn_egg.json",
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/template_spawn_egg\","
                        + "\"tints\":[{\"type\":\"minecraft:constant\",\"value\":-1},"
                        + "{\"type\":\"minecraft:constant\",\"value\":-16777216}]}}"));

        JsonObject egg = definition(tree, "oldmod:frog_spawn_egg");
        assertEquals("oldmod:item/retromod/spawn_egg/template_spawn_egg", egg.get("model").getAsString());
        assertEquals(-16777216, egg.getAsJsonArray("tints").get(1).getAsJsonObject().get("value").getAsInt(),
                "the definition's own tints are kept");
        assertFalse(tree.names().stream().anyMatch(name -> name.startsWith("assets/minecraft/")));
    }

    @ParameterizedTest(name = "built for {0}")
    @ValueSource(strings = {"1.21.4", "unknown"})
    @DisplayName("A definition the mod ships that already loads is left exactly as it is")
    void shippedDefinitionUntouched(String source) throws IOException {
        String shipped = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"oldmod:item/ruby_bow\"}}";
        MapResourceTree tree = run(source.equals("unknown") ? null : source, "26.3", entries(
                "assets/oldmod/models/item/ruby_bow.json", BOW, "assets/oldmod/items/ruby_bow.json", shipped));

        assertEquals(shipped, text(tree.read("assets/oldmod/items/ruby_bow.json")));
    }

    @Test
    @DisplayName("A pre-1.21.4 mod's plain definition gives way to its model's overrides, which its version showed")
    void shippedPlainDefinitionFromOlderModGetsOverrides() throws IOException {
        String shipped = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"oldmod:item/ruby_bow\"}}";
        MapResourceTree tree = run("1.21.1", "26.3", entries(
                "assets/oldmod/models/item/ruby_bow.json", BOW, "assets/oldmod/items/ruby_bow.json", shipped));

        JsonObject model = definition(tree, "oldmod:ruby_bow");
        assertEquals("minecraft:condition", model.get("type").getAsString(), model.toString());
        assertEquals("minecraft:using_item", model.get("property").getAsString());
    }

    @Test
    @DisplayName("A mod's own JSON under items/ is kept as it is, since its code reads it from there")
    void modJsonUnderItemsIsKept() throws IOException {
        String config = "{\"name\":\"amber_cake\",\"maxStackSize\":16}";
        MapResourceTree tree = run("1.12.2", "26.3", entries(
                "assets/oldmod/models/item/amber_cake.json", "{\"parent\":\"item/generated\"}",
                "assets/oldmod/items/amber_cake.json", config));

        assertEquals(config, text(tree.read("assets/oldmod/items/amber_cake.json")));
    }

    @Test
    @DisplayName("Nothing is written in the minecraft namespace, even for a translatable override")
    void vanillaItemsKeepVanillaDefinitions() throws IOException {
        MapResourceTree tree = run("1.18.2", "26.3", entries("assets/minecraft/models/item/fishing_rod.json",
                "{\"parent\":\"item/handheld_rod\",\"overrides\":[{\"predicate\":{\"cast\":1,\"custom_model_data\":7},"
                        + "\"model\":\"oldmod:item/melody_rod_cast\"}]}"));

        assertFalse(tree.exists("assets/minecraft/items/fishing_rod.json"));
    }

    @Test
    @DisplayName("A 1.21.1 host still reads model overrides and the template, so nothing is written")
    void olderHostIsLeftAlone() throws IOException {
        Map<String, byte[]> mod = entries("assets/oldmod/models/item/ruby_bow.json", BOW,
                "assets/oldmod/models/item/ruby_golem_spawn_egg.json", EGG);

        MapResourceTree tree = run("1.20.1", "1.21.1", mod);

        assertEquals(mod.keySet(), tree.entries().keySet());
    }

    @Test
    @DisplayName("Running twice changes nothing the second time")
    void idempotent() throws IOException {
        Map<String, byte[]> mod = new LinkedHashMap<>(entries(
                "assets/oldmod/models/item/ruby_bow.json", BOW,
                "assets/oldmod/models/item/ruby_golem_spawn_egg.json", EGG,
                "assets/oldmod/models/item/statue.json",
                "{\"parent\":\"builtin/entity\",\"textures\":{\"layer0\":\"oldmod:item/statue_icon\"}}",
                "assets/oldmod/textures/item/statue_icon.png", "png",
                "assets/oldmod/models/item/net.json",
                "{\"parent\":\"item/generated\",\"overrides\":[{\"predicate\":{\"variant\":1},"
                        + "\"model\":\"oldmod:item/net_full\"}]}"));
        MapResourceTree once = run("1.20.1", "26.3", mod);
        assertTrue(once.exists("assets/oldmod/items/statue.json"), "the fixture exercises the icon path");

        MapResourceTree twice = run("1.20.1", "26.3", once.entries());

        assertEquals(gameResourceDigests(once.entries()), gameResourceDigests(twice.entries()));
    }

    private static void assertEgg(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "a readable PNG");
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
    }

    /**
     * {@code ITEMS.register("<id>", () -> new ForgeSpawnEggItem(type, base, spots, properties))},
     * as Forge mods and MCreator wrote it.
     */
    static byte[] lambdaRegisteredEgg(String owner, String id, int base, int spots) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        String supplier = "Ljava/util/function/Supplier;";
        String field = id.toUpperCase(java.util.Locale.ROOT);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor lambda = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$static$0", "()Ljava/lang/Object;", null, null);
        lambda.visitCode();
        lambda.visitTypeInsn(Opcodes.NEW, "net/minecraftforge/common/ForgeSpawnEggItem");
        lambda.visitInsn(Opcodes.DUP);
        lambda.visitInsn(Opcodes.ACONST_NULL);
        lambda.visitLdcInsn(base);
        lambda.visitLdcInsn(spots);
        lambda.visitInsn(Opcodes.ACONST_NULL);
        lambda.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraftforge/common/ForgeSpawnEggItem", "<init>",
                "(" + supplier + "IILnet/minecraft/world/item/Item$Properties;)V", false);
        lambda.visitInsn(Opcodes.ARETURN);
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();

        MethodVisitor register = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "register",
                "(Ljava/lang/String;" + supplier + ")Ljava/lang/Object;", null, null);
        register.visitCode();
        register.visitVarInsn(Opcodes.ALOAD, 1);
        register.visitInsn(Opcodes.ARETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();

        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(id);
        clinit.visitInvokeDynamicInsn("get", "()" + supplier,
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                Type.getType("()Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKESTATIC, owner, "lambda$static$0", "()Ljava/lang/Object;", false),
                Type.getType("()Ljava/lang/Object;"));
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "register",
                "(Ljava/lang/String;" + supplier + ")Ljava/lang/Object;", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Ljava/lang/Object;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code MOTH_SPAWN_EGG = new SpawnEggItem(type, base, spots, settings)} in intermediary names. */
    /** {@code FIELD = LegacySpawnEggItem.create(type, base, spots, properties)}, as the 26.2 bridge leaves it. */
    static byte[] factoryStoredEgg(String owner, String field, int base, int spots) {
        String factory = "com/retromod/embedded/oldmod/" + com.retromod.shim.common.LegacySpawnEggBridge.INTERNAL;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Lnet/minecraft/world/item/Item;", null, null)
                .visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitLdcInsn(base);
        clinit.visitLdcInsn(spots);
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, factory, "create",
                "(Lnet/minecraft/world/entity/EntityType;IILnet/minecraft/world/item/Item$Properties;)"
                        + "Lnet/minecraft/world/item/SpawnEggItem;", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Lnet/minecraft/world/item/Item;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] fieldStoredEgg(String owner, String field, int base, int spots) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Lnet/minecraft/class_1792;", null, null)
                .visitEnd();
        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "net/minecraft/class_1826");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitLdcInsn(base);
        clinit.visitLdcInsn(spots);
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/class_1826", "<init>",
                "(Lnet/minecraft/class_1299;IILnet/minecraft/class_1792$class_1793;)V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Lnet/minecraft/class_1792;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
