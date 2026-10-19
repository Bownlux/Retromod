package net.minecraft.network;

import io.netty.buffer.ByteBuf;

public class FriendlyByteBuf extends ByteBuf {
    private final ByteBuf source;

    public FriendlyByteBuf(ByteBuf source) { this.source = source; }

    @Override public int readableBytes() { return source.readableBytes(); }
    @Override public int readerIndex() { return source.readerIndex(); }
    @Override public ByteBuf getBytes(int index, byte[] dst) { source.getBytes(index, dst); return this; }
    @Override public ByteBuf readBytes(byte[] dst) { source.readBytes(dst); return this; }
    @Override public ByteBuf writeBytes(byte[] src) { source.writeBytes(src); return this; }
    @Override public int readInt() { return source.readInt(); }
    @Override public ByteBuf writeInt(int value) { source.writeInt(value); return this; }
}
