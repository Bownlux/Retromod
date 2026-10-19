/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * #302: registers items the way a Farmer's Delight 1.20.1 addon does, against whichever Farmer's
 * Delight the host runs. Its NeoForge 1.21 build only takes a tier and properties for a knife, and
 * declares its effects, items and blocks as {@code Holder}, {@code Supplier} and
 * {@code DeferredHolder} fields. The library's types stay in {@link FarmersDelightCalls}, which only
 * loads when the library is installed. The result is in the log rather than the summary.
 */
final class FarmersDelightAddon {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String CASE = "[Retromod-Test-Forge] #302 a Farmer's Delight 1.20.1 addon's knife and"
            + " registry reads (register event): {}";

    private FarmersDelightAddon() {}

    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            if (!ModList.get().isLoaded("farmersdelight")) {
                LOG.info(CASE, "skipped: Farmer's Delight is not installed");
                return;
            }
            String result;
            try {
                result = FarmersDelightCalls.registerKnifeAndReadEntries(helper);
            } catch (Throwable t) {
                result = "fail: " + t;
            }
            LOG.info(CASE, result);
        });
    }
}
