/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import com.retromod.shim.api.common.embedded.ApiShimWarnings;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shim for FabricItemGroupBuilder, removed in 1.19.3. Adapts the old
 * create/icon/appendItems/build chain onto FabricItemGroup.builder().
 */
public class FabricItemGroupBuilderShim {

    private Object identifier;
    private Supplier<?> iconSupplier;
    private Consumer<?> appendItemsConsumer;

    private FabricItemGroupBuilderShim() {}

    public static FabricItemGroupBuilderShim create(Object identifier) {
        FabricItemGroupBuilderShim builder = new FabricItemGroupBuilderShim();
        builder.identifier = identifier;
        return builder;
    }

    public FabricItemGroupBuilderShim icon(Supplier<?> iconSupplier) {
        this.iconSupplier = iconSupplier;
        return this;
    }

    public FabricItemGroupBuilderShim appendItems(Consumer<?> appendItems) {
        this.appendItemsConsumer = appendItems;
        return this;
    }

    /**
     * Legacy static {@code build(Identifier, Supplier<ItemStack>)} form (#57). The MC types are
     * compile-time stubs (class_2960, class_1761) stripped from the production jar.
     */
    public static net.minecraft.class_1761 build(net.minecraft.class_2960 id, Supplier<?> stack) {
        try {
            FabricItemGroupBuilderShim builder = create(id);
            if (stack != null) builder.icon(stack);
            Object result = builder.build();
            return (net.minecraft.class_1761) result;
        } catch (Throwable t) {
            // no ItemGroup API on the host: return null so the caller's static init survives
            warn("build", id, "it returned null", t);
            return null;
        }
    }

    /** Build via FabricItemGroup.builder(), falling back to the old builder. */
    public Object build() {
        try {
            Class<?> fabricItemGroupClass = Class.forName("net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup");
            Method builderMethod = fabricItemGroupClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            
            Class<?> builderClass = builder.getClass();

            if (iconSupplier != null) {
                Method iconMethod = findMethod(builderClass, "icon", Supplier.class);
                if (iconMethod != null) {
                    builder = iconMethod.invoke(builder, iconSupplier);
                }
            }

            if (identifier != null) {
                try {
                    Method displayNameMethod = findMethod(builderClass, "displayName");
                    if (displayNameMethod != null) {
                        Object text = createDisplayName(identifier);
                        builder = displayNameMethod.invoke(builder, text);
                    }
                } catch (Exception e) {
                    warn("displayName", identifier, "the item group has no display name", e);
                }
            }

            // adapt old Consumer<List<ItemStack>> appendItems onto the new entries() BiConsumer
            if (appendItemsConsumer != null) {
                try {
                    Method entriesMethod = findMethod(builderClass, "entries");
                    if (entriesMethod != null) {
                        Object entriesCallback = createEntriesCallback(appendItemsConsumer);
                        builder = entriesMethod.invoke(builder, entriesCallback);
                    }
                } catch (Exception e) {
                    warn("appendItems", identifier, "the item group's items were not added", e);
                }
            }

            Method buildMethod = findMethod(builderClass, "build");
            return buildMethod.invoke(builder);

        } catch (Exception e) {
            return tryLegacyBuild();
        }
    }

    private Object tryLegacyBuild() {
        try {
            Class<?> oldBuilderClass = Class.forName("net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder");
            Method createMethod = oldBuilderClass.getMethod("create", 
                Class.forName("net.minecraft.util.Identifier"));
            Object builder = createMethod.invoke(null, identifier);
            
            if (iconSupplier != null) {
                Method iconMethod = oldBuilderClass.getMethod("icon", Supplier.class);
                builder = iconMethod.invoke(builder, iconSupplier);
            }
            
            if (appendItemsConsumer != null) {
                Method appendMethod = oldBuilderClass.getMethod("appendItems", Consumer.class);
                builder = appendMethod.invoke(builder, appendItemsConsumer);
            }
            
            Method buildMethod = oldBuilderClass.getMethod("build");
            return buildMethod.invoke(builder);
            
        } catch (Exception e) {
            throw new RuntimeException("Cannot build item group with either old or new API", e);
        }
    }
    
    private Object createDisplayName(Object identifier) {
        try {
            Class<?> textClass = Class.forName("net.minecraft.text.Text");
            Method translatableMethod = textClass.getMethod("translatable", String.class);
            
            String namespace = LegacyPublicMethods.method(identifier.getClass(), "getNamespace").invoke(identifier).toString();
            String path = LegacyPublicMethods.method(identifier.getClass(), "getPath").invoke(identifier).toString();
            String key = "itemGroup." + namespace + "." + path;
            
            return translatableMethod.invoke(null, key);
        } catch (Exception e) {
            warn("createDisplayName", identifier, "the item group has no display name", e);
            return null;
        }
    }

    private static void warn(String call, Object receiver, String effect, Throwable failure) {
        ApiShimWarnings.warnOnce("Fabric item group", call, receiver, effect, failure);
    }
    
    private Object createEntriesCallback(Consumer<?> oldConsumer) {
        // proxy the new entries interface: feed the old consumer a list, forward to Entries.add
        return java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { findEntriesInterface() },
            (proxy, method, args) -> {
                if (method.getName().equals("accept") && args.length == 2) {
                    // args: [0] DisplayContext, [1] Entries
                    java.util.List<Object> items = new java.util.ArrayList<>();
                    ((Consumer<java.util.List<Object>>) oldConsumer).accept(items);

                    Object entries = args[1];
                    Method addMethod = LegacyPublicMethods.method(entries.getClass(), "add",
                        Class.forName("net.minecraft.item.ItemStack"));
                    for (Object item : items) {
                        addMethod.invoke(entries, item);
                    }
                }
                return null;
            }
        );
    }
    
    private Class<?> findEntriesInterface() {
        try {
            return Class.forName("net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents$ModifyEntries");
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName("java.util.function.BiConsumer");
            } catch (ClassNotFoundException e2) {
                return Object.class;
            }
        }
    }
    
    private Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            if (params.length > 0) {
                return clazz.getMethod(name, params);
            }
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
