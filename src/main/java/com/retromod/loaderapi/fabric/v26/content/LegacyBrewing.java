/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.loaderapi.fabric.v26.content.shared.LegacyBrewingBuildCallback;
import com.retromod.loaderapi.fabric.v26.content.shared.LegacyPotionBrewingBuilder;
import com.retromod.shim.forge.embedded.LegacyDataPackSource;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.retromod.loaderapi.fabric.v26.content.LegacyContentRegistries.call;

/** Serves the old Fabric API brewing registries: through Fabric API's builder where it exists, as recipe data on 26.3. */
public final class LegacyBrewing {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The world data pack the recipes go into. */
    static final String PACK_ID = "retromod-legacy-brewing";

    // 26.1 and 26.2 still build brewing in code, through Fabric API's builder event.
    private static final String HOST_BUILDER = "net/fabricmc/fabric/api/registry/FabricPotionBrewingBuilder";
    static final Member HOST_BUILD = new Member(HOST_BUILDER, "BUILD", "Lnet/fabricmc/fabric/api/event/Event;", true);
    static final String HOST_BUILD_CALLBACK = HOST_BUILDER + "$BuildCallback";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    private static final String ITEM = "Lnet/minecraft/world/item/Item;";
    static final Member HOST_POTION_RECIPE = Member.virtual(HOST_BUILDER, "registerPotionRecipe",
            "(" + HOLDER + INGREDIENT + HOLDER + ")V");
    static final Member HOST_ITEM_RECIPE = Member.virtual(HOST_BUILDER, "registerItemRecipe",
            "(" + ITEM + INGREDIENT + ITEM + ")V");
    static final Member WRAP_AS_HOLDER = Member.virtual("net/minecraft/core/Registry", "wrapAsHolder",
            "(Ljava/lang/Object;)" + HOLDER);

