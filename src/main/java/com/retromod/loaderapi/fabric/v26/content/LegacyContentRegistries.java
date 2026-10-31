/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Serves the old Fabric API content registries on hosts that moved their jobs to data and item components. */
public final class LegacyContentRegistries {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String ITEM_LIKE = "Lnet/minecraft/world/level/ItemLike;";
    private static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String EVENT = "Lnet/fabricmc/fabric/api/event/Event;";

    // Fabric API 26.3 builds the axe, hoe and shovel transforms with the old sounds and rules: a
    // stripped block keeps the input's properties, and tilling and flattening need air above and
    // refuse the bottom face, as HoeItem.onlyIfAirAbove and the old shovel did.
    private static final String TRANSFORMERS = "net/fabricmc/fabric/api/item/v1/BlockTransformerHelper";
    static final Member REGISTER_STRIPPING = Member.staticMethod(TRANSFORMERS, "registerStripping",
            "(" + BLOCK + BLOCK + ")V");
    static final Member REGISTER_TILLING = Member.staticMethod(TRANSFORMERS, "registerTilling",
            "(" + BLOCK + STATE + ")V");
    static final Member REGISTER_FLATTENING = Member.staticMethod(TRANSFORMERS, "registerFlattening",
            "(" + BLOCK + STATE + ")V");

    // 26.3 keeps compost chances and fuel times in item components, set through Fabric API.
    private static final String COMPONENT_EVENTS = "net/fabricmc/fabric/api/item/v1/DefaultItemComponentEvents";
    static final Member COMPONENTS_MODIFY = new Member(COMPONENT_EVENTS, "MODIFY", EVENT, true);
    static final String MODIFY_CALLBACK = COMPONENT_EVENTS + "$ModifyCallback";
    static final Member MODIFY_ITEM = Member.virtual(COMPONENT_EVENTS + "$ModifyContext", "modify",
            "(Lnet/minecraft/world/item/Item;Ljava/util/function/Consumer;)V");
    private static final String COMPONENT_TYPE = "Lnet/minecraft/core/component/DataComponentType;";
    static final Member BUILDER_SET = Member.virtual("net/minecraft/core/component/DataComponentMap$Builder",
            "set", "(" + COMPONENT_TYPE + "Ljava/lang/Object;)Lnet/minecraft/core/component/DataComponentMap$Builder;");
    static final Member COMPOSTABLE = new Member("net/minecraft/core/component/DataComponents",
            "COMPOSTABLE", COMPONENT_TYPE, true);
    static final Member COOKING_FUEL = new Member("net/minecraft/core/component/DataComponents",
            "COOKING_FUEL", COMPONENT_TYPE, true);
    static final Member VILLAGER_FOOD = new Member("net/minecraft/core/component/DataComponents",
            "VILLAGER_FOOD", COMPONENT_TYPE, true);
    static final Member NEW_VILLAGER_FOOD = Member.constructor("net/minecraft/world/food/VillagerFood", "(I)V");
    private static final String INTS = "net/minecraft/world/level/storage/loot/providers/number/ints/";
    private static final String FLOATS = "net/minecraft/world/level/storage/loot/providers/number/floats/";
    static final Member NEW_COMPOSTABLE = Member.constructor("net/minecraft/world/item/component/Compostable",
            "(" + RESOURCE_KEY + ")V");
    static final Member NEW_COOKING_FUEL = Member.constructor("net/minecraft/world/item/component/CookingFuel",
            "(L" + INTS + "ResolvableInt;L" + FLOATS + "ResolvableFloat;)V");
    static final Member NEW_INT_CONSTANT = Member.constructor(INTS + "ResolvableInt$Constant", "(I)V");
    static final Member FLOAT_FROM_KEY = Member.staticMethod(FLOATS + "ResolvableFloat", "fromKey",
            "(" + RESOURCE_KEY + ")L" + FLOATS + "ResolvableFloat;");
    static final Member CREATE_KEY = Member.staticMethod("net/minecraft/resources/ResourceKey", "create",
            "(" + RESOURCE_KEY + "Lnet/minecraft/resources/Identifier;)" + RESOURCE_KEY);
    static final Member INT_PROVIDERS = new Member("net/minecraft/core/registries/Registries",
            "CONTEXT_INT_PROVIDER", RESOURCE_KEY, true);
    static final Member FLOAT_PROVIDERS = new Member("net/minecraft/core/registries/Registries",
            "CONTEXT_FLOAT_PROVIDER", RESOURCE_KEY, true);
    static final Member IDENTIFIER = Member.staticMethod("net/minecraft/resources/Identifier",
            "fromNamespaceAndPath", "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;");
    static final Member AS_ITEM = Member.virtual("net/minecraft/world/level/ItemLike", "asItem",
            "()Lnet/minecraft/world/item/Item;");

