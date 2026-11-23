/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The resolver picks a host pipeline for an old render type description, on stand-ins for 26.3's pipelines. */
public class LegacyRenderTypeResolverTest {

    static final Object ENTITY = "ENTITY format";
    static final Object POSITION_COLOR = "POSITION_COLOR format";
    static final Object QUADS = "QUADS";

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void reset() {
        LegacyRenderTypeResolver.useHost(new LegacyRenderTypeResolver.ReflectiveHost());
    }

    @Test
    @DisplayName("a vanilla shader shard picks its 26.3 pipeline and the setup carries texture, lightmap and overlay")
    void vanillaShaderPicksItsPipeline() {
        FakeHost host = useFakeHost();
        Map<String, Object> state = state("RENDERTYPE_ENTITY_TRANSLUCENT_SHADER", "TRANSLUCENT_TRANSPARENCY", false);
        state.put(LegacyRenderTypeResolver.TEXTURE, "mod:textures/glow.png");
        state.put(LegacyRenderTypeResolver.LIGHTMAP, true);
        state.put(LegacyRenderTypeResolver.OVERLAY, true);

        Object type = LegacyRenderTypeResolver.create("mod_glow", ENTITY, QUADS, 256, true, true, state);

        assertEquals("mod_glow@ENTITY_TRANSLUCENT", type);
        LegacyRenderTypeResolver.Setup setup = host.setups.get(0);
        assertEquals("mod:textures/glow.png", setup.texture());
        assertTrue(setup.lightmap() && setup.overlay() && setup.crumbling() && setup.sort());
        assertEquals(256, LegacyRenderTypeResolver.bufferSize(type));
    }

    @Test
    @DisplayName("a custom shader falls back to the pipeline whose layout fits and whose settings match best")
    void customShaderUsesTheBestFit() {
        useFakeHost();
        Map<String, Object> opaque = state("custom_shader", "NO_TRANSPARENCY", true);
        opaque.put(LegacyRenderTypeResolver.CUSTOM_SHADER, true);
        Map<String, Object> blended = state("custom_shader", "TRANSLUCENT_TRANSPARENCY", false);
        blended.put(LegacyRenderTypeResolver.CUSTOM_SHADER, true);

        assertEquals("solid@ENTITY_SOLID", LegacyRenderTypeResolver.create("solid", ENTITY, QUADS, 256, false, false, opaque));
        assertEquals("glass@ENTITY_TRANSLUCENT", LegacyRenderTypeResolver.create("glass", ENTITY, QUADS, 256, false, false, blended));
    }

    @Test
    @DisplayName("a named pipeline whose layout does not fit is not used")
    void namedPipelineMustFitTheLayout() {
        useFakeHost();
        Map<String, Object> state = state("RENDERTYPE_ENTITY_TRANSLUCENT_SHADER", "TRANSLUCENT_TRANSPARENCY", false);

        Object type = LegacyRenderTypeResolver.create("lines", POSITION_COLOR, QUADS, 256, false, false, state);

        assertEquals("lines@DEBUG_QUADS", type, "the entity pipeline cannot draw position-color quads");
    }

    @Test
    @DisplayName("no fitting pipeline leaves the type uncreated, and the same request is answered from the cache")
    void unknownLayoutAndCache() {
        FakeHost host = useFakeHost();
        assertNull(LegacyRenderTypeResolver.create("odd", "UNKNOWN format", QUADS, 256, false, false, new LinkedHashMap<>()));

        Map<String, Object> state = state("RENDERTYPE_ENTITY_SOLID_SHADER", null, true);
        Object first = LegacyRenderTypeResolver.create("same", ENTITY, QUADS, 256, false, false, state);
        Object second = LegacyRenderTypeResolver.create("same", ENTITY, QUADS, 256, false, false, state);
        assertSame(first, second);
        assertEquals(1, host.setups.size(), "a cached type must not be created twice");
    }

    @Test
    @DisplayName("layering keeps the view offset, which 26.3 still has")
    void viewOffsetLayering() {
        FakeHost host = useFakeHost();
        Map<String, Object> state = state("RENDERTYPE_ENTITY_SOLID_SHADER", null, true);
        state.put(LegacyRenderTypeResolver.LAYERING, "VIEW_OFFSET_Z_LAYERING");

        LegacyRenderTypeResolver.create("offset", ENTITY, QUADS, 256, false, false, state);

        assertEquals("VIEW_OFFSET_Z_LAYERING", host.setups.get(0).layering());
    }