    // 26.3 reads brewing from minecraft:brewing recipes, one per bottle.
    static final String BREWING_RECIPE = "net/minecraft/world/item/crafting/BrewingRecipe";
    static final Member POTIONS = new Member("net/minecraft/core/registries/BuiltInRegistries", "POTION",
            "Lnet/minecraft/core/Registry;", true);
    static final Member POTION_KEY = Member.virtual("net/minecraft/core/Registry", "getKey",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/Identifier;");
    static final Member POTION_IDS = Member.virtual("net/minecraft/core/Registry", "keySet", "()Ljava/util/Set;");
    static final Member HOLDER_VALUE = Member.virtual("net/minecraft/core/Holder", "value", "()Ljava/lang/Object;");
    static final Member INGREDIENT_ITEMS = Member.virtual("net/minecraft/world/item/crafting/Ingredient", "items",
            "()Ljava/util/stream/Stream;");
    static final Member WORLD_DATA = Member.virtual("net/minecraft/server/MinecraftServer", "getWorldData",
            "()Lnet/minecraft/world/level/storage/WorldData;");
    static final Member ENABLED_FEATURES = Member.virtual("net/minecraft/world/level/storage/WorldData",
            "enabledFeatures", "()Lnet/minecraft/world/flag/FeatureFlagSet;");

    private static final List<String> BOTTLES = List.of("minecraft:potion", "minecraft:splash_potion",
            "minecraft:lingering_potion");

    /**
     * One old recipe. A potion mix turns one potion into another in every bottle; a container mix
     * moves every potion from one bottle into another. Potions are holders, potions, or ids.
     */
    record Mix(boolean container, Object input, Object reagent, Object output) {}

    /** The builder an old {@code BUILD} callback gets on 26.3; it only records. */
    static final class Recorder implements LegacyPotionBrewingBuilder {
        final Object server;
        final List<Mix> mixes = new ArrayList<>();
        final List<Object> bottles = new ArrayList<>();

        Recorder(Object server) {
            this.server = server;
        }
    }

    private static final List<Mix> STATIC_MIXES = Collections.synchronizedList(new ArrayList<>());
    private static Event<LegacyBrewingBuildCallback> buildEvent;
    private static boolean writing;
    private static boolean replaying;

    private LegacyBrewing() {}

    /** The old {@code BUILD} event, fired with a recording builder once each server has started. */
    public static synchronized Event<LegacyBrewingBuildCallback> buildEvent() {
        if (buildEvent != null) return buildEvent;
        buildEvent = EventFactory.createArrayBacked(LegacyBrewingBuildCallback.class, callbacks -> builder -> {
            for (LegacyBrewingBuildCallback callback : callbacks) callback.build(builder);
        });
        try {
            writeOnServerStart();
        } catch (RuntimeException e) {
            // The mod's class setup reads this event, so a host refusal must not fail it.
            LOGGER.warn("Retromod cannot write the brewing recipes old mods add on this host: {}", e.toString());
        }
        return buildEvent;
    }

    // ---- Old builder calls, receiver first --------------------------------------------------------

    /** Vanilla {@code addMix(Holder, Item, Holder)}. */
    public static void addMix(Object builder, Object input, Object item, Object output) {
        record(builder, new Mix(false, input, item, output));
    }

    /** Fabric API {@code registerPotionRecipe(Holder, Ingredient, Holder)}. */
    public static void registerPotionRecipe(Object builder, Object input, Object ingredient, Object output) {
        record(builder, new Mix(false, input, ingredient, output));
    }

    /** Vanilla {@code addStartMix(Item, Holder)}: water gives mundane and awkward gives the potion. */
    public static void addStartMix(Object builder, Object item, Object potion) {
        record(builder, new Mix(false, "minecraft:water", item, "minecraft:mundane"));
        record(builder, new Mix(false, "minecraft:awkward", item, potion));
    }

    /** Fabric API {@code registerRecipes(Ingredient, Holder)}, the same pair of mixes. */
    public static void registerRecipes(Object builder, Object ingredient, Object potion) {
        addStartMix(builder, ingredient, potion);
    }

    /** Vanilla {@code addContainerRecipe(Item, Item, Item)}. */
    public static void addContainerRecipe(Object builder, Object input, Object item, Object output) {
        record(builder, new Mix(true, input, item, output));
    }

    /** Fabric API {@code registerItemRecipe(Item, Ingredient, Item)}. */
    public static void registerItemRecipe(Object builder, Object input, Object ingredient, Object output) {
        record(builder, new Mix(true, input, ingredient, output));
    }

    /** Vanilla {@code addContainer(Item)}: the old mixes of this build also apply to that bottle. */
    public static void addContainer(Object builder, Object item) {
        if (builder instanceof Recorder recorder) recorder.bottles.add(item);
    }

    /** Fabric API {@code getEnabledFeatures()}: the features of the world being started. */
    public static Object getEnabledFeatures(Object builder) {
        Object server = ((Recorder) builder).server;
        return call(HostLink.require(ENABLED_FEATURES), call(HostLink.require(WORLD_DATA), server));
    }

    private static void record(Object builder, Mix mix) {
        if (builder instanceof Recorder recorder) recorder.mixes.add(mix);
    }

    // ---- The 1.20.1 static registry -------------------------------------------------------------

    /** 1.20.1 {@code FabricBrewingRecipeRegistry.registerPotionRecipe(Potion, Ingredient, Potion)}. */
    public static void registerStaticPotionRecipe(Object input, Object ingredient, Object output) {
        STATIC_MIXES.add(new Mix(false, input, ingredient, output));
        listenForStaticMixes();
    }

    /** 1.20.1 {@code FabricBrewingRecipeRegistry.registerItemRecipe(PotionItem, Ingredient, PotionItem)}. */
    public static void registerStaticItemRecipe(Object input, Object ingredient, Object output) {
        STATIC_MIXES.add(new Mix(true, input, ingredient, output));
        listenForStaticMixes();
    }

    // Where the host reads brewing from data, the old mixes are written with the BUILD event's.
    // Before that, 26.1 and 26.2, they join each build of Fabric API's builder.
    private static synchronized void listenForStaticMixes() {
        if (HostLink.exists(BREWING_RECIPE)) {
            writeOnServerStart();
        } else {
            if (replaying) return;
            Class<?> type = HostLink.type(HOST_BUILD_CALLBACK).orElseThrow();
            Object listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "build" -> {
                            replay(args[0]);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "Retromod legacy brewing listener";
                    });
            call(HostLink.require(LegacyContentRegistries.REGISTER_LISTENER), call(HostLink.require(HOST_BUILD)), listener);
            replaying = true;
        }
    }