    // 26.1 and 26.2 still build fuel values in code, through Fabric API's own event.
    private static final String FUEL_EVENTS = "net/fabricmc/fabric/api/registry/FuelValueEvents";
    static final Member FUEL_BUILD = new Member(FUEL_EVENTS, "BUILD", EVENT, true);
    static final String FUEL_BUILD_CALLBACK = FUEL_EVENTS + "$BuildCallback";
    private static final String FUEL_BUILDER = "net/minecraft/world/level/block/entity/FuelValues$Builder";
    static final Member FUEL_ADD = Member.virtual(FUEL_BUILDER, "add", "(" + ITEM_LIKE + "I)L" + FUEL_BUILDER + ";");
    static final Member FUEL_ADD_TAG = Member.virtual(FUEL_BUILDER, "add", "(" + TAG_KEY + "I)L" + FUEL_BUILDER + ";");
    static final Member FUEL_REMOVE_TAG = Member.virtual(FUEL_BUILDER, "remove", "(" + TAG_KEY + ")L" + FUEL_BUILDER + ";");
    static final Member REGISTER_LISTENER = Member.virtual("net/fabricmc/fabric/api/event/Event", "register",
            "(Ljava/lang/Object;)V");

    // Vanilla's composting tiers, by the old chance each stands for. A component names one of
    // these or a whole number of layers, so an old chance takes the nearest tier.
    private static final Map<Float, String> COMPOST_TIERS = Map.of(
            0.3f, "compostable/low", 0.5f, "compostable/low_medium", 0.65f, "compostable/medium",
            0.85f, "compostable/medium_high", 1.0f, "compostable/always_add_one");
    private static final String FUEL_SPEED = "cooking/speed_default";

    // What old mods asked for, kept as they passed it: a block's item may not exist yet when its
    // mod registers, so ItemLike is turned into an item when the components are built.
    private static final Map<Object, Float> COMPOST = new LinkedHashMap<>();
    private static final Map<Object, Integer> FUEL = new LinkedHashMap<>();
    private static final Map<Object, Integer> VILLAGER_FOODS = new LinkedHashMap<>();
    private static final Map<Object, Integer> FUEL_TAGS = new LinkedHashMap<>();
    private static final Set<Object> FUEL_REMOVED = ConcurrentHashMap.newKeySet();
    private static final Set<Object> FUEL_TAGS_REMOVED = ConcurrentHashMap.newKeySet();
    private static volatile boolean componentsListening;
    private static volatile boolean fuelEventListening;

    private LegacyContentRegistries() {}

    // ---- Stripping, tilling and flattening --------------------------------------------------

    /** Old {@code StrippableBlockRegistry.register(Block, Block)} and {@code registerCopyState}. */
    public static void registerStrippable(Object input, Object stripped) {
        call(HostLink.require(REGISTER_STRIPPING), input, stripped);
    }

    /** Old {@code FlattenableBlockRegistry.register(Block, BlockState)}. */
    public static void registerFlattenable(Object input, Object flattened) {
        call(HostLink.require(REGISTER_FLATTENING), input, flattened);
    }

    /** Old {@code TillableBlockRegistry.register(Block, Predicate, BlockState)}. */
    public static void registerTillableState(Object input, Predicate<?> usage, Object tilled) {
        tillInto(input, tilled, null);
    }

