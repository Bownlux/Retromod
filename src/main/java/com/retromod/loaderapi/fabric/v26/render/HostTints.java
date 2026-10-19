/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.loaderapi.fabric.v26.render.shared.LegacyBlockColor;
import com.retromod.loaderapi.fabric.v26.render.shared.LegacyItemColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** The host Minecraft and Fabric API calls that serve old block and item colors, by 26.x name. */
final class HostTints {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");

    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String BLOCK_TINT_SOURCE = "net/minecraft/client/color/block/BlockTintSource";
    static final String BLOCK_COLORS = "net/minecraft/client/color/block/BlockColors";
    static final String ITEM_TINT_SOURCE = "net/minecraft/client/color/item/ItemTintSource";
    static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String CUBOID_UNBAKED = "net/minecraft/client/renderer/item/CuboidItemModelWrapper$Unbaked";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String BLOCK_COLOR_REGISTRY = "net/fabricmc/fabric/api/client/rendering/v1/BlockColorRegistry";
    static final String BLOCK_TINTS_FACTORY = "net/fabricmc/fabric/api/client/rendering/v1/BlockTintsFactory";
    static final String CLIENT_LIFECYCLE = "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents";
    static final String CLIENT_STARTED_TYPE = CLIENT_LIFECYCLE + "$ClientStarted";
    static final String MODEL_PLUGIN = "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin";
    static final String BEFORE_BAKE_ITEM =
            "net/fabricmc/fabric/api/client/model/loading/v1/ModelModifier$BeforeBakeItem";
    static final String EVENT = "net/fabricmc/fabric/api/event/Event";

