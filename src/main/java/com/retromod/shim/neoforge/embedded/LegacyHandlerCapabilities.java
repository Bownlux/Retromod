/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge.embedded;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Runs NeoForge 1.21.1's item, fluid, and energy capabilities beside the resource handler ones that replaced them. */
public final class LegacyHandlerCapabilities {

    private static final String CAPS = "net.neoforged.neoforge.capabilities.";
    private static final String ITEM_HANDLER = "net.neoforged.neoforge.items.IItemHandler";
    private static final String FLUID_HANDLER = "net.neoforged.neoforge.fluids.capability.IFluidHandler";
    private static final String FLUID_ACTION = FLUID_HANDLER + "$FluidAction";
    private static final String ENERGY_STORAGE = "net.neoforged.neoforge.energy.IEnergyStorage";

    // Each old capability, the current capability it mirrors, and whether it holds item, fluid, or energy handlers.
    private static final Map<Object, Object> MIRRORS = new ConcurrentHashMap<>();
    private static final Map<Object, String> KINDS = new ConcurrentHashMap<>();
    // Block capability caches over an old capability, which fall back to the current one.
    private static final Map<Object, Object> CACHES = Collections.synchronizedMap(new WeakHashMap<>());
    // An adapter over a handler that cannot set its slots changes it only when the outermost
    // transaction commits: these are the changes waiting for that.
    private static final Map<Object, List<Object[]>> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    private LegacyHandlerCapabilities() {}

    // ---- the old capabilities -----------------------------------------------------------------

