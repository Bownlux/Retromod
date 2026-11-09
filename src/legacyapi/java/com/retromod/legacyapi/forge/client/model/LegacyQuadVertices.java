/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.BakedQuad.MaterialInfo;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Gives Minecraft 26.2's baked quads the int[] vertex data of Forge 1.20.1, eight ints for each of four vertices. */
public final class LegacyQuadVertices {

    // Forge 1.20.1's IQuadTransformer layout, DefaultVertexFormat.BLOCK: position, color, uv0, uv2, normal.
    public static final int STRIDE = 8;
    public static final int POSITION = 0;
    public static final int COLOR = 3;
    public static final int UV0 = 4;
    public static final int UV2 = 6;
    public static final int NORMAL = 7;
    public static final int VERTICES = 4;
    public static final int SIZE = STRIDE * VERTICES;
    private static final int WHITE = -1;

    // A host quad is an immutable record, so the array a mod reads and writes lives beside it. The table is keyed
    // by identity, since equal records are separate quads, holds its quads weakly, and takes no lock, since chunk
    // workers read quads in parallel.
    private static final ConcurrentHashMap<Key, Entry> ARRAYS = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> CLEARED = new ReferenceQueue<>();

    private LegacyQuadVertices() {}

    /** Forge 1.20.1's {@code getVertices()}: the same array every time for one quad, so edits to it can be read back. */
    public static int[] getVertices(BakedQuad quad) {
        Entry entry = entry(quad);
        if (entry != null) return entry.vertices;
        return remember(quad, toVertices(quad), true).vertices;
    }

    /** Forge 1.20.1's {@code new BakedQuad(vertices, tintIndex, direction, sprite, shade)}. */
    public static BakedQuad create(int[] vertices, int tintIndex, Direction direction, TextureAtlasSprite sprite,
            boolean shade) {
        return create(vertices, tintIndex, direction, sprite, shade, true);
    }

    /** Forge 1.20.1's quad constructor that also takes ambient occlusion, which 26.2 keeps per model part instead. */
    public static BakedQuad create(int[] vertices, int tintIndex, Direction direction, TextureAtlasSprite sprite,
            boolean shade, boolean ambientOcclusion) {
        MaterialInfo info = LegacyQuadMaterials.create(sprite, tintIndex, shade, emission(vertices));
        noteDroppedColor(vertices);
        BakedQuad quad = build(vertices, direction, info);
        // The quad keeps the caller's own array, as Forge 1.20.1's did, so later edits to it can be rebuilt.
        remember(quad, vertices, ambientOcclusion);
        return quad;
    }

    /** The quad as its array reads now: the same quad when nothing it can hold changed, else a new quad. */
    public static BakedQuad sync(BakedQuad quad) {
        Entry entry = entry(quad);
        if (entry == null) return quad;
        int[] vertices = entry.vertices;
        noteDroppedColor(vertices);
        // The record itself is the baseline: only positions, UVs and the block light can differ from it.
        if (matches(quad, vertices)) return quad;
        BakedQuad rebuilt = entry.rebuilt;
        if (rebuilt != null && matches(rebuilt, vertices)) return rebuilt;
        int[] snapshot = vertices.clone();
        MaterialInfo info = quad.materialInfo();
        int emission = emission(snapshot);
        if (info != null && emission != info.lightEmission()) info = LegacyQuadMaterials.withLightEmission(info, emission);
        rebuilt = build(snapshot, quad.direction(), info);
        // The new quad shares the array, so edits a mod keeps making to it still reach a later sync.
        remember(rebuilt, vertices, entry.ambientOcclusion);
        entry.rebuilt = rebuilt;
        return rebuilt;
    }

