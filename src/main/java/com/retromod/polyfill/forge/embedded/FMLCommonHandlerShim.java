/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.forge.embedded;

/**
 * Reimplementation of FMLCommonHandler (removed 1.13).
 * Old Forge mods called FMLCommonHandler.instance().getSide() etc.
 */
public class FMLCommonHandlerShim {
    private static final FMLCommonHandlerShim INSTANCE = new FMLCommonHandlerShim();

    public static FMLCommonHandlerShim instance() {
        return INSTANCE;
    }

    public SideShim getSide() {
        // initialize=false: a side probe must never run Minecraft's static initializer.
        try {
            Class.forName("net.minecraft.client.Minecraft", false,
                    FMLCommonHandlerShim.class.getClassLoader());
            return SideShim.CLIENT;
        } catch (ClassNotFoundException e) {
            return SideShim.SERVER;
        }
    }

    public SideShim getEffectiveSide() {
        return getSide();
    }

    /**
     * {@code Loader.isModLoaded}, which 1.12 mods call to gate optional integrations. The host's
     * mod list answers it; a 1.12 integration API is still absent even when a mod with that id is
     * installed, so callers that then touch the other mod's classes can still fail.
     */
    public static boolean isModLoaded(String modId) {
        for (String modList : new String[]{"net.neoforged.fml.ModList", "net.minecraftforge.fml.ModList"}) {
            try {
                Class<?> type = Class.forName(modList, false, FMLCommonHandlerShim.class.getClassLoader());
                Object list = type.getMethod("get").invoke(null);
                return list != null && (Boolean) type.getMethod("isLoaded", String.class).invoke(list, modId);
            } catch (ClassNotFoundException e) {
                // Not this loader; try the next one.
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    public void exitJava(int exitCode, boolean hardExit) {
        System.exit(exitCode);
    }
}
