package fixture;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;

/** The client start: mods initialize, the client creates its block colors, then CLIENT_STARTED fires. */
public final class FakeClientStartup {
    private FakeClientStartup() {}

    /** Back to the moment client mods initialize, before block colors exist. */
    public static void begin() {
        Minecraft.getInstance().setBlockColorsForTesting(null);
        BlockColorRegistry.beginStartupForTesting();
    }

    /** Creates the block colors and fires CLIENT_STARTED. */
    public static void finish() {
        Minecraft client = Minecraft.getInstance();
        BlockColors colors = new BlockColors();
        client.setBlockColorsForTesting(colors);
        BlockColorRegistry.initializeForTesting(colors);
        for (ClientLifecycleEvents.ClientStarted listener : ClientLifecycleEvents.CLIENT_STARTED.listeners) {
            listener.onClientStarted(client);
        }
    }
}
