/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Turns an old render type description into a 26.x render type: the closest host pipeline plus a render setup. */
public final class LegacyRenderTypeResolver {

    /** The keys of a description, written by the rebuilt CompositeState. */
    public static final String SHADER = "shader";
    public static final String CUSTOM_SHADER = "customShader";
    public static final String TEXTURE = "texture";
    public static final String BLUR = "blur";
    public static final String TRANSPARENCY = "transparency";
    public static final String CULL = "cull";
    public static final String DEPTH = "depth";
    public static final String WRITE = "write";
    public static final String LIGHTMAP = "lightmap";
    public static final String OVERLAY = "overlay";
    public static final String LAYERING = "layering";
    public static final String OUTPUT = "output";
    public static final String TEXTURING = "texturing";
    public static final String LINE = "line";
    public static final String OUTLINE = "outline";
    public static final String CUSTOM_SHARDS = "customShards";

    // Old vanilla shader shards and the host pipelines that draw the same kind of geometry, newest name first.
    static final Map<String, String> SHADER_PIPELINES = shaderPipelines();

    private static final Map<Object, Integer> BUFFER_SIZES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, Object> CACHE = new HashMap<>();
    private static final Map<String, String> OLD_DEPTH_FUNCTIONS = Map.of("NO_DEPTH_TEST", "ALWAYS_PASS",
            "EQUAL_DEPTH_TEST", "EQUAL", "LEQUAL_DEPTH_TEST", "LESS_THAN_OR_EQUAL", "LESS_DEPTH_TEST", "LESS_THAN",
            "GREATER_DEPTH_TEST", "GREATER_THAN");
    private static final int DEFAULT_BUFFER_SIZE = 1536;
    // Added where Retromod could do better, so a report reaches the right place.
    private static final String REPORT = " Report this line with the mod.";
    private static volatile Host host = new ReflectiveHost();

    private LegacyRenderTypeResolver() {}

    /** What the resolver needs from the game, so tests can stand in for it. */
    public interface Host {
        /** Every host pipeline by its constant name, in declaration order. */
        Map<String, Object> pipelines();

        /** A render type named {@code name} over {@code pipeline}, set up as {@code setup} asks. */
        Object create(String name, Object pipeline, Setup setup) throws ReflectiveOperationException;

        /** A vanilla render type with this vertex layout and topology, or null when the game has none. */
        Object fallback(Object format, Object topology);
    }

    /** The render setup parts the old state asked for; the host applies what its version can express. */
    public record Setup(Object texture, boolean lightmap, boolean overlay, boolean crumbling, boolean sort,
            String layering, boolean affectsOutline, int bufferSize, String output) {}

