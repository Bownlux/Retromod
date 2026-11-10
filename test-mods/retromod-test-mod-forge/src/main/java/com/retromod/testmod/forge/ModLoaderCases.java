/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.retromod.testlib.forge.RetromodTestLibForge;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge 1.20.1's {@code ModLoader.get()} calls, which Forge 26.2 made static. */
final class ModLoaderCases {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String WARNING_TEXT = "Retromod test mod: this loading warning is expected";
    private static boolean warned;

    private ModLoaderCases() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(ModLoaderCases::check);
    }

    /** A warning added while the mod constructs, as ModernUI and embeddium add theirs. */
    static boolean warnDuringConstruction() {
        ModLoader.get().addWarning(new ModLoadingWarning(null, ModLoadingStage.CONSTRUCT, WARNING_TEXT));
        warned = true;
        return true;
    }

    /** Whether Forge's own warning list lacks this mod's warning, which Retromod logs instead, on a server too. */
    static boolean warningStayedOutOfForge() throws ReflectiveOperationException {
        for (ModLoadingWarning warning : ModLoader.get().getWarnings()) {
            if (WARNING_TEXT.equals(messageOf(warning))) return false;
        }
        return true;
    }

    // Forge 26.2's warning is a record with i18nMessage(); Forge 1.20.1's has no getter, so its text is used there.
    private static String messageOf(ModLoadingWarning warning) throws ReflectiveOperationException {
        try {
            return (String) warning.getClass().getMethod("i18nMessage").invoke(warning);
        } catch (NoSuchMethodException noGetter) {
            return warning.formatToString();
        }
    }

    // Every mod has constructed by now, so the library's listener is on its mod bus.
    private static void check(ServerStartedEvent event) {
        // Only a client's loader stops the game bus on a warning, when it shows warnings, which is the default.
        // A dedicated server never does, so there the warning-list check in the summary is the proof.
        log("the game bus still runs after a ModLoader warning during construction (server started)", () -> warned);
        log("ModLoader.get().postEvent reaches the library's own mod-bus listener (server started)", () -> {
            int before = RetromodTestLibForge.modBusEventsSeen();
            ModLoader.get().postEvent(new RetromodTestLibForge.LibModBusEvent());
            return RetromodTestLibForge.modBusEventsSeen() == before + 1;
        });
        log("ModLoader.get().hasCompletedState(\"LOAD_REGISTRIES\") once the server runs (server started)",
                () -> ModLoader.get().hasCompletedState("LOAD_REGISTRIES"));
    }

    private static void log(String description, Check check) {
        String result;
        try {
            result = check.run() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] {}: {}", description, result);
    }

    @FunctionalInterface
    private interface Check {
        boolean run() throws Throwable;
    }
}
