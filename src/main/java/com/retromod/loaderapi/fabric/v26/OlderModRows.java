/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.LoaderApiRegistration;
import org.objectweb.asm.commons.Remapper;

import java.util.Map;

/** Limits table rows for a call shape that only older mods make to mods built before the version that dropped it. */
final class OlderModRows {

    private static final String GENERATION = "net/fabricmc/fabric/api/biome/v1/BiomeModificationContext$GenerationSettingsContext";
    private static final String CARVER_ARGS = "(Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;"
            + "Lnet/minecraft/resources/ResourceKey;)";

    /** {owner, name, descriptor, first version without the shape}. */
    private static final String[][] LIMITS = {
        // 1.21.2 keeps one carver list, so a mod built for it never passes a carving step.
        {GENERATION, "addCarver", CARVER_ARGS + "V", "1.21.2"},
        {GENERATION, "removeCarver", CARVER_ARGS + "Z", "1.21.2"},
    };

    private OlderModRows() {}

    /** Runs after the table, because claiming a row's key clears an earlier limit on it. */
    static void register(LoaderApiRegistration registration) {
        RetromodTransformer transformer = registration.transformer();
        Remapper names = finalNames(transformer.getClassRedirects());
        for (String[] limit : LIMITS) {
            transformer.limitRedirectToModsBefore(limit[0], limit[1], limit[2], limit[3]);
            // A class redirect registered before the runtime, such as one for the carving step, makes the call arrive
            // under its new descriptor, which the row's redirect also answers.
            String remapped = names.mapMethodDesc(limit[2]);
            if (!remapped.equals(limit[2])) transformer.limitRedirectToModsBefore(limit[0], limit[1], remapped, limit[3]);
        }
    }

    private static Remapper finalNames(Map<String, String> redirects) {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                String current = internalName;
                // Follow a chain of moves to the last name, as the class remap does.
                for (int hops = 0; hops < 8; hops++) {
                    String next = redirects.get(current);
                    if (next == null || next.equals(current)) break;
                    current = next;
                }
                return current;
            }
        };
    }
}
