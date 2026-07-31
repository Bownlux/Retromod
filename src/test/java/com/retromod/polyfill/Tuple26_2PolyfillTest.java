/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.minecraft.embedded.Tuple;
import com.retromod.polyfill.minecraft.vanilla.Minecraft26_2RemovedPolyfill;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 26.2 Tuple polyfill: the stub behaves like MC's Tuple, and the redirect
 * is host-gated: it must fire on 26.2+ (where Tuple is removed) and stay OFF
 * on 26.1.x (where Tuple still exists; redirecting it would break live code).
 */
class Tuple26_2PolyfillTest {

    @Test
    void stubMatchesMcTupleSemantics() {
        Tuple<String, Integer> t = new Tuple<>("a", 1);
        assertEquals("a", t.getA());
        assertEquals(1, t.getB());
        t.setA("b");
        t.setB(2);
        assertEquals("b", t.getA());
        assertEquals(2, t.getB());
        assertEquals(new Tuple<>("b", 2), t);
        assertEquals(new Tuple<>("b", 2).hashCode(), t.hashCode());
    }

    @Test
    void flyingAnimalStubIsImplementableSingleMethod() {
        com.retromod.polyfill.minecraft.embedded.FlyingAnimal flyer = () -> true;
        assertTrue(flyer.isFlying());
        assertEquals(1, com.retromod.polyfill.minecraft.embedded.FlyingAnimal.class.getMethods().length,
                "must stay a single-method (functional) interface matching MC's FlyingAnimal");
        assertTrue(com.retromod.polyfill.minecraft.embedded.FlyingAnimal.class.isInterface());
    }

    @Test
    void redirectGatedToHost() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        String saved = RetromodVersion.TARGET_MC_VERSION;
        Minecraft26_2RemovedPolyfill p = new Minecraft26_2RemovedPolyfill();
        try {
            // 26.1.2: Tuple is alive, must not redirect (the third component
            // must not fool the comparison into "26.1.2 > 26.2").
            t.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = "26.1.2";
            p.registerPolyfills(t);
            assertFalse(t.getClassRedirects().containsKey("net/minecraft/util/Tuple"),
                    "Tuple still exists on 26.1.2 - must not be redirected");
            assertFalse(t.getClassRedirects().containsKey("net/minecraft/world/entity/animal/FlyingAnimal"),
                    "FlyingAnimal still exists on 26.1.2 - must not be redirected");
            assertEquals(0, p.getRemovedClasses().length, "manifest must not claim classes bridged on 26.1.2");

            // 1.21.11: also alive, far below, must not redirect.
            t.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = "1.21.11";
            p.registerPolyfills(t);
            assertFalse(t.getClassRedirects().containsKey("net/minecraft/util/Tuple"));

            // 26.2: removed, redirect both Mojang and intermediary names.
            t.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = "26.2";
            p.registerPolyfills(t);
            assertEquals("com/retromod/polyfill/minecraft/embedded/Tuple",
                    t.getClassRedirects().get("net/minecraft/util/Tuple"));
            assertEquals("com/retromod/polyfill/minecraft/embedded/Tuple",
                    t.getClassRedirects().get("net/minecraft/class_3545"));
            assertEquals("com/retromod/polyfill/minecraft/embedded/FlyingAnimal",
                    t.getClassRedirects().get("net/minecraft/world/entity/animal/FlyingAnimal"));
            assertEquals("com/retromod/polyfill/minecraft/embedded/FlyingAnimal",
                    t.getClassRedirects().get("net/minecraft/class_1432"));
            assertEquals(4, p.getRemovedClasses().length);

            // 26.2-rc-1: the raw host string a Prism rc instance reports, still 26.2.
            t.clearRedirectsForTesting();
            RetromodVersion.TARGET_MC_VERSION = "26.2-rc-1";
            p.registerPolyfills(t);
            assertTrue(t.getClassRedirects().containsKey("net/minecraft/util/Tuple"));
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
            t.clearRedirectsForTesting();
        }
    }
}
