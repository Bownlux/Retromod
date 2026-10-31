/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.shim.forge.embedded.LegacyDataPackSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.loaderapi.fabric.v26.content.LegacyContentRegistries.call;

/** Serves the old Fabric API TradeOfferHelper on 26.x, where villager and wandering trader trades are data. */
public final class LegacyTrades {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The world data pack the trades go into, and the namespace of the trades in it. */
    static final String PACK_ID = "retromod-legacy-trades";
    static final String NAMESPACE = LegacyWorldPacks.NAMESPACE;

    // The old wandering trader levels: 1 was the common list and 2 the rare one.
    private static final Map<Integer, String> WANDERER_TAGS = Map.of(
            1, "minecraft:wandering_trader/common", 2, "minecraft:wandering_trader/uncommon");

    private static final String PROFESSION = "net/minecraft/world/entity/npc/villager/VillagerProfession";
    static final Member TRADE_SET = Member.virtual(PROFESSION, "getTrades",
            "(I)Lnet/minecraft/resources/ResourceKey;");
    static final Member PROFESSIONS = new Member("net/minecraft/core/registries/BuiltInRegistries",
            "VILLAGER_PROFESSION", "Lnet/minecraft/core/DefaultedRegistry;", true);
    static final Member REGISTRY_VALUE = Member.virtual("net/minecraft/core/Registry", "getValue",
            "(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;");
    static final Member KEY_ID = Member.virtual("net/minecraft/resources/ResourceKey", "identifier",
            "()Lnet/minecraft/resources/Identifier;");
    static final Member ITEMS = new Member("net/minecraft/core/registries/BuiltInRegistries", "ITEM",
            "Lnet/minecraft/core/DefaultedRegistry;", true);
    static final Member ITEM_KEY = Member.virtual("net/minecraft/core/DefaultedRegistry", "getKey",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/Identifier;");
    static final Member NEW_RANDOM = Member.staticMethod("net/minecraft/util/RandomSource", "create",
            "()Lnet/minecraft/util/RandomSource;");

    /** One old registration: a profession or, for the wandering trader, null, and the mod's callback. */
    private record Registration(Object profession, int level, Consumer<Object> factories) {}

    private static final List<Registration> REGISTRATIONS = Collections.synchronizedList(new ArrayList<>());
    private static boolean listening;

    private LegacyTrades() {}

    /** Old {@code registerVillagerOffers(VillagerProfession or its key, int, Consumer)}. */
    public static void registerVillagerOffers(Object profession, int level, Consumer<Object> factories) {
        REGISTRATIONS.add(new Registration(profession, level, factories));
        listen();
    }

    /** Old {@code registerWanderingTraderOffers(int, Consumer)}. */
    public static void registerWanderingTraderOffers(int level, Consumer<Object> factories) {
        REGISTRATIONS.add(new Registration(null, level, factories));
        listen();
    }

    /** Old {@code refreshOffers()}: 26.x reads trades once per start, so there is nothing to refresh. */
    public static void refreshOffers() {
    }

    // 26.x loads trades before item stacks can be built and keeps them for the session, so the
    // trades are written once the server has started and apply from the next start.
    private static synchronized void listen() {
        if (listening) return;
        LegacyWorldPacks.onServerStarted("legacy trades", LegacyTrades::writePack);
        listening = true;
    }

    /** Writes the old trades into the world's data pack folder, where the next start picks them up. */
    static void writePack(Object server) {
        try {
            Map<String, List<Map<String, Object>>> byTag = collect();
            Path root = LegacyWorldPacks.folder(server, PACK_ID);
            String before = LegacyWorldPacks.snapshot(root);
            int count = write(root, byTag, LegacyDataPackSource.packFormat());
            if (count > 0 && !before.equals(LegacyWorldPacks.snapshot(root))) {
                LOGGER.info("Wrote {} trade(s) from old mods to {}. They apply from the next start.", count, root);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not write the trades old mods registered: {}", e.toString());
        }
    }

    /** Asks each old callback for its listings and groups them by the villager_trade tag they join. */
    static Map<String, List<Map<String, Object>>> collect() {
        Map<String, List<Map<String, Object>>> byTag = new LinkedHashMap<>();
        List<Registration> registrations;
        synchronized (REGISTRATIONS) {
            registrations = new ArrayList<>(REGISTRATIONS);
        }
        for (Registration registration : registrations) {
            String tag = registration.profession() == null ? WANDERER_TAGS.get(registration.level())
                    : tradeSet(registration.profession(), registration.level());
            if (tag == null) {
                LOGGER.info("No 26.x trade list matches the old {} level {}, so its trades are not added",
                        registration.profession() == null ? "wandering trader" : registration.profession(),
                        registration.level());
                continue;
            }
            List<Object> listings = new ArrayList<>();
            try {
                registration.factories().accept(listings);
            } catch (RuntimeException e) {
                LOGGER.warn("An old trade callback failed, so its trades are not added: {}", e.toString());
                continue;
            }
            for (Object listing : listings) {
                Map<String, Object> trade = trade(listing);
                if (trade != null) byTag.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(trade);
            }
        }
        return byTag;
    }

    /** The trade set a profession reads at {@code level}, whose id is also its villager_trade tag. */
    private static String tradeSet(Object profession, int level) {
        Object resolved = profession;
        // 1.21.5 and newer mods pass the profession's registry key.
        if (!HostLink.type(PROFESSION).orElseThrow().isInstance(profession)) {
            resolved = call(HostLink.require(REGISTRY_VALUE), call(HostLink.require(PROFESSIONS)), profession);
        }
        if (resolved == null) return null;
        Object key = call(HostLink.require(TRADE_SET), resolved, level);
        return key == null ? null : String.valueOf(call(HostLink.require(KEY_ID), key));
    }

    /** One listing as villager_trade fields, or null when it cannot become data. */
    static Map<String, Object> trade(Object listing) {
        try {
            // Asked for one offer with no villager, as the trade becomes fixed data.
            Method getOffer = offerMethod(listing.getClass());
            if (getOffer == null) return null;
            Object offer = getOffer.invoke(listing, null, call(HostLink.require(NEW_RANDOM)));
            if (offer == null) return null;
            Map<String, Object> wants = stack(offer.getClass().getMethod("getCostA").invoke(offer));
            Map<String, Object> gives = stack(offer.getClass().getMethod("getResult").invoke(offer));
            if (wants == null || gives == null) return null;
            Map<String, Object> trade = new LinkedHashMap<>();
            trade.put("wants", wants);
            Map<String, Object> additional = stack(offer.getClass().getMethod("getCostB").invoke(offer));
            if (additional != null) trade.put("additional_wants", additional);
            trade.put("gives", gives);
            trade.put("max_uses", offer.getClass().getMethod("getMaxUses").invoke(offer));
            trade.put("xp", offer.getClass().getMethod("getXp").invoke(offer));
            trade.put("reputation_discount", offer.getClass().getMethod("getPriceMultiplier").invoke(offer));
            return trade;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.info("Skipped an old trade that could not be built without a villager: {}", cause.toString());
            return null;
        }
    }

    /** The listing's {@code getOffer}, from its interface, because a lambda's own class is not accessible. */
    private static Method offerMethod(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                for (Method method : iface.getMethods()) {
                    if (method.getName().equals("getOffer") && method.getParameterCount() == 2) return method;
                }
            }
        }
        return null;
    }

