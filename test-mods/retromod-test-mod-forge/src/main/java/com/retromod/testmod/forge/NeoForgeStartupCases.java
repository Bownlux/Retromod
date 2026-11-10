/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.versions.forge.ForgeVersion;
import net.minecraftforge.versions.mcp.MCPVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge 1.20.1 calls that stopped Forge mods while NeoForge constructed them: parent listeners, versions, configs. */
public final class NeoForgeStartupCases {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String PREFIX = "[Retromod-Test-Forge]";
    private static boolean sideLogged;

    private NeoForgeStartupCases() {}

    /** Registers the listeners and logs the checks that run while the mod constructs. */
    static void register() {
        // #398: NeoForge refuses a listener on AdvancementEvent, which only its two children post.
        String parent;
        try {
            MinecraftForge.EVENT_BUS.register(NeoForgeStartupCases.class);
            MinecraftForge.EVENT_BUS.addListener(NeoForgeStartupCases::onAdvancementLambda);
            parent = "success";
        } catch (Throwable t) {
            parent = "fail: " + t;
        }
        LOG.info("{} #398 listeners on the AdvancementEvent parent register (startup): {}", PREFIX, parent);
        LOG.info("{} #392 ForgeVersion and MCPVersion answer (startup): {}", PREFIX, versions());
        LOG.info("{} #394 ForgeConfigSpec.setConfig loads a config into its spec (startup): {}", PREFIX, setConfig());
        LOG.info("{} #394 FMLConfig.defaultConfigPath answers (startup): {}", PREFIX, defaultConfigPath());
        LOG.info("{} FMLEnvironment's production and naming and ModLoader's static checks answer, as GeckoLib reads them"
                + " (startup): {}", PREFIX, environmentChecks());
    }

    // NeoForge made production a getter, never had naming, and answers the two checks from its own loader.
    private static String environmentChecks() {
        try {
            boolean production = net.minecraftforge.fml.loading.FMLEnvironment.production;
            String naming = net.minecraftforge.fml.loading.FMLEnvironment.naming;
            if (naming == null || naming.isEmpty()) return "fail: empty naming";
            if (net.minecraftforge.fml.ModLoader.isDataGenRunning()) return "fail: data generation reported in a game";
            if (!net.minecraftforge.fml.ModLoader.isLoadingStateValid()) return "fail: loading state invalid";
            return "success (production " + production + ", naming " + naming + ")";
        } catch (Throwable t) {
            return "fail: " + t;
        }
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent event) {
        // Registration is the check; a player earning an advancement only proves delivery.
    }

    private static void onAdvancementLambda(AdvancementEvent event) {
        // As above, for the lambda form.
    }

    // #392: Forge's tick events carried the side they ran on; NeoForge's do not.
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (sideLogged) return;
        sideLogged = true;
        String result;
        try {
            result = event.side == LogicalSide.SERVER ? "success" : "fail: side " + event.side;
        } catch (Throwable t) {
            result = "fail: " + t;
        }
        LOG.info("{} #392 a server tick event's side is SERVER (server tick): {}", PREFIX, result);
    }

    private static String versions() {
        try {
            String forge = ForgeVersion.getVersion();
            String minecraft = MCPVersion.getMCVersion();
            if (forge == null || forge.isEmpty()) return "fail: empty loader version";
            return minecraft == null || minecraft.isEmpty() ? "fail: empty Minecraft version" : "success";
        } catch (Throwable t) {
            return "fail: " + t;
        }
    }

    private static String setConfig() {
        try {
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            ForgeConfigSpec.BooleanValue enabled = builder.define("enabled", false);
            ForgeConfigSpec spec = builder.build();
            CommentedConfig config = CommentedConfig.inMemory();
            config.set("enabled", true);
            spec.setConfig(config);
            return enabled.get() ? "success" : "fail: the spec still reads its default";
        } catch (Throwable t) {
            return "fail: " + t;
        }
    }

    private static String defaultConfigPath() {
        try {
            String path = FMLConfig.defaultConfigPath();
            return path == null || path.isEmpty() ? "fail: empty path" : "success";
        } catch (Throwable t) {
            return "fail: " + t;
        }
    }
}
