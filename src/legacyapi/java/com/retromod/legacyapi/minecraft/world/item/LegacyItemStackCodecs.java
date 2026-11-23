/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import java.util.Optional;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

/** An old mod's item stack codecs, which read a stack before item components are bound, as recipes load, as a stack that waits for them. */
public final class LegacyItemStackCodecs {

    private static volatile Codec<ItemStack> codec;
    private static volatile Codec<ItemStack> strict;
    private static volatile Codec<ItemStack> optional;
    private static volatile Codec<ItemStack> simple;
    private static volatile Codec<ItemStackTemplate> template;

    private LegacyItemStackCodecs() {}

    /** Old {@code ItemStack.CODEC}, and {@code SINGLE_ITEM_CODEC}, whose count was always one. */
    public static Codec<ItemStack> codec() {
        if (codec == null) codec = Codec.of(ItemStack.CODEC, new StackDecoder(false));
        return codec;
    }

    /** Old {@code ItemStack.STRICT_CODEC}: a stack read once components are bound must also pass the host's strict check. */
    public static Codec<ItemStack> strict() {
        if (strict == null) strict = Codec.of(ItemStack.CODEC, new StackDecoder(true));
        return strict;
    }

    /** Old {@code ItemStack.OPTIONAL_CODEC}: an empty map or a missing stack reads as the empty stack. */
    public static Codec<ItemStack> optional() {
        if (optional == null) {
            optional = ExtraCodecs.optionalEmptyMap(codec()).xmap(
                    stack -> stack.orElse(ItemStack.EMPTY),
                    stack -> stack.isEmpty() ? Optional.empty() : Optional.of(stack));
        }
        return optional;
    }

    /** Old {@code ItemStack.SIMPLE_ITEM_CODEC}: a stack of one, written as its item id. */
    public static Codec<ItemStack> simple() {
        if (simple == null) simple = Item.CODEC.xmap(EarlyItemStacks::of, ItemStack::typeHolder);
        return simple;
    }

    /** {@code template.create()}, which waits for the item's components when they are not bound yet. */
    public static ItemStack create(ItemStackTemplate template) {
        if (template.item().areComponentsBound()) return template.create();
        return EarlyItemStacks.of(template.item(), template.count(), template.components());
    }

    // Every item's components are bound in one pass, so air answers for all of them. A client unbinds between worlds.
    private static boolean componentsBound() {
        return Items.AIR.builtInRegistryHolder().areComponentsBound();
    }

    private static Codec<ItemStackTemplate> templateCodec() {
        if (template == null) template = ItemStackTemplate.MAP_CODEC.codec();
        return template;
    }

    /** Reads with the host's codec once components are bound, and as a waiting stack before. */
    private static final class StackDecoder implements Decoder<ItemStack> {
        private final boolean strict;

        StackDecoder(boolean strict) {
            this.strict = strict;
        }

        @Override
        public <T> DataResult<Pair<ItemStack, T>> decode(DynamicOps<T> ops, T input) {
            if (componentsBound()) return host(ops, input);
            // The template reads the same id, count and components without asking for bound components.
            Pair<ItemStackTemplate, T> read;
            try {
                read = templateCodec().decode(ops, input).result().orElse(null);
            } catch (RuntimeException emptyOrMalformed) {
                read = null;
            }
            // The host's codec reports anything the template cannot read.
            if (read == null) return host(ops, input);
            // A waiting stack has no stack size yet, so the strict check cannot run on it.
            try {
                return DataResult.success(Pair.of(create(read.getFirst()), read.getSecond()));
            } catch (RuntimeException failure) {
                return DataResult.error(() -> "Could not read item stack " + input + " before item components were bound: " + failure);
            }
        }

        private <T> DataResult<Pair<ItemStack, T>> host(DynamicOps<T> ops, T input) {
            DataResult<Pair<ItemStack, T>> read = ItemStack.CODEC.decode(ops, input);
            if (!strict) return read;
            return read.flatMap(pair -> ItemStack.validateStrict(pair.getFirst()).map(stack -> Pair.of(stack, pair.getSecond())));
        }
    }
}
