package io.netty.buffer;

import java.util.Arrays;

final class HeapByteBuf extends ByteBuf {
    private byte[] data;
    private int reader;
    private int writer;

    HeapByteBuf(byte[] data, int writer) { this.data = data; this.writer = writer; }

    @Override public int readableBytes() { return writer - reader; }
    @Override public int readerIndex() { return reader; }
    @Override public ByteBuf getBytes(int index, byte[] dst) {
        System.arraycopy(data, index, dst, 0, dst.length);
        return this;
    }
    @Override public ByteBuf readBytes(byte[] dst) {
        if (dst.length > readableBytes()) throw new IndexOutOfBoundsException("read past the end");
        System.arraycopy(data, reader, dst, 0, dst.length);
        reader += dst.length;
        return this;
    }
    @Override public ByteBuf writeBytes(byte[] src) {
        ensure(src.length);
        System.arraycopy(src, 0, data, writer, src.length);
        writer += src.length;
        return this;
    }
    @Override public int readInt() {
        byte[] four = new byte[4];
        readBytes(four);
        return ((four[0] & 0xff) << 24) | ((four[1] & 0xff) << 16) | ((four[2] & 0xff) << 8) | (four[3] & 0xff);
    }
    @Override public ByteBuf writeInt(int value) {
        return writeBytes(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }
    private void ensure(int extra) {
        if (writer + extra > data.length) data = Arrays.copyOf(data, Math.max(16, (writer + extra) * 2));
    }
}
