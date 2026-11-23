/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Sends old {@code FireBlock.setFlammable} calls to Fabric API's flammable registry, since the method is private on 26.x. */
public final class LegacyFlammablesBridge {

    static final String FIRE = "net/minecraft/world/level/block/FireBlock";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/block/LegacyFlammables";
    static final String DESC = "(Lnet/minecraft/world/level/block/Block;II)V";

    private LegacyFlammablesBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostHidesIt(host.apply(FIRE))) return;
        RebuiltApiClasses.registerTree(t, HELPER);
        t.registerMethodRedirect(FIRE, "setFlammable", DESC, HELPER, "setFlammable", "(L" + FIRE + ";" + DESC.substring(1), true);
    }

    /** Whether the host's setFlammable is private; offline, a 26.x target, which this shim only registers on, has it so. */
    static boolean hostHidesIt(ClassNode fire) {
        if (fire == null) return true;
        for (MethodNode method : fire.methods) {
            if (method.name.equals("setFlammable") && method.desc.equals(DESC)) return (method.access & Opcodes.ACC_PRIVATE) != 0;
        }
        return false;
    }
}
