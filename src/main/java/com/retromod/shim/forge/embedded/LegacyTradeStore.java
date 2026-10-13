/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runtime half of {@link com.retromod.shim.forge.LegacyTradeBridge}. Embedded into each mod that
 * handles a trade event and reflective, because Retromod is not compiled against Minecraft or
 * NeoForge; their names are Mojang's and NeoForge's on every host this runs on.
 *
 * <p>The folder is offered as a data pack whenever the game looks for server data packs. The
 * handlers cannot run then: 26.x binds item components after data packs load, and a handler builds
 * item stacks. Once the server has started, the mod's trade handlers get one event per profession
 * and one for the wandering trader. What they add is written as {@code villager_trade} entries and
 * appended to the tags the trade sets read. 26.x fixes data-driven registries for the whole
 * session, so new trades take effect from the next start; the first start with the mod, or with a
 * changed mod, writes them. Each tag entry is optional, so a stale entry never stops a tag, and
 * vanilla's trades, from loading.
 */
public final class LegacyTradeStore {

    private static final String SELF = "com.retromod.shim.forge.embedded.LegacyTradeStore";
    private static final int LEVELS = 5;
    /** The wandering trader's old lists, and the tags 26.x draws its offers from. */
    private static final Map<String, String> WANDERER_TAGS = Map.of(
            "generic", "minecraft:wandering_trader/common",
            "rare", "minecraft:wandering_trader/uncommon",
            "buying", "minecraft:wandering_trader/buying");

    private static String namespace;
    private static boolean listening;
    private static Path root;

    private LegacyTradeStore() {}

    /** Called from a trade handler's class initializer, while NeoForge constructs the mod. */
    public static synchronized void watch() {
        if (listening) return;
        listening = true;
        try {
            namespace = activeNamespace();
            if (namespace == null) {
                log("could not tell which mod handles trade events, so its trades are not added");
                return;
            }
            Object container = Class.forName("net.neoforged.fml.ModList").getMethod("get").invoke(null);
            Object found = container.getClass().getMethod("getModContainerById", String.class).invoke(container, namespace);
            Object mod = found instanceof Optional<?> optional ? optional.orElse(null) : null;
            if (mod == null) return;
            root = gameDirectory().resolve("config").resolve("retromod").resolve("legacy-trades").resolve(packId());
            Object modBus = mod.getClass().getMethod("getEventBus").invoke(mod);
            Object gameBus = Class.forName("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
            Consumer<Object> offer = LegacyTradeStore::onAddPackFinders;
            Consumer<Object> fill = LegacyTradeStore::onServerStarted;
            addListener(modBus, "net.neoforged.neoforge.event.AddPackFindersEvent", offer);
            addListener(gameBus, "net.neoforged.neoforge.event.server.ServerStartedEvent", fill);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("cannot offer trades as a data pack on this loader: " + e);
        }
    }

    private static void addListener(Object bus, String eventClass, Consumer<Object> listener)
            throws ReflectiveOperationException {
        Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority");
        Class.forName("net.neoforged.bus.api.IEventBus")
                .getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                .invoke(bus, priority.getField("NORMAL").get(null), false, Class.forName(eventClass), listener);
    }

    private static String packId() {
        return "retromod_legacy_trades_" + namespace;
    }

    /** Offers the folder, with the trades the last session wrote, so they apply without a reload. */
    private static void onAddPackFinders(Object event) {
        try {
            Object packType = event.getClass().getMethod("getPackType").invoke(event);
            if (!(packType instanceof Enum<?> type) || !type.name().equals("SERVER_DATA")) return;
            writeMetadata();
            Object source = LegacyDataPackSource.create(packId(), root);
            if (source == null) return;
            for (Method method : event.getClass().getMethods()) {
                if (method.getName().equals("addRepositorySource") && method.getParameterCount() == 1) {
                    method.invoke(event, source);
                    return;
                }
            }
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not offer the trades of " + namespace + " as a data pack: " + e);
        }
    }

    private static void onServerStarted(Object event) {
        try {
            String before = snapshot();
            int written = writeTrades(collectTrades());
            if (before.equals(snapshot())) return;
            log("wrote " + written + " trades from " + namespace + "; they apply from the next start, because"
                    + " this Minecraft version loads trades before a mod can build the items they use");
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log("could not add the trades of " + namespace + ": " + cause);
        }
    }

    /** Posts the old events and returns the trades they collected, by the tag each one joins. */
    static Map<String, List<Map<String, Object>>> collectTrades() throws ReflectiveOperationException {
        Map<String, List<Map<String, Object>>> byTag = new LinkedHashMap<>();
        ClassLoader loader = LegacyTradeStore.class.getClassLoader();
        Object eventBus = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader)
                .getField("EVENT_BUS").get(null);
        Method post = Class.forName("net.neoforged.bus.api.IEventBus", true, loader)
                .getMethod("post", Class.forName("net.neoforged.bus.api.Event", true, loader));
        Object professions = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                .getField("VILLAGER_PROFESSION").get(null);
        Class<?> intMap = Class.forName("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap", true, loader);
        Class<?> villagerEvent = generated("LegacyVillagerTradesEvent");

        for (Object entry : (Set<?>) professions.getClass().getMethod("entrySet").invoke(professions)) {
            Map.Entry<?, ?> profession = (Map.Entry<?, ?>) entry;
            Object levels = intMap.getConstructor().newInstance();
            Method put = intMap.getMethod("put", int.class, Object.class);
            for (int level = 1; level <= LEVELS; level++) put.invoke(levels, level, new ArrayList<>());
            Object event = villagerEvent.getConstructors()[0].newInstance(levels, profession.getValue(), profession.getKey());
            if (!postQuietly(post, eventBus, event)) continue;
            Method get = intMap.getMethod("get", int.class);
            for (int level = 1; level <= LEVELS; level++) {
                List<?> listings = (List<?>) get.invoke(levels, level);
                if (listings.isEmpty()) continue;
                Object tradeSet = profession.getValue().getClass().getMethod("getTrades", int.class)
                        .invoke(profession.getValue(), level);
                if (tradeSet == null) {
                    log("profession " + profession.getKey() + " has no level " + level + " trades, so "
                            + listings.size() + " added trade(s) are dropped");
                    continue;
                }
                addAll(byTag, identifier(tradeSet), listings, loader);
            }
        }

        Map<String, List<Object>> wanderer = new LinkedHashMap<>();
        for (String list : List.of("generic", "rare", "buying")) wanderer.put(list, new ArrayList<>());
        Object event = generated("LegacyWandererTradesEvent").getConstructors()[0]
                .newInstance(wanderer.get("generic"), wanderer.get("rare"), wanderer.get("buying"));
        if (postQuietly(post, eventBus, event)) {
            for (Map.Entry<String, List<Object>> list : wanderer.entrySet()) {
                addAll(byTag, WANDERER_TAGS.get(list.getKey()), list.getValue(), loader);
            }
        }
        return byTag;
    }

