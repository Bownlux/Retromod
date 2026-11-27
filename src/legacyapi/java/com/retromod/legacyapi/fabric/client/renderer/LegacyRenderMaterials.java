/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.renderer;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/** Old render materials as packed property bits, and how 26.x quads take them on. */
public final class LegacyRenderMaterials {

    // Bit layout shared by every mod's copy, which reads another copy's material through IntSupplier.
    static final int BLEND_MASK = 0x7;
    static final int NO_COLOR_INDEX = 1 << 3;
    static final int EMISSIVE = 1 << 4;
    static final int NO_DIFFUSE = 1 << 5;
    static final int AO_SHIFT = 6;
    static final int GLINT_SHIFT = 8;
    static final int VANILLA_SHADE = 1 << 10;
    /** The standard material: default blend mode, ambient occlusion and glint. */
    public static final int STANDARD_BITS = TriState.DEFAULT.ordinal() << AO_SHIFT | TriState.DEFAULT.ordinal() << GLINT_SHIFT;

    // Every value made up front, so two threads never make two instances of one value for mods that compare with ==.
    private static final Material[] BY_BITS = new Material[1 << 11];
    private static final LegacyBlendMode[] BLEND_MODES = LegacyBlendMode.values();
    private static final TriState[] TRI_STATES = TriState.values();
    private static final String REGISTRY = "fabric.renderer.materials";
    private static final int PROTOCOL = 1;

    static {
        for (int bits = 0; bits < BY_BITS.length; bits++) BY_BITS[bits] = new Material(bits);
    }

    private LegacyRenderMaterials() {}

    /** The standard material of this copy. */
    public static LegacyRenderMaterial standard() {
        return of(STANDARD_BITS);
    }

    /** The material for {@code bits}, one instance per value. */
    public static LegacyRenderMaterial of(int bits) {
        return BY_BITS[bits & (BY_BITS.length - 1)];
    }

    /** A material's bits, read from any mod's copy; null and unknown objects read as the standard material. */
    public static int bits(Object material) {
        return material instanceof IntSupplier packed ? packed.getAsInt() : STANDARD_BITS;
    }

    /** {@code materialById(id)}: what {@link #register} stored, shared by every mod, the standard one by default. */
    public static LegacyRenderMaterial byId(Identifier id) {
        Object stored = id == null ? null : registry().get(id.toString());
        if (stored != null) return of(bits(stored));
        return LegacyRenderMaterial.MATERIAL_STANDARD.equals(id) ? standard() : null;
    }

    /** {@code registerMaterial(id, material)}: false when the id is taken, as the old renderer answered. */
    public static boolean register(Identifier id, Object material) {
        if (id == null || material == null) return false;
        return registry().putIfAbsent(id.toString(), Integer.valueOf(bits(material))) == null;
    }

    /** Sets the quad properties an old material carried. */
    public static void apply(MutableQuadView quad, Object material) {
        int bits = bits(material);
        ChunkSectionLayer layer = blendMode(bits).layer();
        if (layer != null) quad.chunkLayer(layer);
        quad.emissive((bits & EMISSIVE) != 0);
        quad.ambientOcclusion(triState(bits >> AO_SHIFT));
        quad.shadeMode((bits & VANILLA_SHADE) != 0 ? ShadeMode.VANILLA : ShadeMode.ENHANCED);
        if ((bits & NO_COLOR_INDEX) != 0) quad.tintIndex(-1);
        if ((bits & NO_DIFFUSE) != 0) noDiffuse(quad);
    }

    static LegacyBlendMode blendMode(int bits) {
        return BLEND_MODES[Math.min(bits & BLEND_MASK, BLEND_MODES.length - 1)];
    }

    static TriState triState(int twoBits) {
        return TRI_STATES[Math.min(twoBits & 3, TRI_STATES.length - 1)];
    }

    /** The material a quad's properties describe, for the old {@code material()} getter. */
    public static LegacyRenderMaterial read(QuadView quad) {
        int bits = STANDARD_BITS & ~(3 << AO_SHIFT);
        ChunkSectionLayer layer = quad.chunkLayer();
        if (layer == ChunkSectionLayer.SOLID) bits |= LegacyBlendMode.SOLID.ordinal();
        else if (layer == ChunkSectionLayer.CUTOUT) bits |= LegacyBlendMode.CUTOUT.ordinal();
        else if (layer == ChunkSectionLayer.TRANSLUCENT) bits |= LegacyBlendMode.TRANSLUCENT.ordinal();
        if (quad.emissive()) bits |= EMISSIVE;
        TriState ao = quad.ambientOcclusion();
        bits |= (ao == null ? TriState.DEFAULT : ao).ordinal() << AO_SHIFT;
        if (quad.shadeMode() == ShadeMode.VANILLA) bits |= VANILLA_SHADE;
        return of(bits);
    }

