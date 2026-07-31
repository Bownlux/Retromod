/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.vanilla;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfills for vanilla MC classes removed in 26.2.
 *
 * <p>Host-gated to 26.2+: these classes still exist on a 26.1 host, where
 * redirecting them would point live code at a stub. Both
 * {@link #getRemovedClasses()} and {@link #registerPolyfills} no-op below 26.2.
 *
 * <p>Covers {@code net/minecraft/util/Tuple} (intermediary {@code class_3545}),
 * a mutable pair reimplemented by
 * {@link com.retromod.polyfill.minecraft.embedded.Tuple}; and
 * {@code net/minecraft/world/entity/animal/FlyingAnimal} (intermediary
 * {@code class_1432}), a single-method marker interface reimplemented by
 * {@link com.retromod.polyfill.minecraft.embedded.FlyingAnimal}.
 *
 * <p>The reshaped 26.2 render APIs ({@code MultiBufferSource}, the
 * {@code Tesselator}/{@code VertexFormat} vertex layer) need adapter polyfills,
 * not trivial reimplements, and are tracked for 1.3.0.
 */
public class Minecraft26_2RemovedPolyfill implements PolyfillProvider {

    private static final String EMBEDDED_TUPLE = "com/retromod/polyfill/minecraft/embedded/Tuple";
    private static final String EMBEDDED_FLYING_ANIMAL = "com/retromod/polyfill/minecraft/embedded/FlyingAnimal";

    /** True only when the host MC is 26.2 or newer. */
    private static boolean active() {
        // target >= 26.2, so 26.1.2 (< 26.2 despite the third component) stays out.
        return !RetromodVersion.mcVersionExceeds("26.2", RetromodVersion.TARGET_MC_VERSION);
    }

    @Override
    public String getName() {
        return "Minecraft 26.2 Removed APIs";
    }

    @Override
    public String getCategory() {
        return "minecraft_vanilla";
    }

    @Override
    public String[] getRemovedClasses() {
        if (!active()) return new String[0];
        return new String[]{
            "net/minecraft/util/Tuple",
            "net/minecraft/class_3545",
            "net/minecraft/world/entity/animal/FlyingAnimal",
            "net/minecraft/class_1432"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{ EMBEDDED_TUPLE, EMBEDDED_FLYING_ANIMAL };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        if (!active()) {
            return; // Tuple still exists on 26.1 and earlier.
        }
        // Register both the Mojang and intermediary names: the ASM remapper is
        // single-pass, so class_3545 to Tuple wouldn't chain into Tuple to polyfill.
        transformer.registerClassRedirect("net/minecraft/util/Tuple", EMBEDDED_TUPLE);
        transformer.registerClassRedirect("net/minecraft/class_3545", EMBEDDED_TUPLE);

        // FlyingAnimal (single-method interface, boolean isFlying()) was removed in 26.2 with no
        // successor. A flying-mob content mod that `implements FlyingAnimal` on its entity fails to
        // load (NoClassDefFoundError) before any code runs; redirecting the implements-reference to
        // the embedded interface lets the mob class load. Both spellings, same single-pass reason.
        transformer.registerClassRedirect(
                "net/minecraft/world/entity/animal/FlyingAnimal", EMBEDDED_FLYING_ANIMAL);
        transformer.registerClassRedirect("net/minecraft/class_1432", EMBEDDED_FLYING_ANIMAL);
    }
}