    @Test
    @DisplayName("a failed rebuild falls back to a vanilla type of the same layout, and to nothing when none exists")
    void failedRebuildFallsBack() {
        FakeHost host = useFakeHost();
        host.failCreate = true;
        Map<String, Object> state = state("RENDERTYPE_ENTITY_SOLID_SHADER", null, true);

        assertEquals("vanilla entity type", LegacyRenderTypeResolver.create("broken", ENTITY, QUADS, 256, false, false, state));
        assertNull(LegacyRenderTypeResolver.create("broken_quads", POSITION_COLOR, QUADS, 256, false, false,
                state("POSITION_COLOR_SHADER", null, true)));
    }

    @Test
    @DisplayName("the cache keeps types apart that share a name but not their buffer size or flags")
    void cacheKeyCoversEveryArgument() {
        FakeHost host = useFakeHost();
        Map<String, Object> state = state("RENDERTYPE_ENTITY_SOLID_SHADER", null, true);

        LegacyRenderTypeResolver.create("shared", ENTITY, QUADS, 256, false, false, state);
        LegacyRenderTypeResolver.create("shared", ENTITY, QUADS, 1024, false, false, state);
        LegacyRenderTypeResolver.create("shared", ENTITY, QUADS, 256, true, false, state);

        assertEquals(3, host.setups.size());
        assertEquals(1024, host.setups.get(1).bufferSize(), "the requested buffer size reaches the setup");
    }

    @Test
    @DisplayName("26.1 pipelines, with one layout, a vertex mode and one color target, are read as well")
    void reads261Pipelines() {
        LegacyRenderTypeResolver.useHost(new LegacyRenderTypeResolver.Host() {
            @Override
            public Map<String, Object> pipelines() {
                Map<String, Object> pipelines = new LinkedHashMap<>();
                pipelines.put("ENTITY_SOLID", new Pipeline261(ENTITY, false));
                pipelines.put("ENTITY_TRANSLUCENT", new Pipeline261(ENTITY, true));
                return pipelines;
            }

            @Override
            public Object create(String name, Object pipeline, LegacyRenderTypeResolver.Setup setup) {
                return name + "@" + (((Pipeline261) pipeline).blend() ? "translucent" : "solid");
            }

            @Override
            public Object fallback(Object format, Object topology) {
                return null;
            }
        });
        Map<String, Object> glass = state("custom_shader", "TRANSLUCENT_TRANSPARENCY", true);
        glass.put(LegacyRenderTypeResolver.CUSTOM_SHADER, true);

        assertEquals("glass@translucent", LegacyRenderTypeResolver.create("glass", ENTITY, QUADS, 256, false, false, glass));
    }

    @Test
    @DisplayName("1.21.11 pipelines, with the blend function and depth function on the pipeline, are read as well")
    void reads12111Pipelines() {
        LegacyRenderTypeResolver.useHost(new LegacyRenderTypeResolver.Host() {
            @Override
            public Map<String, Object> pipelines() {
                Map<String, Object> pipelines = new LinkedHashMap<>();
                pipelines.put("ENTITY_CUTOUT", new Pipeline12111("cutout", ENTITY, false, "LEQUAL_DEPTH_TEST"));
                pipelines.put("ENTITY_CUTOUT_NO_CULL", new Pipeline12111("cutout_no_cull", ENTITY, false, "LEQUAL_DEPTH_TEST"));
                pipelines.put("ENTITY_SEE_THROUGH", new Pipeline12111("see_through", ENTITY, true, "NO_DEPTH_TEST"));
                return pipelines;
            }

            @Override
            public Object create(String name, Object pipeline, LegacyRenderTypeResolver.Setup setup) {
                for (Map.Entry<String, Object> entry : pipelines().entrySet()) {
                    if (entry.getValue().equals(pipeline)) return name + "@" + entry.getKey();
                }
                return name;
            }

            @Override
            public Object fallback(Object format, Object topology) {
                return null;
            }
        });
        // The 26.x name ENTITY_CUTOUT is the culled pipeline on 1.21.11, so the no-cull shader must not take it.
        assertEquals("plain@ENTITY_CUTOUT_NO_CULL", LegacyRenderTypeResolver.create("plain", ENTITY, QUADS, 256, false, false,
                state("RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER", null, false)));
        Map<String, Object> xray = state("custom_shader", "TRANSLUCENT_TRANSPARENCY", true);
        xray.put(LegacyRenderTypeResolver.CUSTOM_SHADER, true);
        xray.put(LegacyRenderTypeResolver.DEPTH, "NO_DEPTH_TEST");
        assertEquals("xray@ENTITY_SEE_THROUGH", LegacyRenderTypeResolver.create("xray", ENTITY, QUADS, 256, false, false, xray));
    }

