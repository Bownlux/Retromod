/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Bridges Forge's old capability API (LazyOptional, ICapabilityProvider) to NeoForge's
 * BlockCapability/EntityCapability/ItemCapability.
 */
public final class CapabilityShim {

    private static Class<?> blockCapabilityClass;
    private static Class<?> entityCapabilityClass;
    private static Class<?> itemCapabilityClass;
    private static Class<?> lazyOptionalClass;

    private static boolean initialized = false;

    private CapabilityShim() {}

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        
        try {
            blockCapabilityClass = Class.forName(
                "net.neoforged.neoforge.capabilities.BlockCapability"
            );
            entityCapabilityClass = Class.forName(
                "net.neoforged.neoforge.capabilities.EntityCapability"
            );
            itemCapabilityClass = Class.forName(
                "net.neoforged.neoforge.capabilities.ItemCapability"
            );

        } catch (ClassNotFoundException e) {
            try {
                lazyOptionalClass = Class.forName(
                    "net.minecraftforge.common.util.LazyOptional"
                );
            } catch (ClassNotFoundException e2) {
                System.err.println("Retromod: No capability system found");
            }
        }
    }
    
    /** {@code LazyOptional.of(supplier)}, or the wrapper on hosts without LazyOptional. */
    public static <T> Object lazyOptionalOf(Supplier<T> supplier) {
        initialize();

        if (lazyOptionalClass != null) {
            try {
                Method ofMethod = lazyOptionalClass.getMethod("of", Supplier.class);
                return ofMethod.invoke(null, supplier);
            } catch (Exception e) {
                // fall through to wrapper
            }
        }

        return new LazyOptionalWrapper<>(supplier);
    }

    public static Object lazyOptionalEmpty() {
        initialize();

        if (lazyOptionalClass != null) {
            try {
                Method emptyMethod = lazyOptionalClass.getMethod("empty");
                return emptyMethod.invoke(null);
            } catch (Exception e) {
                // fall through to wrapper
            }
        }

        return new LazyOptionalWrapper<>(null);
    }

    /** Forge {@code blockEntity.getCapability(cap, side)} mapped to NeoForge {@code level.getCapability(cap, pos, ...)}. */
    public static Object getBlockCapability(Object level, Object capability,
            Object pos, Object state, Object blockEntity, Object side) {

        initialize();

        if (blockCapabilityClass != null) {
            try {
                Method getCapMethod = LegacyPublicMethods.method(level.getClass(), "getCapability",
                    blockCapabilityClass,
                    pos.getClass(),
                    Class.forName("net.minecraft.world.level.block.state.BlockState"),
                    Class.forName("net.minecraft.world.level.block.entity.BlockEntity"),
                    Class.forName("net.minecraft.core.Direction")
                );

                return getCapMethod.invoke(level, capability, pos, state, blockEntity, side);

            } catch (Exception e) {
                try {
                    Method getCapMethod = LegacyPublicMethods.method(level.getClass(), "getCapability",
                        blockCapabilityClass, pos.getClass(), Object.class);
                    return getCapMethod.invoke(level, capability, pos, side);
                } catch (Exception e2) {
                    System.err.println("Retromod: Could not get block capability: " + e2);
                }
            }
        }

        if (blockEntity != null) {
            return getCapabilityOldStyle(blockEntity, capability, side);
        }

        return lazyOptionalEmpty();
    }

    public static Object getEntityCapability(Object entity, Object capability, Object side) {
        initialize();

        if (entityCapabilityClass != null) {
            try {
                Method getCapMethod = LegacyPublicMethods.method(entity.getClass(), "getCapability",
                    entityCapabilityClass,
                    Class.forName("net.minecraft.core.Direction")
                );

                return getCapMethod.invoke(entity, capability, side);

            } catch (Exception e) {
                try {
                    Method getCapMethod = LegacyPublicMethods.method(entity.getClass(), "getCapability",
                        entityCapabilityClass);
                    return getCapMethod.invoke(entity, capability);
                } catch (Exception e2) {
                    // fall through to old style
                }
            }
        }

        return getCapabilityOldStyle(entity, capability, side);
    }

    public static Object getItemCapability(Object stack, Object capability) {
        initialize();

        if (itemCapabilityClass != null) {
            try {
                Method getCapMethod = LegacyPublicMethods.method(stack.getClass(), "getCapability",
                    itemCapabilityClass);
                return getCapMethod.invoke(stack, capability);
            } catch (Exception e) {
                // fall through to old style
            }
        }

        return getCapabilityOldStyle(stack, capability, null);
    }

    private static Object getCapabilityOldStyle(Object target, Object capability, Object side) {
        try {
            if (side != null) {
                Method getCapMethod = LegacyPublicMethods.method(target.getClass(), "getCapability",
                    capability.getClass(), 
                    Class.forName("net.minecraft.core.Direction"));
                return getCapMethod.invoke(target, capability, side);
            } else {
                Method getCapMethod = LegacyPublicMethods.method(target.getClass(), "getCapability",
                    capability.getClass());
                return getCapMethod.invoke(target, capability);
            }
        } catch (Exception e) {
            return lazyOptionalEmpty();
        }
    }
    
    /** Invalidate a LazyOptional on block entity removal. */
    public static void invalidate(Object lazyOptional) {
        if (lazyOptional == null) return;

        try {
            Method invalidateMethod = LegacyPublicMethods.method(lazyOptional.getClass(), "invalidate");
            invalidateMethod.invoke(lazyOptional);
        } catch (Exception e) {
            // wrapper needs none
        }
    }

    /** Stand-in for LazyOptional on hosts that lack it. */
    public static class LazyOptionalWrapper<T> {
        private final Supplier<T> supplier;
        private T cached;
        private boolean resolved = false;
        private boolean valid = true;

        public LazyOptionalWrapper(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        public boolean isPresent() {
            resolve();
            return valid && cached != null;
        }

        public T orElse(T other) {
            resolve();
            return (valid && cached != null) ? cached : other;
        }

        public T orElseThrow() {
            resolve();
            if (!valid || cached == null) {
                throw new NoSuchElementException("LazyOptional is empty or invalid");
            }
            return cached;
        }

        public void ifPresent(java.util.function.Consumer<T> consumer) {
            resolve();
            if (valid && cached != null) {
                consumer.accept(cached);
            }
        }

        public <U> LazyOptionalWrapper<U> lazyMap(java.util.function.Function<T, U> mapper) {
            if (!isPresent()) {
                return new LazyOptionalWrapper<>(null);
            }
            return new LazyOptionalWrapper<>(() -> mapper.apply(orElseThrow()));
        }

        public void invalidate() {
            valid = false;
            cached = null;
        }

        private void resolve() {
            if (!resolved && supplier != null) {
                try {
                    cached = supplier.get();
                } catch (Exception e) {
                    // cached stays null on supplier failure
                }
                resolved = true;
            }
        }
    }

    /** Bridges ForgeCapabilities.X to the matching NeoForge Capabilities.X. */
    public static class Tokens {
        public static Object ITEM_HANDLER;
        public static Object FLUID_HANDLER;
        public static Object ENERGY;

        static {
            try {
                Class<?> caps = Class.forName(
                    "net.neoforged.neoforge.capabilities.Capabilities$ItemHandler"
                );
                ITEM_HANDLER = caps.getField("BLOCK").get(null);
            } catch (Exception e) {
                // not on this host
            }

            try {
                Class<?> caps = Class.forName(
                    "net.neoforged.neoforge.capabilities.Capabilities$FluidHandler"
                );
                FLUID_HANDLER = caps.getField("BLOCK").get(null);
            } catch (Exception e) {
                // not on this host
            }

            try {
                Class<?> caps = Class.forName(
                    "net.neoforged.neoforge.capabilities.Capabilities$EnergyStorage"
                );
                ENERGY = caps.getField("BLOCK").get(null);
            } catch (Exception e) {
                // not on this host
            }
        }
    }
}
