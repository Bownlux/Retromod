/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.ServiceConfigurationError;

/**
 * Registry that discovers and manages polyfill providers.
 *
 * Loads PolyfillProvider implementations via ServiceLoader,
 * checks config for enabled categories, and registers their
 * redirects with the transformer.
 */
public class PolyfillRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Polyfills");

    private final List<PolyfillProvider> providers = new ArrayList<>();
    private final Set<String> enabledCategories = new HashSet<>();
    private boolean polyfillsEnabled = true;

    public PolyfillRegistry() {
        // All categories enabled by default
        enabledCategories.add("fabric_api");
        enabledCategories.add("rendering");
        enabledCategories.add("entity");
        enabledCategories.add("mixin_targets");
        enabledCategories.add("minecraft_vanilla");
        enabledCategories.add("forge");
        enabledCategories.add("neoforge");
        enabledCategories.add("thirdparty");
    }

    /**
     * Set whether polyfills are globally enabled.
     */
    public void setEnabled(boolean enabled) {
        this.polyfillsEnabled = enabled;
    }

    /**
     * Enable or disable a specific category.
     */
    public void setCategoryEnabled(String category, boolean enabled) {
        if (enabled) {
            enabledCategories.add(category);
        } else {
            enabledCategories.remove(category);
        }
    }

    /**
     * Load all polyfill providers via ServiceLoader and register
     * enabled ones with the transformer.
     */
    public void loadAndRegister(RetromodTransformer transformer) {
        if (!polyfillsEnabled) {
            LOGGER.info("Polyfills are disabled in config");
            return;
        }

        // Discover providers via ServiceLoader
        // Lite builds intentionally omit some provider classes, so discovery must tolerate a
        // missing ServiceLoader entry.
        ServiceLoader<PolyfillProvider> loader = ServiceLoader.load(PolyfillProvider.class);

        int registered = 0;
        int skipped = 0;

        Iterator<PolyfillProvider> it = loader.iterator();
        while (it.hasNext()) {
            PolyfillProvider provider;
            try {
                provider = it.next();
            } catch (ServiceConfigurationError e) {
                LOGGER.debug("Skipping a polyfill that is not included in this build: {}",
                        e.getMessage());
                skipped++;
                continue;
            }

            providers.add(provider);

            if (enabledCategories.contains(provider.getCategory())) {
                try {
                    provider.registerPolyfills(transformer);
                    registered++;

                    String[] removed = provider.getRemovedClasses();
                    LOGGER.info("Loaded {} from the {} category ({})",
                            provider.getName(), provider.getCategory(),
                            removedClassSummary(removed.length));
                } catch (Exception e) {
                    LOGGER.warn("Could not load the {} polyfill", provider.getName(), e);
                }
            } else {
                skipped++;
                LOGGER.debug("Skipping {} because its category is disabled", provider.getName());
            }
        }

        LOGGER.info("Loaded {} polyfills and skipped {}", registered, skipped);
    }

    private static String removedClassSummary(int count) {
        if (count == 0) {
            return "no removed classes";
        }
        return count + (count == 1 ? " removed class" : " removed classes");
    }

    /**
     * @return All discovered providers (both enabled and disabled)
     */
    public List<PolyfillProvider> getProviders() {
        return Collections.unmodifiableList(providers);
    }

    /**
     * @return Set of enabled category names
     */
    public Set<String> getEnabledCategories() {
        return Collections.unmodifiableSet(enabledCategories);
    }
}
