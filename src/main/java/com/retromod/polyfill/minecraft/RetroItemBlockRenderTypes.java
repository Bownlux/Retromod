/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0: bridge for {@code ItemBlockRenderTypes}, deleted in the 26.1 render rewrite (12 corpus
 * mods). The old static block-to-RenderType table is gone: the layer decision became per-quad data
 * ({@code ChunkSectionLayer {SOLID, CUTOUT, TRANSLUCENT}} derived from the model material). This
 * polyfill re-derives a block's layer from its live model (the way vanilla's SectionCompiler reads
 * it) and maps it to the surviving {@code RenderTypes.solidMovingBlock()/cutoutMovingBlock()/
 * translucentMovingBlock()} tokens, vanilla's own layer-to-RenderType conversion; Retromod's
 * RenderType getter redirects map the old {@code RenderType.solid()/cutout()/translucent()} statics
 * to the SAME tokens, so mod-side {@code ==} comparisons stay consistent.
 *
 * <p><b>Lookup chains</b> (workflow-verified on both jars): block models via
 * {@code Minecraft.getModelManager()}, probing {@code getBlockStateModelSet()} FIRST (26.2-only)
 * and falling back to {@code getBlockModelSet()} (26.1; NOTE it exists on 26.2 too but returns a
 * DIFFERENT type, which is why probe order matters); then
 * {@code model.collectParts(RandomSource, List)} -> {@code part.getQuads(Direction)} -> the first
 * quad's {@code materialInfo()} (26.2) / {@code spriteInfo()} (26.1) {@code .layer()}. Fluids via
 * {@code getFluidStateModelSet().get(fs).layer()} (26.2) or the live
 * {@code getBlockRenderer().getLiquidRenderer().getRenderLayer(fs)} chain (26.1; the water/lava
 * table is resource-pack-dependent, so it must be read live, never replicated).
 *
 * <p><b>Caching:</b> per-BlockState results are cached in a map keyed on the model-set INSTANCE
 * (weakly), so a resource reload that rebuilds the model set naturally invalidates the cache; the
 * hot path (chunk compilation calls this per block) is one IdentityHashMap hit.
 *
 * <p><b>Zero compile-time Minecraft dependency</b>, public members only. <b>Fail-safe:</b> any
 * resolution failure returns the SOLID token (vanilla's own default), and if even that is
 * unresolvable, null; worst case is a wrong render layer, never a crash.
 */
public final class RetroItemBlockRenderTypes {

    private RetroItemBlockRenderTypes() {}

    private static final java.util.Map<Object, java.util.Map<Object, Object>> CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // Cached reflective plumbing; volatile-published after first resolution.
    private static volatile java.lang.reflect.Method solidToken;
    private static volatile java.lang.reflect.Method cutoutToken;
    private static volatile java.lang.reflect.Method translucentToken;

    /** Old {@code getChunkRenderType(BlockState)}. */
    public static Object getChunkRenderType(Object blockState) {
        return layerToken(blockLayerName(blockState));
    }

    /** Old {@code getRenderType(BlockState, boolean useTranslucentFabulous)}. */
    public static Object getRenderTypeBlock(Object blockState, boolean fabulous) {
        return layerToken(blockLayerName(blockState));
    }

    /** Old {@code getMovingBlockRenderType(BlockState)}: the *MovingBlock tokens ARE the moving forms. */
    public static Object getMovingBlockRenderType(Object blockState) {
        return layerToken(blockLayerName(blockState));
    }

    /** Old {@code getRenderLayer(FluidState)}. */
    public static Object getRenderLayer(Object fluidState) {
        return layerToken(fluidLayerName(fluidState));
    }

    /**
     * Old {@code getRenderType(ItemStack, boolean)}: the item render pipeline was rebuilt wholesale
     * (ItemModel/ItemStackRenderState), so the honest approximation for the single corpus call site
     * is the translucent token (items composite fine on it).
     */
    public static Object getRenderTypeItem(Object itemStack, boolean fabulous) {
        return layerToken("TRANSLUCENT");
    }

    // ------------------------------------------------------------------ layer derivation

    private static String blockLayerName(Object state) {
        try {
            Object modelSet = modelSet();
            if (modelSet == null || state == null) return "SOLID";
            java.util.Map<Object, Object> perSet =
                    CACHE.computeIfAbsent(modelSet, k ->
                            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>()));
            Object cached = perSet.get(state);
            if (cached != null) return (String) cached;

            String layer = deriveBlockLayer(modelSet, state);
            perSet.put(state, layer);
            return layer;
        } catch (Throwable t) {
            return "SOLID";
        }
    }

    private static String deriveBlockLayer(Object modelSet, Object state) throws Exception {
        Object model = call1(modelSet, "get", state);
        if (model == null) return "SOLID";
        // collectParts(RandomSource, List)V exists on BOTH 26.x lines.
        Class<?> randomCls = Class.forName("net.minecraft.util.RandomSource");
        Object random = randomCls.getMethod("create").invoke(null);
        java.util.List<Object> parts = new java.util.ArrayList<>();
        java.lang.reflect.Method collect = null;
        for (java.lang.reflect.Method m : model.getClass().getMethods()) {
            if (m.getName().equals("collectParts") && m.getParameterCount() == 2) { collect = m; break; }
        }
        if (collect == null) return "SOLID";
        collect.invoke(model, random, parts);
        Class<?> dirCls = Class.forName("net.minecraft.core.Direction");
        Object[] dirs = (Object[]) dirCls.getMethod("values").invoke(null);
        for (Object part : parts) {
            java.lang.reflect.Method getQuads = part.getClass().getMethod("getQuads", dirCls);
            getQuads.setAccessible(true); // impl classes may be package-private; the METHOD is public
            for (int i = -1; i < dirs.length; i++) {
                Object dir = i < 0 ? null : dirs[i]; // null = the unculled quad bucket
                java.util.List<?> quads;
                try {
                    quads = (java.util.List<?>) getQuads.invoke(part, dir);
                } catch (Throwable perDir) {
                    continue; // an impl that rejects null just skips that bucket
                }
                if (quads == null || quads.isEmpty()) continue;
                Object quad = quads.get(0);
                Object info = tryCall(quad, "materialInfo"); // 26.2
                if (info == null) info = tryCall(quad, "spriteInfo"); // 26.1
                if (info == null) continue;
                Object layer = tryCall(info, "layer");
                if (layer instanceof Enum<?> e) return e.name();
            }
        }
        return "SOLID"; // vanilla's own default (SectionCompiler / LiquidBlockRenderer)
    }

    private static String fluidLayerName(Object fluidState) {
        try {
            Object mm = modelManager();
            if (mm == null || fluidState == null) return "SOLID";
            // 26.2: getFluidStateModelSet().get(fs).layer()
            Object fluidSet = tryCall(mm, "getFluidStateModelSet");
            if (fluidSet != null) {
                Object model = call1(fluidSet, "get", fluidState);
                Object layer = model != null ? tryCall(model, "layer") : null;
                if (layer instanceof Enum<?> e) return e.name();
            }
            // 26.1: the live liquid-renderer chain (pack-dependent, must not be replicated).
            Object mc = minecraft();
            Object dispatcher = mc != null ? tryCall(mc, "getBlockRenderer") : null;
            if (dispatcher != null) {
                Object layer = tryCall1(dispatcher, "getRenderLayer", fluidState);
                if (layer == null) {
                    Object liquid = tryCall(dispatcher, "getLiquidRenderer");
                    if (liquid != null) layer = tryCall1(liquid, "getRenderLayer", fluidState);
                }
                if (layer instanceof Enum<?> e) return e.name();
            }
        } catch (Throwable ignored) {
        }
        return "SOLID";
    }

    // ------------------------------------------------------------------ token mapping

    private static Object layerToken(String layerName) {
        try {
            java.lang.reflect.Method m = switch (layerName) {
                case "TRANSLUCENT" -> translucentToken();
                case "CUTOUT" -> cutoutToken();
                default -> solidToken();
            };
            return m != null ? m.invoke(null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Method solidToken() throws Exception {
        java.lang.reflect.Method m = solidToken;
        if (m == null) solidToken = m = tokenMethod("solidMovingBlock");
        return m;
    }

    private static java.lang.reflect.Method cutoutToken() throws Exception {
        java.lang.reflect.Method m = cutoutToken;
        if (m == null) cutoutToken = m = tokenMethod("cutoutMovingBlock");
        return m;
    }

    private static java.lang.reflect.Method translucentToken() throws Exception {
        java.lang.reflect.Method m = translucentToken;
        if (m == null) translucentToken = m = tokenMethod("translucentMovingBlock");
        return m;
    }

    private static java.lang.reflect.Method tokenMethod(String name) throws Exception {
        return Class.forName("net.minecraft.client.renderer.rendertype.RenderTypes")
                .getMethod(name);
    }

    // ------------------------------------------------------------------ client plumbing

    private static Object minecraft() {
        try {
            return Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance")
                    .invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object modelManager() {
        Object mc = minecraft();
        return mc == null ? null : tryCall(mc, "getModelManager");
    }

    /**
     * The state-keyed model set. Probe {@code getBlockStateModelSet} FIRST: it exists only on 26.2;
     * {@code getBlockModelSet} exists on BOTH lines but returns a different type on 26.2.
     */
    private static Object modelSet() {
        Object mm = modelManager();
        if (mm == null) return null;
        Object set = tryCall(mm, "getBlockStateModelSet");
        return set != null ? set : tryCall(mm, "getBlockModelSet");
    }

    private static Object tryCall(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object tryCall1(Object target, String name, Object arg) {
        try {
            for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(arg)) {
                    return m.invoke(target, arg);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object call1(Object target, String name, Object arg) throws Exception {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(arg)) {
                return m.invoke(target, arg);
            }
        }
        return null;
    }
}
