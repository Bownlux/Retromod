/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Balm, Farmer's Delight and Cobblemon register biome modifier serializers as Forge 1.20.1 codecs, which Forge 26.2
 * and NeoForge read as map codecs. One registers through a deferred register and one through the register event, each
 * with a data biome modifier, and a data biome keeps 1.20.1's carvers per step. The world loads only if all three are
 * read, and modifiers apply as the server starts, so the results are in the log rather than the summary.
 */
final class LegacyBiomeModifierCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String MOD_ID = "retromod_test_mod_forge";

    static final DeferredRegister<Codec<? extends BiomeModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MOD_ID);
    // Balm's serializer: a unit codec through a deferred register.
    static final RegistryObject<Codec<UnitModifier>> UNIT =
            SERIALIZERS.register("legacy_unit", () -> Codec.unit(UnitModifier.INSTANCE));
    // Farmer's Delight's record codec, registered through the event as Cobblemon does.
    static final Codec<RecordModifier> RECORD = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("marker").forGetter(RecordModifier::marker)).apply(instance, RecordModifier::new));

    private static volatile boolean unitApplied;
    private static volatile boolean recordApplied;

    private LegacyBiomeModifierCase() {}

    static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
        modBus.addListener(LegacyBiomeModifierCase::registerInEvent);
        MinecraftForge.EVENT_BUS.addListener(LegacyBiomeModifierCase::check);
    }

    private static void registerInEvent(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS,
                helper -> helper.register(new ResourceLocation(MOD_ID, "legacy_record"), RECORD));
    }

    private static void check(ServerStartedEvent event) {
        LOG.info("[Retromod-Test-Forge] A 1.20.1 biome modifier codec from a deferred register reads its data and "
                + "applies (server started): {}", unitApplied ? "success" : "fail: the modifier never applied");
        LOG.info("[Retromod-Test-Forge] A 1.20.1 biome modifier codec from the register event reads its data and "
                + "applies (server started): {}", recordApplied ? "success" : "fail: the modifier never applied");
        String readBack;
        try {
            // The mod still reads its own entry as the Codec it registered.
            Codec<UnitModifier> codec = UNIT.get();
            readBack = codec != null ? "success" : "fail: the entry is empty";
        } catch (Throwable t) {
            readBack = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] The deferred register's serializer reads back as a Codec (server started): {}",
                readBack);
    }

    /** Balm's modifier: no fields, read whole from its data's type. */
    static final class UnitModifier implements BiomeModifier {
        static final UnitModifier INSTANCE = new UnitModifier();

        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            unitApplied = true;
        }

        @Override
        public Codec<? extends BiomeModifier> codec() {
            return UNIT.get();
        }
    }

    /** Farmer's Delight's modifier: one field read inline beside the type. */
    record RecordModifier(String marker) implements BiomeModifier {
        @Override
        public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
            if ("from data".equals(marker)) recordApplied = true;
        }

        @Override
        public Codec<? extends BiomeModifier> codec() {
            return RECORD;
        }
    }
}