    /**
     * {@code Capabilities.ItemHandler.BLOCK} and the others: a capability of the old handler type
     * under Retromod's id, which NeoForge returns to every mod that asks for the same one.
     */
    public static Object capability(String kind, String scope) {
        try {
            ClassLoader loader = loader();
            boolean items = kind.equals("item");
            String handlerType = switch (kind) {
                case "item" -> ITEM_HANDLER;
                case "energy" -> ENERGY_STORAGE;
                default -> scope.equals("item") ? FLUID_HANDLER + "Item" : FLUID_HANDLER;
            };
            Class<?> handler = Class.forName(handlerType, true, loader);
            String path = kind.equals("energy") ? "legacy_energy_storage"
                    : "legacy_" + kind + "_handler" + (scope.equals("entity_automation") ? "_automation" : "");
            Class<?> idClass = Class.forName("net.minecraft.resources.Identifier", true, loader);
            Object id = idClass.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, "retromod", path);
            // The old entity item capability had no context; every other entity and block one was sided.
            boolean sided = !scope.equals("item") && !(items && scope.equals("entity"));
            String owner = switch (scope) {
                case "block" -> "BlockCapability";
                case "item" -> "ItemCapability";
                default -> "EntityCapability";
            };
            Object capability = Class.forName(CAPS + owner, true, loader)
                    .getMethod(sided ? "createSided" : "createVoid", idClass, Class.class).invoke(null, id, handler);
            String field = scope.equals("entity_automation") ? "ENTITY_AUTOMATION" : scope.toUpperCase(java.util.Locale.ROOT);
            String holder = switch (kind) {
                case "item" -> "Item";
                case "energy" -> "Energy";
                default -> "Fluid";
            };
            Object current = Class.forName(CAPS + "Capabilities$" + holder, true, loader).getField(field).get(null);
            MIRRORS.put(capability, current);
            KINDS.put(capability, kind);
            return capability;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not create the old " + kind + " handler capability", failure);
        }
    }

    // ---- registration: the old handler is also offered on the current capability --------------

    /** After {@code registerBlockEntity(capability, type, provider)}, the same on the current capability. */
    public static void mirrorBlockEntity(Object event, Object capability, Object type, Object provider,
            Class<?> itemAdapter, Class<?> fluidAdapter, Class<?> energyAdapter) {
        Object current = MIRRORS.get(capability);
        if (current == null) return;
        Object adapted = provider("ICapabilityProvider", arguments ->
                adapt(capability, call(provider, "getCapability", arguments[0], arguments[1]), itemAdapter, fluidAdapter, energyAdapter));
        invoke(event, "registerBlockEntity", current, type, adapted);
    }

    /** After {@code registerBlock(capability, provider, blocks)}, the same on the current capability. */
    public static void mirrorBlock(Object event, Object capability, Object provider, Object blocks,
            Class<?> itemAdapter, Class<?> fluidAdapter, Class<?> energyAdapter) {
        Object current = MIRRORS.get(capability);
        if (current == null) return;
        Object adapted = provider("IBlockCapabilityProvider", arguments -> adapt(capability,
                call(provider, "getCapability", arguments), itemAdapter, fluidAdapter, energyAdapter));
        invoke(event, "registerBlock", current, adapted, blocks);
    }

    /** After {@code registerEntity(capability, type, provider)}, the same on the current capability. */
    public static void mirrorEntity(Object event, Object capability, Object type, Object provider,
            Class<?> itemAdapter, Class<?> fluidAdapter, Class<?> energyAdapter) {
        Object current = MIRRORS.get(capability);
        if (current == null) return;
        Object adapted = provider("ICapabilityProvider", arguments ->
                adapt(capability, call(provider, "getCapability", arguments[0], arguments[1]), itemAdapter, fluidAdapter, energyAdapter));
        invoke(event, "registerEntity", current, type, adapted);
    }

    /** After {@code registerItem(capability, provider, items)}: the current one gives an item access, the old one had no context. */
    public static void mirrorItem(Object event, Object capability, Object provider, Object items,
            Class<?> itemAdapter, Class<?> fluidAdapter, Class<?> energyAdapter) {
        Object current = MIRRORS.get(capability);
        if (current == null) return;
        Object adapted = provider("ICapabilityProvider", arguments ->
                adapt(capability, call(provider, "getCapability", arguments[0], null), itemAdapter, fluidAdapter, energyAdapter));
        invoke(event, "registerItem", current, adapted, items);
    }

    private static Object adapt(Object capability, Object handler, Class<?> itemAdapter, Class<?> fluidAdapter,
            Class<?> energyAdapter) {
        if (handler == null) return null;
        try {
            Class<?> adapter = switch (KINDS.get(capability)) {
                case "item" -> itemAdapter;
                case "energy" -> energyAdapter;
                default -> fluidAdapter;
            };
            return adapter.getConstructor(Object.class).newInstance(handler);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not adapt an old handler", failure);
        }
    }

    // ---- lookups: an old capability falls back to the current one -----------------------------

    /** {@code level.getCapability(capability, pos, [state, blockEntity,] context)}. */
    public static Object level(Object level, Object capability, Object pos, Object state, Object blockEntity,
            Object context, boolean full) {
        Object found = levelLookup(level, capability, pos, state, blockEntity, context, full);
        Object current = MIRRORS.get(capability);
        if (found != null || current == null) return found;
        return wrap(capability, levelLookup(level, current, pos, state, blockEntity, context, full));
    }

    private static Object levelLookup(Object level, Object capability, Object pos, Object state, Object blockEntity,
            Object context, boolean full) {
        try {
            if (!full) return levelMethod(false).invoke(level, capability, pos, context);
            return levelMethod(true).invoke(level, capability, pos, state, blockEntity, context);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not look up a block capability", failure);
        }
    }

    // Lookups run every tick for machines and hoppers, so their methods are found once.
    private static volatile Method levelShort;
    private static volatile Method levelFull;
    private static volatile Method stackLookup;

    private static Method levelMethod(boolean full) throws ReflectiveOperationException {
        Method method = full ? levelFull : levelShort;
        if (method != null) return method;
        ClassLoader loader = loader();
        Class<?> extension = Class.forName("net.neoforged.neoforge.common.extensions.ILevelExtension", true, loader);
        Class<?> cap = Class.forName(CAPS + "BlockCapability", true, loader);
        Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos", true, loader);
        if (full) {
            method = extension.getMethod("getCapability", cap, blockPos,
                    Class.forName("net.minecraft.world.level.block.state.BlockState", true, loader),
                    Class.forName("net.minecraft.world.level.block.entity.BlockEntity", true, loader), Object.class);
            levelFull = method;
        } else {
            method = extension.getMethod("getCapability", cap, blockPos, Object.class);
            levelShort = method;
        }
        return method;
    }

    /** {@code stack.getCapability(capability[, context])}: the current item capability reads the stack through an access. */
    public static Object stack(Object stack, Object capability, Object context) {
        try {
            ClassLoader loader = loader();
            Method lookup = stackLookup;
            if (lookup == null) {
                lookup = Class.forName("net.neoforged.neoforge.common.extensions.IItemStackExtension", true, loader)
                        .getMethod("getCapability", Class.forName(CAPS + "ItemCapability", true, loader), Object.class);
                stackLookup = lookup;
            }
            Object found = lookup.invoke(stack, capability, context);
            Object current = MIRRORS.get(capability);
            // An item's fluid handler had to be IFluidHandlerItem, which has no adapter to wrap with.
            if (found != null || current == null || KINDS.get(capability).equals("fluid")) return found;
            Object access = Class.forName("net.neoforged.neoforge.transfer.access.ItemAccess", true, loader)
                    .getMethod("forStack", Class.forName("net.minecraft.world.item.ItemStack", true, loader)).invoke(null, stack);
            return wrap(capability, lookup.invoke(stack, current, access));
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not look up an item capability", failure);
        }
    }

    /** {@code entity.getCapability(capability[, context])}, which 26.1.2 removed: the capability asks the entity. */
    public static Object entity(Object entity, Object capability, Object context) {
        Object found = call(capability, "getCapability", entity, context);
        Object current = MIRRORS.get(capability);
        if (found != null || current == null) return found;
        return wrap(capability, call(current, "getCapability", entity, context));
    }

    /** {@code BlockCapabilityCache.create(capability, level, pos, context)}, remembered when the capability is an old one. */
    public static Object cache(Object capability, Object level, Object pos, Object context) {
        try {
            ClassLoader loader = loader();
            Class<?> cacheClass = Class.forName(CAPS + "BlockCapabilityCache", true, loader);
            Object cache = cacheClass.getMethod("create", Class.forName(CAPS + "BlockCapability", true, loader),
                    Class.forName("net.minecraft.server.level.ServerLevel", true, loader),
                    Class.forName("net.minecraft.core.BlockPos", true, loader), Object.class)
                    .invoke(null, capability, level, pos, context);
            if (MIRRORS.containsKey(capability)) CACHES.put(cache, capability);
            return cache;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Retromod could not create a block capability cache", failure);
        }
    }

    /** {@code cache.getCapability()}: for an old capability, the current one at the same place when the old is absent. */
    public static Object cached(Object cache) {
        Object found = call(cache, "getCapability");
        Object capability = CACHES.get(cache);
        if (found != null || capability == null) return found;
        return wrap(capability, levelLookup(call(cache, "level"), MIRRORS.get(capability), call(cache, "pos"),
                null, null, call(cache, "context"), false));
    }

    /** A current handler as the old type, through NeoForge's own {@code of} adapters. */
    private static Object wrap(Object capability, Object resourceHandler) {
        if (resourceHandler == null) return null;
        try {
            ClassLoader loader = loader();
            String kind = KINDS.get(capability);
            Class<?> current = Class.forName(kind.equals("energy") ? "net.neoforged.neoforge.transfer.energy.EnergyHandler"
                    : "net.neoforged.neoforge.transfer.ResourceHandler", true, loader);
            String type = switch (kind) {
                case "item" -> ITEM_HANDLER;
                case "energy" -> ENERGY_STORAGE;
                default -> FLUID_HANDLER;
            };
            Object wrapped = Class.forName(type, true, loader).getMethod("of", current).invoke(null, resourceHandler);
            // An item's fluid handler had to be IFluidHandlerItem, which has no adapter to wrap with.
            Class<?> expected = (Class<?>) capability.getClass().getMethod("typeClass").invoke(capability);
            return expected.isInstance(wrapped) ? wrapped : null;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    // ---- the item adapter: an old item handler as a current resource handler -------------------

    public static int itemSize(Object handler) {
        return (int) call(handler, "getSlots");
    }

    public static Object itemResource(Object handler, int slot) {
        return itemResourceOf(call(handler, "getStackInSlot", slot));
    }

    public static long itemAmount(Object handler, int slot) {
        return (int) call(call(handler, "getStackInSlot", slot), "getCount");
    }

    public static long itemCapacity(Object handler, int slot, Object resource) {
        int limit = (int) call(handler, "getSlotLimit", slot);
        if ((boolean) call(resource, "isEmpty")) return limit;
        return Math.min(limit, (int) call(resource, "getMaxStackSize"));
    }

    public static boolean itemValid(Object handler, int slot, Object resource) {
        return (boolean) call(handler, "isItemValid", slot, call(resource, "toStack", 1));
    }

    public static int itemInsert(Object adapter, Object handler, int slot, Object resource, int amount, Object transaction) {
        if (amount <= 0 || (boolean) call(resource, "isEmpty")) return 0;
        Object stack = call(resource, "toStack", amount);
        if (modifiable(handler)) {
            call(adapter, "updateSnapshots", transaction);
            return amount - (int) call(call(handler, "insertItem", slot, stack, false), "getCount");
        }
        int inserted = amount - (int) call(call(handler, "insertItem", slot, stack, true), "getCount");
        if (inserted > 0) record(adapter, transaction, new Object[]{"insert", slot, resource, inserted});
        return inserted;
    }

    public static int itemExtract(Object adapter, Object handler, int slot, Object resource, int amount, Object transaction) {
        if (amount <= 0 || !(boolean) call(resource, "matches", call(handler, "getStackInSlot", slot))) return 0;
        if (modifiable(handler)) {
            call(adapter, "updateSnapshots", transaction);
            return (int) call(call(handler, "extractItem", slot, amount, false), "getCount");
        }
        int extracted = (int) call(call(handler, "extractItem", slot, amount, true), "getCount");
        if (extracted > 0) record(adapter, transaction, new Object[]{"extract", slot, resource, extracted});
        return extracted;
    }

    /** The state to return to: every slot of a handler that can set them, else the waiting changes. */
    public static Object itemSnapshot(Object adapter, Object handler) {
        if (!modifiable(handler)) return pendingCopy(adapter);
        List<Object> slots = new ArrayList<>();
        for (int slot = 0; slot < itemSize(handler); slot++) slots.add(call(call(handler, "getStackInSlot", slot), "copy"));
        return slots;
    }

    public static void itemRevert(Object adapter, Object handler, Object snapshot) {
        List<?> state = (List<?>) snapshot;
        if (!modifiable(handler)) {
            PENDING.put(adapter, new ArrayList<>(castOps(state)));
            return;
        }
        for (int slot = 0; slot < state.size(); slot++) call(handler, "setStackInSlot", slot, state.get(slot));
    }

    public static void itemCommit(Object adapter, Object handler) {
        for (Object[] change : drain(adapter)) {
            int slot = (int) change[1];
            int amount = (int) change[3];
            if (change[0].equals("insert")) call(handler, "insertItem", slot, call(change[2], "toStack", amount), false);
            else call(handler, "extractItem", slot, amount, false);
        }
    }

    private static boolean modifiable(Object handler) {
        try {
            return Class.forName(ITEM_HANDLER + "Modifiable", true, loader()).isInstance(handler);
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    private static Object itemResourceOf(Object stack) {
        try {
            ClassLoader loader = loader();
            return Class.forName("net.neoforged.neoforge.transfer.item.ItemResource", true, loader)
                    .getMethod("of", Class.forName("net.minecraft.world.item.ItemStack", true, loader)).invoke(null, stack);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---- the fluid adapter: an old fluid handler as a current resource handler ----------------
    // A fluid handler fills and drains as a whole and cannot set a tank, so every change waits for
    // the outermost transaction to commit.

    public static int fluidSize(Object handler) {
        return (int) call(handler, "getTanks");
    }

    public static Object fluidResource(Object handler, int tank) {
        try {
            ClassLoader loader = loader();
            return Class.forName("net.neoforged.neoforge.transfer.fluid.FluidResource", true, loader)
                    .getMethod("of", Class.forName("net.neoforged.neoforge.fluids.FluidStack", true, loader))
                    .invoke(null, call(handler, "getFluidInTank", tank));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static long fluidAmount(Object handler, int tank) {
        return (int) call(call(handler, "getFluidInTank", tank), "getAmount");
    }

    public static long fluidCapacity(Object handler, int tank, Object resource) {
        return (int) call(handler, "getTankCapacity", tank);
    }

    public static boolean fluidValid(Object handler, int tank, Object resource) {
        return (boolean) call(handler, "isFluidValid", tank, call(resource, "toStack", 1));
    }

    public static int fluidInsert(Object adapter, Object handler, int tank, Object resource, int amount, Object transaction) {
        if (amount <= 0 || (boolean) call(resource, "isEmpty")) return 0;
        int filled = (int) call(handler, "fill", call(resource, "toStack", amount), fluidAction("SIMULATE"));
        if (filled > 0) record(adapter, transaction, new Object[]{"fill", tank, resource, filled});
        return filled;
    }

    public static int fluidExtract(Object adapter, Object handler, int tank, Object resource, int amount, Object transaction) {
        if (amount <= 0 || (boolean) call(resource, "isEmpty")) return 0;
        int drained = (int) call(call(handler, "drain", call(resource, "toStack", amount), fluidAction("SIMULATE")), "getAmount");
        if (drained > 0) record(adapter, transaction, new Object[]{"drain", tank, resource, drained});
        return drained;
    }

    public static Object fluidSnapshot(Object adapter, Object handler) {
        return pendingCopy(adapter);
    }

    public static void fluidRevert(Object adapter, Object handler, Object snapshot) {
        PENDING.put(adapter, new ArrayList<>(castOps((List<?>) snapshot)));
    }

    public static void fluidCommit(Object adapter, Object handler) {
        for (Object[] change : drain(adapter)) {
            Object stack = call(change[2], "toStack", (int) change[3]);
            call(handler, change[0].equals("fill") ? "fill" : "drain", stack, fluidAction("EXECUTE"));
        }
    }

    private static Object fluidAction(String name) {
        try {
            return Class.forName(FLUID_ACTION, true, loader()).getField(name).get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---- the energy adapter: an old energy storage as a current energy handler ------------------
    // A storage can only receive and extract, so like a fluid handler every change waits for the
    // outermost transaction to commit.

    public static long energyAmount(Object storage) {
        return (int) call(storage, "getEnergyStored");
    }

    public static long energyCapacity(Object storage) {
        return (int) call(storage, "getMaxEnergyStored");
    }

    public static int energyInsert(Object adapter, Object storage, int amount, Object transaction) {
        if (amount <= 0) return 0;
        int received = (int) call(storage, "receiveEnergy", amount, true);
        if (received > 0) record(adapter, transaction, new Object[]{"receive", received});
        return received;
    }

    public static int energyExtract(Object adapter, Object storage, int amount, Object transaction) {
        if (amount <= 0) return 0;
        int extracted = (int) call(storage, "extractEnergy", amount, true);
        if (extracted > 0) record(adapter, transaction, new Object[]{"extract", extracted});
        return extracted;
    }

    public static Object energySnapshot(Object adapter, Object storage) {
        return pendingCopy(adapter);
    }

    public static void energyRevert(Object adapter, Object storage, Object snapshot) {
        PENDING.put(adapter, new ArrayList<>(castOps((List<?>) snapshot)));
    }

    public static void energyCommit(Object adapter, Object storage) {
        for (Object[] change : drain(adapter)) {
            call(storage, change[0].equals("receive") ? "receiveEnergy" : "extractEnergy", change[1], false);
        }
    }

    // ---- shared plumbing ------------------------------------------------------------------------

    private static void record(Object adapter, Object transaction, Object[] change) {
        call(adapter, "updateSnapshots", transaction);
        PENDING.computeIfAbsent(adapter, unused -> new ArrayList<>()).add(change);
    }

    private static List<Object> pendingCopy(Object adapter) {
        return new ArrayList<>(PENDING.getOrDefault(adapter, List.of()));
    }

    private static List<Object[]> drain(Object adapter) {
        List<Object[]> changes = PENDING.remove(adapter);
        return changes == null ? List.of() : changes;
    }

    private static List<Object[]> castOps(List<?> state) {
        List<Object[]> ops = new ArrayList<>();
        for (Object op : state) ops.add((Object[]) op);
        return ops;
    }

    /** A capability provider of the named NeoForge interface, answered by {@code answer}. */
    private static Object provider(String type, java.util.function.Function<Object[], Object> answer) {
        try {
            Class<?> provider = Class.forName(CAPS + type, true, loader());
            InvocationHandler handler = (proxy, method, arguments) -> switch (method.getName()) {
                case "getCapability" -> answer.apply(arguments);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "Retromod adapter for an old " + type;
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return Proxy.newProxyInstance(provider.getClassLoader(), new Class<?>[]{provider}, handler);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void invoke(Object target, String name, Object... arguments) {
        call(target, name, arguments);
    }

    /** A public method by name and arity whose parameters accept the arguments. */
    private static Object call(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < parameters.length && fits; i++) {
                fits = arguments[i] == null ? !parameters[i].isPrimitive() : wrapper(parameters[i]).isInstance(arguments[i]);
            }
            if (!fits) continue;
            try {
                method.trySetAccessible();
                return method.invoke(target, arguments);
            } catch (InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException(target.getClass().getName() + " has no " + name + " for " + arguments.length + " arguments");
    }

    private static Class<?> wrapper(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Object.class;
    }

    private static ClassLoader loader() {
        return LegacyHandlerCapabilities.class.getClassLoader();
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
