/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.resources.ResourceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.12 blockstates name the plain state {@code normal}, write model names relative to
 * {@code models/block/}, and carry the item model as an {@code inventory} variant. Forge mods also
 * used Forge's own {@code forge_marker} format, which no loader reads after Forge 1.14.4. These
 * become the vanilla JSON the target reads, and older hosts that still read the old shape are left
 * alone.
 */
class LegacyBlockStateMigratorTest {

    private static final MigrationContext.Loader FORGE = MigrationContext.Loader.FORGE;
    private static final MigrationContext.Loader NEOFORGE = MigrationContext.Loader.NEOFORGE;
    private static final MigrationContext.Loader FABRIC = MigrationContext.Loader.FABRIC;

    private static MigrationContext context(String source, String target, MigrationContext.Loader loader) {
        return MigrationContext.builder(target).sourceMcVersion(source).targetLoader(loader)
                .declaredNamespaces(List.of("oldmod")).build().forRun(List.of("oldmod"));
    }

    private static int migrate(ResourceTree tree, String source, String target,
                               MigrationContext.Loader loader) throws IOException {
        MigrationContext context = context(source, target, loader);
        LegacyBlockStateMigrator migrator = new LegacyBlockStateMigrator();
        return migrator.appliesTo(context) ? migrator.apply(tree, context) : 0;
    }

    private static JsonObject json(ResourceTree tree, String name) throws IOException {
        return JsonParser.parseString(text(tree.read(name))).getAsJsonObject();
    }

    private static JsonObject variant(ResourceTree tree, String blockstate, String key) throws IOException {
        return json(tree, blockstate).getAsJsonObject("variants").getAsJsonObject(key);
    }

