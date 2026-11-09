/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L_Ender's Cataclysm registers structure processor types through a deferred register, which 26.2 turned into a
 * registry of map codecs. A data processor list names the type, so the world opens only if the registry reads it, and
 * the mod's own holder must still return its processor type.
 */
final class LegacyProcessorTypeCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");

    static final DeferredRegister<StructureProcessorType<?>> PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, "retromod_test_mod_forge");
    static final RegistryObject<StructureProcessorType<KeepProcessor>> KEEP =
            PROCESSORS.register("legacy_keep", () -> () -> KeepProcessor.CODEC);

    private LegacyProcessorTypeCase() {}

    static void register(IEventBus modBus) {
        PROCESSORS.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(LegacyProcessorTypeCase::check);
    }

    private static void check(ServerStartedEvent event) {
        String readBack;
        try {
            StructureProcessorType<KeepProcessor> type = KEEP.get();
            readBack = type != null ? "success" : "fail: the entry is empty";
        } catch (Throwable t) {
            readBack = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] A 1.20.1 structure processor type from a deferred register reads back as its type "
                + "after a processor list named it (server started): {}", readBack);
    }

    /** A processor that keeps every block, with a unit codec as most small processors have. */
    static final class KeepProcessor extends StructureProcessor {
        static final Codec<KeepProcessor> CODEC = Codec.unit(KeepProcessor::new);

        @Override
        protected StructureProcessorType<?> getType() {
            return KEEP.get();
        }
    }
}