    /** Old {@code TillableBlockRegistry.register(Block, Predicate, BlockState, ItemLike)}. */
    public static void registerTillableStateAndDrop(Object input, Predicate<?> usage, Object tilled,
                                                    Object dropped) {
        tillInto(input, tilled, dropped);
    }

    /** Old {@code TillableBlockRegistry.register(Block, Predicate, Consumer)}. */
    public static void registerTillableAction(Object input, Predicate<?> usage, Consumer<?> action) {
        // A block transformer holds a block state, not code, so only the actions HoeItem's own
        // helpers built can be written as one.
        if (action instanceof LegacyHoeActions.ChangeIntoState change) {
            tillInto(input, change.state(), change.droppedItem());
            return;
        }
        warnOnce("till-action:" + input, "A mod registered " + input + " as tillable with its own "
                + "action. 26.3 tills through block transformers, which can only set a block state, so "
                + "the block stays as it is when a hoe is used on it.");
    }

    private static void tillInto(Object input, Object tilled, Object dropped) {
        call(HostLink.require(REGISTER_TILLING), input, tilled);
        if (dropped != null) {
            warnOnce("till-drop:" + input, "Tilling " + input + " on 26.3 sets the new block but does "
                    + "not drop " + dropped + ", because a block transformer drops items only from a loot table.");
        }
    }

    // ---- Composting --------------------------------------------------------------------------

    /** Old {@code CompostingChanceRegistry.add(ItemLike, Float)}. */
    public static void addCompostable(Object item, Object chance) {
        synchronized (COMPOST) {
            COMPOST.put(item, ((Number) chance).floatValue());
        }
        listenForComponents();
    }

    /** Old {@code add(TagKey, Float)}: item tags are not bound yet when 26.3 builds item components. */
    public static void addCompostableTag(Object tag, Object chance) {
        warnOnce("compost-tag:" + tag, "A mod made the item tag " + tag + " compostable. 26.3 sets "
                + "compost chances on each item before tags load, so the tag's items are not compostable.");
    }

    /** Old {@code get(ItemLike)}: the chance an old mod set, or null. */
    public static Object compostChance(Object item) {
        synchronized (COMPOST) {
            return COMPOST.get(item);
        }
    }

    /** Old {@code remove(ItemLike)}: forgets the chance an old mod set; a vanilla item keeps its own. */
    public static void removeCompostable(Object item) {
        synchronized (COMPOST) {
            COMPOST.remove(item);
        }
    }

    /** Old {@code remove(TagKey)}, which has nothing to undo on 26.3. */
    public static void removeCompostableTag(Object tag) {
        debugOnce("compost-tag-remove:" + tag, "Ignored removing compost chances for the tag " + tag);
    }

    // ---- Fuel --------------------------------------------------------------------------------

    /** Old {@code FuelRegistry.add(ItemLike, Integer)}. */
    public static void addFuel(Object item, Object ticks) {
        synchronized (FUEL) {
            FUEL.put(item, ((Number) ticks).intValue());
        }
        FUEL_REMOVED.remove(item);
        listenForFuel();
    }

    /** Old {@code FuelRegistry.add(TagKey, Integer)}, which only the 26.1 and 26.2 fuel event can apply. */
    public static void addFuelTag(Object tag, Object ticks) {
        synchronized (FUEL) {
            FUEL_TAGS.put(tag, ((Number) ticks).intValue());
        }
        FUEL_TAGS_REMOVED.remove(tag);
        listenForFuel();
        if (!usesFuelEvent()) {
            warnOnce("fuel-tag:" + tag, "A mod made the item tag " + tag + " a fuel. 26.3 sets fuel "
                    + "times on each item before tags load, so the tag's items do not burn.");
        }
    }

    /** Old {@code FuelRegistry.get(ItemLike)}: the burn time an old mod set, or null. */
    public static Object fuelTime(Object item) {
        synchronized (FUEL) {
            return FUEL_REMOVED.contains(item) ? null : FUEL.get(item);
        }
    }