    /** Each quad synced; the same list when none changed. */
    public static List<BakedQuad> sync(List<BakedQuad> quads) {
        List<BakedQuad> synced = null;
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            BakedQuad now = sync(quad);
            if (now != quad && synced == null) synced = new ArrayList<>(quads.subList(0, i));
            if (synced != null) synced.add(now);
        }
        return synced == null ? quads : synced;
    }

    /** Writes each changed quad back into the list where it can, as Forge 1.20.1's in-place edits changed the list's quads. */
    public static void syncInPlace(List<BakedQuad> quads) {
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            BakedQuad now = sync(quad);
            if (now == quad) continue;
            try {
                quads.set(i, now);
            } catch (UnsupportedOperationException readOnly) {
                // The edits stay with the old quad's array for a later sync.
                return;
            }
        }
    }

    /** A separate quad with its own copy of the array, as Forge 1.20.1's {@code IQuadTransformer} copied before a change. */
    public static BakedQuad copy(BakedQuad quad) {
        BakedQuad current = sync(quad);
        Entry entry = entry(current);
        // A quad no old code has read yet is converted for the copy only, and stays out of the table.
        int[] vertices = entry == null ? toVertices(current) : entry.vertices.clone();
        BakedQuad copy = build(vertices, current.direction(), current.materialInfo());
        remember(copy, vertices, entry == null || entry.ambientOcclusion);
        return copy;
    }

    /** The synced quad drawn in another chunk layer, for a model that names its quads' layers. */
    public static BakedQuad withLayer(BakedQuad quad, ChunkSectionLayer layer) {
        BakedQuad current = sync(quad);
        MaterialInfo info = current.materialInfo();
        MaterialInfo stamped = LegacyQuadMaterials.withLayer(info, layer);
        return stamped == info ? current : withMaterial(current, stamped);
    }

    /**
     * The quad with another material and the same vertices. The copy stays out of the table, so read
     * {@link #hasAmbientOcclusion} from the quad before stamping it.
     */
    public static BakedQuad withMaterial(BakedQuad quad, MaterialInfo info) {
        return new BakedQuad(quad.position(0), quad.position(1), quad.position(2), quad.position(3),
                quad.packedUV(0), quad.packedUV(1), quad.packedUV(2), quad.packedUV(3), quad.direction(), info);
    }

    // Forge 1.20.1's other quad getters, now read from the material.

    public static int getTintIndex(BakedQuad quad) {
        return quad.materialInfo().tintIndex();
    }

    public static TextureAtlasSprite getSprite(BakedQuad quad) {
        return quad.materialInfo().sprite();
    }

    public static boolean isShade(BakedQuad quad) {
        return LegacyQuadMaterials.shade(quad.materialInfo());
    }

    /** Whether the quad asked for ambient occlusion; a quad no old code built says yes, as most did. */
    public static boolean hasAmbientOcclusion(BakedQuad quad) {
        Entry entry = entry(quad);
        return entry == null || entry.ambientOcclusion;
    }

    /** A fresh int[32] of the quad: its positions and UVs, white, its light emission as a lightmap, and its normal. */
    public static int[] toVertices(BakedQuad quad) {
        int[] vertices = new int[SIZE];
        MaterialInfo info = quad.materialInfo();
        int emission = info == null ? 0 : info.lightEmission();
        int light = (emission << 4) | (emission << 20);
        int normal = packedNormal(quad);
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            int base = vertex * STRIDE;
            Vector3fc position = quad.position(vertex);
            vertices[base + POSITION] = Float.floatToRawIntBits(position.x());
            vertices[base + POSITION + 1] = Float.floatToRawIntBits(position.y());
            vertices[base + POSITION + 2] = Float.floatToRawIntBits(position.z());
            vertices[base + COLOR] = WHITE;
            // 26.2 packs u in the high half and v in the low half of one long.
            long uv = quad.packedUV(vertex);
            vertices[base + UV0] = (int) (uv >>> 32);
            vertices[base + UV0 + 1] = (int) uv;
            vertices[base + UV2] = light;
            vertices[base + NORMAL] = normal;
        }
        return vertices;
    }

    /** A host quad from an int[32]: positions and UVs from the array, direction and material as given. */
    public static BakedQuad build(int[] vertices, Direction direction, MaterialInfo info) {
        int[] data = padded(vertices);
        Vector3fc[] positions = new Vector3fc[VERTICES];
        long[] uvs = new long[VERTICES];
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            int base = vertex * STRIDE;
            positions[vertex] = new Vector3f(Float.intBitsToFloat(data[base + POSITION]),
                    Float.intBitsToFloat(data[base + POSITION + 1]), Float.intBitsToFloat(data[base + POSITION + 2]));
            uvs[vertex] = uv(data, base);
        }
        return new BakedQuad(positions[0], positions[1], positions[2], positions[3], uvs[0], uvs[1], uvs[2], uvs[3],
                direction, info);
    }

    /** The block light the array's lightmap asks for: the brightest vertex's block half, 0 to 15. */
    public static int emission(int[] vertices) {
        int brightest = 0;
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            int index = vertex * STRIDE + UV2;
            if (index < vertices.length) brightest = Math.max(brightest, (vertices[index] & 0xFFFF) >> 4);
        }
        return Math.min(15, brightest);
    }

    // Whether the record holds what the array says: positions, UVs and, where materials can change, the block light.
    private static boolean matches(BakedQuad quad, int[] vertices) {
        int[] data = padded(vertices);
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            int base = vertex * STRIDE;
            Vector3fc position = quad.position(vertex);
            if (Float.floatToRawIntBits(position.x()) != data[base + POSITION]
                    || Float.floatToRawIntBits(position.y()) != data[base + POSITION + 1]
                    || Float.floatToRawIntBits(position.z()) != data[base + POSITION + 2]
                    || quad.packedUV(vertex) != uv(data, base)) {
                return false;
            }
        }
        MaterialInfo info = quad.materialInfo();
        return info == null || !LegacyQuadMaterials.isKnown() || info.lightEmission() == emission(data);
    }

    private static int[] padded(int[] vertices) {
        return vertices.length >= SIZE ? vertices : Arrays.copyOf(vertices, SIZE);
    }

    private static long uv(int[] data, int base) {
        float u = Float.intBitsToFloat(data[base + UV0]);
        float v = Float.intBitsToFloat(data[base + UV0 + 1]);
        return ((long) Float.floatToIntBits(u) << 32) | (Float.floatToIntBits(v) & 0xFFFFFFFFL);
    }

    // Baked quads keep no vertex color, so a color an old mod writes is lost; it is noted once.
    private static void noteDroppedColor(int[] vertices) {
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            int index = vertex * STRIDE + COLOR;
            if (index >= vertices.length || vertices[index] == WHITE) continue;
            LegacyLog.warnOnce("legacy quad color " + LegacyQuadVertices.class.getName(), "Baked quads in Minecraft"
                    + " 26.2 and newer keep no vertex colors, so Retromod drew an old mod's colored quads white."
                    + " Tinting through block and item colors still works.");
            return;
        }
    }

    // Forge 1.20.1 baked the face normal from the positions; a flat or broken face uses its direction instead.
    private static int packedNormal(BakedQuad quad) {
        Vector3fc p0 = quad.position(0);
        Vector3fc p1 = quad.position(1);
        Vector3fc p2 = quad.position(2);
        Vector3fc p3 = quad.position(3);
        float ax = p2.x() - p0.x(), ay = p2.y() - p0.y(), az = p2.z() - p0.z();
        float bx = p3.x() - p1.x(), by = p3.y() - p1.y(), bz = p3.z() - p1.z();
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1.0e-6f)) {
            int[] step = step(quad.direction());
            return packNormal(step[0], step[1], step[2]);
        }
        return packNormal(nx / length, ny / length, nz / length);
    }

    private static int packNormal(float x, float y, float z) {
        return ((byte) (x * 127) & 0xFF) | (((byte) (y * 127) & 0xFF) << 8) | (((byte) (z * 127) & 0xFF) << 16);
    }

    private static int[] step(Direction direction) {
        if (direction == Direction.DOWN) return new int[]{0, -1, 0};
        if (direction == Direction.UP) return new int[]{0, 1, 0};
        if (direction == Direction.NORTH) return new int[]{0, 0, -1};
        if (direction == Direction.SOUTH) return new int[]{0, 0, 1};
        if (direction == Direction.WEST) return new int[]{-1, 0, 0};
        return direction == Direction.EAST ? new int[]{1, 0, 0} : new int[]{0, 0, 0};
    }

    // ---- the side table ----

    private static Entry entry(BakedQuad quad) {
        expunge();
        return quad == null ? null : ARRAYS.get(new Key(quad, null));
    }

    /** Records the quad's array; when another thread recorded one first, that one stands. */
    private static Entry remember(BakedQuad quad, int[] vertices, boolean ambientOcclusion) {
        expunge();
        Entry fresh = new Entry(vertices, ambientOcclusion);
        Entry earlier = ARRAYS.putIfAbsent(new Key(quad, CLEARED), fresh);
        return earlier == null ? fresh : earlier;
    }

    private static void expunge() {
        for (Object cleared = CLEARED.poll(); cleared != null; cleared = CLEARED.poll()) ARRAYS.remove(cleared);
    }

    private static final class Key extends WeakReference<Object> {
        private final int hash;

        Key(Object quad, ReferenceQueue<Object> queue) {
            super(quad, queue);
            hash = System.identityHashCode(quad);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            Object quad = get();
            return quad != null && quad == key.get();
        }
    }

    private static final class Entry {
        final int[] vertices;
        final boolean ambientOcclusion;
        // The quad last rebuilt from this array, handed out again while the array still reads the same.
        volatile BakedQuad rebuilt;

        Entry(int[] vertices, boolean ambientOcclusion) {
            this.vertices = vertices;
            this.ambientOcclusion = ambientOcclusion;
        }
    }
}
