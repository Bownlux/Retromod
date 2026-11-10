/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network;

import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.world.phys.BlockHitResult;

import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** The {@code FriendlyByteBuf} members 26.3 removed, rebuilt with the same bytes on the wire. */
public final class FriendlyByteBufMembers {

    // The old public key reader refused anything longer.
    private static final int MAX_PUBLIC_KEY_BYTES = 512;

    private FriendlyByteBufMembers() {}

    /** {@code writeCollection(collection, encoder)}: the count as a VarInt, then each element. */
    public static <T> void writeCollection(FriendlyByteBuf buf, Collection<T> collection,
            StreamEncoder<? super FriendlyByteBuf, T> encoder) {
        buf.writeVarInt(collection.size());
        for (T element : collection) encoder.encode(buf, element);
    }

    /** {@code readCollection(factory, decoder)}: the factory gets the count, as it did before. */
    public static <T, C extends Collection<T>> C readCollection(FriendlyByteBuf buf, IntFunction<C> factory,
            StreamDecoder<? super FriendlyByteBuf, T> decoder) {
        int count = buf.readVarInt();
        C collection = factory.apply(count);
        for (int i = 0; i < count; i++) collection.add(decoder.decode(buf));
        return collection;
    }

    /** {@code readList(decoder)}: a mutable list, since old callers add to it. */
    public static <T> List<T> readList(FriendlyByteBuf buf, StreamDecoder<? super FriendlyByteBuf, T> decoder) {
        return readCollection(buf, ArrayList::new, decoder);
    }

    /** {@code writeMap(map, keyEncoder, valueEncoder)}: the size, then each key followed by its value. */
    public static <K, V> void writeMap(FriendlyByteBuf buf, Map<K, V> map,
            StreamEncoder<? super FriendlyByteBuf, K> keyEncoder, StreamEncoder<? super FriendlyByteBuf, V> valueEncoder) {
        buf.writeVarInt(map.size());
        for (Map.Entry<K, V> entry : map.entrySet()) {
            keyEncoder.encode(buf, entry.getKey());
            valueEncoder.encode(buf, entry.getValue());
        }
    }

    /** {@code readMap(factory, keyDecoder, valueDecoder)}. */
    public static <K, V, M extends Map<K, V>> M readMap(FriendlyByteBuf buf, IntFunction<M> factory,
            StreamDecoder<? super FriendlyByteBuf, K> keyDecoder, StreamDecoder<? super FriendlyByteBuf, V> valueDecoder) {
        int count = buf.readVarInt();
        M map = factory.apply(count);
        for (int i = 0; i < count; i++) {
            // The key is read first, as it was written.
            K key = keyDecoder.decode(buf);
            map.put(key, valueDecoder.decode(buf));
        }
        return map;
    }

    /** {@code readMap(keyDecoder, valueDecoder)}: a mutable hash map. */
    public static <K, V> Map<K, V> readMap(FriendlyByteBuf buf, StreamDecoder<? super FriendlyByteBuf, K> keyDecoder,
            StreamDecoder<? super FriendlyByteBuf, V> valueDecoder) {
        return readMap(buf, HashMap::new, keyDecoder, valueDecoder);
    }

    /** {@code writeIntIdList(list)}: the count, then each id as a VarInt. */
    public static void writeIntIdList(FriendlyByteBuf buf, IntList ids) {
        buf.writeVarInt(ids.size());
        for (int i = 0; i < ids.size(); i++) buf.writeVarInt(ids.getInt(i));
    }

    /** {@code readIntIdList()}. */
    public static IntList readIntIdList(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        IntList ids = new IntArrayList();
        for (int i = 0; i < count; i++) ids.add(buf.readVarInt());
        return ids;
    }

    /** {@code writeInstant(instant)}: epoch milliseconds as a long. */
    public static void writeInstant(FriendlyByteBuf buf, Instant instant) {
        buf.writeLong(instant.toEpochMilli());
    }

    /** {@code readInstant()}. */
    public static Instant readInstant(FriendlyByteBuf buf) {
        return Instant.ofEpochMilli(buf.readLong());
    }

    /** {@code writePublicKey(key)}: the encoded key as a byte array. Returns the buffer for chained calls. */
    public static FriendlyByteBuf writePublicKey(FriendlyByteBuf buf, PublicKey key) {
        buf.writeByteArray(key.getEncoded());
        return buf;
    }

    /** {@code readPublicKey()}. A key that does not parse fails the packet, as before. */
    public static PublicKey readPublicKey(FriendlyByteBuf buf) {
        try {
            return Crypt.byteToPublicKey(buf.readByteArray(MAX_PUBLIC_KEY_BYTES));
        } catch (CryptException e) {
            throw new DecoderException("Malformed public key bytes", e);
        }
    }

    /** {@code writeBlockHitResult(hit)}, through the codec 26.3 gave the hit result itself. */
    public static void writeBlockHitResult(FriendlyByteBuf buf, BlockHitResult hit) {
        BlockHitResult.STREAM_CODEC.encode(buf, hit);
    }

    /** {@code readBlockHitResult()}. */
    public static BlockHitResult readBlockHitResult(FriendlyByteBuf buf) {
        return BlockHitResult.STREAM_CODEC.decode(buf);
    }

    /** {@code FriendlyByteBuf.limitValue(factory, limit)}: refuses a count above the limit before allocating. */
    public static <T> IntFunction<T> limitValue(IntFunction<T> factory, int limit) {
        return count -> {
            if (count > limit) throw new DecoderException("Value " + count + " is larger than limit " + limit);
            return factory.apply(count);
        };
    }
}