    /** Replaces the host access, for tests. */
    public static void useHost(Host replacement) {
        host = replacement;
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /** The old {@code RenderType.create(...)}: a cached host render type for the name and description. */
    public static Object create(String name, Object format, Object topology, int bufferSize, boolean crumbling, boolean sort,
            Map<String, Object> state) {
        // Everything the old call passed is part of the key, so two types that share a name but not a layout stay apart.
        String key = name + "|" + format + "|" + topology + "|" + bufferSize + "|" + crumbling + "|" + sort + "|" + state;
        synchronized (CACHE) {
            Object cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        Object made = resolve(name, format, topology, bufferSize, crumbling, sort, state);
        if (made != null) {
            BUFFER_SIZES.put(made, bufferSize);
            synchronized (CACHE) {
                CACHE.put(key, made);
            }
        }
        return made;
    }

    private static Object resolve(String name, Object format, Object topology, int bufferSize, boolean crumbling, boolean sort,
            Map<String, Object> state) {
        try {
            Map<String, Object> pipelines = host.pipelines();
            String chosen = choosePipeline(name, pipelines, format, topology, state);
            if (chosen == null) {
                warn(name, "has no pipeline on this Minecraft version with its vertex format and primitive mode, so it is not"
                        + " created and draws nothing." + REPORT);
                return null;
            }
            Object pipeline = pipelines.get(chosen);
            reportDeviations(name, chosen, pipeline, state);
            reportUnmapped(name, state);
            Object output = state.get(OUTPUT);
            Setup setup = new Setup(state.get(TEXTURE), flag(state, LIGHTMAP), flag(state, OVERLAY), crumbling, sort,
                    layering(state), flag(state, OUTLINE), bufferSize, output == null ? null : String.valueOf(output));
            return host.create(name, pipeline, setup);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // A vanilla type with the same layout draws the mod's geometry, if not with its exact look.
            Object fallback = host.fallback(format, topology);
            warn(name, "could not be rebuilt on this Minecraft version (" + failure + "), so it "
                    + (fallback != null ? "draws as the vanilla render type " + fallback + "." : "is not created and draws nothing.")
                    + REPORT);
            return fallback;
        }
    }

    /** The vanilla pipeline for the old shader if its vertex layout fits, else the best fit by layout and settings. */
    static String choosePipeline(String name, Map<String, Object> pipelines, Object format, Object topology,
            Map<String, Object> state) {
        String named = SHADER_PIPELINES.get(String.valueOf(state.get(SHADER)));
        if (named != null) {
            // Some pipelines were renamed between versions; the first name the host has wins.
            for (String candidate : named.split("\\|")) {
                if (fits(pipelines.get(candidate), format, topology)) return candidate;
            }
        }
        if (flag(state, CUSTOM_SHADER)) {
            warn(name, "uses its own shader program, which this Minecraft version cannot load from an old mod, so it draws with the closest vanilla"
                    + " shader instead.");
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map.Entry<String, Object> entry : pipelines.entrySet()) {
            if (!fits(entry.getValue(), format, topology)) continue;
            int score = score(entry.getValue(), state);
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
            }
        }
        return best;
    }

    // A pipeline can draw the mesh only when its first vertex layout and primitive topology are the old ones.
    private static boolean fits(Object pipeline, Object format, Object topology) {
        if (pipeline == null) return false;
        // 26.2 and 26.3 bind layouts by index and name the topology; 26.1 has one layout and a vertex mode.
        Object layout = call(pipeline, "getVertexFormatBinding", 0);
        if (layout == null) layout = call(pipeline, "getVertexFormat");
        Object pipelineTopology = call(pipeline, "getPrimitiveTopology");
        if (pipelineTopology == null) pipelineTopology = call(pipeline, "getVertexFormatMode");
        return layout != null && layout.equals(format) && pipelineTopology != null && pipelineTopology.equals(topology);
    }

    // One point for each setting the pipeline shares with the old state.
    private static int score(Object pipeline, Map<String, Object> state) {
        int score = 0;
        if (blends(pipeline) == wantsBlend(state)) score += 4;
        if (Boolean.valueOf(culls(pipeline)).equals(wantsCull(state))) score += 2;
        if (depthTest(pipeline).equals(wantedDepth(state))) score += 2;
        if (writesDepth(pipeline) == wantsDepthWrite(state)) score += 1;
        return score;
    }

    /** Logs once when the chosen pipeline's cull, blend, depth or write settings differ from the old state's. */
    private static void reportDeviations(String name, String chosen, Object pipeline, Map<String, Object> state) {
        List<String> differences = new ArrayList<>();
        if (blends(pipeline) != wantsBlend(state)) differences.add(wantsBlend(state) ? "blending" : "no blending");
        if (culls(pipeline) != wantsCull(state)) differences.add(wantsCull(state) ? "back-face culling" : "no culling");
        if (!depthTest(pipeline).equals(wantedDepth(state))) differences.add("depth test " + wantedDepth(state));
        if (writesDepth(pipeline) != wantsDepthWrite(state)) differences.add(wantsDepthWrite(state) ? "depth writes" : "no depth writes");
        if (!differences.isEmpty()) {
            warn(name, "draws with the vanilla " + chosen + " pipeline, which does not match its " + String.join(", ", differences)
                    + "." + REPORT);
        }
    }

    /** Logs once for each old state part the host has no place for. */
    private static void reportUnmapped(String name, Map<String, Object> state) {
        Object texturing = state.get(TEXTURING);
        if (texturing != null && !"DEFAULT_TEXTURING".equals(texturing)) {
            warn(name, "moved its texture with " + texturing + ", which is not rebuilt, so the texture stays still.");
        }
        Object layering = state.get(LAYERING);
        if (layering != null && layering(state) == null && !"NO_LAYERING".equals(layering)) {
            warn(name, "offset its geometry with " + layering + ", which this Minecraft version has no equivalent for.");
        }
        if (state.get(LINE) != null) warn(name, "set a line width, which this Minecraft version reads from each vertex instead.");
        if (flag(state, BLUR)) warn(name, "asked for a blurred texture, which uses the default sampler instead.");
        Object custom = state.get(CUSTOM_SHARDS);
        if (custom instanceof List<?> names && !names.isEmpty()) {
            warn(name, "has state with its own setup code (" + names + "), which this Minecraft version cannot run, so that state is left out.");
        }
    }

    /** {@code renderType.bufferSize()}: the size the old create asked for, else the old default. */
    public static int bufferSize(Object renderType) {
        Integer size = renderType == null ? null : BUFFER_SIZES.get(renderType);
        return size != null ? size : DEFAULT_BUFFER_SIZE;
    }

    /** {@code renderType.setupRenderState()}: 26.x sets state per draw, so the call does nothing. */
    public static void setupRenderState(Object renderType) {
        warn(String.valueOf(renderType), "was set up by hand with setupRenderState, which this Minecraft version does per draw, so the call does"
                + " nothing and a draw that follows it may look wrong.");
    }

    /** {@code renderType.clearRenderState()}: nothing to clear. */
    public static void clearRenderState(Object renderType) {
    }

    /** {@code renderType.draw(mesh)} on 26.2 and newer, which draw meshes through render passes, so the mesh is closed. */
    public static void draw(Object renderType, Object mesh) {
        LegacyBufferUploader.draw(mesh);
    }

    private static String layering(Map<String, Object> state) {
        Object layering = state.get(LAYERING);
        // 26.x keeps the view offset; the polygon offset became a per-pipeline depth bias.
        return "VIEW_OFFSET_Z_LAYERING".equals(layering) ? "VIEW_OFFSET_Z_LAYERING" : null;
    }

    private static boolean wantsBlend(Map<String, Object> state) {
        Object transparency = state.get(TRANSPARENCY);
        return transparency != null && !"NO_TRANSPARENCY".equals(transparency);
    }

    // The old composite state culled unless told otherwise.
    private static boolean wantsCull(Map<String, Object> state) {
        Object cull = state.get(CULL);
        return cull == null || Boolean.TRUE.equals(cull);
    }

    private static String wantedDepth(Map<String, Object> state) {
        Object depth = state.get(DEPTH);
        if ("NO_DEPTH_TEST".equals(depth)) return "ALWAYS_PASS";
        if ("EQUAL_DEPTH_TEST".equals(depth)) return "EQUAL";
        if ("GREATER_DEPTH_TEST".equals(depth)) return "GREATER_THAN";
        return "LESS_THAN_OR_EQUAL";
    }

    private static boolean wantsDepthWrite(Map<String, Object> state) {
        Object write = state.get(WRITE);
        return write == null || "COLOR_DEPTH_WRITE".equals(write) || "DEPTH_WRITE".equals(write);
    }

    private static boolean blends(Object pipeline) {
        // 1.21.11 keeps the blend function on the pipeline itself.
        if (hasMethod(pipeline, "getBlendFunction")) {
            return call(pipeline, "getBlendFunction") instanceof Optional<?> blend && blend.isPresent();
        }
        // 26.3 lists the color targets, 26.2 keeps them in an array, 26.1 has one.
        Object targets = call(pipeline, "getColorTargetStates");
        Object first = null;
        if (targets instanceof List<?> list && !list.isEmpty()) first = list.get(0);
        if (targets instanceof Object[] array && array.length > 0) first = array[0];
        if (first == null) first = call(pipeline, "getColorTargetState");
        return call(first, "blendFunction") instanceof Optional<?> blend && blend.isPresent();
    }

    private static boolean culls(Object pipeline) {
        return Boolean.TRUE.equals(call(pipeline, "isCull"));
    }

    private static String depthTest(Object pipeline) {
        // 1.21.11 names the old depth functions; they are read as the comparison each one made.
        if (hasMethod(pipeline, "getDepthTestFunction")) return OLD_DEPTH_FUNCTIONS.getOrDefault(
                String.valueOf(call(pipeline, "getDepthTestFunction")), "LESS_THAN_OR_EQUAL");
        Object depth = call(pipeline, "getDepthStencilState");
        if (depth == null) return "ALWAYS_PASS";
        return String.valueOf(call(depth, "depthTest"));
    }

    private static boolean writesDepth(Object pipeline) {
        if (hasMethod(pipeline, "isWriteDepth")) return Boolean.TRUE.equals(call(pipeline, "isWriteDepth"));
        Object depth = call(pipeline, "getDepthStencilState");
        return depth != null && Boolean.TRUE.equals(call(depth, "writeDepth"));
    }

    private static boolean hasMethod(Object target, String name) {
        if (target == null) return false;
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name)) return true;
        }
        return false;
    }

    private static boolean flag(Map<String, Object> state, String key) {
        return Boolean.TRUE.equals(state.get(key));
    }

    static void warn(String renderType, String message) {
        LegacyLog.warnOnce("render type " + renderType + " " + message.hashCode(), "The old render type " + renderType + " " + message);
    }

    private static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try {
                return method.invoke(target, args);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, String> shaderPipelines() {
        Map<String, String> map = new HashMap<>();
        String[][] pairs = {
            {"POSITION_COLOR_SHADER", "DEBUG_QUADS"}, {"POSITION_TEX_COLOR_SHADER", "GUI_TEXTURED"},
            {"POSITION_TEX_SHADER", "GUI_TEXTURED"}, {"POSITION_COLOR_TEX_LIGHTMAP_SHADER", "TEXT"},
            {"PARTICLE_SHADER", "TRANSLUCENT_PARTICLE"}, {"RENDERTYPE_SOLID_SHADER", "SOLID_BLOCK"},
            {"RENDERTYPE_CUTOUT_MIPPED_SHADER", "CUTOUT_BLOCK"}, {"RENDERTYPE_CUTOUT_SHADER", "CUTOUT_BLOCK"},
            {"RENDERTYPE_TRANSLUCENT_SHADER", "TRANSLUCENT_BLOCK|TRANSLUCENT_MOVING_BLOCK"}, {"RENDERTYPE_TRANSLUCENT_MOVING_BLOCK_SHADER", "TRANSLUCENT_MOVING_BLOCK|TRANSLUCENT_BLOCK"},
            {"RENDERTYPE_TRIPWIRE_SHADER", "TRIPWIRE_BLOCK|TRANSLUCENT_BLOCK"}, {"RENDERTYPE_ARMOR_CUTOUT_NO_CULL_SHADER", "ARMOR_CUTOUT_NO_CULL"},
            {"RENDERTYPE_ENTITY_SOLID_SHADER", "ENTITY_SOLID"},
            // The old culled entity cutout is ENTITY_CUTOUT_CULL on 26.x and ENTITY_CUTOUT on 1.21.11; the old no-cull one is
            // ENTITY_CUTOUT on 26.x and ENTITY_CUTOUT_NO_CULL on 1.21.11. Each list tries the 26.x name first.
            {"RENDERTYPE_ENTITY_CUTOUT_SHADER", "ENTITY_CUTOUT_CULL|ENTITY_CUTOUT"}, {"RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER", "ENTITY_CUTOUT_NO_CULL|ENTITY_CUTOUT"},
            {"RENDERTYPE_ENTITY_CUTOUT_NO_CULL_Z_OFFSET_SHADER", "ENTITY_CUTOUT_NO_CULL_Z_OFFSET|ENTITY_CUTOUT_Z_OFFSET"},
            {"RENDERTYPE_ENTITY_SMOOTH_CUTOUT_SHADER", "ENTITY_SMOOTH_CUTOUT|ENTITY_CUTOUT_NO_CULL|ENTITY_CUTOUT"}, {"RENDERTYPE_ENTITY_ALPHA_SHADER", "ENTITY_CUTOUT_NO_CULL|ENTITY_CUTOUT"},
            {"RENDERTYPE_ENTITY_DECAL_SHADER", "ENTITY_DECAL|ARMOR_DECAL_CUTOUT_NO_CULL"},
            {"RENDERTYPE_ITEM_ENTITY_TRANSLUCENT_CULL_SHADER", "ITEM_TRANSLUCENT|ITEM_ENTITY_TRANSLUCENT_CULL"},
            {"RENDERTYPE_ENTITY_TRANSLUCENT_SHADER", "ENTITY_TRANSLUCENT"},
            {"RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER", "ENTITY_TRANSLUCENT_CULL"},
            {"RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER", "ENTITY_TRANSLUCENT_EMISSIVE"},
            {"RENDERTYPE_ENTITY_NO_OUTLINE_SHADER", "ENTITY_NO_OUTLINE|ENTITY_TRANSLUCENT"}, {"RENDERTYPE_ENTITY_SHADOW_SHADER", "ENTITY_SHADOW"},
            {"RENDERTYPE_BEACON_BEAM_SHADER", "BEACON_BEAM_TRANSLUCENT"}, {"RENDERTYPE_EYES_SHADER", "EYES"},
            {"RENDERTYPE_ENERGY_SWIRL_SHADER", "ENERGY_SWIRL"}, {"RENDERTYPE_BREEZE_WIND_SHADER", "BREEZE_WIND"},
            {"RENDERTYPE_LEASH_SHADER", "LEASH"}, {"RENDERTYPE_WATER_MASK_SHADER", "WATER_MASK"},
            {"RENDERTYPE_OUTLINE_SHADER", "OUTLINE_CULL"}, {"RENDERTYPE_ARMOR_GLINT_SHADER", "GLINT"},
            {"RENDERTYPE_ARMOR_ENTITY_GLINT_SHADER", "GLINT"}, {"RENDERTYPE_GLINT_TRANSLUCENT_SHADER", "GLINT"},
            {"RENDERTYPE_GLINT_SHADER", "GLINT"}, {"RENDERTYPE_ENTITY_GLINT_SHADER", "GLINT"},
            {"RENDERTYPE_ENTITY_GLINT_DIRECT_SHADER", "GLINT"}, {"RENDERTYPE_CRUMBLING_SHADER", "CRUMBLING"},
            {"RENDERTYPE_TEXT_SHADER", "TEXT"}, {"RENDERTYPE_TEXT_INTENSITY_SHADER", "TEXT_GRAYSCALE|TEXT_INTENSITY"},
            {"RENDERTYPE_TEXT_SEE_THROUGH_SHADER", "TEXT_SEE_THROUGH"},
            // 26.1 and 26.2 keep the text background pipelines; 26.3 dropped them, so the best fit takes over there.
            {"RENDERTYPE_TEXT_BACKGROUND_SHADER", "TEXT_BACKGROUND"},
            {"RENDERTYPE_TEXT_BACKGROUND_SEE_THROUGH_SHADER", "TEXT_BACKGROUND_SEE_THROUGH"},
            {"RENDERTYPE_TEXT_INTENSITY_SEE_THROUGH_SHADER", "TEXT_GRAYSCALE_SEE_THROUGH|TEXT_INTENSITY_SEE_THROUGH"},
            {"RENDERTYPE_LIGHTNING_SHADER", "LIGHTNING"}, {"RENDERTYPE_END_PORTAL_SHADER", "END_PORTAL"},
            {"RENDERTYPE_END_GATEWAY_SHADER", "END_GATEWAY"}, {"RENDERTYPE_CLOUDS_SHADER", "CLOUDS"},
            {"RENDERTYPE_LINES_SHADER", "LINES"}, {"RENDERTYPE_GUI_SHADER", "GUI"}, {"RENDERTYPE_GUI_OVERLAY_SHADER", "GUI"},
            {"RENDERTYPE_GUI_TEXT_HIGHLIGHT_SHADER", "GUI_TEXT_HIGHLIGHT"},
            {"RENDERTYPE_GUI_GHOST_RECIPE_OVERLAY_SHADER", "GUI"},
        };
        for (String[] pair : pairs) map.put(pair[0], pair[1]);
        return Collections.unmodifiableMap(map);
    }

    /** The game itself, reached by reflection: its pipeline constants and the render setup builder. */
    static final class ReflectiveHost implements Host {
        private static final String PIPELINES = "net.minecraft.client.renderer.RenderPipelines";
        private static final String RENDER_SETUP = "net.minecraft.client.renderer.rendertype.RenderSetup";
        private static final String RENDER_TYPE = "net.minecraft.client.renderer.rendertype.RenderType";
        private static final String LAYERING_TRANSFORM = "net.minecraft.client.renderer.rendertype.LayeringTransform";
        private static final String OUTLINE_PROPERTY = "net.minecraft.client.renderer.rendertype.RenderSetup$OutlineProperty";
        private static final String OUTPUT_TARGET = "net.minecraft.client.renderer.rendertype.OutputTarget";
        private static final String RENDER_TYPES = "net.minecraft.client.renderer.rendertype.RenderTypes";

        private Map<String, Object> pipelines;
        private List<Object> vanillaTypes;

        @Override
        public synchronized Map<String, Object> pipelines() {
            if (pipelines != null) return pipelines;
            Map<String, Object> found = new LinkedHashMap<>();
            try {
                Class<?> owner = load(PIPELINES);
                for (Field field : owner.getFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || !field.getType().getSimpleName().equals("RenderPipeline")) continue;
                    found.put(field.getName(), field.get(null));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // An empty map leaves every old render type uncreated, each with its warning.
            }
            pipelines = found;
            return found;
        }

        @Override
        public synchronized Object fallback(Object format, Object topology) {
            for (Object type : vanillaTypes()) {
                Object typeFormat = call(type, "format");
                Object typeTopology = call(type, "primitiveTopology");
                if (typeTopology == null) typeTopology = call(type, "mode");
                if (format != null && format.equals(typeFormat) && topology != null && topology.equals(typeTopology)) return type;
            }
            return null;
        }

        // The game's own render types that take no arguments, made once.
        private List<Object> vanillaTypes() {
            if (vanillaTypes != null) return vanillaTypes;
            List<Object> found = new ArrayList<>();
            try {
                for (Method method : load(RENDER_TYPES).getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                            || !method.getReturnType().getSimpleName().equals("RenderType")) continue;
                    try {
                        Object type = method.invoke(null);
                        if (type != null) found.add(type);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError skipped) {
                        // A factory that cannot run here is simply not offered.
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // No vanilla types: a failed rebuild then draws nothing.
            }
            vanillaTypes = found;
            return found;
        }

        @Override
        public Object create(String name, Object pipeline, Setup setup) throws ReflectiveOperationException {
            Class<?> setupClass = load(RENDER_SETUP);
            Object builder = find(setupClass, "builder", 1).invoke(null, pipeline);
            if (setup.texture() != null) builder = find(builder.getClass(), "withTexture", 2).invoke(builder, "Sampler0", setup.texture());
            if (setup.lightmap()) builder = invoke(builder, "useLightmap");
            if (setup.overlay()) builder = invoke(builder, "useOverlay");
            if (setup.crumbling()) builder = invoke(builder, "affectsCrumbling");
            if (setup.sort()) builder = invoke(builder, "sortOnUpload");
            if (setup.layering() != null) {
                Object transform = load(LAYERING_TRANSFORM).getField(setup.layering()).get(null);
                builder = find(builder.getClass(), "setLayeringTransform", 1).invoke(builder, transform);
            }
            // 26.1 still sizes buffers per render type; 26.2 and 26.3 size them themselves.
            Method bufferSize = optional(builder.getClass(), "bufferSize", 1);
            if (bufferSize != null && setup.bufferSize() > 0) builder = bufferSize.invoke(builder, setup.bufferSize());
            builder = applyOutput(name, builder, setup.output());
            if (setup.affectsOutline()) {
                Object property = load(OUTLINE_PROPERTY).getField("AFFECTS_OUTLINE").get(null);
                builder = find(builder.getClass(), "setOutline", 1).invoke(builder, property);
            }
            Object renderSetup = invoke(builder, "createRenderSetup");
            // Vanilla keeps create package-private; NeoForge and Fabric API open it, reflection reaches it either way.
            Method create = load(RENDER_TYPE).getDeclaredMethod("create", String.class, setupClass);
            create.setAccessible(true);
            return create.invoke(null, name, renderSetup);
        }

        // 26.1 and 26.2 name the old targets they still have; 26.3 and missing targets draw into the main one.
        private static Object applyOutput(String name, Object builder, String output) throws ReflectiveOperationException {
            if (output == null || output.equals("MAIN_TARGET")) return builder;
            Method setOutput = optional(builder.getClass(), "setOutputTarget", 1);
            Object target = null;
            if (setOutput != null) {
                try {
                    target = load(OUTPUT_TARGET).getField(output).get(null);
                } catch (NoSuchFieldException | ClassNotFoundException missing) {
                    target = null;
                }
            }
            if (target == null) {
                warn(name, "drew into the " + output + " target, which this Minecraft version does not offer, so it draws into"
                        + " the main target.");
                return builder;
            }
            return setOutput.invoke(builder, target);
        }

        private static Method optional(Class<?> owner, String name, int parameters) {
            try {
                return find(owner, name, parameters);
            } catch (NoSuchMethodException absent) {
                return null;
            }
        }

        private static Object invoke(Object target, String name) throws ReflectiveOperationException {
            return find(target.getClass(), name, 0).invoke(target);
        }

        private static Method find(Class<?> owner, String name, int parameters) throws NoSuchMethodException {
            for (Method method : owner.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
            }
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }

        private static Class<?> load(String name) throws ClassNotFoundException {
            return Class.forName(name, true, LegacyRenderTypeResolver.class.getClassLoader());
        }
    }
}
