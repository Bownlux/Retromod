/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.minecraft.world.BlockPropertyPolyfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the removed
 * {@code net.minecraft.world.level.block.state.properties.DirectionProperty} (#24).
 *
 * <p>Verifies the polyfill redirects the type to the surviving {@code EnumProperty}
 * and bridges all four removed {@code create(...)} factories, keyed on the
 * post-class-remap call shape (no {@code Class} arg) so they don't collide with the
 * {@code EnumProperty.create} overloads. DirectionProperty was removed in 26.1, so
 * the polyfill is host-gated: these tests pin the host to 26.1, and
 * {@link #gatedOffBelowRemoval()} covers the pre-26.1 host where the class still
 * exists and must not be hijacked.
 */
class BlockPropertyPolyfillTest {

    private static final String DIRECTION_PROPERTY =
            "net/minecraft/world/level/block/state/properties/DirectionProperty";
    private static final String ENUM_PROPERTY =
            "net/minecraft/world/level/block/state/properties/EnumProperty";
    private static final String ENUM_DESC =
            "Lnet/minecraft/world/level/block/state/properties/EnumProperty;";
    private static final String LOOKUP =
            "com/retromod/polyfill/registry/DirectionPropertyLookup";

    private String savedVersion;

    @BeforeEach
    void setUp() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        // 26.1: DirectionProperty is removed here, so the polyfill is active.
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Polyfill reports DirectionProperty as a removed class (on 26.1+)")
    void reportsRemovedClass() {
        BlockPropertyPolyfill p = new BlockPropertyPolyfill();
        assertEquals("minecraft_vanilla", p.getCategory());
        assertArrayEquals(new String[]{ DIRECTION_PROPERTY }, p.getRemovedClasses());
    }

    @Test
    @DisplayName("Type redirect: DirectionProperty -> EnumProperty")
    void registersTypeRedirect() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        new BlockPropertyPolyfill().registerPolyfills(t);
        assertEquals(ENUM_PROPERTY, t.getClassRedirects().get(DIRECTION_PROPERTY),
                "DirectionProperty must redirect to its surviving superclass EnumProperty");
    }

    @Test
    @DisplayName("All four create(...) factories bridge to DirectionPropertyLookup")
    void bridgesAllCreateFactories() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        new BlockPropertyPolyfill().registerPolyfills(t);

        // The four post-class-remap create shapes; none collide with the
        // EnumProperty.create(String, Class, ...) overloads.
        String[] descs = {
                "(Ljava/lang/String;)" + ENUM_DESC,
                "(Ljava/lang/String;Ljava/util/function/Predicate;)" + ENUM_DESC,
                "(Ljava/lang/String;[Lnet/minecraft/core/Direction;)" + ENUM_DESC,
                "(Ljava/lang/String;Ljava/util/Collection;)" + ENUM_DESC,
        };
        for (String desc : descs) {
            var key = new RetromodTransformer.MethodKey(ENUM_PROPERTY, "create", desc);
            var target = t.getMethodRedirects().get(key);
            assertNotNull(target, "missing create bridge for " + desc);
            assertEquals(LOOKUP, target.owner());
            assertEquals("create", target.name());
            assertTrue(target.desc().endsWith(")Ljava/lang/Object;"),
                    "bridge must return Object so the transformer emits CHECKCAST EnumProperty: " + desc);
        }
    }

    @Test
    @DisplayName("Bridges do NOT shadow the real EnumProperty.create(String, Class) overloads")
    void doesNotShadowRealOverloads() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        new BlockPropertyPolyfill().registerPolyfills(t);
        // The 26.1 factory takes a Class<Direction> 2nd arg, so it must be untouched.
        var realKey = new RetromodTransformer.MethodKey(
                ENUM_PROPERTY, "create",
                "(Ljava/lang/String;Ljava/lang/Class;)" + ENUM_DESC);
        assertNull(t.getMethodRedirects().get(realKey),
                "the real EnumProperty.create(String, Class) must not be redirected");
    }

    @Test
    @DisplayName("Host-gated: no redirect on a pre-26.1 host (DirectionProperty still exists)")
    void gatedOffBelowRemoval() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        BlockPropertyPolyfill p = new BlockPropertyPolyfill();

        // 1.21.1: DirectionProperty is alive; the type/factory redirects must not fire,
        // or a NeoForge mod's working DirectionProperty references get hijacked.
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        p.registerPolyfills(t);
        assertFalse(t.getClassRedirects().containsKey(DIRECTION_PROPERTY),
                "DirectionProperty exists pre-26.1; the polyfill must not hijack it");
        assertNull(t.getMethodRedirects().get(new RetromodTransformer.MethodKey(
                        ENUM_PROPERTY, "create", "(Ljava/lang/String;)" + ENUM_DESC)),
                "no create() bridge below 26.1");
        assertEquals(0, p.getRemovedClasses().length,
                "manifest must not claim DirectionProperty bridged below 26.1");
    }
}
