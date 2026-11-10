/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** Forge 1.20.1's and NeoForge 1.21.1's set of chunk render types, kept as a set of Minecraft 26.x's three block layers. */
public class ChunkRenderTypeSet implements Iterable<RenderType> {

    // One bit per layer, in ChunkSectionLayer order. RetroItemBlockRenderTypes records old setRenderLayer calls
    // with the same bits.
    public static final int SOLID_BIT = 1;
    public static final int CUTOUT_BIT = 2;
    public static final int TRANSLUCENT_BIT = 4;
    private static final int ALL_BITS = SOLID_BIT | CUTOUT_BIT | TRANSLUCENT_BIT;

    // Plain instances: an old static initializer can build a set before render types may load.
    private static final ChunkRenderTypeSet NONE = new ChunkRenderTypeSet(0);
    private static final ChunkRenderTypeSet ALL = new ChunkRenderTypeSet(ALL_BITS);

    // The render type that stands for each layer, read on first use, never while this class loads.
    private static volatile RenderType[] tokens;

    private final int bits;

    private ChunkRenderTypeSet(int bits) {
        this.bits = bits & ALL_BITS;
    }

    public static ChunkRenderTypeSet none() {
        return NONE;
    }

    public static ChunkRenderTypeSet all() {
        return ALL;
    }

    public static ChunkRenderTypeSet of(RenderType... renderTypes) {
        return of(Arrays.asList(renderTypes));
    }

    /** The layers of these render types; a render type that is no block layer is refused, as Forge 1.20.1 refused it. */
    public static ChunkRenderTypeSet of(Collection<RenderType> renderTypes) {
        int bits = 0;
        for (RenderType renderType : renderTypes) {
            int bit = bitOf(renderType);
            if (bit == 0) {
                throw new IllegalArgumentException("The render type " + renderType + " is not a block layer. Use "
                        + "RenderType.solid(), cutout(), cutoutMipped(), translucent() or tripwire().");
            }
            bits |= bit;
        }
        return ofBits(bits);
    }

    public static ChunkRenderTypeSet union(ChunkRenderTypeSet... sets) {
        return union(Arrays.asList(sets));
    }

    public static ChunkRenderTypeSet union(Collection<ChunkRenderTypeSet> sets) {
        return union((Iterable<ChunkRenderTypeSet>) sets);
    }

    public static ChunkRenderTypeSet union(Iterable<ChunkRenderTypeSet> sets) {
        int bits = 0;
        for (ChunkRenderTypeSet set : sets) bits |= set.bits;
        return ofBits(bits);
    }

    public static ChunkRenderTypeSet intersection(ChunkRenderTypeSet... sets) {
        return intersection(Arrays.asList(sets));
    }

    /** The layers every set has; no sets at all is every layer, as before. */
    public static ChunkRenderTypeSet intersection(Collection<ChunkRenderTypeSet> sets) {
        return intersection((Iterable<ChunkRenderTypeSet>) sets);
    }

    public static ChunkRenderTypeSet intersection(Iterable<ChunkRenderTypeSet> sets) {
        int bits = ALL_BITS;
        for (ChunkRenderTypeSet set : sets) bits &= set.bits;
        return ofBits(bits);
    }

    /** The set with these layer bits: SOLID_BIT, CUTOUT_BIT and TRANSLUCENT_BIT. */
    public static ChunkRenderTypeSet ofBits(int bits) {
        int kept = bits & ALL_BITS;
        if (kept == 0) return NONE;
        return kept == ALL_BITS ? ALL : new ChunkRenderTypeSet(kept);
    }

    /** The set of these Minecraft 26.2 layers. */
    public static ChunkRenderTypeSet ofLayers(ChunkSectionLayer... layers) {
        int bits = 0;
        for (ChunkSectionLayer layer : layers) bits |= bitOf(layer);
        return ofBits(bits);
    }

    /** The layers whose render type passes the test, as Forge 1.20.1 built a set from a predicate. */
    public static ChunkRenderTypeSet matching(Predicate<RenderType> test) {
        RenderType[] types = tokens();
        int bits = 0;
        for (int i = 0; i < types.length; i++) {
            if (test.test(types[i])) bits |= 1 << i;
        }
        return ofBits(bits);
    }

    public boolean isEmpty() {
        return bits == 0;
    }

    public boolean contains(RenderType renderType) {
        int bit = bitOf(renderType);
        return bit != 0 && (bits & bit) != 0;
    }

    public boolean contains(ChunkSectionLayer layer) {
        int bit = bitOf(layer);
        return bit != 0 && (bits & bit) != 0;
    }

    /** The render types in layer order: solid, cutout, translucent. */
    @Override
    public Iterator<RenderType> iterator() {
        return asList().iterator();
    }

    public List<RenderType> asList() {
        RenderType[] types = tokens();
        List<RenderType> list = new ArrayList<>(3);
        for (int i = 0; i < types.length; i++) {
            if ((bits & (1 << i)) != 0) list.add(types[i]);
        }
        return List.copyOf(list);
    }

    /** The Minecraft 26.2 layers in this set, in their own order. */
    public List<ChunkSectionLayer> layers() {
        List<ChunkSectionLayer> layers = new ArrayList<>(3);
        if ((bits & SOLID_BIT) != 0) layers.add(ChunkSectionLayer.SOLID);
        if ((bits & CUTOUT_BIT) != 0) layers.add(ChunkSectionLayer.CUTOUT);
        if ((bits & TRANSLUCENT_BIT) != 0) layers.add(ChunkSectionLayer.TRANSLUCENT);
        return List.copyOf(layers);
    }

    /** The layer bits of this set. */
    public int bits() {
        return bits;
    }

    /** The layer a block render type stands for, or null for any other render type. */
    public static ChunkSectionLayer layerOf(RenderType renderType) {
        switch (bitOf(renderType)) {
            case SOLID_BIT:
                return ChunkSectionLayer.SOLID;
            case CUTOUT_BIT:
                return ChunkSectionLayer.CUTOUT;
            case TRANSLUCENT_BIT:
                return ChunkSectionLayer.TRANSLUCENT;
            default:
                return null;
        }
    }

    /** The render type old code uses for a layer: Minecraft 26.2's moving-block type of that layer. */
    public static RenderType renderTypeOf(ChunkSectionLayer layer) {
        int bit = bitOf(layer);
        return bit == 0 ? null : tokens()[Integer.numberOfTrailingZeros(bit)];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChunkRenderTypeSet set && set.bits == bits;
    }

    @Override
    public int hashCode() {
        return bits;
    }

    @Override
    public String toString() {
        return "ChunkRenderTypeSet" + layers();
    }

    private static int bitOf(RenderType renderType) {
        if (renderType == null) return 0;
        // Old RenderType.solid(), cutout() and the rest already come back as these three types.
        RenderType[] types = tokens();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == renderType) return 1 << i;
        }
        return 0;
    }

    private static int bitOf(ChunkSectionLayer layer) {
        if (layer == ChunkSectionLayer.SOLID) return SOLID_BIT;
        if (layer == ChunkSectionLayer.CUTOUT) return CUTOUT_BIT;
        return layer == ChunkSectionLayer.TRANSLUCENT ? TRANSLUCENT_BIT : 0;
    }

    private static RenderType[] tokens() {
        RenderType[] types = tokens;
        if (types == null) {
            types = new RenderType[]{RenderTypes.solidMovingBlock(), RenderTypes.cutoutMovingBlock(),
                    RenderTypes.translucentMovingBlock()};
            tokens = types;
        }
        return types;
    }
}
