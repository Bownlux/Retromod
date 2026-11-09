/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 26.3 removed the buffer helpers old packets use: collections, maps, id lists, times, and hit results. */
final class LegacyBufferCase {

    private LegacyBufferCase() {}

    /** The first value that read back wrong, or success. */
    static String run() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BlockHitResult hit = new BlockHitResult(new Vec3(1.5, 2.25, 3.75), Direction.UP, new BlockPos(1, 2, 3), false);
            buf.writeCollection(List.of("a", "b"), FriendlyByteBuf::writeUtf);
            buf.writeMap(Map.of("x", 3), FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
            buf.writeIntIdList(IntList.of(7, 9));
            buf.writeInstant(Instant.ofEpochMilli(1234L));
            // Jade writes and reads its hit result through method references.
            StreamEncoder<FriendlyByteBuf, BlockHitResult> hitWriter = FriendlyByteBuf::writeBlockHitResult;
            hitWriter.encode(buf, hit);
            buf.writeCollection(List.of("p", "q", "r"), FriendlyByteBuf::writeUtf);

            List<String> names = buf.readList(FriendlyByteBuf::readUtf);
            if (!names.equals(List.of("a", "b"))) return "fail: list read back as " + names;
            Map<String, Integer> counts = buf.readMap(FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt);
            if (!counts.equals(Map.of("x", 3))) return "fail: map read back as " + counts;
            IntList ids = buf.readIntIdList();
            if (!ids.equals(IntList.of(7, 9))) return "fail: ids read back as " + ids;
            long millis = buf.readInstant().toEpochMilli();
            if (millis != 1234L) return "fail: instant read back as " + millis;
            StreamDecoder<FriendlyByteBuf, BlockHitResult> hitReader = FriendlyByteBuf::readBlockHitResult;
            BlockHitResult read = hitReader.decode(buf);
            if (!read.getBlockPos().equals(hit.getBlockPos()) || read.getDirection() != Direction.UP
                    || !read.getLocation().equals(hit.getLocation())) {
                return "fail: hit read back at " + read.getLocation() + " " + read.getDirection();
            }
            // Three names against a limit of two must fail before the list is built.
            try {
                buf.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, 2), FriendlyByteBuf::readUtf);
                return "fail: limitValue let three values through a limit of two";
            } catch (DecoderException expected) {
                return "success";
            }
        } finally {
            buf.release();
        }
    }
}
