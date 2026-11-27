/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Sends a pre-1.20.3 mod's short grass and no-break-particles names to the ones 1.20.3 gave them. */
public final class Legacy1203RenameBridge {

    static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String FIRST_HOST = "1.20.3";

    private Legacy1203RenameBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer transformer, Function<String, ClassNode> host) {
        // Unreadable, a host before 1.20.3 still has the old names.
        boolean offlineOld = RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST) < 0;
        String desc = "()L" + PROPERTIES + ";";
        ClassNode properties = host.apply(PROPERTIES);
        boolean keepsOldProperty = properties == null ? offlineOld
                : properties.methods.stream().anyMatch(m -> m.name.equals("noParticlesOnBreak") && m.desc.equals(desc));
        if (!keepsOldProperty) {
            transformer.registerMethodRedirect(PROPERTIES, "noParticlesOnBreak", desc, PROPERTIES, "noTerrainParticles", desc);
        }
        // The grass block and its item became SHORT_GRASS.
        String[][] grass = {{"net/minecraft/world/level/block/Blocks", "Lnet/minecraft/world/level/block/Block;"},
                {"net/minecraft/world/item/Items", "Lnet/minecraft/world/item/Item;"}};
        for (String[] holder : grass) {
            ClassNode fields = host.apply(holder[0]);
            boolean keepsGrass = fields == null ? offlineOld : fields.fields.stream().anyMatch(f -> f.name.equals("GRASS"));
            if (keepsGrass) continue;
            transformer.registerFieldRedirect(holder[0], "GRASS", holder[1], holder[0], "SHORT_GRASS", holder[1]);
        }
    }
}
