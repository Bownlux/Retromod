/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

/** Writes old model quads into a buffer and hands out old glint buffers, as Minecraft 1.21.1's item renderer did. */
public final class LegacyQuadDraws {

    private static final Direction[] SIDES = Direction.values();
    // Quads are written on the render thread, one after another, so one instance carries each quad's color and light.
    private static final QuadInstance INSTANCE = new QuadInstance();
    private static final RandomSource RANDOM = RandomSource.create(42L);
    // 26.1 and 26.2 draw glint as its own render type over the item; 26.3 folded it into each item render type.
    private static final MethodHandle GLINT = glint("glint");
    private static final MethodHandle GLINT_TRANSLUCENT = glint("glintTranslucent");
    private static final MethodHandle ENTITY_GLINT = glint("entityGlint");
    private static final MethodHandle ARMOR_GLINT = glint("armorEntityGlint");
    // The old compass glint's texture scale.
    private static final float COMPASS_GLINT_SCALE = 0.0078125f;
    // 26.3's item sheets, each of which has a variant that draws the item and its glint in one pass.
    private static final List<String> ITEM_SHEETS = List.of("cutoutItemSheet", "translucentItemSheet", "cutoutBlockItemSheet",
            "translucentBlockItemSheet");
    private static volatile boolean notedNoGlint;
    // {sheet, its glint variant} for each item sheet the host has, read on first use, never while this class loads.
    private static volatile List<RenderType[]> glintSheets;

    private LegacyQuadDraws() {}

    /** Writes each quad at {@code pose}, colored by its tint index from {@code tints} and white when untinted. */
    public static void put(PoseStack.Pose pose, VertexConsumer out, List<BakedQuad> quads, int[] tints, int light, int overlay) {
        for (BakedQuad quad : quads) {
            if (quad != null) put(pose, out, quad, tintOf(quad, tints), light, overlay);
        }
    }

    /** Writes one quad with one color, light and overlay. */
    public static synchronized void put(PoseStack.Pose pose, VertexConsumer out, BakedQuad quad, int argb, int light, int overlay) {
        INSTANCE.setColor(argb);
        INSTANCE.setLightCoords(light);
        INSTANCE.setOverlayCoords(overlay);
        out.putBakedQuad(pose, quad, INSTANCE);
    }

    /** The color a quad's tint index takes, or white for an untinted quad or an index with no color. */
    public static int tintOf(BakedQuad quad, int[] tints) {
        BakedQuad.MaterialInfo info = quad.materialInfo();
        if (info == null || !info.isTinted()) return -1;
        int index = info.tintIndex();
        return index >= 0 && index < tints.length ? tints[index] : -1;
    }

