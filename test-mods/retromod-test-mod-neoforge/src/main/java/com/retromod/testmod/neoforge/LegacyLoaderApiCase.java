/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ColorArgument;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** APIs NeoForge and Minecraft removed after 1.21.1, used as Tom's Storage, Open Parties and Claims and JourneyMap use them. */
final class LegacyLoaderApiCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");
    private static final String PREFIX = "[Retromod-Test-NeoForge]";

    /** A payload with no content, handled on whichever side receives it. */
    record Ping() implements CustomPacketPayload {
        static final Type<Ping> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("retromod_test_mod_neoforge", "ping"));
        static final StreamCodec<RegistryFriendlyByteBuf, Ping> CODEC = StreamCodec.unit(new Ping());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private LegacyLoaderApiCase() {}

    static void register(IEventBus modBus) {
        // Tom's Storage registers its two-way payloads with a DirectionalPayloadHandler, which NeoForge 1.21.7 removed.
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            event.registrar("1").playBidirectional(Ping.TYPE, Ping.CODEC,
                    new DirectionalPayloadHandler<>((ping, context) -> { }, (ping, context) -> { }));
            LOG.info("{} a two-way payload registered with DirectionalPayloadHandler: success", PREFIX);
        });
        // Open Parties and Claims cancels chorus fruit teleports; NeoForge 1.21.2 posts ItemConsumption instead.
        NeoForge.EVENT_BUS.addListener(EntityTeleportEvent.ChorusFruit.class, event -> { });
        // JourneyMap's waypoint command takes a color argument, which 26.2 replaced with TeamColorArgument.
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {
            event.getDispatcher().register(Commands.literal("retromodcolor")
                    .then(Commands.argument("color", ColorArgument.color()).executes(context -> {
                        ChatFormatting color = ColorArgument.getColor(context, "color");
                        return color.ordinal();
                    })));
            LOG.info("{} a command with a 1.21.1 ColorArgument registered: success", PREFIX);
        });
    }
}