    /** A stand-in for 1.21.11's RenderPipeline: blend and depth settings on the pipeline itself; the id keeps equal settings apart. */
    public record Pipeline12111(String id, Object format, boolean blend, String depth) {
        public Object getVertexFormat() {
            return format;
        }

        public Object getVertexFormatMode() {
            return QUADS;
        }

        public boolean isCull() {
            return true;
        }

        public Optional<Object> getBlendFunction() {
            return blend ? Optional.of("blend") : Optional.empty();
        }

        public String getDepthTestFunction() {
            return depth;
        }

        public boolean isWriteDepth() {
            return true;
        }
    }

    /** A stand-in for 26.1's RenderPipeline: one vertex layout, a vertex mode, and a single color target. */
    public record Pipeline261(Object format, boolean blend) {
        public Object getVertexFormat() {
            return format;
        }

        public Object getVertexFormatMode() {
            return QUADS;
        }

        public boolean isCull() {
            return true;
        }

        public Target getColorTargetState() {
            return new Target(blend ? Optional.of("blend") : Optional.empty());
        }

        public Depth getDepthStencilState() {
            return new Depth("LESS_THAN_OR_EQUAL", true);
        }
    }

    private static Map<String, Object> state(String shader, String transparency, boolean cull) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(LegacyRenderTypeResolver.SHADER, shader);
        if (transparency != null) state.put(LegacyRenderTypeResolver.TRANSPARENCY, transparency);
        state.put(LegacyRenderTypeResolver.CULL, cull);
        return state;
    }

    private FakeHost useFakeHost() {
        FakeHost host = new FakeHost();
        LegacyRenderTypeResolver.useHost(host);
        return host;
    }

    /** Three entity pipelines and one debug pipeline, as 26.3 declares them. */
    static final class FakeHost implements LegacyRenderTypeResolver.Host {
        final List<LegacyRenderTypeResolver.Setup> setups = new ArrayList<>();

        @Override
        public Map<String, Object> pipelines() {
            Map<String, Object> pipelines = new LinkedHashMap<>();
            pipelines.put("ENTITY_SOLID", new Pipeline(ENTITY, false, true));
            pipelines.put("ENTITY_TRANSLUCENT", new Pipeline(ENTITY, true, false));
            pipelines.put("ENTITY_CUTOUT", new Pipeline(ENTITY, false, false));
            pipelines.put("DEBUG_QUADS", new Pipeline(POSITION_COLOR, true, false));
            return pipelines;
        }

        boolean failCreate;

        @Override
        public Object fallback(Object format, Object topology) {
            return ENTITY.equals(format) ? "vanilla entity type" : null;
        }

        @Override
        public Object create(String name, Object pipeline, LegacyRenderTypeResolver.Setup setup) {
            if (failCreate) throw new IllegalStateException("render setup refused");
            setups.add(setup);
            for (Map.Entry<String, Object> entry : pipelines().entrySet()) {
                if (entry.getValue().equals(pipeline)) return name + "@" + entry.getKey();
            }
            return name;
        }
    }

    /** A stand-in for 26.3's RenderPipeline with the getters the resolver reads. */
    public record Pipeline(Object format, boolean blend, boolean cull) {
        public Object getVertexFormatBinding(int index) {
            return format;
        }

        public Object getPrimitiveTopology() {
            return QUADS;
        }

        public boolean isCull() {
            return cull;
        }

        public List<Target> getColorTargetStates() {
            return List.of(new Target(blend ? Optional.of("blend") : Optional.empty()));
        }

        public Depth getDepthStencilState() {
            return new Depth("LESS_THAN_OR_EQUAL", true);
        }
    }

    /** A stand-in color target. */
    public record Target(Optional<Object> blendFunction) {}

    /** A stand-in depth state. */
    public record Depth(String depthTest, boolean writeDepth) {}
}