    private static boolean postQuietly(Method post, Object bus, Object event) {
        try {
            post.invoke(bus, event);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log("a trade handler in " + namespace + " failed, so its trades for this event are not added: " + cause);
            return false;
        }
    }

    private static void addAll(Map<String, List<Map<String, Object>>> byTag, String tag, List<?> listings,
            ClassLoader loader) {
        for (Object listing : listings) {
            Map<String, Object> trade = trade(listing, loader);
            if (trade != null) byTag.computeIfAbsent(tag, t -> new ArrayList<>()).add(trade);
        }
    }

    /** One listing as {@code villager_trade} fields, or null when it cannot become data. */
    private static Map<String, Object> trade(Object listing, ClassLoader loader) {
        try {
            Object price;
            Object price2;
            Object result;
            Number maxUses;
            Number xp;
            Number multiplier;
            Field forSale = field(listing, "forSale");
            if (forSale != null) {
                price = field(listing, "price").get(listing);
                price2 = field(listing, "price2").get(listing);
                result = forSale.get(listing);
                maxUses = (Number) field(listing, "maxTrades").get(listing);
                xp = (Number) field(listing, "xp").get(listing);
                multiplier = (Number) field(listing, "priceMult").get(listing);
            } else {
                Object random = Class.forName("net.minecraft.util.RandomSource", true, loader).getMethod("create").invoke(null);
                Method getOffer = null;
                for (Method method : listing.getClass().getMethods()) {
                    if (method.getName().equals("getOffer") && method.getParameterCount() == 2) getOffer = method;
                }
                Object offer = getOffer == null ? null : getOffer.invoke(listing, null, random);
                if (offer == null) return null;
                price = offer.getClass().getMethod("getCostA").invoke(offer);
                price2 = offer.getClass().getMethod("getCostB").invoke(offer);
                result = offer.getClass().getMethod("getResult").invoke(offer);
                maxUses = (Number) offer.getClass().getMethod("getMaxUses").invoke(offer);
                xp = (Number) offer.getClass().getMethod("getXp").invoke(offer);
                multiplier = (Number) offer.getClass().getMethod("getPriceMultiplier").invoke(offer);
            }
            Map<String, Object> wants = stack(price, loader);
            Map<String, Object> gives = stack(result, loader);
            if (wants == null || gives == null) return null;
            Map<String, Object> trade = new LinkedHashMap<>();
            trade.put("wants", wants);
            Map<String, Object> additional = stack(price2, loader);
            if (additional != null) trade.put("additional_wants", additional);
            trade.put("gives", gives);
            trade.put("max_uses", maxUses.intValue());
            trade.put("xp", xp.intValue());
            trade.put("reputation_discount", multiplier.floatValue());
            return trade;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log("skipped a trade from " + namespace + " that could not be read without a villager: " + cause);
            return null;
        }
    }

