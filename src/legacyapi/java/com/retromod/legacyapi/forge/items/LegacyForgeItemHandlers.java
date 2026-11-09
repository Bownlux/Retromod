/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.items;

import com.retromod.legacyapi.minecraft.core.LegacyRegistryContext;
import com.retromod.legacyapi.minecraft.world.item.LegacyItemData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Forge's ItemHandlerHelper methods NeoForge dropped, and Forge's item handler saves, which took no registries. */
public final class LegacyForgeItemHandlers {

    private LegacyForgeItemHandlers() {}

    /** Whether the two stacks could share a slot: the same item with the same components, the first not empty. */
    public static boolean canItemStacksStack(ItemStack first, ItemStack second) {
        return !first.isEmpty() && ItemStack.isSameItemSameComponents(first, second);
    }

    /** The same, for two stacks that are both present and stackable. */
    public static boolean canItemStacksStackRelaxed(ItemStack first, ItemStack second) {
        return !first.isEmpty() && !second.isEmpty() && first.isStackable() && ItemStack.isSameItemSameComponents(first, second);
    }

    /** A copy of the stack with {@code size} items; none is the empty stack. */
    public static ItemStack copyStackWithSize(ItemStack stack, int size) {
        return size == 0 ? ItemStack.EMPTY : stack.copyWithCount(size);
    }

    /** {@code handler.serializeNBT()}, with the registries of the save running on this thread. */
    public static CompoundTag serializeNBT(Object handler) {
        HolderLookup.Provider registries = LegacyRegistryContext.provider("ItemStackHandler.serializeNBT");
        try {
            Method withRegistries = method(handler, "serializeNBT", HolderLookup.Provider.class);
            if (withRegistries != null) return (CompoundTag) withRegistries.invoke(handler, registries);
            // NeoForge 1.21.6 to 26.2 save a handler only through a value output.
            ClassLoader loader = handler.getClass().getClassLoader();
            Class<?> reporter = Class.forName("net.minecraft.util.ProblemReporter", true, loader);
            Class<?> output = Class.forName("net.minecraft.world.level.storage.TagValueOutput", true, loader);
            Object out = output.getMethod("createWithContext", reporter, HolderLookup.Provider.class)
                    .invoke(null, reporter.getField("DISCARDING").get(null), registries);
            required(handler, "serialize", Class.forName("net.minecraft.world.level.storage.ValueOutput", true, loader))
                    .invoke(handler, out);
            return (CompoundTag) output.getMethod("buildResult").invoke(out);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not save the item handler " + handler.getClass().getName(), e);
        }
    }

    /** {@code handler.deserializeNBT(tag)}, with the registries of the load running on this thread. */
    public static void deserializeNBT(Object handler, CompoundTag tag) {
        deserializeNBT(handler, tag, LegacyItemData.DEFAULT_DATA_VERSION);
    }

    /** The same, for a mod that saved its handler at {@code dataVersion}; the transform passes the mod's own. */
    public static void deserializeNBT(Object handler, CompoundTag saved, int dataVersion) {
        HolderLookup.Provider registries = LegacyRegistryContext.provider("ItemStackHandler.deserializeNBT");
        // A 1.20.1 world saved each slot with a byte Count and its tag, which the host's reader would drop.
        CompoundTag tag = upgradeItems(saved, dataVersion);
        try {
            Method withRegistries = method(handler, "deserializeNBT", HolderLookup.Provider.class, CompoundTag.class);
            if (withRegistries != null) {
                withRegistries.invoke(handler, registries, tag);
                return;
            }
            ClassLoader loader = handler.getClass().getClassLoader();
            Class<?> reporter = Class.forName("net.minecraft.util.ProblemReporter", true, loader);
            Object in = Class.forName("net.minecraft.world.level.storage.TagValueInput", true, loader)
                    .getMethod("create", reporter, HolderLookup.Provider.class, CompoundTag.class)
                    .invoke(null, reporter.getField("DISCARDING").get(null), registries, tag);
            required(handler, "deserialize", Class.forName("net.minecraft.world.level.storage.ValueInput", true, loader))
                    .invoke(handler, in);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not load the item handler " + handler.getClass().getName(), e);
        }
    }