    // ---- vanilla 1.12 blockstates

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.13", "1.21.1", "26.3"})
    @DisplayName("normal becomes \"\" and model names gain the block/ folder 1.12 implied")
    void normalVariantAndBareModelNames(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/ruby_ore.json",
                "{\"variants\":{\"normal\":{\"model\":\"oldmod:ruby_ore\"}}}",
                "assets/oldmod/blockstates/dark_stairs.json",
                "{\"variants\":{\"facing=east\":{\"model\":\"oldmod:stairs/dark\",\"y\":90},"
                        + "\"facing=west\":{\"model\":\"stone\"}}}",
                "assets/oldmod/models/block/ruby_ore.json", "{\"parent\":\"block/cube_all\"}",
                "assets/oldmod/models/block/stairs/dark.json", "{\"parent\":\"block/stairs\"}"));

        migrate(tree, "1.12.2", target, NEOFORGE);

        assertEquals("oldmod:block/ruby_ore",
                variant(tree, "assets/oldmod/blockstates/ruby_ore.json", "").get("model").getAsString());
        JsonObject east = variant(tree, "assets/oldmod/blockstates/dark_stairs.json", "facing=east");
        assertEquals("oldmod:block/stairs/dark", east.get("model").getAsString(),
                "1.12 put every variant model under models/block/, even one with its own folder");
        assertEquals(90, east.get("y").getAsInt(), "the rotation is kept");
        assertEquals("minecraft:block/stone", variant(tree, "assets/oldmod/blockstates/dark_stairs.json",
                "facing=west").get("model").getAsString());
    }

    @Test
    @DisplayName("normal beside property keys is dropped, since \"\" would overlap every one of them")
    void normalBesidePropertiesIsDropped() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lamp.json",
                "{\"variants\":{\"normal\":{\"model\":\"oldmod:lamp\"},\"lit=true\":{\"model\":\"oldmod:lamp_on\"}}}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        JsonObject variants = json(tree, "assets/oldmod/blockstates/lamp.json").getAsJsonObject("variants");
        assertEquals(Set.of("lit=true"), variants.keySet());
    }

    @Test
    @DisplayName("An inventory variant becomes the item model, unless the mod ships one")
    void inventoryVariantBecomesItemModelWhenAbsent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/ruby_block.json",
                "{\"variants\":{\"normal\":{\"model\":\"oldmod:ruby_block\"},"
                        + "\"inventory\":{\"model\":\"oldmod:ruby_block\"}}}",
                "assets/oldmod/blockstates/jade_block.json",
                "{\"variants\":{\"normal\":{\"model\":\"oldmod:jade_block\"},"
                        + "\"inventory\":{\"model\":\"oldmod:jade_block\"}}}",
                "assets/oldmod/models/item/jade_block.json", "{\"parent\":\"oldmod:item/jade_custom\"}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        assertEquals("oldmod:block/ruby_block",
                json(tree, "assets/oldmod/models/item/ruby_block.json").get("parent").getAsString());
        assertEquals("{\"parent\":\"oldmod:item/jade_custom\"}",
                text(tree.read("assets/oldmod/models/item/jade_block.json")), "the mod's item model wins");
        assertFalse(json(tree, "assets/oldmod/blockstates/ruby_block.json")
                .getAsJsonObject("variants").has("inventory"), "1.13+ reads inventory as a property");
    }

    @Test
    @DisplayName("A blockstate already in the 1.13 shape stays byte for byte")
    void modernBlockStateIsUntouched() throws IOException {
        String modern = "{\n  \"variants\": {\"\": {\"model\": \"oldmod:block/ruby\"}}\n}";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/ruby.json", modern,
                "assets/oldmod/models/block/ruby.json", "{}"));

        assertEquals(0, migrate(tree, "1.12.2", "26.3", NEOFORGE));
        assertEquals(modern, text(tree.read("assets/oldmod/blockstates/ruby.json")));
    }

    /** A 1.12 pane: multipart for the block, and an inventory variant only for the item. */
    private static final String PANE = "{\"variants\":{\"inventory\":[{\"model\":\"oldmod:rune_pane_item\"}]},"
            + "\"multipart\":[{\"apply\":{\"model\":\"oldmod:rune_pane_post\"}},"
            + "{\"when\":{\"north\":true},\"apply\":{\"model\":\"oldmod:rune_pane_side\"}}]}";

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.13", "1.21.1", "26.3"})
    @DisplayName("A multipart file whose only variant was inventory keeps no empty variants map")
    void inventoryBesideMultipartLeavesNoEmptyVariants(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/rune_pane.json", PANE,
                "assets/oldmod/models/block/rune_pane_item.json", "{}"));

        migrate(tree, "1.12.2", target, NEOFORGE);

        JsonObject root = json(tree, "assets/oldmod/blockstates/rune_pane.json");
        assertFalse(root.has("variants"), "26.3 rejects an empty variants map, even beside multipart");
        JsonArray multipart = root.getAsJsonArray("multipart");
        assertEquals("oldmod:block/rune_pane_post",
                multipart.get(0).getAsJsonObject().getAsJsonObject("apply").get("model").getAsString());
        assertEquals("oldmod:block/rune_pane_side",
                multipart.get(1).getAsJsonObject().getAsJsonObject("apply").get("model").getAsString());
        assertEquals("oldmod:block/rune_pane_item",
                json(tree, "assets/oldmod/models/item/rune_pane.json").get("parent").getAsString());
    }

    @ParameterizedTest(name = "{0} {1} gives {2}")
    @CsvSource({"NEOFORGE, 26.3, neoforge:obj", "NEOFORGE, 1.21.1, neoforge:obj", "FORGE, 1.20.1, forge:obj"})
    @DisplayName("A plain variant naming an OBJ model gets the loader model Forge 1.12 loaded it with")
    void vanillaObjVariantBecomesLoaderModel(MigrationContext.Loader loader, String target, String objLoader)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/statue.json", "{\"variants\":{"
                        + "\"normal\":{\"model\":\"oldmod:statue.obj\",\"y\":90},"
                        + "\"inventory\":{\"model\":\"oldmod:statue.obj\"}}}",
                "assets/oldmod/models/block/statue.obj", "o statue"));

        migrate(tree, "1.12.2", target, loader);

        JsonObject placed = variant(tree, "assets/oldmod/blockstates/statue.json", "");
        assertEquals(90, placed.get("y").getAsInt(), "the rotation is kept");
        JsonObject model = json(tree, modelFile(placed));
        assertEquals(JsonParser.parseString("{\"loader\":\"" + objLoader + "\","
                + "\"model\":\"oldmod:models/block/statue.obj\"}"), model,
                "1.12 read a plain variant's OBJ from models/block/, with no options");
        assertEquals(model, json(tree, "assets/oldmod/models/item/statue.json"));
    }

    @ParameterizedTest(name = "{1} on {0}")
    @CsvSource({"FABRIC, oldmod:idol.obj", "NEOFORGE, oldmod:idol.b3d"})
    @DisplayName("A plain variant whose model no 1.13+ loader reads is left out, and the rest converts")
    void vanillaVariantsWithoutALoaderAreLeftOut(MigrationContext.Loader loader, String model) throws IOException {
        String onlyUnloadable = "{\"variants\":{\"normal\":{\"model\":\"" + model + "\"}}}";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/idol.json", onlyUnloadable,
                "assets/oldmod/blockstates/plinth.json", "{\"variants\":{"
                        + "\"tall=true\":{\"model\":\"" + model + "\"},"
                        + "\"tall=false\":{\"model\":\"oldmod:plinth\"}}}",
                "assets/oldmod/models/block/idol.obj", "o idol",
                "assets/oldmod/models/block/idol.b3d", "b3d"));

        migrate(tree, "1.12.2", "26.3", loader);

        assertEquals(onlyUnloadable, text(tree.read("assets/oldmod/blockstates/idol.json")),
                "nothing in it converts, so it stays as shipped");
        assertEquals(JsonParser.parseString("{\"variants\":{\"tall=false\":{\"model\":\"oldmod:block/plinth\"}}}"),
                json(tree, "assets/oldmod/blockstates/plinth.json"));
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({"1.12.2, 1.12.2, FORGE", ", 26.3, NEOFORGE", "1.13, 26.3, NEOFORGE"})
    @DisplayName("A plain 1.12 blockstate stays on a 1.12 host, or when the source is not known to be 1.12")
    void vanillaRulesNeedBothGates(String source, String target, MigrationContext.Loader loader)
            throws IOException {
        String old = "{\"variants\":{\"normal\":{\"model\":\"oldmod:ruby_ore\"},"
                + "\"inventory\":{\"model\":\"oldmod:ruby_ore\"}}}";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/ruby_ore.json", old,
                "assets/oldmod/models/block/ruby_ore.json", "{}"));

        migrate(tree, source, target, loader);

        assertEquals(old, text(tree.read("assets/oldmod/blockstates/ruby_ore.json")));
        assertFalse(tree.exists("assets/oldmod/models/item/ruby_ore.json"));
    }

    @Test
    @DisplayName("Blockstates in vanilla's namespace are never rewritten")
    void sharedNamespaceIsLeftAlone() throws IOException {
        String override = "{\"variants\":{\"normal\":{\"model\":\"stone\"}}}";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/minecraft/blockstates/stone.json", override));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        assertEquals(override, text(tree.read("assets/minecraft/blockstates/stone.json")));
    }

    // ---- Forge v1 blockstates

    /** A block with one textured model, written the way small 1.12 Forge mods wrote them. */
    private static final String TEXTURED_CUBE = "{\"forge_marker\":1,"
            + "\"defaults\":{\"model\":\"cube_all\",\"textures\":{\"all\":\"oldmod:blocks/lift_red\"}},"
            + "\"variants\":{\"normal\":[{}],\"inventory\":[{}]}}";

    @ParameterizedTest(name = "on {0} {1}")
    @CsvSource({"NEOFORGE, 1.21.1", "NEOFORGE, 26.3", "FORGE, 1.20.1", "FABRIC, 26.3"})
    @DisplayName("forge_marker defaults become a generated model the block and its item both use")
    void forgeMarkerDefaultsBecomeGeneratedModel(MigrationContext.Loader loader, String target)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lift_red.json", TEXTURED_CUBE));

        migrate(tree, "1.12.2", target, loader);

        assertEquals(JsonParser.parseString("{\"variants\":{\"\":{\"model\":"
                        + "\"oldmod:block/retromod/lift_red/0\"}}}"),
                json(tree, "assets/oldmod/blockstates/lift_red.json"));
        assertEquals(JsonParser.parseString("{\"parent\":\"minecraft:block/cube_all\","
                        + "\"textures\":{\"all\":\"oldmod:blocks/lift_red\"}}"),
                json(tree, "assets/oldmod/models/block/retromod/lift_red/0.json"),
                "the texture keeps its 1.12 path for the texture converters that run later");
        assertEquals("oldmod:block/retromod/lift_red/0",
                json(tree, "assets/oldmod/models/item/lift_red.json").get("parent").getAsString());
    }

    @Test
    @DisplayName("Property maps expand like Forge: the earlier property wins, a full key fills in last")
    void forgeMarkerPropertyMapsExpand() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/hammock.json", "{\"forge_marker\":1,"
                        + "\"defaults\":{\"textures\":{\"cloth\":\"oldmod:blocks/cloth\"},\"uvlock\":true},"
                        + "\"variants\":{"
                        + "\"part\":{\"head\":{\"model\":\"oldmod:hammock_head\"},"
                        + "\"foot\":{\"model\":\"oldmod:hammock_foot\",\"y\":180}},"
                        + "\"facing\":{\"north\":{},\"east\":{\"y\":90}},"
                        + "\"facing=east,part=head\":[{\"textures\":{\"cloth\":\"oldmod:blocks/cloth_east\"}}]"
                        + "}}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        JsonObject variants = json(tree, "assets/oldmod/blockstates/hammock.json").getAsJsonObject("variants");
        assertEquals(Set.of("facing=north,part=head", "facing=north,part=foot",
                "facing=east,part=head", "facing=east,part=foot"), variants.keySet(),
                "only the properties the file mentions, so other properties match any value");
        JsonObject eastFoot = variants.getAsJsonObject("facing=east,part=foot");
        assertEquals(90, eastFoot.get("y").getAsInt(), "facing sorts before part, so its rotation wins");
        assertTrue(eastFoot.get("uvlock").getAsBoolean(), "defaults fill what nothing else set");
        assertEquals(180, variants.getAsJsonObject("facing=north,part=foot").get("y").getAsInt());

        JsonObject eastHead = json(tree, modelFile(variants.getAsJsonObject("facing=east,part=head")));
        assertEquals("oldmod:block/hammock_head", eastHead.get("parent").getAsString());
        assertEquals("oldmod:blocks/cloth_east", eastHead.getAsJsonObject("textures").get("cloth").getAsString(),
                "the fully written key overrides the default texture");
        JsonObject northHead = json(tree, modelFile(variants.getAsJsonObject("facing=north,part=head")));
        assertEquals("oldmod:blocks/cloth", northHead.getAsJsonObject("textures").get("cloth").getAsString());
    }

    @Test
    @DisplayName("Submodels become multipart parts, with each part's rotation combined with its state's")
    void forgeMarkerSubmodelsBecomeMultipart() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/rune_wall.json", "{\"forge_marker\":1,"
                        + "\"defaults\":{\"textures\":{\"wall\":\"oldmod:blocks/rune\"}},"
                        + "\"variants\":{"
                        + "\"up\":{\"true\":{\"submodel\":\"wall_post\"},\"false\":{}},"
                        + "\"east\":{\"true\":{\"submodel\":{\"side\":{\"model\":\"wall_side\",\"y\":90}}},"
                        + "\"false\":{}}}}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        JsonArray multipart = json(tree, "assets/oldmod/blockstates/rune_wall.json").getAsJsonArray("multipart");
        assertEquals(2, multipart.size(), "one entry per distinct part");
        JsonObject side = multipart.get(0).getAsJsonObject();
        assertEquals(JsonParser.parseString("{\"OR\":[{\"east\":\"true\",\"up\":\"true\"},"
                + "{\"east\":\"true\",\"up\":\"false\"}]}"), side.get("when"));
        assertEquals(90, side.getAsJsonObject("apply").get("y").getAsInt());
        JsonObject post = multipart.get(1).getAsJsonObject();
        assertEquals(JsonParser.parseString("{\"OR\":[{\"east\":\"true\",\"up\":\"true\"},"
                + "{\"east\":\"false\",\"up\":\"true\"}]}"), post.get("when"));
        assertFalse(post.getAsJsonObject("apply").has("y"), "the short-form part keeps no rotation");
        assertEquals("minecraft:block/wall_post", json(tree, modelFile(post.getAsJsonObject("apply")))
                .get("parent").getAsString());
        assertEquals("oldmod:blocks/rune", json(tree, modelFile(side.getAsJsonObject("apply")))
                .getAsJsonObject("textures")
                .get("wall").getAsString(), "parts take the state's textures");
    }

    @Test
    @DisplayName("A rotated part in a rotated state turns by both, as Forge composed them")
    void submodelRotationComposesWithTheState() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/shelf.json", "{\"forge_marker\":1,\"variants\":{\"facing\":{"
                        + "\"east\":{\"model\":\"oldmod:shelf\",\"y\":90,"
                        + "\"submodel\":{\"book\":{\"model\":\"oldmod:book\",\"y\":90}}}}}}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        JsonArray multipart = json(tree, "assets/oldmod/blockstates/shelf.json").getAsJsonArray("multipart");
        assertEquals(JsonParser.parseString("{\"when\":{\"facing\":\"east\"},"
                + "\"apply\":{\"model\":\"oldmod:block/shelf\",\"y\":90}}"), multipart.get(0));
        assertEquals(JsonParser.parseString("{\"when\":{\"facing\":\"east\"},"
                + "\"apply\":{\"model\":\"oldmod:block/book\",\"y\":180}}"), multipart.get(1));
    }

    @Test
    @DisplayName("Without defaults, a short-form part keeps only its own fields, as Forge 1.12 read it")
    void shortFormPartWithoutDefaultsTakesNothingFromItsState() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lantern.json", "{\"forge_marker\":1,\"variants\":{"
                        + "\"lit\":{\"true\":{\"model\":\"oldmod:base\",\"uvlock\":true},"
                        + "\"false\":{\"model\":\"oldmod:base\"}},"
                        + "\"post\":{\"true\":{\"submodel\":\"oldmod:post\",\"y\":90},\"false\":{}}}}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        JsonArray multipart = json(tree, "assets/oldmod/blockstates/lantern.json").getAsJsonArray("multipart");
        List<JsonObject> posts = new java.util.ArrayList<>();
        for (JsonElement part : multipart) {
            JsonObject apply = part.getAsJsonObject().getAsJsonObject("apply");
            if (apply.get("model").getAsString().equals("oldmod:block/post")) posts.add(part.getAsJsonObject());
        }
        assertEquals(1, posts.size(), "the post is the same part whether or not the lantern is lit");
        assertEquals(JsonParser.parseString("{\"model\":\"oldmod:block/post\",\"y\":90}"),
                posts.get(0).get("apply"), "Forge gave a short-form part its state's uvlock only through defaults");
        assertEquals(JsonParser.parseString("{\"OR\":[{\"lit\":\"true\",\"post\":\"true\"},"
                + "{\"lit\":\"false\",\"post\":\"true\"}]}"), posts.get(0).get("when"));
    }

    @ParameterizedTest(name = "{0} {1} gives {2}")
    @CsvSource({"NEOFORGE, 26.3, neoforge:obj", "NEOFORGE, 1.21.1, neoforge:obj",
            "NEOFORGE, 1.20.1, forge:obj", "FORGE, 1.20.1, forge:obj", "FORGE, 26.2, forge:obj"})
    @DisplayName("An OBJ model becomes a model for the loader's own OBJ loader")
    void objModelsUseTheTargetLoader(MigrationContext.Loader loader, String target, String objLoader)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/idol.json", "{\"forge_marker\":1,"
                        + "\"defaults\":{\"model\":\"oldmod:idol.obj\",\"custom\":{\"flip-v\":true}},"
                        + "\"variants\":{\"normal\":[{}],\"inventory\":[{\"transform\":\"forge:default-block\"}]}}",
                "assets/oldmod/models/block/idol.obj", "o idol"));

        migrate(tree, "1.12.2", target, loader);

        JsonObject model = json(tree, modelFile(variant(tree, "assets/oldmod/blockstates/idol.json", "")));
        assertEquals(objLoader, model.get("loader").getAsString());
        assertEquals("oldmod:models/block/idol.obj", model.get("model").getAsString());
        assertTrue(model.get("flip_v").getAsBoolean());
        JsonObject item = json(tree, "assets/oldmod/models/item/idol.json");
        assertEquals("minecraft:block/block", item.get("parent").getAsString(),
                "forge:default-block is the block display vanilla's block/block carries");
        assertEquals(objLoader, item.get("loader").getAsString());
    }

    @Test
    @DisplayName("A Fabric host has no OBJ loader, so OBJ variants are left out and the rest converts")
    void fabricDropsObjVariants() throws IOException {
        String onlyObj = "{\"forge_marker\":1,\"defaults\":{\"model\":\"oldmod:idol.obj\"},"
                + "\"variants\":{\"normal\":[{}]}}";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/idol.json", onlyObj,
                "assets/oldmod/blockstates/plinth.json", "{\"forge_marker\":1,"
                        + "\"variants\":{\"tall=true\":{\"model\":\"oldmod:idol.obj\"},"
                        + "\"tall=false\":{\"model\":\"oldmod:plinth\"}}}",
                "assets/oldmod/models/block/idol.obj", "o idol"));

        migrate(tree, "1.12.2", "26.3", FABRIC);

        assertEquals(onlyObj, text(tree.read("assets/oldmod/blockstates/idol.json")),
                "nothing in it converts, so it stays as shipped");
        assertEquals(JsonParser.parseString("{\"variants\":{\"tall=false\":{\"model\":\"oldmod:block/plinth\"}}}"),
                json(tree, "assets/oldmod/blockstates/plinth.json"));
    }

    @Test
    @DisplayName("Forge's item layer model with an item transform becomes item/generated or item/handheld")
    void itemLayerModelsUseTheVanillaItemParents() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/charm.json", "{\"forge_marker\":1,\"defaults\":{\"model\":"
                        + "\"builtin/generated\",\"transform\":\"forge:default-item\"},\"variants\":{"
                        + "\"inventory\":[{\"textures\":{\"layer0\":\"oldmod:items/charm\"}}]}}",
                "assets/oldmod/blockstates/club.json", "{\"forge_marker\":1,\"defaults\":{\"model\":"
                        + "\"builtin/generated\",\"transform\":\"forge:default-tool\"},\"variants\":{"
                        + "\"inventory\":[{\"textures\":{\"layer0\":\"oldmod:items/club\"}}]}}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        assertEquals(JsonParser.parseString("{\"parent\":\"minecraft:item/generated\","
                + "\"textures\":{\"layer0\":\"oldmod:items/charm\"}}"), json(tree, "assets/oldmod/models/item/charm.json"));
        assertEquals("minecraft:item/handheld",
                json(tree, "assets/oldmod/models/item/club.json").get("parent").getAsString());
    }

    @Test
    @DisplayName("A transform that moves the placed block has no vanilla form, so the file stays")
    void movingTransformLeavesTheFileAlone() throws IOException {
        String moved = "{\"forge_marker\":1,\"defaults\":{\"model\":\"oldmod:plate\","
                + "\"transform\":{\"translation\":[0,-0.1875,0]}},\"variants\":{\"normal\":[{}]}}";
        MapResourceTree tree = new MapResourceTree(entries("assets/oldmod/blockstates/plate.json", moved));

        assertEquals(0, migrate(tree, "1.12.2", "26.3", NEOFORGE));
        assertEquals(moved, text(tree.read("assets/oldmod/blockstates/plate.json")));
    }

    @Test
    @DisplayName("Keys that would both match one state on 1.13+ leave the file alone")
    void overlappingKeysLeaveTheFileAlone() throws IOException {
        String overlapping = "{\"forge_marker\":1,\"variants\":{"
                + "\"facing=north\":{\"model\":\"oldmod:a\"},\"lit=true\":{\"model\":\"oldmod:b\"}}}";
        MapResourceTree tree = new MapResourceTree(entries("assets/oldmod/blockstates/lamp.json", overlapping));

        assertEquals(0, migrate(tree, "1.12.2", "26.3", NEOFORGE));
        assertEquals(overlapping, text(tree.read("assets/oldmod/blockstates/lamp.json")));
    }

    @Test
    @DisplayName("Overlapping keys with the same model collapse into the more general key")
    void sameModelOverlapCollapsesToTheGeneralKey() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/flesh_slab_double.json", "{\"forge_marker\":1,\"variants\":{"
                        + "\"variant=default\":{\"model\":\"oldmod:flesh_slab_double\"},"
                        + "\"half=bottom,variant=default\":{\"model\":\"oldmod:flesh_slab_double\"},"
                        + "\"half=top,variant=default\":{\"model\":\"oldmod:flesh_slab_double\"},"
                        + "\"inventory\":[{}]}}"));

        migrate(tree, "1.12.2", "26.3", NEOFORGE);

        assertEquals(JsonParser.parseString("{\"variants\":{\"variant=default\":"
                        + "{\"model\":\"oldmod:block/flesh_slab_double\"}}}"),
                json(tree, "assets/oldmod/blockstates/flesh_slab_double.json"),
                "every state the specific keys matched also matches variant=default, with the same model");
    }

    @Test
    @DisplayName("Overlapping keys with the same model stay when neither key covers the other")
    void sameModelOverlapWithoutACoveringKeyLeavesTheFileAlone() throws IOException {
        String overlapping = "{\"forge_marker\":1,\"variants\":{"
                + "\"facing=north\":{\"model\":\"oldmod:a\"},\"lit=true\":{\"model\":\"oldmod:a\"}}}";
        MapResourceTree tree = new MapResourceTree(entries("assets/oldmod/blockstates/lamp.json", overlapping));

        assertEquals(0, migrate(tree, "1.12.2", "26.3", NEOFORGE));
        assertEquals(overlapping, text(tree.read("assets/oldmod/blockstates/lamp.json")));
    }

    @Test
    @DisplayName("Forge 1.14 read model names as written, so a 1.14 file gets no block/ folder")
    void forge114SourceKeepsModelNames() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/pillar.json", "{\"forge_marker\":1,"
                        + "\"defaults\":{\"model\":\"oldmod:block/pillar\"},\"variants\":{\"\":[{}]}}"));

        migrate(tree, "1.14.4", "26.3", NEOFORGE);

        assertEquals("oldmod:block/pillar",
                variant(tree, "assets/oldmod/blockstates/pillar.json", "").get("model").getAsString());
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({"1.14.4, 1.14.4, FORGE", "1.12.2, 1.14.4, FORGE", "1.12.2, 1.12.2, FORGE",
            ", 26.3, NEOFORGE", "1.15.2, 26.3, NEOFORGE"})
    @DisplayName("A host that still reads forge_marker, or a source not known to be old, is left alone")
    void forgeMarkerNeedsBothGates(String source, String target, MigrationContext.Loader loader)
            throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lift_red.json", TEXTURED_CUBE));

        migrate(tree, source, target, loader);

        assertEquals(TEXTURED_CUBE, text(tree.read("assets/oldmod/blockstates/lift_red.json")));
        assertFalse(tree.exists("assets/oldmod/models/item/lift_red.json"));
    }

    @ParameterizedTest(name = "on {0} 1.14.4")
    @CsvSource({"FABRIC", "QUILT"})
    @DisplayName("Fabric and Quilt never read forge_marker, so it converts even where Forge still read it")
    void forgeMarkerConvertsForFabricFamilyBefore115(MigrationContext.Loader loader) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lift_red.json", TEXTURED_CUBE));

        migrate(tree, "1.12.2", "1.14.4", loader);

        assertEquals(JsonParser.parseString("{\"variants\":{\"\":{\"model\":"
                        + "\"oldmod:block/retromod/lift_red/0\"}}}"),
                json(tree, "assets/oldmod/blockstates/lift_red.json"));
        assertTrue(tree.exists("assets/oldmod/models/item/lift_red.json"));
    }

    @Test
    @DisplayName("A second run changes nothing")
    void idempotent() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lift_red.json", TEXTURED_CUBE,
                "assets/oldmod/blockstates/ruby_ore.json",
                "{\"variants\":{\"normal\":{\"model\":\"oldmod:ruby_ore\"},\"inventory\":{\"model\":\"oldmod:ruby_ore\"}}}",
                "assets/oldmod/models/block/ruby_ore.json", "{}",
                "assets/oldmod/blockstates/rune_pane.json", PANE,
                "assets/oldmod/blockstates/statue.json", "{\"variants\":{\"normal\":{\"model\":\"oldmod:statue.obj\"}}}",
                "assets/oldmod/models/block/statue.obj", "o statue"));
        migrate(tree, "1.12.2", "26.3", NEOFORGE);
        Map<String, byte[]> first = Map.copyOf(tree.entries());

        assertEquals(0, migrate(tree, "1.12.2", "26.3", NEOFORGE));
        assertEquals(gameResourceDigests(first), gameResourceDigests(tree.entries()));
    }

    // ---- strict asset JSON

    private static final String COMMENTED = "{\n  // the plain state\n  \"variants\": {\n"
            + "    \"\": {\"model\": \"oldmod:block/ruby\"},\n  }\n}";

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.21.6", "1.21.11", "26.3"})
    @DisplayName("Comments and trailing commas leave the blockstates, items and atlases strict hosts read")
    void lenientAssetJsonIsStrictFrom1216(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/ruby.json", COMMENTED,
                "assets/oldmod/items/ruby.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"oldmod:item/ruby\",},}",
                "assets/oldmod/atlases/blocks.json", "{\"sources\":[] /* none */}"));

        assertEquals(3, migrate(tree, "1.20.1", target, FABRIC));

        for (String name : List.of("assets/oldmod/blockstates/ruby.json", "assets/oldmod/items/ruby.json",
                "assets/oldmod/atlases/blocks.json")) {
            String strict = text(tree.read(name));
            assertDoesNotThrow(() -> parseStrictly(strict), name);
        }
    }

    private static final String LENIENT_EQUIPMENT =
            "{\"layers\":{\"humanoid\":[{\"texture\":\"oldmod:ruby\"},]}}";
    private static final String LENIENT_POST_EFFECT = "{/* glow */ \"targets\":{},\"passes\":[]}";

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.21.6", "26.3"})
    @DisplayName("Equipment assets and post effects are parsed strictly from 1.21.6 as well")
    void lenientEquipmentAndPostEffectsAreStrictFrom1216(String target) throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/equipment/ruby.json", LENIENT_EQUIPMENT,
                "assets/oldmod/post_effect/glow.json", LENIENT_POST_EFFECT));

        assertEquals(2, migrate(tree, "1.21.4", target, NEOFORGE));

        for (String name : List.of("assets/oldmod/equipment/ruby.json", "assets/oldmod/post_effect/glow.json")) {
            String strict = text(tree.read(name));
            assertDoesNotThrow(() -> parseStrictly(strict), name);
        }
    }

    @Test
    @DisplayName("A mod's atlas file in vanilla's namespace is repaired, since atlases only add sprites")
    void sharedNamespaceAtlasIsRepairedButOtherSharedAssetsAreNot() throws IOException {
        String atlas = "{\"sources\":[{\"type\":\"minecraft:directory\",\"source\":\"oldmod\","
                + "\"prefix\":\"oldmod/\"},] // the mod's sprites\n}";
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/minecraft/atlases/blocks.json", atlas,
                "assets/minecraft/blockstates/stone.json", COMMENTED,
                "assets/minecraft/equipment/diamond.json", LENIENT_EQUIPMENT));

        assertEquals(1, migrate(tree, "1.20.1", "26.3", FABRIC));

        assertDoesNotThrow(() -> parseStrictly(text(tree.read("assets/minecraft/atlases/blocks.json"))));
        assertEquals(COMMENTED, text(tree.read("assets/minecraft/blockstates/stone.json")),
                "vanilla's own blockstate still loads beside the broken copy, and a repair would replace it");
        assertEquals(LENIENT_EQUIPMENT, text(tree.read("assets/minecraft/equipment/diamond.json")));
    }

    @Test
    @DisplayName("1.21.5 still read these leniently, so the file stays as shipped")
    void lenientAssetJsonUntouchedOn1215() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/ruby.json", COMMENTED,
                "assets/oldmod/equipment/ruby.json", LENIENT_EQUIPMENT,
                "assets/oldmod/post_effect/glow.json", LENIENT_POST_EFFECT));

        assertEquals(0, migrate(tree, "1.20.1", "1.21.5", FABRIC));
        assertEquals(COMMENTED, text(tree.read("assets/oldmod/blockstates/ruby.json")));
        assertEquals(LENIENT_EQUIPMENT, text(tree.read("assets/oldmod/equipment/ruby.json")));
        assertEquals(LENIENT_POST_EFFECT, text(tree.read("assets/oldmod/post_effect/glow.json")));
    }

    // ---- through the standard order

    @Test
    @DisplayName("On 26.3 an item model from an inventory variant also gets its item definition")
    void inventoryItemModelGetsItemDefinitionOn263() throws IOException {
        MapResourceTree tree = new MapResourceTree(entries(
                "assets/oldmod/blockstates/lift_red.json", TEXTURED_CUBE,
                "assets/oldmod/lang/en_us.lang", "tile.lift_red.name=Red Lift"));

        ModResourceMigrations.standard().run(tree, MigrationContext.builder("26.3").sourceMcVersion("1.12.2")
                .targetLoader(NEOFORGE).declaredNamespaces(List.of("oldmod")).build());

        assertTrue(tree.exists("assets/oldmod/models/item/lift_red.json"));
        assertEquals("oldmod:item/lift_red", json(tree, "assets/oldmod/items/lift_red.json")
                .getAsJsonObject("model").get("model").getAsString());
    }

    private static String modelFile(JsonObject variant) {
        String id = variant.get("model").getAsString();
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/models/" + id.substring(colon + 1) + ".json";
    }

    /** Reads JSON the way a strict host does: no comments, no trailing commas. */
    private static JsonElement parseStrictly(String json) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(false);
        JsonElement value = new Gson().getAdapter(JsonElement.class).read(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("trailing data");
        return value;
    }
}
