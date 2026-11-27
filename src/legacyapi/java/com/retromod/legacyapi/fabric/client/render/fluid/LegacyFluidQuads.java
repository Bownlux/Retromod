/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.render.fluid;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Holds a fluid draw's vertices and passes on only its whole quads once the draw is done, so a failed draw leaves the builder clean. */
final class LegacyFluidQuads implements VertexConsumer {
    private static final int QUAD = 4;
    // Which parts of a vertex were set; a part never set is not passed on.
    private static final int COLOR = 1, ARGB = 2, UV = 4, UV1 = 8, UV2 = 16, UV3 = 32, NORMAL = 64, LINE = 128;
    // Per vertex: x, y, z, u, v, u3, v3, normal x, y, z, line width; and parts, red or argb, green, blue, alpha, uv1, uv2.
    private static final int FLOATS = 11, INTS = 9;

    private final VertexConsumer out;
    private final TextureAtlasSprite[] from;
    private final TextureAtlasSprite[] to;
    private float[] floats = new float[16 * FLOATS];
    private int[] ints = new int[16 * INTS];
    private int count;

    /** Collects vertices for {@code out}; with sprites given, texture coordinates move from {@code from} onto {@code to}. */
    LegacyFluidQuads(VertexConsumer out, TextureAtlasSprite[] from, TextureAtlasSprite[] to) {
        this.out = out;
        this.from = from;
        this.to = to;
    }

    /** Passes on every whole quad collected so far and drops a quad left unfinished; true when one was dropped. */
    boolean commit() {
        int whole = count - count % QUAD;
        for (int quad = 0; quad < whole; quad += QUAD) {
            int sprite = from == null ? -1 : spriteOf(quad);
            for (int vertex = quad; vertex < quad + QUAD; vertex++) send(vertex, sprite);
        }
        boolean dropped = whole != count;
        count = 0;
        return dropped;
    }

    /** Drops everything collected, for a draw whose code failed. */
    void discard() {
        count = 0;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (count * FLOATS == floats.length) {
            floats = Arrays.copyOf(floats, floats.length * 2);
            ints = Arrays.copyOf(ints, ints.length * 2);
        }
        int f = count * FLOATS;
        floats[f] = x;
        floats[f + 1] = y;
        floats[f + 2] = z;
        ints[count * INTS] = 0;
        count++;
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        int i = part(COLOR, ARGB);
        if (i < 0) return this;
        ints[i + 1] = red;
        ints[i + 2] = green;
        ints[i + 3] = blue;
        ints[i + 4] = alpha;
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        int i = part(ARGB, COLOR);
        if (i >= 0) ints[i + 1] = argb;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        return twoFloats(UV, 3, u, v);
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return twoInts(UV1, 5, u, v);
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return twoInts(UV2, 7, u, v);
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return twoFloats(UV3, 5, u, v);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        if (part(NORMAL, 0) < 0) return this;
        int f = (count - 1) * FLOATS;
        floats[f + 7] = x;
        floats[f + 8] = y;
        floats[f + 9] = z;
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        if (part(LINE, 0) >= 0) floats[(count - 1) * FLOATS + 10] = width;
        return this;
    }

    // Marks a part set on the current vertex, and clears one it replaces; -1 when no vertex was started.
    private int part(int set, int replaced) {
        if (count == 0) return -1;
        int i = (count - 1) * INTS;
        ints[i] = ints[i] & ~replaced | set;
        return i;
    }

    private VertexConsumer twoFloats(int set, int slot, float a, float b) {
        if (part(set, 0) < 0) return this;
        int f = (count - 1) * FLOATS;
        floats[f + slot] = a;
        floats[f + slot + 1] = b;
        return this;
    }

    private VertexConsumer twoInts(int set, int slot, int a, int b) {
        int i = part(set, 0);
        if (i < 0) return this;
        ints[i + slot] = a;
        ints[i + slot + 1] = b;
        return this;
    }

    // ---- Output -------------------------------------------------------------------------------------------------

    private void send(int vertex, int sprite) {
        int f = vertex * FLOATS;
        int i = vertex * INTS;
        int parts = ints[i];
        out.addVertex(floats[f], floats[f + 1], floats[f + 2]);
        if ((parts & COLOR) != 0) out.setColor(ints[i + 1], ints[i + 2], ints[i + 3], ints[i + 4]);
        if ((parts & ARGB) != 0) out.setColor(ints[i + 1]);
        if ((parts & UV) != 0) sendUv(floats[f + 3], floats[f + 4], sprite);
        if ((parts & UV1) != 0) out.setUv1(ints[i + 5], ints[i + 6]);
        if ((parts & UV2) != 0) out.setUv2(ints[i + 7], ints[i + 8]);
        if ((parts & UV3) != 0) setUv3(out, floats[f + 5], floats[f + 6]);
        if ((parts & NORMAL) != 0) out.setNormal(floats[f + 7], floats[f + 8], floats[f + 9]);
        if ((parts & LINE) != 0) out.setLineWidth(floats[f + 10]);
    }

    // The same spot of the own sprite that matches the stand-in sprite the quad was drawn with.
    private void sendUv(float u, float v, int sprite) {
        if (sprite < 0) {
            out.setUv(u, v);
            return;
        }
        TextureAtlasSprite source = from[sprite];
        TextureAtlasSprite target = to[sprite];
        float x = (u - source.getU0()) / (source.getU1() - source.getU0());
        float y = (v - source.getV0()) / (source.getV1() - source.getV0());
        out.setUv(target.getU0() + (target.getU1() - target.getU0()) * x, target.getV0() + (target.getV1() - target.getV0()) * y);
    }

    // One stand-in sprite per quad, the one holding the quad's center, so a corner on an edge two sprites share is not split.
    private int spriteOf(int quad) {
        float u = 0, v = 0;
        int withUv = 0;
        for (int vertex = quad; vertex < quad + QUAD; vertex++) {
            if ((ints[vertex * INTS] & UV) == 0) continue;
            u += floats[vertex * FLOATS + 3];
            v += floats[vertex * FLOATS + 4];
            withUv++;
        }
        if (withUv == 0) return -1;
        u /= withUv;
        v /= withUv;
        for (int i = 0; i < from.length && i < to.length; i++) {
            TextureAtlasSprite source = from[i];
            if (source == null || to[i] == null) continue;
            if (u >= source.getU0() && u <= source.getU1() && v >= source.getV0() && v <= source.getV1()) return i;
        }
        return -1;
    }

    // 26.3 added setUv3; it is passed on through a handle, so the class names no member 26.1 and 26.2 lack.
    private static void setUv3(VertexConsumer target, float u, float v) {
        try {
            if (SET_UV3 != null) SET_UV3.invoke(target, u, v);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static final MethodHandle SET_UV3 = setUv3Handle();

    private static MethodHandle setUv3Handle() {
        try {
            return MethodHandles.publicLookup().findVirtual(VertexConsumer.class, "setUv3",
                    MethodType.methodType(VertexConsumer.class, float.class, float.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
