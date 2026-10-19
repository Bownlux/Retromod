package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

public interface CustomPacketPayload {
    Type<? extends CustomPacketPayload> type();

    final class Type<T extends CustomPacketPayload> {
        private final Identifier id;
        public Type(Identifier id) { this.id = id; }
        public Identifier id() { return id; }
        @Override public boolean equals(Object other) { return other instanceof Type<?> t && t.id.equals(id); }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public String toString() { return "Type[" + id + "]"; }
    }

    final class TypeAndCodec<B, T extends CustomPacketPayload> {
        private final Type<T> type;
        private final StreamCodec<B, T> codec;
        public TypeAndCodec(Type<T> type, StreamCodec<B, T> codec) { this.type = type; this.codec = codec; }
        public Type<T> type() { return type; }
        public StreamCodec<B, T> codec() { return codec; }
    }
}
