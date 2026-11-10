/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.function.BiConsumer;
import java.util.function.Function;

/** The buffer's id members, which 1.21.11 renamed, by call and by method reference, and Formatting.byName, which 26.2 removed. */
public final class Test63IdentifierBufferAndFormatting implements Test {

    @Override
    public String description() {
        return "PacketByteBuf.writeIdentifier and readIdentifier, by call and reference, and Formatting.byName";
    }

    @Override
    public TestResult run() {
        Identifier id = new Identifier("retromod_test", "buffer_id");
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        // Balm's stream codecs hold these as method references.
        BiConsumer<PacketByteBuf, Identifier> write = PacketByteBuf::writeIdentifier;
        Function<PacketByteBuf, Identifier> read = PacketByteBuf::readIdentifier;
        write.accept(buf, id);
        buf.writeIdentifier(id);
        Identifier first = read.apply(buf);
        Identifier second = buf.readIdentifier();
        if (!id.equals(first) || !id.equals(second)) {
            return TestResult.fail("the ids read back as " + first + " and " + second);
        }
        Formatting gold = Formatting.byName("gold");
        if (gold != Formatting.GOLD || Formatting.byName("teal") != null) {
            return TestResult.fail("Formatting.byName(\"gold\") gave " + gold);
        }
        return TestResult.success();
    }
}
