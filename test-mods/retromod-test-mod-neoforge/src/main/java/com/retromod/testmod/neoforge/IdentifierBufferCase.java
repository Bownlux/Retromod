/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import io.netty.buffer.Unpooled;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** The buffer's id members, which 1.21.11 renamed after Identifier, and ChatFormatting.getByName, which 26.2 removed. */
final class IdentifierBufferCase {

    private IdentifierBufferCase() {}

    /** Writes and reads an id by call and by method reference, as Balm's stream codecs do, also through RegistryFriendlyByteBuf. */
    static boolean bufferRoundTrip() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("retromod_test_mod_neoforge", "buffer_id");
        BiConsumer<FriendlyByteBuf, ResourceLocation> write = FriendlyByteBuf::writeResourceLocation;
        Function<FriendlyByteBuf, ResourceLocation> read = FriendlyByteBuf::readResourceLocation;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        write.accept(buf, id);
        buf.writeResourceLocation(id);
        boolean plain = id.equals(read.apply(buf)) && id.equals(buf.readResourceLocation());
        RegistryFriendlyByteBuf registryBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
        registryBuf.writeResourceLocation(id);
        return plain && id.equals(registryBuf.readResourceLocation());
    }

    static boolean formattingByName() {
        return ChatFormatting.getByName("gold") == ChatFormatting.GOLD && ChatFormatting.getByName("teal") == null;
    }
}
