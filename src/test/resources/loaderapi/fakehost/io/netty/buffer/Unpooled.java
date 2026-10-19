package io.netty.buffer;

public final class Unpooled {
    private Unpooled() {}
    public static ByteBuf wrappedBuffer(byte[] data) { return new HeapByteBuf(data, data.length); }
    public static ByteBuf buffer() { return new HeapByteBuf(new byte[16], 0); }
}
