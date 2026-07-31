/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 * 
 * FABRIC LEGACY SHIMS
 * 
 * Virtual implementations of APIs that changed or were removed
 * between Fabric versions 1.14 through 1.21.
 */
package com.retromod.legacy.shim.fabric;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

// Bridges old NBT item data to the component system introduced in 1.20.5.

/**
 * Bridges the old ItemStack NBT system to the new Component system.
 * 
 * Old way (pre-1.20.5):
 *   NbtCompound nbt = stack.getOrCreateNbt();
 *   nbt.putInt("CustomDamage", 5);
 * 
 * New way (1.20.5+):
 *   stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
 */
public class ComponentBridgeShim {
    
    private static Class<?> dataComponentTypesClass;
    private static Class<?> nbtComponentClass;
    private static Class<?> nbtCompoundClass;
    private static Method itemStackSetMethod;
    private static Method itemStackGetMethod;
    private static Method nbtComponentOfMethod;
    private static Object customDataType;
    private static boolean initialized = false;
    private static boolean useNewSystem = false;
    
    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        
        try {
            // Try to find new component classes
            dataComponentTypesClass = Class.forName("net.minecraft.component.DataComponentTypes");
            nbtComponentClass = Class.forName("net.minecraft.component.type.NbtComponent");
            customDataType = dataComponentTypesClass.getField("CUSTOM_DATA").get(null);
            useNewSystem = true;
            
            // Find methods
            Class<?> itemStackClass = Class.forName("net.minecraft.item.ItemStack");
            for (Method m : itemStackClass.getMethods()) {
                if (m.getName().equals("set") && m.getParameterCount() == 2) {
                    itemStackSetMethod = m;
                }
                if (m.getName().equals("get") && m.getParameterCount() == 1) {
                    itemStackGetMethod = m;
                }
            }
            
            for (Method m : nbtComponentClass.getMethods()) {
                if (m.getName().equals("of") && m.getParameterCount() == 1) {
                    nbtComponentOfMethod = m;
                }
            }
        } catch (Exception e) {
            // Old system: components don't exist
            useNewSystem = false;
        }
        
