/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Remembers which pre-1.19.3 creative tab each item asked for, and registers those tabs.
 *
 * <p>Up to 1.19.2 an item joined a tab through {@code new Item.Properties().tab(TAB)}, and a tab
 * listed itself the moment it was constructed. 1.19.3 fills a tab from a {@code displayItems}
 * callback and only shows tabs that are in the creative tab registry, so an old mod's tabs were
 * invisible and every item was in none of them.
 *
 * <p>The bridged {@code tab(...)} call records the properties object with its tab, and the item
 * constructor call that receives those properties records the finished item. The generated tab's
 * {@code displayItems} callback then lists those items in the order the mod created them. A tab is
 * registered under the constructing mod's namespace and its old label, during the creative tab
 * registry's {@code RegisterEvent}.
 *
 * <p>This class names no Minecraft member, because a Forge host runs SRG member names and this code
 * is not remapped. The Forge calls use Forge's own API names, which are never obfuscated. Embedded
 * per mod, so each mod keeps its own tabs.
 */
public final class LegacyCreativeTabContents {

    private static final String TAB_REGISTRY = "minecraft:creative_mode_tab";

    /** Properties do not override {@code equals}, so this is keyed by identity, and weakly. */
    private static final Map<Object, Object> TAB_BY_PROPERTIES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Set<Object>> ITEMS_BY_TAB = new IdentityHashMap<>();
    private static final Map<String, Object> PENDING_TABS = new LinkedHashMap<>();
    private static boolean listening;

    private LegacyCreativeTabContents() {}

    /** {@code properties.tab(tab)}: remembers the tab for the item built from these properties. */
    public static Object tag(Object properties, Object tab) {
        if (properties != null && tab != null) TAB_BY_PROPERTIES.put(properties, tab);
        return properties;
    }

    /** An item was constructed from {@code properties}; lists it in the tab they named. */
    public static void claim(Object item, Object properties) {
        if (item == null || properties == null) return;
        Object tab = TAB_BY_PROPERTIES.get(properties);
        if (tab == null || !isItem(item.getClass())) return;
        synchronized (ITEMS_BY_TAB) {
            ITEMS_BY_TAB.computeIfAbsent(tab, t -> new LinkedHashSet<>()).add(item);
        }
    }

    /** The items that asked for {@code tab}, in creation order, each listed once. */
    public static Object[] contents(Object tab) {
        synchronized (ITEMS_BY_TAB) {
            Set<Object> items = ITEMS_BY_TAB.get(tab);
            return items == null ? new Object[0] : items.toArray();
        }
    }

    /** A legacy tab was constructed with {@code label}; registers it when the registry opens. */
    public static void created(Object tab, String label) {
        String id = tabId(activeNamespace(), label);
        if (id == null) return;
        synchronized (PENDING_TABS) {
            PENDING_TABS.putIfAbsent(id, tab);
        }
        listenForRegistration();
    }

    /** {@code namespace:label} as a valid resource id, or null when neither part is usable. */
    static String tabId(String namespace, String label) {
        if (namespace == null || namespace.isEmpty() || "minecraft".equals(namespace)
                || !namespace.matches("[a-z0-9_.-]+") || label == null) {
            return null;
        }
        String path = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return path.isEmpty() ? null : namespace + ":" + path;
    }

    /** The tabs waiting for the creative tab registry, as {@code id -> tab}. */
    static Map<String, Object> pendingTabs() {
        synchronized (PENDING_TABS) {
            return new LinkedHashMap<>(PENDING_TABS);
        }
    }

    private static boolean isItem(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if ("net.minecraft.world.item.Item".equals(c.getName())) return true;
        }
        return false;
    }

    private static synchronized void listenForRegistration() {
        if (listening) return;
        listening = true;
        try {
            Object bus = modEventBus();
            if (bus == null) {
                log("no mod event bus is active, so its creative tabs stay unregistered");
                return;
            }
            Class<?> eventType = Class.forName("net.minecraftforge.registries.RegisterEvent");
            Class<?> priorityType = Class.forName("net.minecraftforge.eventbus.api.EventPriority");
            Object normal = priorityType.getField("NORMAL").get(null);
            Method addListener = Class.forName("net.minecraftforge.eventbus.api.IEventBus")
                    .getMethod("addListener", priorityType, boolean.class, Class.class, Consumer.class);
            Consumer<Object> listener = LegacyCreativeTabContents::onRegister;
            addListener.invoke(bus, normal, false, eventType, listener);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("cannot register old creative tabs on this loader: " + e);
        }
    }

    private static void onRegister(Object event) {
        try {
            Object registryKey = event.getClass().getMethod("getRegistryKey").invoke(event);
            // ResourceKey.location() has an SRG name here; its toString does not, and it ends
            // with "<registry id>]".
            if (!String.valueOf(registryKey).endsWith(TAB_REGISTRY + "]")) return;
            Class<?> keyType = Class.forName("net.minecraft.resources.ResourceKey");
            Class<?> locationType = Class.forName("net.minecraft.resources.ResourceLocation");
            Method register = event.getClass().getMethod("register", keyType, locationType, Supplier.class);
            List<Map.Entry<String, Object>> tabs = new ArrayList<>(pendingTabs().entrySet());
            for (Map.Entry<String, Object> tab : tabs) {
                Object location = location(locationType, tab.getKey());
                Object value = tab.getValue();
                Supplier<Object> supplier = () -> value;
                register.invoke(event, registryKey, location, supplier);
            }
            if (!tabs.isEmpty()) {
                log("registered " + tabs.size() + " creative tab(s): "
                        + String.join(", ", pendingTabs().keySet()));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log("could not register old creative tabs: " + e);
        }
    }

    /**
     * {@code new ResourceLocation(id)}. 1.21 made that constructor private and added
     * {@code parse(String)}, which Forge exposes under its Mojang name from 1.20.6 on.
     */
    private static Object location(Class<?> locationType, String id) throws ReflectiveOperationException {
        try {
            return locationType.getConstructor(String.class).newInstance(id);
        } catch (NoSuchMethodException privateConstructor) {
            return locationType.getMethod("parse", String.class).invoke(null, id);
        }
    }

    private static Object modEventBus() throws ReflectiveOperationException {
        Object context = Class.forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext")
                .getMethod("get").invoke(null);
        return context == null ? null : context.getClass().getMethod("getModEventBus").invoke(context);
    }

    private static String activeNamespace() {
        try {
            Object context = Class.forName("net.minecraftforge.fml.ModLoadingContext")
                    .getMethod("get").invoke(null);
            Object namespace = context.getClass().getMethod("getActiveNamespace").invoke(context);
            return namespace == null ? null : namespace.toString();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static void log(String message) {
        System.out.println("[Retromod] Legacy creative tabs: " + message);
    }
}