    /** {@code {"id", "count"}} for a non-empty stack, or null. */
    private static Map<String, Object> stack(Object stack, ClassLoader loader) throws ReflectiveOperationException {
        if (stack == null || (Boolean) stack.getClass().getMethod("isEmpty").invoke(stack)) return null;
        Object item = stack.getClass().getMethod("getItem").invoke(stack);
        Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader).getField("ITEM").get(null);
        Method getKey = null;
        for (Method method : items.getClass().getMethods()) {
            if (method.getName().equals("getKey") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Object.class) {
                getKey = method;
            }
        }
        if (getKey == null) return null;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", String.valueOf(getKey.invoke(items, item)));
        json.put("count", stack.getClass().getMethod("getCount").invoke(stack));
        return json;
    }

    private static void writeMetadata() throws IOException {
        int format = LegacyDataPackSource.packFormat();
        write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + format + ",\"min_format\":"
                + format + ",\"max_format\":" + format + ",\"description\":\"Retromod legacy trades\"}}");
    }

    /** Replaces the folder's data with these trades and returns how many there are. */
    static int writeTrades(Map<String, List<Map<String, Object>>> byTag) throws IOException {
        deleteTree(root.resolve("data"));
        int count = 0;
        for (Map.Entry<String, List<Map<String, Object>>> tag : byTag.entrySet()) {
            String[] tagId = split(tag.getKey());
            List<Object> values = new ArrayList<>();
            int index = 0;
            for (Map<String, Object> trade : tag.getValue()) {
                count++;
                String path = "legacy/" + tagId[0] + "/" + tagId[1] + "/" + index++;
                write(root.resolve("data").resolve(namespace).resolve("villager_trade").resolve(path + ".json"), json(trade));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", namespace + ":" + path);
                entry.put("required", false);
                values.add(entry);
            }
            Map<String, Object> tagJson = new LinkedHashMap<>();
            tagJson.put("replace", false);
            tagJson.put("values", values);
            write(root.resolve("data").resolve(tagId[0]).resolve("tags").resolve("villager_trade")
                    .resolve(tagId[1] + ".json"), json(tagJson));
        }
        return count;
    }

    /** The folder's data files and contents, to tell whether a write changed anything. */
    private static String snapshot() throws IOException {
        Path data = root.resolve("data");
        if (!Files.exists(data)) return "";
        StringBuilder out = new StringBuilder();
        try (Stream<Path> walk = Files.walk(data)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.append(data.relativize(file)).append('\n').append(Files.readString(file)).append('\n');
            }
        }
        return out.toString();
    }

    private static void write(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /** JSON for the maps, lists, strings, numbers, and booleans this store builds. */
    static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(json(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) {
                if (out.length() > 1) out.append(',');
                out.append(json(item));
            }
            return out.append(']').toString();
        }
        if (value instanceof String text) {
            return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return String.valueOf(value);
    }

    private static String[] split(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? new String[] {"minecraft", id} : new String[] {id.substring(0, colon), id.substring(colon + 1)};
    }

    private static String identifier(Object resourceKey) throws ReflectiveOperationException {
        return String.valueOf(resourceKey.getClass().getMethod("identifier").invoke(resourceKey));
    }

    private static Field field(Object owner, String name) {
        try {
            return owner.getClass().getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** A class Retromod generated into this mod, which sits beside this one under the mod's embed prefix. */
    private static Class<?> generated(String simpleName) throws ClassNotFoundException {
        String self = LegacyTradeStore.class.getName();
        String prefix = self.substring(0, self.length() - SELF.length());
        return Class.forName(prefix + "com.retromod.generated." + simpleName, true, LegacyTradeStore.class.getClassLoader());
    }

    private static String activeNamespace() {
        try {
            Object context = Class.forName("net.neoforged.fml.ModLoadingContext").getMethod("get").invoke(null);
            Object active = context.getClass().getMethod("getActiveNamespace").invoke(context);
            return active == null ? null : active.toString();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static Path gameDirectory() {
        try {
            Object gameDir = Class.forName("net.neoforged.fml.loading.FMLPaths").getField("GAMEDIR").get(null);
            if (gameDir.getClass().getMethod("get").invoke(gameDir) instanceof Path path) return path;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // The working directory is the game directory on every launcher.
        }
        return Path.of("").toAbsolutePath();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void log(String message) {
        System.out.println("[Retromod] Legacy trades: " + message);
    }
}