    /** Adds the 1.20.1 mixes to the host's own brewing builder. */
    static void replay(Object hostBuilder) {
        for (Mix mix : snapshot(STATIC_MIXES)) {
            try {
                if (mix.container()) {
                    call(HostLink.require(HOST_ITEM_RECIPE), hostBuilder, mix.input(), mix.reagent(), mix.output());
                } else {
                    call(HostLink.require(HOST_POTION_RECIPE), hostBuilder, holder(mix.input()), mix.reagent(),
                            holder(mix.output()));
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Skipped an old brewing recipe the host refused: {}", e.toString());
            }
        }
    }

    private static Object holder(Object potion) {
        if (HostLink.type("net/minecraft/core/Holder").orElseThrow().isInstance(potion)) return potion;
        return call(HostLink.require(WRAP_AS_HOLDER), call(HostLink.require(POTIONS)), potion);
    }

    // ---- 26.3: brewing recipes as data ----------------------------------------------------------

    // 26.3 loads recipes before the items they use can be read, and keeps them for the session,
    // so the recipes are written once the server has started and apply from the next start.
    private static synchronized void writeOnServerStart() {
        if (writing) return;
        LegacyWorldPacks.onServerStarted("legacy brewing", LegacyBrewing::writePack);
        writing = true;
    }

    /** Writes the old mixes into the world's data pack. */
    static void writePack(Object server) {
        try {
            List<Map<String, Object>> recipes = collect(server);
            Path root = LegacyWorldPacks.folder(server, PACK_ID);
            String before = LegacyWorldPacks.snapshot(root);
            int count = write(root, recipes, LegacyDataPackSource.packFormat());
            if (count > 0 && !before.equals(LegacyWorldPacks.snapshot(root))) {
                LOGGER.info("Wrote {} brewing recipe(s) from old mods to {}. They apply from the next start.", count, root);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not write the brewing recipes old mods registered: {}", e.toString());
        }
    }

    /** Fires the old event with a recording builder and returns its mixes, and the 1.20.1 ones, as recipes. */
    static List<Map<String, Object>> collect(Object server) {
        Recorder recorder = new Recorder(server);
        Event<LegacyBrewingBuildCallback> event = buildEvent;
        if (event != null) {
            try {
                event.invoker().build(recorder);
            } catch (RuntimeException e) {
                LOGGER.warn("An old brewing callback failed, so its later recipes are not added: {}", e.toString());
            }
        }
        List<Mix> mixes = new ArrayList<>(snapshot(STATIC_MIXES));
        mixes.addAll(recorder.mixes);
        return recipes(mixes, recorder.bottles);
    }

    /** Each mix as minecraft:brewing recipes, one per bottle or per potion it applies to. */
    static List<Map<String, Object>> recipes(List<Mix> mixes, List<Object> extraBottles) {
        List<String> bottles = new ArrayList<>(BOTTLES);
        for (Object bottle : extraBottles) {
            String id = itemId(bottle);
            if (id != null && !bottles.contains(id)) bottles.add(id);
        }
        List<Map<String, Object>> recipes = new ArrayList<>();
        for (Mix mix : mixes) {
            Object reagent = reagent(mix.reagent());
            if (reagent == null) {
                LOGGER.info("Skipped an old brewing recipe whose reagent matches no item");
                continue;
            }
            if (mix.container()) {
                String from = itemId(mix.input());
                String to = itemId(mix.output());
                if (from == null || to == null) continue;
                // A recipe's output is fixed, so the old "same potion, new bottle" is one recipe per potion.
                for (String potion : potionIds()) recipes.add(recipe(from, potion, reagent, to, potion));
            } else {
                String from = potionId(mix.input());
                String to = potionId(mix.output());
                if (from == null || to == null) {
                    LOGGER.info("Skipped an old brewing recipe with an unregistered potion");
                    continue;
                }
                for (String bottle : bottles) recipes.add(recipe(bottle, from, reagent, bottle, to));
            }
        }
        return recipes;
    }

    private static Map<String, Object> recipe(String inputBottle, String inputPotion, Object reagent,
            String outputBottle, String outputPotion) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("item", inputBottle);
        input.put("potion_contents", Map.of("potions", inputPotion));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", outputBottle);
        output.put("components", Map.of("minecraft:potion_contents", Map.of("potion", outputPotion)));
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("type", "minecraft:brewing");
        recipe.put("input", input);
        recipe.put("reagent", Map.of("item", reagent));
        recipe.put("output", output);
        return recipe;
    }

    /** An item's id, or a list of ids for an ingredient that matches several, or null. */
    private static Object reagent(Object reagent) {
        if (!HostLink.type("net/minecraft/world/item/crafting/Ingredient").orElseThrow().isInstance(reagent)) {
            return itemId(reagent);
        }
        List<String> ids = new ArrayList<>();
        try (Stream<?> items = (Stream<?>) call(HostLink.require(INGREDIENT_ITEMS), reagent)) {
            for (Object holder : items.toList()) {
                String id = itemId(call(HostLink.require(HOLDER_VALUE), holder));
                if (id != null && !ids.contains(id)) ids.add(id);
            }
        }
        if (ids.isEmpty()) return null;
        return ids.size() == 1 ? ids.get(0) : ids;
    }

    private static String itemId(Object item) {
        if (item == null) return null;
        Object id = call(HostLink.require(LegacyTrades.ITEM_KEY), call(HostLink.require(LegacyTrades.ITEMS)), item);
        return id == null ? null : id.toString();
    }

    private static String potionId(Object potion) {
        if (potion instanceof String id) return id;
        Object value = HostLink.type("net/minecraft/core/Holder").orElseThrow().isInstance(potion)
                ? call(HostLink.require(HOLDER_VALUE), potion) : potion;
        Object id = call(HostLink.require(POTION_KEY), call(HostLink.require(POTIONS)), value);
        return id == null ? null : id.toString();
    }

    private static Set<String> potionIds() {
        Set<String> ids = new TreeSet<>();
        for (Object id : (Set<?>) call(HostLink.require(POTION_IDS), call(HostLink.require(POTIONS)))) {
            ids.add(id.toString());
        }
        return ids;
    }

    /** Replaces the pack's data with these recipes and returns how many there are. */
    static int write(Path root, List<Map<String, Object>> recipes, int packFormat) throws IOException {
        LegacyWorldPacks.deleteTree(root.resolve("data"));
        if (recipes.isEmpty()) return 0;
        LegacyWorldPacks.writePackMeta(root, packFormat, "Retromod: brewing recipes from old mods");
        Path folder = root.resolve("data").resolve(LegacyWorldPacks.NAMESPACE).resolve("recipe").resolve("legacy_brewing");
        for (int i = 0; i < recipes.size(); i++) {
            LegacyWorldPacks.writeFile(folder.resolve(i + ".json"), GSON.toJson(recipes.get(i)));
        }
        return recipes.size();
    }

    private static List<Mix> snapshot(List<Mix> mixes) {
        synchronized (mixes) {
            return new ArrayList<>(mixes);
        }
    }

    /** Forgets every recorded recipe and listener, for tests that start a fresh host. */
    static synchronized void resetForTesting() {
        STATIC_MIXES.clear();
        buildEvent = null;
        writing = false;
        replaying = false;
    }
}
