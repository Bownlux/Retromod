/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.LoggerFactory;

/** The server brand on the player and the connection's id, which JEI reads while joining and 1.20.2 removed. Client only. */
final class ClientConnectionCase {

    private static final String WHAT = "The player's server brand and the connection id still read (first client tick in a world)";

    private static boolean reported;

    private ClientConnectionCase() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientConnectionCase::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || reported || minecraft.level == null || minecraft.player == null) return;
        reported = true;
        try {
            String brand = minecraft.player.getServerBrand();
            ClientPacketListener connection = minecraft.getConnection();
            UUID first = connection.getId();
            // 1.20.1 kept one id for the whole connection, so asking again must give the same one.
            String result = first != null && first.equals(connection.getId()) ? "success (brand " + brand + ")" : "fail: the id changed";
            log(result);
        } catch (Throwable t) {
            log("fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", WHAT, result);
    }
}