    /** {@code {"id", "count"}} for a non-empty stack, or null. */
    private static Map<String, Object> stack(Object stack) throws ReflectiveOperationException {
        if (stack == null || (Boolean) stack.getClass().getMethod("isEmpty").invoke(stack)) return null;
        Object item = stack.getClass().getMethod("getItem").invoke(stack);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", String.valueOf(call(HostLink.require(ITEM_KEY), call(HostLink.require(ITEMS)), item)));
        json.put("count", stack.getClass().getMethod("getCount").invoke(stack));
        return json;
    }

    /** Replaces the pack's data with these trades and returns how many there are. */
    static int write(Path root, Map<String, List<Map<String, Object>>> byTag, int packFormat) throws IOException {
        LegacyWorldPacks.deleteTree(root.resolve("data"));
        if (byTag.isEmpty()) return 0;
        LegacyWorldPacks.writePackMeta(root, packFormat, "Retromod: trades from old mods");
        int count = 0;
        for (Map.Entry<String, List<Map<String, Object>>> tag : byTag.entrySet()) {
            String[] tagId = tag.getKey().split(":", 2);
            List<Object> values = new ArrayList<>();
            int index = 0;
            for (Map<String, Object> trade : tag.getValue()) {
                count++;
                String path = "legacy/" + tagId[0] + "/" + tagId[1] + "/" + index++;
                LegacyWorldPacks.writeFile(root.resolve("data").resolve(NAMESPACE).resolve("villager_trade").resolve(path + ".json"),
                        GSON.toJson(trade));
                // Optional, so a stale entry never stops the tag, and vanilla's trades, from loading.
                // An ordered map, so the same trades write the same file on every start.
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", NAMESPACE + ":" + path);
                entry.put("required", false);
                values.add(entry);
            }
            Map<String, Object> tagJson = new LinkedHashMap<>();
            tagJson.put("replace", false);
            tagJson.put("values", values);
            LegacyWorldPacks.writeFile(root.resolve("data").resolve(tagId[0]).resolve("tags").resolve("villager_trade")
                    .resolve(tagId[1] + ".json"), GSON.toJson(tagJson));
        }
        return count;
    }

    /** Forgets every recorded registration, for tests that start a fresh host. */
    static synchronized void resetForTesting() {
        REGISTRATIONS.clear();
        listening = false;
    }
}
