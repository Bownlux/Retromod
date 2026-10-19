package io.netty.buffer;

/** A growable byte buffer with a reader and a writer index. */
public abstract class ByteBuf {
    public abstract int readableBytes();
    public abstract int readerIndex();
    public abstract ByteBuf getBytes(int index, byte[] dst);
    public abstract ByteBuf readBytes(byte[] dst);
    public abstract ByteBuf writeBytes(byte[] src);
    public abstract int readInt();
    public abstract ByteBuf writeInt(int value);
    public ByteBuf retain() { return this; }
    public boolean release() { return true; }
}