        try {
            nbtCompoundClass = Class.forName("net.minecraft.nbt.NbtCompound");
        } catch (Exception e) {
            try {
                nbtCompoundClass = Class.forName("net.minecraft.nbt.CompoundTag");
            } catch (Exception e2) {}
        }
    }
    
    /**
     * Get or create NBT data for an ItemStack.
     * Works on both old and new systems.
     */
    public static Object getOrCreateNbt(Object itemStack) {
        initialize();
        
        try {
            if (useNewSystem) {
                // New system: get custom data component
                Object component = itemStackGetMethod.invoke(itemStack, customDataType);
                if (component != null) {
                    Method copyNbt = component.getClass().getMethod("copyNbt");
                    return copyNbt.invoke(component);
                }
                // Create new NBT
                return nbtCompoundClass.getDeclaredConstructor().newInstance();
            } else {
                // Old system: direct NBT access
                Method getOrCreateNbt = itemStack.getClass().getMethod("getOrCreateNbt");
                return getOrCreateNbt.invoke(itemStack);
            }
        } catch (Exception e) {
            try {
                return nbtCompoundClass.getDeclaredConstructor().newInstance();
            } catch (Exception e2) {
                return null;
            }
        }
    }
    
    /**
     * Set NBT data on an ItemStack.
     * Works on both old and new systems.
     */
    public static void setNbt(Object itemStack, Object nbt) {
        initialize();
        
        try {
            if (useNewSystem) {
                // New system: wrap in component and set
                Object component = nbtComponentOfMethod.invoke(null, nbt);
                itemStackSetMethod.invoke(itemStack, customDataType, component);
            } else {
                // Old system: direct NBT set
                Method setNbt = itemStack.getClass().getMethod("setNbt", nbtCompoundClass);
                setNbt.invoke(itemStack, nbt);
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    /**
     * Check if an ItemStack has NBT data.
     */
    public static boolean hasNbt(Object itemStack) {
        initialize();
        
        try {
            if (useNewSystem) {
                Object component = itemStackGetMethod.invoke(itemStack, customDataType);
                return component != null;
            } else {
                Method hasNbt = itemStack.getClass().getMethod("hasNbt");
                return (boolean) hasNbt.invoke(itemStack);
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get the display name of an ItemStack.
     * Handles Text component changes across versions.
     */
    public static Object getName(Object itemStack) {
        try {
            Method getName = findMethod(itemStack.getClass(), "getName", "getDisplayName", "getHoverName");
            return getName.invoke(itemStack);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get the count of items in a stack.
     */
    public static int getCount(Object itemStack) {
        try {
            Method getCount = findMethod(itemStack.getClass(), "getCount");
            return (int) getCount.invoke(itemStack);
        } catch (Exception e) {
            // Try field access for very old versions
            try {
                Field countField = findField(itemStack.getClass(), "count", "stackSize");
                return countField.getInt(itemStack);
            } catch (Exception e2) {
                return 0;
            }
        }
    }
    
    private static Method findMethod(Class<?> clazz, String... names) throws NoSuchMethodException {
        for (String name : names) {
            try {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == 0) {
                        return m;
                    }
                }
            } catch (Exception e) {}
        }
        throw new NoSuchMethodException(Arrays.toString(names));
    }
    
    private static Field findField(Class<?> clazz, String... names) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            for (Field f : current.getDeclaredFields()) {
                for (String name : names) {
                    if (f.getName().equals(name)) {
                        f.setAccessible(true);
                        return f;
                    }
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException(Arrays.toString(names));
    }
}

// Bridges the old 0-256 world height to the range used since 1.17.

/**
 * Handles world height changes between versions.
 * 
 * Pre-1.17: World height 0-256
 * 1.17:     World height 0-256 (transition)
 * 1.18+:    World height -64 to 320
 */
class WorldHeightShim {
    
    private static boolean initialized = false;
    private static int minY = 0;
    private static int maxY = 256;
    private static int height = 256;
    
    private static synchronized void initialize(Object world) {
        if (initialized) return;
        initialized = true;
        
        try {
            // Try to get world dimensions from modern API
            Method getBottomY = findMethod(world.getClass(), "getBottomY");
            Method getTopY = findMethod(world.getClass(), "getTopY");
            Method getHeight = findMethod(world.getClass(), "getHeight");
            
            minY = (int) getBottomY.invoke(world);
            maxY = (int) getTopY.invoke(world);
            height = (int) getHeight.invoke(world);
        } catch (Exception e) {
            // Old API: use defaults
            minY = 0;
            maxY = 256;
            height = 256;
        }
    }
    
    /**
     * Get the minimum Y coordinate for a world.
     */
    public static int getBottomY(Object world) {
        initialize(world);
        return minY;
    }
    
    /**
     * Get the maximum Y coordinate for a world.
     */
    public static int getTopY(Object world) {
        initialize(world);
        return maxY;
    }
    
    /**
     * Get the total height of a world.
     */
    public static int getHeight(Object world) {
        initialize(world);
        return height;
    }
    
    /**
     * Convert an old-style Y coordinate (0-256) to new-style (-64 to 320).
     */
    public static int convertYCoordinate(int oldY) {
        if (minY < 0) {
            // New world: shift coordinates
            return oldY + minY;
        }
        return oldY;
    }
    
    /**
     * Check if a Y coordinate is valid for the world.
     */
    public static boolean isValidY(Object world, int y) {
        initialize(world);
        return y >= minY && y < maxY;
    }
    
    /**
     * Get the section index for a Y coordinate.
     */
    public static int getSectionIndex(Object world, int y) {
        initialize(world);
        return (y - minY) >> 4;
    }
    
    private static Method findMethod(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }
}

// Handles chunk storage format changes.

/**
 * Bridges chunk format differences between versions.
 */
class ChunkHeightShim {
    
    /**
     * Get the number of chunk sections in a world.
     * Pre-1.18: 16 sections (0-256)
     * 1.18+: 24 sections (-64 to 320)
     */
    public static int getSectionCount(Object world) {
        try {
            Method getHeight = world.getClass().getMethod("getHeight");
            int height = (int) getHeight.invoke(world);
            return height >> 4;
        } catch (Exception e) {
            return 16; // Default for old versions
        }
    }
    
    /**
     * Convert old section index to new section index.
     */
    public static int convertSectionIndex(int oldIndex, Object world) {
        int bottomY = WorldHeightShim.getBottomY(world);
        if (bottomY < 0) {
            // New world has negative Y
            return oldIndex + (Math.abs(bottomY) >> 4);
        }
        return oldIndex;
    }
}

// Bridges the world generation changes introduced in 1.18.

/**
 * Bridges world generation API changes.
 */
class WorldGenShim {
    
    /**
     * Get a biome at a position.
     * Handles the change from direct biome access to holder-based access.
     */
    public static Object getBiome(Object world, int x, int y, int z) {
        try {
            // Try new API first
            Method getBiome = findMethod(world.getClass(), "getBiome");
            Object blockPos = createBlockPos(x, y, z);
            Object holder = getBiome.invoke(world, blockPos);
            
            // Unwrap if it's a Holder
            if (holder != null && holder.getClass().getSimpleName().contains("Holder")) {
                Method value = holder.getClass().getMethod("value");
                return value.invoke(holder);
            }
            return holder;
        } catch (Exception e) {
            return null;
        }
    }
    
    private static Object createBlockPos(int x, int y, int z) {
        try {
            Class<?> blockPosClass = Class.forName("net.minecraft.util.math.BlockPos");
            return blockPosClass.getConstructor(int.class, int.class, int.class)
                               .newInstance(x, y, z);
        } catch (Exception e) {
            try {
                Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
                return blockPosClass.getConstructor(int.class, int.class, int.class)
                                   .newInstance(x, y, z);
            } catch (Exception e2) {
                return null;
            }
        }
    }
    
    private static Method findMethod(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }
}

// Bridges text component changes across versions.

/**
 * Bridges Text/Component API changes.
 * 
 * Pre-1.19: new LiteralText("hello"), new TranslatableText("key")
 * 1.19+:    Text.literal("hello"), Text.translatable("key")
 */
class TextShim {
    
    private static Class<?> textClass;
    private static Method literalMethod;
    private static Method translatableMethod;
    private static boolean useNewApi = false;
    private static boolean initialized = false;
    
    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        
        try {
            textClass = Class.forName("net.minecraft.text.Text");
            
            // Try new static methods
            literalMethod = textClass.getMethod("literal", String.class);
            translatableMethod = textClass.getMethod("translatable", String.class);
            useNewApi = true;
        } catch (Exception e) {
            useNewApi = false;
        }
    }
    
    /**
     * Create a literal text component.
     */
    public static Object literal(String text) {
        initialize();
        
        try {
            if (useNewApi) {
                return literalMethod.invoke(null, text);
            } else {
                // Old API: use LiteralText constructor
                Class<?> literalTextClass = Class.forName("net.minecraft.text.LiteralText");
                return literalTextClass.getConstructor(String.class).newInstance(text);
            }
        } catch (Exception e) {
            return text; // Fallback to string
        }
    }
    
    /**
     * Create a translatable text component.
     */
    public static Object translatable(String key, Object... args) {
        initialize();
        
        try {
            if (useNewApi) {
                if (args.length == 0) {
                    return translatableMethod.invoke(null, key);
                } else {
                    Method m = textClass.getMethod("translatable", String.class, Object[].class);
                    return m.invoke(null, key, args);
                }
            } else {
                // Old API: use TranslatableText constructor
                Class<?> translatableTextClass = Class.forName("net.minecraft.text.TranslatableText");
                if (args.length == 0) {
                    return translatableTextClass.getConstructor(String.class).newInstance(key);
                } else {
                    return translatableTextClass.getConstructor(String.class, Object[].class)
                                                .newInstance(key, args);
                }
            }
        } catch (Exception e) {
            return key; // Fallback to key string
        }
    }
    
    /**
     * Get the string content of a text component.
     */
    public static String getString(Object text) {
        if (text == null) return "";
        if (text instanceof String) return (String) text;
        
        try {
            Method getString = text.getClass().getMethod("getString");
            return (String) getString.invoke(text);
        } catch (Exception e) {
            return text.toString();
        }
    }
}

// Bridges registry access changes.

/**
 * Bridges registry API changes across versions.
 */
class RegistryShim {
    
    private static Class<?> registriesClass;
    private static Class<?> registryClass;
    private static boolean initialized = false;
    
    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        
        try {
            // Try new location
            registriesClass = Class.forName("net.minecraft.registry.Registries");
        } catch (Exception e) {
            try {
                // Old location
                registriesClass = Class.forName("net.minecraft.util.registry.Registry");
            } catch (Exception e2) {}
        }
    }
    
    /**
     * Get the block registry.
     */
    public static Object getBlockRegistry() {
        initialize();
        try {
            Field field = registriesClass.getField("BLOCK");
            return field.get(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get the item registry.
     */
    public static Object getItemRegistry() {
        initialize();
        try {
            Field field = registriesClass.getField("ITEM");
            return field.get(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get the entity type registry.
     */
    public static Object getEntityTypeRegistry() {
        initialize();
        try {
            Field field = registriesClass.getField("ENTITY_TYPE");
            return field.get(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get a value from a registry by identifier.
     */
    public static Object get(Object registry, Object identifier) {
        try {
            Method getMethod = registry.getClass().getMethod("get", identifier.getClass());
            return getMethod.invoke(registry, identifier);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get the identifier of a registry entry.
     */
    public static Object getId(Object registry, Object entry) {
        try {
            Method getIdMethod = registry.getClass().getMethod("getId", Object.class);
            return getIdMethod.invoke(registry, entry);
        } catch (Exception e) {
            return null;
        }
    }
}