    /** A host block model's quads on {@code side}, null for the unculled ones, from the parts its seed-42 variant picks. */
    public static synchronized List<BakedQuad> blockQuads(BlockStateModel model, Direction side) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        RANDOM.setSeed(42L);
        model.collectParts(RANDOM, parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) quads.addAll(part.getQuads(side));
        return quads;
    }

    /** Every quad of a host block model, each side and the unculled ones together, as an item draw wrote them. */
    public static List<BakedQuad> allBlockQuads(BlockStateModel model) {
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction side : SIDES) quads.addAll(blockQuads(model, side));
        quads.addAll(blockQuads(model, null));
        return quads;
    }

    /** The item render types {@code quads} draw with; the translucent item sheet when they name none. */
    public static List<RenderType> itemRenderTypes(List<BakedQuad> quads) {
        List<RenderType> types = new ArrayList<>(2);
        for (BakedQuad quad : quads) {
            RenderType type = quad == null || quad.materialInfo() == null ? null : quad.materialInfo().itemRenderType();
            if (type != null && !types.contains(type)) types.add(type);
        }
        if (types.isEmpty()) types.add(Sheets.translucentItemSheet());
        return types;
    }

    /** Old getFoilBuffer and getFoilBufferDirect: the type's buffer, with the item or entity glint drawn over it when the stack has foil. */
    public static VertexConsumer foil(MultiBufferSource buffers, RenderType type, boolean item, boolean foil) {
        // 26.3 has no glint render type of its own; an item sheet's glint variant draws the item with its glint.
        if (foil && item && GLINT == null) {
            RenderType glinted = glintSheet(type);
            if (glinted != null) return buffers.getBuffer(glinted);
        }
        VertexConsumer base = buffers.getBuffer(type);
        if (!foil) return base;
        MethodHandle glint = !item ? ENTITY_GLINT : type == Sheets.translucentItemSheet() && GLINT_TRANSLUCENT != null ? GLINT_TRANSLUCENT : GLINT;
        RenderType glintType = renderType(glint);
        return glintType == null ? base : new Both(base, buffers.getBuffer(glintType));
    }

    /** Old getArmorFoilBuffer: the armor glint over the type's buffer. */
    public static VertexConsumer armorFoil(MultiBufferSource buffers, RenderType type, boolean foil) {
        VertexConsumer base = buffers.getBuffer(type);
        RenderType glintType = foil ? renderType(ARMOR_GLINT) : null;
        return glintType == null ? base : new Both(base, buffers.getBuffer(glintType));
    }

    /** Old getCompassFoilBuffer: the glint projected flat across the item at {@code pose}, over the type's buffer. */
    public static VertexConsumer compassFoil(MultiBufferSource buffers, RenderType type, PoseStack.Pose pose) {
        VertexConsumer base = buffers.getBuffer(type);
        RenderType glintType = renderType(GLINT);
        if (glintType == null) return base;
        return new Both(base, new SheetedDecalTextureGenerator(buffers.getBuffer(glintType), pose, COMPASS_GLINT_SCALE));
    }

    /** The glint variant of a 26.3 item sheet, or null for any other render type and on hosts without the variants. */
    static RenderType glintSheet(RenderType type) {
        for (RenderType[] pair : glintSheets()) {
            if (pair[0] == type) return pair[1];
        }
        return null;
    }

    private static List<RenderType[]> glintSheets() {
        List<RenderType[]> known = glintSheets;
        if (known != null) return known;
        List<RenderType[]> found = new ArrayList<>(ITEM_SHEETS.size());
        for (String sheet : ITEM_SHEETS) {
            RenderType plain = sheet(sheet);
            RenderType glinted = sheet(sheet.replace("Sheet", "GlintSheet"));
            if (plain != null && glinted != null) found.add(new RenderType[]{plain, glinted});
        }
        glintSheets = found;
        return found;
    }

    // Read by name, as 26.1 and 26.2 have no glint variants and some hosts lack a sheet.
    private static RenderType sheet(String name) {
        try {
            return (RenderType) Sheets.class.getMethod(name).invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    private static RenderType renderType(MethodHandle glint) {
        if (glint == null) {
            if (!notedNoGlint) {
                notedNoGlint = true;
                LegacyLog.debug("An old renderer asked for a glint buffer that is no item sheet, which Minecraft 26.3 draws inside"
                        + " each item render type, so Retromod gave it the plain buffer");
            }
            return null;
        }
        try {
            return (RenderType) glint.invokeExact();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    private static MethodHandle glint(String name) {
        try {
            Class<?> types = Class.forName("net.minecraft.client.renderer.rendertype.RenderTypes", false, LegacyQuadDraws.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(types, name, MethodType.methodType(RenderType.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /** Writes every vertex into two buffers, as Minecraft 1.21.1's VertexMultiConsumer did for an item and its glint. */
    static final class Both implements VertexConsumer {
        private final VertexConsumer first;
        private final VertexConsumer second;

        Both(VertexConsumer first, VertexConsumer second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            first.addVertex(x, y, z);
            second.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            first.setColor(red, green, blue, alpha);
            second.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            first.setColor(argb);
            second.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            first.setUv(u, v);
            second.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            first.setUv1(u, v);
            second.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            first.setUv2(u, v);
            second.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            first.setNormal(x, y, z);
            second.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            first.setLineWidth(width);
            second.setLineWidth(width);
            return this;
        }

        // Abstract on Minecraft 26.3, where no glint buffer is ever paired; no old renderer writes it.
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }
    }
}
