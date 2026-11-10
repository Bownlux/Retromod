/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The buffer's collection, map, id list, time, and hit result helpers, which 26.3 removed. */
public final class Test57LegacyBufferHelpers implements Test {

    @Override
    public String description() {
        return "PacketByteBuf collection, map, id list, time, and hit result helpers";
    }

    @Override
    public TestResult run() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            BlockHitResult hit = new BlockHitResult(new Vec3d(1.5, 2.25, 3.75), Direction.UP, new BlockPos(1, 2, 3), false);
            // The 1.20.1 forms take the buffer's own reader and writer views.
            buf.writeCollection(List.of("a", "b"), PacketByteBuf::writeString);
            buf.writeMap(Map.of("x", 3), PacketByteBuf::writeString, PacketByteBuf::writeVarInt);
            buf.writeIntList(IntList.of(7, 9));
            buf.writeInstant(Instant.ofEpochMilli(1234L));
            buf.writeBlockHitResult(hit);
            buf.writeCollection(List.of("p", "q", "r"), PacketByteBuf::writeString);

            List<String> names = buf.readList(PacketByteBuf::readString);
            if (!names.equals(List.of("a", "b"))) return TestResult.fail("list read back as " + names);
            Map<String, Integer> counts = buf.readMap(PacketByteBuf::readString, PacketByteBuf::readVarInt);
            if (!counts.equals(Map.of("x", 3))) return TestResult.fail("map read back as " + counts);
            IntList ids = buf.readIntList();
            if (!ids.equals(IntList.of(7, 9))) return TestResult.fail("ids read back as " + ids);
            long millis = buf.readInstant().toEpochMilli();
            if (millis != 1234L) return TestResult.fail("instant read back as " + millis);
            BlockHitResult read = buf.readBlockHitResult();
            if (!read.getBlockPos().equals(hit.getBlockPos()) || read.getSide() != Direction.UP
                    || !read.getPos().equals(hit.getPos())) {
                return TestResult.fail("hit read back at " + read.getPos() + " " + read.getSide());
            }
            // Three names against a limit of two must fail before the list is built.
            try {
                buf.readCollection(PacketByteBuf.getMaxValidator(ArrayList::new, 2), PacketByteBuf::readString);
                return TestResult.fail("the size limit let three values through a limit of two");
            } catch (DecoderException expected) {
                return TestResult.success();
            }
        } finally {
            buf.release();
        }
    }
}
