/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import com.retromod.shim.common.embedded.LegacyLog;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Writes the trades an old mod's trade handlers add as a data pack, from the copy embedded in that mod. */
public final class LegacyTradeStore {

    // Reached by reflection, because Retromod is not compiled against Minecraft or a loader.
    private static final String SELF = "com.retromod.shim.forge.embedded.LegacyTradeStore";
    private static final int LEVELS = 5;
    /** The wandering trader's old lists, and the tags 26.x draws its offers from. */
    private static final Map<String, String> WANDERER_TAGS = Map.of(
            "generic", "minecraft:wandering_trader/common",
            "rare", "minecraft:wandering_trader/uncommon",
            "buying", "minecraft:wandering_trader/buying");
    // Forge 26.2 and newer run EventBus 7, where each event class has its own bus; NeoForge keeps one game bus.
    private static final String FORGE_EVENT_BUS = "net.minecraftforge.eventbus.api.bus.EventBus";

    private static String namespace;
    private static boolean listening;
    private static Path root;

    private LegacyTradeStore() {}

    /** Starts the store for the mod whose jar holds this copy; called from a trade handler's class initializer, and on Forge from the mod's constructor. */
    public static synchronized void watch() {
        if (listening) return;
        listening = true;
        try {
            boolean forge = onForge();
            Object mod = forge ? forgeMod() : neoForgeMod();
            String id = mod == null ? null : String.valueOf(LegacyPublicMethods.method(mod.getClass(), "getNamespace").invoke(mod));
            // The pack is named after the mod and writes under its namespace, and nothing may write under Minecraft's.
            if (id == null || id.equals("minecraft")) {
                log("found no mod jar that holds this trade store, so its trades are not added");
                return;
            }
            namespace = id;
            root = packFolder();
            // The folder is offered whenever the game looks for server data packs, and filled once the server has started.
            Consumer<Object> offer = LegacyTradeStore::onAddPackFinders;
            Consumer<Object> fill = LegacyTradeStore::onServerStarted;
            if (forge) {
                // Both events keep their bus in a static BUS field on Forge.
                addForgeListener("net.minecraftforge.event.AddPackFindersEvent", offer);
                addForgeListener("net.minecraftforge.event.server.ServerStartedEvent", fill);
                return;
            }
            Object modBus = LegacyPublicMethods.method(mod.getClass(), "getEventBus").invoke(mod);
            Object gameBus = Class.forName("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);
            addListener(modBus, "net.neoforged.neoforge.event.AddPackFindersEvent", offer);
            addListener(gameBus, "net.neoforged.neoforge.event.server.ServerStartedEvent", fill);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("cannot offer trades as a data pack on this loader: " + e);
        }
    }

    private static Path packFolder() {
        return gameDirectory().resolve("config").resolve("retromod").resolve("legacy-trades").resolve(packId());
    }

    private static Object neoForgeMod() throws ReflectiveOperationException {
        Object mods = Class.forName("net.neoforged.fml.ModList").getMethod("get").invoke(null);
        Object mod = owningMod((List<?>) LegacyPublicMethods.method(mods.getClass(), "getSortedMods").invoke(mods),
                "net.neoforged.fml", "net.neoforged.neoforgespi");
        String active = mod == null ? activeNamespace() : null;
        if (active == null) return mod;
        return orNull(LegacyPublicMethods.method(mods.getClass(), "getModContainerById", String.class).invoke(mods, active));
    }

    /** Forge 26.x keeps its mod list behind static methods. */
    private static Object forgeMod() throws ReflectiveOperationException {
        Class<?> mods = Class.forName("net.minecraftforge.fml.ModList");
        Object mod = owningMod((List<?>) mods.getMethod("getLoadedMods").invoke(null),
                "net.minecraftforge.fml", "net.minecraftforge.forgespi");
        String active = mod == null ? activeNamespace() : null;
        if (active == null) return mod;
        return orNull(mods.getMethod("getModContainerById", String.class).invoke(null, active));
    }

    /**
     * The mod whose jar holds this class. Each mod carries its own renamed copy of the store, and a
     * subscriber class can initialize while another mod, or none, is active.
     */
    private static Object owningMod(List<?> containers, String fml, String spi) throws ReflectiveOperationException {
        // Read through the public interfaces: the implementations sit in packages a mod's module may not reflect into.
        // A scanned class prints as its descriptor, such as clazz=Lmod/Store;
        String descriptor = "L" + LegacyTradeStore.class.getName().replace('.', '/') + ";";
        Method modInfo = Class.forName(fml + ".ModContainer").getMethod("getModInfo");
        Method owningFile = Class.forName(spi + ".language.IModInfo").getMethod("getOwningFile");
        Method file = Class.forName(spi + ".language.IModFileInfo").getMethod("getFile");
        Method scan = Class.forName(spi + ".locating.IModFile").getMethod("getScanResult");
        Method classes = Class.forName(spi + ".language.ModFileScanData").getMethod("getClasses");
        for (Object container : containers) {
            Object scanData = scan.invoke(file.invoke(owningFile.invoke(modInfo.invoke(container))));
            for (Object classData : (Collection<?>) classes.invoke(scanData)) {
                if (String.valueOf(classData).contains("clazz=" + descriptor)) return container;
            }
        }
        return null;
    }

    private static Object orNull(Object optional) {
        return optional instanceof Optional<?> present ? present.orElse(null) : null;
    }

    private static void addListener(Object bus, String eventClass, Consumer<Object> listener)
            throws ReflectiveOperationException {
        Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority");
        Class.forName("net.neoforged.bus.api.IEventBus")
                .getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                .invoke(bus, priority.getField("NORMAL").get(null), false, Class.forName(eventClass), listener);
    }

    /** {@code event.BUS.addListener(listener)}, at EventBus 7's normal priority. */
    private static void addForgeListener(String eventClass, Consumer<Object> listener) throws ReflectiveOperationException {
        Object bus = Class.forName(eventClass).getField("BUS").get(null);
        Class.forName(FORGE_EVENT_BUS).getMethod("addListener", Consumer.class).invoke(bus, listener);
    }

    /** Whether this is Forge 26.2 or newer, which has EventBus 7 where NeoForge has its own bus. */
    private static boolean onForge() {
        try {
            Class.forName(FORGE_EVENT_BUS, false, LegacyTradeStore.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError notForge) {
            return false;
        }
    }

    private static String packId() {
        return "retromod_legacy_trades_" + namespace;
    }

    /** Offers the folder, with the trades the last session wrote, so they apply without a reload. */
    private static void onAddPackFinders(Object event) {
        try {
            Object packType = LegacyPublicMethods.method(event.getClass(), "getPackType").invoke(event);
            if (!(packType instanceof Enum<?> type) || !type.name().equals("SERVER_DATA")) return;
            writeMetadata();
            Object source = LegacyDataPackSource.create(packId(), root);
            if (source == null) return;
            for (Method method : LegacyPublicMethods.methods(event.getClass())) {
                if (method.getName().equals("addRepositorySource") && method.getParameterCount() == 1) {
                    method.invoke(event, source);
                    return;
                }
            }
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not offer the trades of " + namespace + " as a data pack: " + e);
        }
    }

    // Handlers build item stacks, which 26.x can make only after data packs load, so they run once the
    // server has started. Trades are fixed for the session, so what they add applies from the next start.
    private static void onServerStarted(Object event) {
        try {
            String before = snapshot();
            int written = writeTrades(collectTrades());
            if (before.equals(snapshot())) return;
            info("wrote " + written + " trades from " + namespace + "; they apply from the next start, because"
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
        Method post = postMethod(loader);
        // The embed adds only the events the jar names, and nothing in the jar can listen to a missing one.
        Class<?> wandererEvent = generated("LegacyWandererTradesEvent");
        Class<?> villagerEvent = generated("LegacyVillagerTradesEvent");
        // Forge 1.20.1 and NeoForge posted the wandering trader's event first, then one per profession.
        if (wandererEvent != null) collectWandererTrades(byTag, wandererEvent, post, loader);
        if (villagerEvent != null) collectVillagerTrades(byTag, villagerEvent, post, loader);
        return byTag;
    }

    private static void collectWandererTrades(Map<String, List<Map<String, Object>>> byTag, Class<?> wandererEvent,
            Method post, ClassLoader loader) throws ReflectiveOperationException {
        Map<String, List<Object>> wanderer = new LinkedHashMap<>();
        for (String list : List.of("generic", "rare", "buying")) wanderer.put(list, new ArrayList<>());
        Object wandererTrades = wandererEvent.getConstructors()[0]
                .newInstance(wanderer.get("generic"), wanderer.get("rare"), wanderer.get("buying"));
        if (!postQuietly(post, busFor(wandererEvent, loader), wandererTrades)) return;
        for (Map.Entry<String, List<Object>> list : wanderer.entrySet()) {
            addAll(byTag, WANDERER_TAGS.get(list.getKey()), list.getValue(), loader);
        }
    }

    private static void collectVillagerTrades(Map<String, List<Map<String, Object>>> byTag, Class<?> villagerEvent,
            Method post, ClassLoader loader) throws ReflectiveOperationException {
        Object villagerBus = busFor(villagerEvent, loader);
        Class<?> intMap = Class.forName("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap", true, loader);
        for (Map.Entry<?, ?> profession : professions(loader)) {
            Object levels = intMap.getConstructor().newInstance();
            Method put = intMap.getMethod("put", int.class, Object.class);
            for (int level = 1; level <= LEVELS; level++) put.invoke(levels, level, new ArrayList<>());
            Object event = villagerEvent.getConstructors()[0].newInstance(levels, profession.getValue(), profession.getKey());
            if (!postQuietly(post, villagerBus, event)) continue;
            Method get = intMap.getMethod("get", int.class);
            for (int level = 1; level <= LEVELS; level++) {
                List<?> listings = (List<?>) get.invoke(levels, level);
                if (listings.isEmpty()) continue;
                // A profession with no trade set for the level, such as a mod's own one without trade data, gets nothing.
                Object tradeSet = LegacyPublicMethods.method(profession.getValue().getClass(), "getTrades", int.class)
                        .invoke(profession.getValue(), level);
                if (tradeSet == null) {
                    log("profession " + profession.getKey() + " has no level " + level + " trades, so "
                            + listings.size() + " added trade(s) are dropped");
                    continue;
                }
                addAll(byTag, identifier(tradeSet), listings, loader);
            }
        }
    }

    /** Each registered profession with its key, in the order the old loader walked them. */
    private static List<Map.Entry<?, ?>> professions(ClassLoader loader) throws ReflectiveOperationException {
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                .getField("VILLAGER_PROFESSION").get(null);
        List<Map.Entry<?, ?>> professions = new ArrayList<>();
        if (!onForge()) {
            for (Object entry : (Set<?>) LegacyPublicMethods.method(registry.getClass(), "entrySet").invoke(registry)) {
                professions.add((Map.Entry<?, ?>) entry);
            }
            return professions;
        }
        // Forge 1.20.1 walked the registry in id order, which is the order the registry iterates in.
        Method keyOf = LegacyPublicMethods.method(registry.getClass(), "getResourceKey", Object.class);
        for (Object profession : (Iterable<?>) registry) {
            Object key = orNull(keyOf.invoke(registry, profession));
            if (key != null) professions.add(Map.entry(key, profession));
        }
        return professions;
    }

    /** The post method of the game bus: NeoForge's {@code IEventBus.post}, or EventBus 7's {@code EventBus.post} on Forge. */
    private static Method postMethod(ClassLoader loader) throws ReflectiveOperationException {
        if (!onForge()) {
            return Class.forName("net.neoforged.bus.api.IEventBus", true, loader)
                    .getMethod("post", Class.forName("net.neoforged.bus.api.Event", true, loader));
        }
        // EventBus 7 declares post on its generic event type, whose erasure sits in a closed package, so it is found by name.
        for (Method method : Class.forName(FORGE_EVENT_BUS, true, loader).getMethods()) {
            if (method.getName().equals("post") && method.getParameterCount() == 1) return method;
        }
        throw new NoSuchMethodException(FORGE_EVENT_BUS + ".post");
    }

    /** The bus that delivers a generated event: NeoForge's game bus, or on Forge the bus the event keeps in BUS. */
    private static Object busFor(Class<?> event, ClassLoader loader) throws ReflectiveOperationException {
        if (onForge()) return event.getField("BUS").get(null);
        return Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader).getField("EVENT_BUS").get(null);
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

    /** One listing as {@code villager_trade} fields, or null when it cannot become data. Item components are not carried. */
    private static Map<String, Object> trade(Object listing, ClassLoader loader) {
        try {
            Object price;
            Object price2;
            Object result;
            Number maxUses;
            Number xp;
            Number multiplier;
            // Forge's BasicItemListing carries its stacks, so it converts exactly.
            Field forSale = field(listing, "forSale");
            if (forSale != null) {
                price = field(listing, "price").get(listing);
                price2 = field(listing, "price2").get(listing);
                result = forSale.get(listing);
                maxUses = (Number) field(listing, "maxTrades").get(listing);
                xp = (Number) field(listing, "xp").get(listing);
                multiplier = (Number) field(listing, "priceMult").get(listing);
            } else {
                // Any other listing gives one offer: a random one keeps what it picked, and one that needs a villager is skipped.
                Object random = Class.forName("net.minecraft.util.RandomSource", true, loader).getMethod("create").invoke(null);
                Method getOffer = null;
                for (Method method : LegacyPublicMethods.methods(listing.getClass())) {
                    if (method.getName().equals("getOffer") && method.getParameterCount() == 2) getOffer = method;
                }
                Object offer = getOffer == null ? null : getOffer.invoke(listing, null, random);
                if (offer == null) return null;
                price = LegacyPublicMethods.method(offer.getClass(), "getCostA").invoke(offer);
                price2 = LegacyPublicMethods.method(offer.getClass(), "getCostB").invoke(offer);
                result = LegacyPublicMethods.method(offer.getClass(), "getResult").invoke(offer);
                maxUses = (Number) LegacyPublicMethods.method(offer.getClass(), "getMaxUses").invoke(offer);
                xp = (Number) LegacyPublicMethods.method(offer.getClass(), "getXp").invoke(offer);
                multiplier = (Number) LegacyPublicMethods.method(offer.getClass(), "getPriceMultiplier").invoke(offer);
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
        if (stack == null || (Boolean) LegacyPublicMethods.method(stack.getClass(), "isEmpty").invoke(stack)) return null;
        Object item = LegacyPublicMethods.method(stack.getClass(), "getItem").invoke(stack);
        Object items = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader).getField("ITEM").get(null);
        Method getKey = null;
        for (Method method : LegacyPublicMethods.methods(items.getClass())) {
            if (method.getName().equals("getKey") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Object.class) {
                getKey = method;
            }
        }
        if (getKey == null) return null;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", String.valueOf(getKey.invoke(items, item)));
        json.put("count", LegacyPublicMethods.method(stack.getClass(), "getCount").invoke(stack));
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
                // Optional, so a stale entry never stops the tag, and vanilla's trades, from loading.
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", namespace + ":" + path);
                entry.put("required", false);
                values.add(entry);
            }
            // Appended, so vanilla's trades and other packs' additions stay in the tag the trade set reads.
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
        return String.valueOf(LegacyPublicMethods.method(resourceKey.getClass(), "identifier").invoke(resourceKey));
    }

    private static Field field(Object owner, String name) {
        try {
            return owner.getClass().getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** A class Retromod generated into this mod beside this one under its embed prefix, or null when the jar never named it. */
    private static Class<?> generated(String simpleName) {
        String self = LegacyTradeStore.class.getName();
        String prefix = self.substring(0, self.length() - SELF.length());
        try {
            return Class.forName(prefix + "com.retromod.generated." + simpleName, true, LegacyTradeStore.class.getClassLoader());
        } catch (ClassNotFoundException notInThisJar) {
            return null;
        }
    }

    /** FML's package on this loader: Forge kept its own names for the classes NeoForge renamed. */
    private static String fml() {
        return onForge() ? "net.minecraftforge.fml" : "net.neoforged.fml";
    }

    private static String activeNamespace() {
        try {
            Object context = Class.forName(fml() + ".ModLoadingContext").getMethod("get").invoke(null);
            Object active = LegacyPublicMethods.method(context.getClass(), "getActiveNamespace").invoke(context);
            // With no mod active both loaders answer minecraft, which is no mod's pack.
            return active == null || active.toString().equals("minecraft") ? null : active.toString();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static Path gameDirectory() {
        try {
            Object gameDir = Class.forName(fml() + ".loading.FMLPaths").getField("GAMEDIR").get(null);
            if (LegacyPublicMethods.method(gameDir.getClass(), "get").invoke(gameDir) instanceof Path path) return path;
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
        LegacyLog.warn("Legacy trades: " + message);
    }

    private static void info(String message) {
        LegacyLog.info("Legacy trades: " + message);
    }
}