    // Every host member these helpers call; HostTintsMembersTest checks each on the 26.x hosts.
    static final Member REGISTER_BLOCK_COLORS = Member.staticMethod(BLOCK_COLOR_REGISTRY, "register",
            "(Ljava/util/List;[L" + BLOCK + ";)V");
    static final Member BLOCK_COLOR_FACTORY = Member.staticMethod(BLOCK_COLOR_REGISTRY, "getFactory",
            "(L" + BLOCK_STATE + ";)L" + BLOCK_TINTS_FACTORY + ";");
    static final Member CLIENT_STARTED = new Member(CLIENT_LIFECYCLE, "CLIENT_STARTED", "L" + EVENT + ";", true);
    static final Member ON_CLIENT_STARTED = Member.virtual(CLIENT_STARTED_TYPE, "onClientStarted",
            "(L" + MINECRAFT + ";)V");
    static final Member REGISTER_MODEL_PLUGIN = Member.staticMethod(MODEL_PLUGIN, "register",
            "(L" + MODEL_PLUGIN + ";)V");
    static final Member ITEM_MODEL_EVENT = Member.virtual(MODEL_PLUGIN + "$Context",
            "modifyItemModelBeforeBake", "()L" + EVENT + ";");
    static final Member REGISTER_LISTENER = Member.virtual(EVENT, "register", "(Ljava/lang/Object;)V");
    static final Member BAKED_ITEM_ID = Member.virtual(BEFORE_BAKE_ITEM + "$Context", "itemId",
            "()L" + IDENTIFIER + ";");
    static final Member MINECRAFT_INSTANCE = Member.staticMethod(MINECRAFT, "getInstance",
            "()L" + MINECRAFT + ";");
    static final Member GET_BLOCK_COLORS = Member.virtual(MINECRAFT, "getBlockColors",
            "()L" + BLOCK_COLORS + ";");
    static final Member GET_RESOURCE_MANAGER = Member.virtual(MINECRAFT, "getResourceManager",
            "()Lnet/minecraft/server/packs/resources/ResourceManager;");
    static final Member OPEN_AS_READER = Member.virtual("net/minecraft/server/packs/resources/ResourceProvider",
            "openAsReader", "(L" + IDENTIFIER + ";)Ljava/io/BufferedReader;");
    static final Member DEFAULT_STATE = Member.virtual(BLOCK, "defaultBlockState", "()L" + BLOCK_STATE + ";");
    static final Member TINT_SOURCES = Member.virtual(BLOCK_COLORS, "getTintSources",
            "(L" + BLOCK_STATE + ";)Ljava/util/List;");
    static final Member TINT_COLOR = Member.virtual(BLOCK_TINT_SOURCE, "color", "(L" + BLOCK_STATE + ";)I");
    static final Member TINT_COLOR_IN_WORLD = Member.virtual(BLOCK_TINT_SOURCE, "colorInWorld",
            "(L" + BLOCK_STATE + ";Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                    + "Lnet/minecraft/core/BlockPos;)I");
    static final Member AS_ITEM = Member.virtual(ITEM_LIKE, "asItem", "()Lnet/minecraft/world/item/Item;");
    static final Member ITEM_REGISTRY = new Member("net/minecraft/core/registries/BuiltInRegistries", "ITEM",
            "Lnet/minecraft/core/DefaultedRegistry;", true);
    static final Member REGISTRY_VALUE = Member.virtual("net/minecraft/core/Registry", "getValue",
            "(L" + IDENTIFIER + ";)Ljava/lang/Object;");
    static final Member IDENTIFIER_OF = Member.staticMethod(IDENTIFIER, "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";");
    static final Member UNBAKED_MODEL = Member.virtual(CUBOID_UNBAKED, "model", "()L" + IDENTIFIER + ";");
    static final Member UNBAKED_TRANSFORMATION = Member.virtual(CUBOID_UNBAKED, "transformation",
            "()Ljava/util/Optional;");
    static final Member NEW_UNBAKED = Member.constructor(CUBOID_UNBAKED,
            "(L" + IDENTIFIER + ";Ljava/util/Optional;Ljava/util/List;)V");
    static final Member UNIT_CODEC = Member.staticMethod(MAP_CODEC, "unit",
            "(Ljava/lang/Object;)L" + MAP_CODEC + ";");

    /** Members newer Fabric API builds have and older 26.1 ones lack; the helpers check before calling. */
    static final Set<Member> NEWER_HOST_MEMBERS = Set.of(BLOCK_COLOR_FACTORY);

    /**
     * Tint sources registered per block. The host computes a tint only for an index a quad uses,
     * so unused slots cost a list copy per tinted block. Old models used tint indices up to 108
     * (Traveler's Backpack); an index past the last slot renders untinted.
     */
    static final int BLOCK_TINT_SLOTS = 128;

    private static final AtomicBoolean ITEM_HOOK_INSTALLED = new AtomicBoolean();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    // Old block colors that wait for the client to start. The flags are guarded by the list.
    private static final List<PendingBlockColor> PENDING_BLOCK_COLORS = new ArrayList<>();
    private static boolean clientStarted;
    private static boolean startListenerInstalled;

    private HostTints() {}

    // ---- Blocks -------------------------------------------------------------------------

    /** Gives {@code provider} to the host for {@code blocks}, one tint source per tint index. */
    static void registerBlockColor(Object provider, Object[] blocks) {
        Optional<MethodHandle> register = HostLink.handle(REGISTER_BLOCK_COLORS);
        if (register.isEmpty()) {
            // A dedicated server, or a client without Fabric API: old get() calls still answer.
            debugOnce("no-block-registry", "Old block colors are kept for get() calls only: this "
                    + "side has no Fabric API BlockColorRegistry.");
            return;
        }
        List<?> sources;
        if (provider instanceof HostBlockColor host) {
            // A provider an old get() returned for another block: the host's own sources serve it.
            sources = host.sources();
        } else if (provider instanceof LegacyBlockColor legacy) {
            sources = blockTintSources(legacy);
        } else {
            infoOnce("block-provider:" + provider.getClass().getName(), "An old block color of type "
                    + provider.getClass().getName() + " is not an old BlockColor, so it was not applied.");
            return;
        }
        // Newer Fabric API builds refuse a static color for a block another mod colors dynamically,
        // and the reverse, whichever registers second. Mods add dynamic colors while the client
        // initializes them, so old colors wait for the client to start and then leave those blocks.
        synchronized (PENDING_BLOCK_COLORS) {
            if (mustWaitForClientStart()) {
                PENDING_BLOCK_COLORS.add(new PendingBlockColor(sources, blocks.clone()));
                debugOnce("block-colors-wait", "Old block colors wait for the client to start, so a "
                        + "mod that colors the same block dynamically keeps it.");
                return;
            }
        }
        applyBlockColor(register.get(), sources, blocks);
    }

    /** Whether the host has dynamic block colors and the client has not created its block colors. */
    private static boolean mustWaitForClientStart() {
        if (clientStarted || HostLink.handle(BLOCK_COLOR_FACTORY).isEmpty() || hostBlockColors() != null) {
            return false;
        }
        return startListenerInstalled || listenForClientStart();
    }

    /** Registers the client start listener once; false when the host has no lifecycle events. */
    private static boolean listenForClientStart() {
        Optional<MethodHandle> event = HostLink.handle(CLIENT_STARTED);
        Optional<Class<?>> listenerType = HostLink.type(CLIENT_STARTED_TYPE);
        if (event.isEmpty() || listenerType.isEmpty()) return false;
        Object listener = proxy(listenerType.get(), new ClientStartListener());
        call(HostLink.require(REGISTER_LISTENER), call(event.get()), listener);
        startListenerInstalled = true;
        return true;
    }

    /** Applies every old block color that waited for the client to start. */
    private static final class ClientStartListener implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!method.getName().equals(ON_CLIENT_STARTED.name())) {
                return objectMethodOrDefault(proxy, method, args, "legacy block color start listener");
            }
            List<PendingBlockColor> pending;
            synchronized (PENDING_BLOCK_COLORS) {
                clientStarted = true;
                pending = List.copyOf(PENDING_BLOCK_COLORS);
                PENDING_BLOCK_COLORS.clear();
            }
            MethodHandle register = HostLink.require(REGISTER_BLOCK_COLORS);
            for (PendingBlockColor color : pending) applyBlockColor(register, color.sources(), color.blocks());
            return null;
        }
    }

    private record PendingBlockColor(List<?> sources, Object[] blocks) {}

    /** Registers {@code sources} for every block that no mod colors dynamically. */
    private static void applyBlockColor(MethodHandle register, List<?> sources, Object[] blocks) {
        List<Object> staticBlocks = new ArrayList<>(blocks.length);
        for (Object block : blocks) {
            if (block == null) continue;
            if (hasDynamicColor(block)) {
                infoOnce("dynamic-block:" + block, "The old block color of " + block + " was not applied: "
                        + "another mod colors this block through a Fabric API BlockTintsFactory.");
                continue;
            }
            staticBlocks.add(block);
        }
        if (staticBlocks.isEmpty()) return;
        try {
            call(register, sources, hostArray(BLOCK, staticBlocks.toArray()));
        } catch (IllegalStateException e) {
            // The host refused it; the rest of the old mod's client setup still runs.
            LOGGER.warn("The old block color of {} was not applied: {}", staticBlocks, e.getMessage());
        }
    }

    /** Whether a mod colors {@code block} through a {@code BlockTintsFactory}. */
    private static boolean hasDynamicColor(Object block) {
        Optional<MethodHandle> getFactory = HostLink.handle(BLOCK_COLOR_FACTORY);
        if (getFactory.isEmpty()) return false;
        return call(getFactory.get(), call(HostLink.require(DEFAULT_STATE), block)) != null;
    }

    /** The client's block colors, or null before the client creates them or off a client. */
    private static Object hostBlockColors() {
        Optional<MethodHandle> getInstance = HostLink.handle(MINECRAFT_INSTANCE);
        if (getInstance.isEmpty()) return null;
        Object minecraft = call(getInstance.get());
        return minecraft == null ? null : call(HostLink.require(GET_BLOCK_COLORS), minecraft);
    }

    /** One host {@code BlockTintSource} per tint index, each asking {@code provider} for its index. */
    static List<Object> blockTintSources(LegacyBlockColor provider) {
        Class<?> type = requireType(BLOCK_TINT_SOURCE);
        List<Object> sources = new ArrayList<>(BLOCK_TINT_SLOTS);
        for (int index = 0; index < BLOCK_TINT_SLOTS; index++) {
            sources.add(proxy(type, new BlockTint(provider, index)));
        }
        return List.copyOf(sources);
    }

    /** The host's tint sources for {@code block} as an old provider, or null when it has none. */
    static Object hostBlockColor(Object block) {
        // Before the client builds its block colors, the old registry answered null too.
        Object colors = hostBlockColors();
        if (colors == null) return null;
        Object state = call(HostLink.require(DEFAULT_STATE), block);
        List<?> sources = (List<?>) call(HostLink.require(TINT_SOURCES), colors, state);
        return sources == null || sources.isEmpty() ? null : new HostBlockColor(List.copyOf(sources));
    }

    /** An old {@code BlockColor} answered by the host's tint sources of one block. */
    record HostBlockColor(List<?> sources) implements LegacyBlockColor {
        @Override
        public int legacyBlockColor(Object state, Object level, Object pos, int tintIndex) {
            if (tintIndex < 0 || tintIndex >= sources.size()) return -1;
            Object source = sources.get(tintIndex);
            if (source == null) return -1;
            // The old provider took a null level and position outside a world, as color(state) does.
            if (level == null || pos == null) {
                return (Integer) call(HostLink.require(TINT_COLOR), source, state);
            }
            return (Integer) call(HostLink.require(TINT_COLOR_IN_WORLD), source, state, level, pos);
        }
    }

    /** One tint index of an old block color, as the host's {@code BlockTintSource}. */
    private record BlockTint(LegacyBlockColor provider, int index) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                // Old providers returned RGB, and old block renderers ignored alpha; 26.x
                // multiplies it into the vertex, so it is made opaque.
                case "color" -> provider.legacyBlockColor(args[0], null, null, index) | 0xFF000000;
                case "colorInWorld" ->
                        provider.legacyBlockColor(args[0], args[1], args[2], index) | 0xFF000000;
                default -> objectMethodOrDefault(proxy, method, args, "legacy block color " + index);
            };
        }
    }

    // ---- Items --------------------------------------------------------------------------

    /** The item an old {@code ItemLike} stands for: an item, or the item of a block. */
    static Object asItem(Object itemLike) {
        if (itemLike == null) return null;
        Optional<MethodHandle> asItem = HostLink.handle(AS_ITEM);
        boolean isItemLike = HostLink.type(ITEM_LIKE).map(type -> type.isInstance(itemLike)).orElse(false);
        return asItem.isPresent() && isItemLike ? call(asItem.get(), itemLike) : itemLike;
    }

    /**
     * Adds old item colors to item models as the host bakes them. Item tints are item model data
     * on 26.x, so Fabric API's model loading plugin is the only place they can be attached.
     */
    static void watchItemModels() {
        if (!HostLink.exists(MODEL_PLUGIN) || !HostLink.exists(ITEM_TINT_SOURCE)) {
            debugOnce("no-item-hook", "Old item colors are kept for get() calls only: this side "
                    + "has no item models or no Fabric API model loading plugin.");
            return;
        }
        if (!ITEM_HOOK_INSTALLED.compareAndSet(false, true)) return;
        call(HostLink.require(REGISTER_MODEL_PLUGIN), proxy(requireType(MODEL_PLUGIN), new ItemModelPlugin()));
    }

    /** The model loading plugin: it adds one item model modifier on every model reload. */
    private static final class ItemModelPlugin implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!method.getName().equals("initialize")) {
                return objectMethodOrDefault(proxy, method, args, "legacy item color plugin");
            }
            Object event = call(HostLink.require(ITEM_MODEL_EVENT), args[0]);
            Object modifier = proxy(requireType(BEFORE_BAKE_ITEM), new ItemModelModifier());
            call(HostLink.require(REGISTER_LISTENER), event, modifier);
            return null;
        }
    }

    /** Swaps a plain item model of an item with an old color for one with that color's tints. */
    private static final class ItemModelModifier implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!method.getName().equals("modifyModelBeforeBake")) {
                return objectMethodOrDefault(proxy, method, args, "legacy item color modifier");
            }
            Object model = args[0];
            Object itemId = null;
            try {
                itemId = call(HostLink.require(BAKED_ITEM_ID), args[1]);
                return withLegacyTints(model, itemId);
            } catch (RuntimeException e) {
                // One item's tints must not stop every model from loading.
                LOGGER.warn("Could not add the old item color of {} to its model: {}", itemId, e.toString());
                return model;
            }
        }
    }

    /** {@code model} with tint sources for the old item color of {@code itemId}, or {@code model}. */
    static Object withLegacyTints(Object model, Object itemId) {
        Object registry = call(HostLink.require(ITEM_REGISTRY));
        Object item = call(HostLink.require(REGISTRY_VALUE), registry, itemId);
        Object provider = LegacyColorProviders.itemProvider(item);
        if (provider == null) return model;
        if (!(provider instanceof LegacyItemColor color)) {
            infoOnce("item-provider:" + provider.getClass().getName(), "An old item color of type "
                    + provider.getClass().getName() + " is not an old ItemColor, so it was not applied.");
            return model;
        }
        Class<?> cuboid = HostLink.type(CUBOID_UNBAKED).orElse(null);
        if (cuboid == null || !cuboid.isInstance(model)) {
            infoOnce("item-model:" + itemId, "The old item color of " + itemId + " was not applied: "
                    + "its item model is not a plain model.");
            return model;
        }
        Object modelId = call(HostLink.require(UNBAKED_MODEL), model);
        // Item tints are computed for every slot on every frame, so the model decides the count.
        int slots = ModelTintIndex.tintSlots(String.valueOf(modelId), HostTints::openModel);
        if (slots == ModelTintIndex.UNKNOWN) slots = ModelTintIndex.FALLBACK_SLOTS;
        if (slots == 0) return model;
        Class<?> type = requireType(ITEM_TINT_SOURCE);
        List<Object> tints = new ArrayList<>(slots);
        for (int index = 0; index < slots; index++) tints.add(proxy(type, new ItemTint(color, index)));
        Object transformation = call(HostLink.require(UNBAKED_TRANSFORMATION), model);
        return call(HostLink.require(NEW_UNBAKED), modelId, transformation, List.copyOf(tints));
    }

    /** One tint index of an old item color, as the host's {@code ItemTintSource}. */
    private record ItemTint(LegacyItemColor provider, int index) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "calculate" -> opaqueWhenNoAlpha(provider.legacyItemColor(args[0], index));
                // Item models are never encoded in play; a unit codec keeps the method honest.
                case "type" -> HostLink.handle(UNIT_CODEC).map(unit -> call(unit, proxy)).orElse(null);
                default -> objectMethodOrDefault(proxy, method, args, "legacy item color " + index);
            };
        }
    }

    /**
     * Old 1.20 item colors were RGB with no alpha, while 1.21 ones are ARGB. A color with no alpha
     * at all is read as RGB, because 26.x would otherwise draw that layer invisible.
     */
    static int opaqueWhenNoAlpha(int color) {
        return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
    }

    /** Opens model {@code id} (such as {@code minecraft:block/leaves}) from the host's resources. */
    private static Reader openModel(String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        Object minecraft = call(HostLink.require(MINECRAFT_INSTANCE));
        Object resources = call(HostLink.require(GET_RESOURCE_MANAGER), minecraft);
        Object location = call(HostLink.require(IDENTIFIER_OF), namespace, "models/" + path + ".json");
        return (Reader) call(HostLink.require(OPEN_AS_READER), resources, location);
    }

    // ---- Plumbing -----------------------------------------------------------------------

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** Object methods by identity, and the host interface's own default for anything else. */
    private static Object objectMethodOrDefault(Object proxy, Method method, Object[] args, String label)
            throws Throwable {
        boolean noArgs = method.getParameterCount() == 0;
        if (method.getName().equals("equals") && method.getParameterCount() == 1) return proxy == args[0];
        if (method.getName().equals("hashCode") && noArgs) return System.identityHashCode(proxy);
        if (method.getName().equals("toString") && noArgs) return label;
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        throw new UnsupportedOperationException(label + " has no answer for " + method.getName()
                + " on this host");
    }

    private static Object hostArray(String elementType, Object[] values) {
        Object array = Array.newInstance(requireType(elementType), values.length);
        for (int i = 0; i < values.length; i++) Array.set(array, i, values[i]);
        return array;
    }

    private static Class<?> requireType(String internalName) {
        return HostLink.type(internalName).orElseThrow(() -> new IllegalStateException(
                "This Minecraft host has no " + internalName + "."));
    }

    /** Calls a host member, rethrowing what it threw as an unchecked exception. */
    private static Object call(MethodHandle handle, Object... args) {
        try {
            // Fixed arity, so an array passed to a varargs member is not wrapped in another array.
            return handle.asFixedArity().invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("A host call failed: " + handle, e);
        }
    }

    private static void debugOnce(String key, String message) {
        if (REPORTED.add(key)) LOGGER.debug(message);
    }

    private static void infoOnce(String key, String message) {
        if (REPORTED.add(key)) LOGGER.info(message);
    }
}