    // 26.1 and 26.2 turn diffuse shading off directly; 26.3 shades every face as an upward one, which is unshaded. Each
    // host lacks the other's member, so both are found at runtime and no translated jar names a missing one.
    private static void noDiffuse(MutableQuadView quad) {
        try {
            if (DIFFUSE_SHADE != null) DIFFUSE_SHADE.invoke(quad, false);
            else if (SHADE_DIRECTION != null) SHADE_DIRECTION.invoke(quad, Direction.UP);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static final MethodHandle DIFFUSE_SHADE = quadSetter("diffuseShade", boolean.class);
    private static final MethodHandle SHADE_DIRECTION = quadSetter("shadeDirectionOverride", Direction.class);

    private static MethodHandle quadSetter(String name, Class<?> argument) {
        try {
            return MethodHandles.publicLookup().findVirtual(MutableQuadView.class, name, MethodType.methodType(MutableQuadView.class, argument));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> registry() {
        return LegacySharedSlots.get(REGISTRY, PROTOCOL, Map.class, ConcurrentHashMap::new);
    }

    /** One material value; its bits are the whole of it. */
    static final class Material implements LegacyRenderMaterial, IntSupplier {
        private final int bits;

        Material(int bits) {
            this.bits = bits;
        }

        @Override
        public int getAsInt() {
            return bits;
        }

        @Override
        public LegacyBlendMode blendMode() {
            return LegacyRenderMaterials.blendMode(bits);
        }

        @Override
        public boolean disableColorIndex() {
            return (bits & NO_COLOR_INDEX) != 0;
        }

        @Override
        public boolean emissive() {
            return (bits & EMISSIVE) != 0;
        }

        @Override
        public boolean disableDiffuse() {
            return (bits & NO_DIFFUSE) != 0;
        }

        @Override
        public TriState ambientOcclusion() {
            return triState(bits >> AO_SHIFT);
        }

        @Override
        public TriState glint() {
            return triState(bits >> GLINT_SHIFT);
        }

        @Override
        public LegacyShadeMode shadeMode() {
            return (bits & VANILLA_SHADE) != 0 ? LegacyShadeMode.VANILLA : LegacyShadeMode.ENHANCED;
        }
    }

    /** {@code materialFinder()}: builds a material property by property. */
    public static final class Finder implements LegacyMaterialFinder, IntSupplier {
        private int bits = STANDARD_BITS;

        @Override
        public int getAsInt() {
            return bits;
        }

        @Override
        public LegacyMaterialFinder blendMode(LegacyBlendMode blendMode) {
            bits = bits & ~BLEND_MASK | (blendMode == null ? 0 : blendMode.ordinal());
            return this;
        }

        @Override
        public LegacyMaterialFinder disableColorIndex(boolean disable) {
            return flag(NO_COLOR_INDEX, disable);
        }

        @Override
        public LegacyMaterialFinder emissive(boolean isEmissive) {
            return flag(EMISSIVE, isEmissive);
        }

        @Override
        public LegacyMaterialFinder disableDiffuse(boolean disable) {
            return flag(NO_DIFFUSE, disable);
        }

        @Override
        public LegacyMaterialFinder ambientOcclusion(TriState mode) {
            bits = bits & ~(3 << AO_SHIFT) | (mode == null ? TriState.DEFAULT : mode).ordinal() << AO_SHIFT;
            return this;
        }

        @Override
        public LegacyMaterialFinder glint(TriState mode) {
            bits = bits & ~(3 << GLINT_SHIFT) | (mode == null ? TriState.DEFAULT : mode).ordinal() << GLINT_SHIFT;
            return this;
        }

        @Override
        public LegacyMaterialFinder shadeMode(LegacyShadeMode mode) {
            return flag(VANILLA_SHADE, mode == LegacyShadeMode.VANILLA);
        }

        @Override
        public LegacyMaterialFinder copyFrom(LegacyMaterialView material) {
            bits = LegacyRenderMaterials.bits(material);
            return this;
        }

        @Override
        public LegacyMaterialFinder clear() {
            bits = STANDARD_BITS;
            return this;
        }

        @Override
        public LegacyRenderMaterial find() {
            return of(bits);
        }

        @Override
        public LegacyBlendMode blendMode() {
            return of(bits).blendMode();
        }

        @Override
        public boolean disableColorIndex() {
            return (bits & NO_COLOR_INDEX) != 0;
        }

        @Override
        public boolean emissive() {
            return (bits & EMISSIVE) != 0;
        }

        @Override
        public boolean disableDiffuse() {
            return (bits & NO_DIFFUSE) != 0;
        }

        @Override
        public TriState ambientOcclusion() {
            return of(bits).ambientOcclusion();
        }

        @Override
        public TriState glint() {
            return of(bits).glint();
        }

        @Override
        public LegacyShadeMode shadeMode() {
            return of(bits).shadeMode();
        }

        private LegacyMaterialFinder flag(int flag, boolean on) {
            bits = on ? bits | flag : bits & ~flag;
            return this;
        }
    }
}
