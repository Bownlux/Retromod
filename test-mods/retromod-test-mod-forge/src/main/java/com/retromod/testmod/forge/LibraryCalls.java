/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.retromod.testlib.forge.RetromodTestLibForge;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.util.LazyOptional;

/**
 * #362: calls into the companion library jar (retromod-test-lib-forge), whose API takes types
 * Retromod rebuilds inside every translated jar on newer hosts. They only link when the test mod
 * reuses the library's copies. Kept out of the mod class, so a type a host lacks fails only its
 * own check instead of the whole mod.
 */
final class LibraryCalls {

    private LibraryCalls() {}

    static boolean lazyOptionalLinks() {
        return RetromodTestLibForge.isPresent(LazyOptional.of(() -> "ready"));
    }

    static boolean tierLinks() {
        return RetromodTestLibForge.speed(new LibraryTier()) == 6.0F;
    }

    /** #358: whether the library and {@code mod} were both translated, or both left as they are. */
    static boolean manifestVersionedLibraryTranslatedLike(Class<?> mod, String modTitle)
            throws java.io.IOException {
        return translatedByRetromod(RetromodTestLibForge.class, "retromod-test-lib-forge")
                == translatedByRetromod(mod, modTitle);
    }

    /**
     * Whether the jar that defined {@code type} carries Retromod's output stamp. The title check
     * proves the manifest read is that jar's own, so a lookup that reaches another jar fails the
     * case instead of passing it.
     */
    private static boolean translatedByRetromod(Class<?> type, String title) throws java.io.IOException {
        try (java.io.InputStream in = type.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                throw new IllegalStateException("no manifest readable for " + type.getName());
            }
            java.util.jar.Attributes main = new java.util.jar.Manifest(in).getMainAttributes();
            if (!title.equals(main.getValue("Implementation-Title"))) {
                throw new IllegalStateException("read another jar's manifest for " + type.getName()
                        + ": " + main.getValue("Implementation-Title"));
            }
            return "true".equalsIgnoreCase(main.getValue("Retromod-Transformed"))
                    || main.getValue("Retromod-AOT-Version") != null;
        }
    }

    /** An iron-like tier the test mod hands to its companion library. */
    private static final class LibraryTier implements Tier {
        @Override public int getUses() { return 250; }
        @Override public float getSpeed() { return 6.0F; }
        @Override public float getAttackDamageBonus() { return 2.0F; }
        @Override public int getLevel() { return 2; }
        @Override public int getEnchantmentValue() { return 14; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.EMPTY; }
    }
}
