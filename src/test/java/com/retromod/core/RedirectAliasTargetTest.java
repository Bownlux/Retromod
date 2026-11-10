/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodTransformer.MethodTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A class rename gives an old redirect a new key, but never over a target written for the new names. */
class RedirectAliasTargetTest {

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String OLD_ID = "net/minecraft/resources/ResourceLocation";
    private static final String NEW_ID = "net/minecraft/resources/Identifier";
    private static final String GET_NEW = "(L" + NEW_ID + ";)Ljava/lang/Object;";
    private static final String GET_OLD = "(L" + OLD_ID + ";)Ljava/lang/Object;";
    private static final MethodKey GET = new MethodKey(REGISTRY, "get", GET_NEW);

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    @AfterEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void theRenameKeepsTheNewNameRedirectWhenItArrivesLast() {
        // The 26.1 getter, then the 1.21.2 one, then the 26.1 rename: the game's order on Forge 26.x.
        transformer.registerMethodRedirect(REGISTRY, "get", GET_NEW, REGISTRY, "getValue", GET_NEW);
        transformer.registerMethodRedirect(REGISTRY, "get", GET_OLD, REGISTRY, "getValue", GET_OLD);
        transformer.registerClassRedirect(OLD_ID, NEW_ID);

        assertEquals(new MethodTarget(REGISTRY, "getValue", GET_NEW), target(GET),
                "a single-pass reader would emit getValue(ResourceLocation), which 26.x lacks");
    }

    @Test
    void theRenameKeepsTheNewNameRedirectWhenItArrivesFirst() {
        transformer.registerClassRedirect(OLD_ID, NEW_ID);
        transformer.registerMethodRedirect(REGISTRY, "get", GET_NEW, REGISTRY, "getValue", GET_NEW);
        transformer.registerMethodRedirect(REGISTRY, "get", GET_OLD, REGISTRY, "getValue", GET_OLD);

        assertEquals(new MethodTarget(REGISTRY, "getValue", GET_NEW), target(GET));
    }

    @Test
    void anAliasToAHelperStillReplacesTheNewNameRedirect() {
        // A target that names no renamed class works on the host, so a later one still wins.
        String register = "(L" + REGISTRY + ";L%s;Ljava/lang/Object;)Ljava/lang/Object;";
        String helper = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
        transformer.registerMethodRedirect(REGISTRY, "register", register.formatted(NEW_ID),
                "fixture/Bridge", "register", register.formatted(NEW_ID));
        transformer.registerMethodRedirect(REGISTRY, "register", register.formatted(OLD_ID),
                "fixture/LateRegistration", "register", helper);
        transformer.registerClassRedirect(OLD_ID, NEW_ID);

        assertEquals(new MethodTarget("fixture/LateRegistration", "register", helper),
                target(new MethodKey(REGISTRY, "register", register.formatted(NEW_ID))));
    }

    @Test
    void anAliasStillFillsAKeyNothingElseRegistered() {
        transformer.registerMethodRedirect(REGISTRY, "get", GET_OLD, REGISTRY, "getValue", GET_OLD);
        transformer.registerClassRedirect(OLD_ID, NEW_ID);

        assertEquals(new MethodTarget(REGISTRY, "getValue", GET_OLD), target(GET));
    }

    private MethodTarget target(MethodKey key) {
        return transformer.getMethodRedirects().get(key);
    }
}
