/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FabricNetworkingChannelEventRenameTest {

    @AfterEach
    void tearDown() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void clientC2sHoldersAndCallbacksBecomeServerbound() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        for (String kind : new String[]{"Configuration", "Play"}) {
            String oldOwner = "net/fabricmc/fabric/api/client/networking/v1/C2S"
                    + kind + "ChannelEvents";
            String newOwner = "net/fabricmc/fabric/api/client/networking/v1/Serverbound"
                    + kind + "ChannelEvents";
            assertEquals(newOwner, transformer.getClassRedirects().get(oldOwner));
            assertEquals(newOwner + "$Register",
                    transformer.getClassRedirects().get(oldOwner + "$Register"));
            assertEquals(newOwner + "$Unregister",
                    transformer.getClassRedirects().get(oldOwner + "$Unregister"));
        }
    }
}