    /** Old {@code FuelRegistry.remove(ItemLike)}. */
    public static void removeFuel(Object item) {
        synchronized (FUEL) {
            FUEL.remove(item);
        }
        FUEL_REMOVED.add(item);
        listenForFuel();
        if (usesFuelEvent()) {
            warnOnce("fuel-remove:" + item, "A mod removed " + item + " as a fuel. The 26.1 and 26.2 "
                    + "fuel event removes only item tags, so a vanilla fuel item keeps burning.");
        }
    }

    /** Old {@code FuelRegistry.remove(TagKey)}. */
    public static void removeFuelTag(Object tag) {
        synchronized (FUEL) {
            FUEL_TAGS.remove(tag);
        }
        FUEL_TAGS_REMOVED.add(tag);
        listenForFuel();
    }

    /** Whether this host builds fuel values through Fabric API's event, as 26.1 and 26.2 do. */
    private static boolean usesFuelEvent() {
        return HostLink.handle(COOKING_FUEL).isEmpty() && HostLink.handle(FUEL_BUILD).isPresent();
    }

    private static synchronized void listenForFuel() {
        if (usesFuelEvent()) {
            if (fuelEventListening) return;
            Object event = call(HostLink.require(FUEL_BUILD));
            call(HostLink.require(REGISTER_LISTENER), event,
                    proxy(FUEL_BUILD_CALLBACK, new FuelBuildListener()));
            fuelEventListening = true;
            return;
        }
        listenForComponents();
    }

