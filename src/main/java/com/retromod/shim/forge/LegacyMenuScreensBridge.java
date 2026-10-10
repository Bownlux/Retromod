/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyMenuScreens;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;

/**
 * Routes a Forge-family mod's {@code MenuScreens.register} call through {@link LegacyMenuScreens}
 * on hosts that keep the method private.
 *
 * <p>Forge opens the method with an access transformer and NeoForge does not, so this is
 * registered only from the NeoForge chains. Access transformers apply at load time, so the class
 * file is private on every loader and the probe only confirms the method is there. A dedicated
 * server has no {@code MenuScreens} and never runs the client code that calls it.
 */
public final class LegacyMenuScreensBridge {

    static final String HELPER = "com/retromod/shim/forge/embedded/LegacyMenuScreens";
    static final String SCREENS = "net/minecraft/client/gui/screens/MenuScreens";
    static final String REGISTER_DESC =
            "(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/gui/screens/MenuScreens$ScreenConstructor;)V";

    private LegacyMenuScreensBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (hostKeepsRegisterPrivate(ClassResourceInspector.read(SCREENS))) {
            registerRedirect(transformer);
        }
    }

    static void registerRedirect(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyMenuScreens.class);
        transformer.registerMethodRedirect(SCREENS, "register", REGISTER_DESC,
                HELPER, "register", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    }

    static boolean hostKeepsRegisterPrivate(ClassNode screens) {
        if (screens == null) return false;
        for (MethodNode method : screens.methods) {
            if (method.name.equals("register") && method.desc.equals(REGISTER_DESC)) {
                return (method.access & ACC_PRIVATE) != 0;
            }
        }
        return false;
    }
}