    /** The handler tag with its old-shaped stacks upgraded: by Retromod's item data upgrade from 1.21.5, else here. */
    static CompoundTag upgradeItems(CompoundTag tag, int dataVersion) {
        if (method(tag, "getListOrEmpty", String.class) != null) return LegacyItemData.upgradeItems(tag, dataVersion);
        return OlderHost.upgradeItems(tag, dataVersion);
    }

    private static Method method(Object target, String name, Class<?>... parameters) {
        try {
            return target.getClass().getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Method required(Object target, String name, Class<?> parameter) throws NoSuchMethodException {
        return target.getClass().getMethod(name, parameter);
    }

    /** The same upgrade on hosts before 1.21.5, through the tag calls and data fixer they share with newer ones. */
    private static final class OlderHost {
        private static final String ITEMS = "Items";

        @SuppressWarnings("unchecked")
        static CompoundTag upgradeItems(CompoundTag tag, int dataVersion) {
            // The list is read through java.util.List, whose methods every host's ListTag has.
            if (!(tag.get(ITEMS) instanceof List<?> items)) return tag;
            ListTag upgraded = new ListTag();
            boolean changed = false;
            for (Object item : items) {
                boolean old = item instanceof CompoundTag entry && entry.contains("Count") && !entry.contains("count");
                ((List<Tag>) (List<?>) upgraded).add(old ? fix((CompoundTag) item, dataVersion) : ((Tag) item).copy());
                changed |= old;
            }
            if (!changed) return tag;
            CompoundTag copy = tag.copy();
            copy.put(ITEMS, upgraded);
            return copy;
        }

        // The host's own item stack fix, from the save's version (1.20.4 at the latest for this shape) to the host's.
        private static Tag fix(CompoundTag stack, int dataVersion) {
            try {
                ClassLoader loader = LegacyForgeItemHandlers.class.getClassLoader();
                Object fixer = Class.forName("net.minecraft.util.datafix.DataFixers", true, loader).getMethod("getDataFixer").invoke(null);
                Object type = Class.forName("net.minecraft.util.datafix.fixes.References", true, loader).getField("ITEM_STACK").get(null);
                Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps", true, loader);
                Class<?> dynamic = Class.forName("com.mojang.serialization.Dynamic", true, loader);
                Object input = dynamic.getConstructor(ops, Object.class)
                        .newInstance(Class.forName("net.minecraft.nbt.NbtOps", true, loader).getField("INSTANCE").get(null), stack.copy());
                Object fixed = Class.forName("com.mojang.datafixers.DataFixer", true, loader)
                        .getMethod("update", Class.forName("com.mojang.datafixers.DSL$TypeReference", true, loader), dynamic, int.class, int.class)
                        .invoke(fixer, type, input, Math.min(dataVersion, LegacyItemData.LAST_OLD_SHAPE_DATA_VERSION), currentDataVersion(loader));
                Object value = dynamic.getMethod("getValue").invoke(fixed);
                CompoundTag upgraded = value instanceof CompoundTag compound ? compound : stack.copy();
                // The same count repair as on newer hosts, read through whichever number accessor this host has.
                upgraded.putInt("count", LegacyItemData.repairCount(number(upgraded.get("count"), number(stack.get("Count"), 1))));
                return upgraded;
            } catch (InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not upgrade a saved item stack " + stack, e);
            }
        }

        // 1.21.1 reads a number tag with getAsInt, newer hosts with intValue.
        private static int number(Tag tag, int fallback) throws ReflectiveOperationException {
            if (tag == null) return fallback;
            Method read = method(tag, "getAsInt");
            if (read == null) read = method(tag, "intValue");
            return read == null ? fallback : (int) read.invoke(tag);
        }

        // 1.21.1 names it getDataVersion().getVersion(); later versions dataVersion().version().
        private static int currentDataVersion(ClassLoader loader) throws ReflectiveOperationException {
            Object version = Class.forName("net.minecraft.SharedConstants", true, loader).getMethod("getCurrentVersion").invoke(null);
            Object data = version.getClass().getMethod(method(version, "dataVersion") != null ? "dataVersion" : "getDataVersion").invoke(version);
            return (int) data.getClass().getMethod(method(data, "version") != null ? "version" : "getVersion").invoke(data);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException(cause);
    }
}