    /** Adds and removes old fuels each time a 26.1 or 26.2 host builds its fuel values. */
    private static final class FuelBuildListener implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!method.getName().equals("build")) return objectMethodOrDefault(proxy, method, args);
            Object builder = args[0];
            synchronized (FUEL) {
                FUEL.forEach((item, ticks) -> call(HostLink.require(FUEL_ADD), builder, item, ticks));
                FUEL_TAGS.forEach((tag, ticks) -> call(HostLink.require(FUEL_ADD_TAG), builder, tag, ticks));
            }
            for (Object tag : FUEL_TAGS_REMOVED) call(HostLink.require(FUEL_REMOVE_TAG), builder, tag);
            return null;
        }
    }

    // ---- Villager food -----------------------------------------------------------------------

    /** Old {@code VillagerInteractionRegistries.registerFood(ItemLike, int)}, a villager_food component on 26.3. */
    public static void addVillagerFood(Object item, int nutrition) {
        synchronized (VILLAGER_FOODS) {
            VILLAGER_FOODS.put(item, nutrition);
        }
        listenForComponents();
    }

    // ---- Item components (26.3) --------------------------------------------------------------

    private static synchronized void listenForComponents() {
        if (componentsListening) return;
        Object event = call(HostLink.require(COMPONENTS_MODIFY));
        call(HostLink.require(REGISTER_LISTENER), event, proxy(MODIFY_CALLBACK, new ComponentListener()));
        componentsListening = true;
    }

    /** Sets the old compost chances and fuel times each time the host builds item components. */
    private static final class ComponentListener implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!method.getName().equals("modify")) return objectMethodOrDefault(proxy, method, args);
            Object context = args[0];
            Map<Object, Float> compost;
            Map<Object, Integer> fuel;
            synchronized (COMPOST) {
                compost = new LinkedHashMap<>(COMPOST);
            }
            Map<Object, Integer> villagerFood;
            synchronized (FUEL) {
                fuel = new LinkedHashMap<>(FUEL);
            }
            synchronized (VILLAGER_FOODS) {
                villagerFood = new LinkedHashMap<>(VILLAGER_FOODS);
            }
            // A chance of 0 never added a layer, so such an item is left as the host has it.
            compost.forEach((item, chance) -> {
                if (chance > 0) setComponent(context, item, COMPOSTABLE, compostable(chance));
            });
            fuel.forEach((item, ticks) -> setComponent(context, item, COOKING_FUEL, cookingFuel(ticks)));
            villagerFood.forEach((item, nutrition) -> setComponent(context, item, VILLAGER_FOOD,
                    call(HostLink.require(NEW_VILLAGER_FOOD), nutrition)));
            // Removing a vanilla fuel takes its component away; null is how the builder removes one.
            for (Object item : FUEL_REMOVED) setComponent(context, item, COOKING_FUEL, null);
            return null;
        }
    }

    private static void setComponent(Object context, Object itemLike, Member type, Object value) {
        Object item = call(HostLink.require(AS_ITEM), itemLike);
        Object componentType = call(HostLink.require(type));
        Consumer<Object> edit = builder -> call(HostLink.require(BUILDER_SET), builder, componentType, value);
        call(HostLink.require(MODIFY_ITEM), context, item, edit);
    }

    /** The compost component for an old chance, at the nearest of vanilla's tiers. */
    static Object compostable(float chance) {
        String tier = nearestTier(chance);
        if (Math.abs(nearestTierChance(chance) - chance) > 0.001f) {
            debugOnce("compost-round:" + chance, "Composting chance " + chance + " uses vanilla's "
                    + tier + " tier, the nearest 26.3 has");
        }
        Object key = call(HostLink.require(CREATE_KEY), call(HostLink.require(INT_PROVIDERS)), id(tier));
        return call(HostLink.require(NEW_COMPOSTABLE), key);
    }

    /** The fuel component for an old burn time, with vanilla's default cooking speed. */
    static Object cookingFuel(int ticks) {
        Object burnTime = call(HostLink.require(NEW_INT_CONSTANT), ticks);
        Object speedKey = call(HostLink.require(CREATE_KEY), call(HostLink.require(FLOAT_PROVIDERS)), id(FUEL_SPEED));
        Object speed = call(HostLink.require(FLOAT_FROM_KEY), speedKey);
        return call(HostLink.require(NEW_COOKING_FUEL), burnTime, speed);
    }

    static String nearestTier(float chance) {
        return COMPOST_TIERS.get(nearestTierChance(chance));
    }

    private static float nearestTierChance(float chance) {
        float best = 1.0f;
        for (float tier : COMPOST_TIERS.keySet()) {
            if (Math.abs(tier - chance) < Math.abs(best - chance)) best = tier;
        }
        return best;
    }

    private static Object id(String path) {
        return call(HostLink.require(IDENTIFIER), "minecraft", path);
    }

    // ---- Host plumbing -----------------------------------------------------------------------

    private static Object proxy(String interfaceName, InvocationHandler handler) {
        Class<?> type = HostLink.type(interfaceName).orElseThrow(() -> new IllegalStateException(
                "This Minecraft host has no " + interfaceName + ". The loader API runtime cannot bridge this call here."));
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** Object methods by identity, and the host interface's own default for anything else. */
    private static Object objectMethodOrDefault(Object proxy, Method method, Object[] args) throws Throwable {
        boolean noArgs = method.getParameterCount() == 0;
        if (method.getName().equals("equals") && method.getParameterCount() == 1) return proxy == args[0];
        if (method.getName().equals("hashCode") && noArgs) return System.identityHashCode(proxy);
        if (method.getName().equals("toString") && noArgs) return "legacy content registry listener";
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        throw new UnsupportedOperationException("legacy content registry listener has no answer for "
                + method.getName() + " on this host");
    }

    static Object call(MethodHandle handle, Object... args) {
        try {
            // Fixed arity, so an array passed to a varargs member is not wrapped in another array.
            return handle.asFixedArity().invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("A host call failed: " + handle, e);
        }
    }

    private static void warnOnce(String key, String message) {
        if (REPORTED.add(key)) LOGGER.warn(message);
    }

    private static void debugOnce(String key, String message) {
        if (REPORTED.add(key)) LOGGER.debug(message);
    }

    /** Forgets every recorded registration, for tests that start a fresh host. */
    static synchronized void resetForTesting() {
        synchronized (COMPOST) {
            COMPOST.clear();
        }
        synchronized (FUEL) {
            FUEL.clear();
            FUEL_TAGS.clear();
        }
        synchronized (VILLAGER_FOODS) {
            VILLAGER_FOODS.clear();
        }
        FUEL_REMOVED.clear();
        FUEL_TAGS_REMOVED.clear();
        REPORTED.clear();
        componentsListening = false;
        fuelEventListening = false;
    }
}
