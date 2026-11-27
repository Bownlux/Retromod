/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.testsupport.fakemc.frapi.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt renderer API drives an old model's emit methods inside 26.x's block draw, run against stand-ins for the host. */
class LegacyRendererRuntimeTest {

    private static final Path LEGACY = Path.of("target", "legacyapi-classes");
    private static final Path MAIN = Path.of("target", "classes");
    private static final String R = "com/retromod/legacyapi/fabric/client/renderer/";
    private static final String MESH = "net/fabricmc/fabric/api/client/renderer/v1/mesh/";
    private static final Map<Class<?>, String> HOST = new HashMap<>();
    private static final Map<Class<?>, String> REBUILT = new HashMap<>();
    private static final List<Class<?>> FIXTURES = List.of(FakeOldModels.class, FakeOldModels.Dynamic.class, FakeOldModels.Vanilla.class,
            FakeOldModels.Broken.class, FakeOldModels.LeavesTransform.class, FakeOldModels.Delegating.class, FakeOldModels.HostModel.class, FakeRandomImpl.class,
            FakeFluidHandlers.class);
    // Old handlers in classes Retromod's package cannot reach, named rather than referenced.
    private static final List<String> HIDDEN_FIXTURES = List.of(internal(FakeFluidHandlers.class) + "$TwoQuads",
            internal(FakeFluidHandlers.class) + "$FailsMidVertex");
    private static final String FLUID = "com.retromod.legacyapi.fabric.client.render.fluid.";

    static {
        HOST.put(FakeDirection.class, "net/minecraft/core/Direction");
        HOST.put(FakeChunkSectionLayer.class, "net/minecraft/client/renderer/chunk/ChunkSectionLayer");
        HOST.put(FakeTriState.class, "net/fabricmc/fabric/api/util/TriState");
        HOST.put(FakeShadeMode.class, MESH + "ShadeMode");
        HOST.put(FakeItemDisplayContext.class, "net/minecraft/world/item/ItemDisplayContext");
        HOST.put(FakeBlockPos.class, "net/minecraft/core/BlockPos");
        HOST.put(FakeBlockState.class, "net/minecraft/world/level/block/state/BlockState");
        HOST.put(FakeItemStack.class, "net/minecraft/world/item/ItemStack");
        HOST.put(FakeRenderType.class, "net/minecraft/client/renderer/rendertype/RenderType");
        HOST.put(FakeBlockAndTintGetter.class, "net/minecraft/client/renderer/block/BlockAndTintGetter");
        HOST.put(FakeIdentifier.class, "net/minecraft/resources/Identifier");
        HOST.put(FakeRandom.class, "net/minecraft/util/RandomSource");
        HOST.put(FakeSprite.class, "net/minecraft/client/renderer/texture/TextureAtlasSprite");
        HOST.put(FakeMaterial.class, "net/minecraft/client/resources/model/sprite/Material");
        HOST.put(FakeMaterial.Baked.class, "net/minecraft/client/resources/model/sprite/Material$Baked");
        HOST.put(FakeBakedQuad.class, "net/minecraft/client/resources/model/geometry/BakedQuad");
        HOST.put(FakeBakedQuad.MaterialInfo.class, "net/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo");
        HOST.put(FakeQuadView.class, MESH + "QuadView");
        HOST.put(FakeMutableQuadView.class, MESH + "MutableQuadView");
        HOST.put(FakeQuadEmitter.class, MESH + "QuadEmitter");
        HOST.put(FakeQuadTransform.class, MESH + "QuadTransform");
        HOST.put(FakeBlockStateModelPart.class, "net/minecraft/client/renderer/block/dispatch/BlockStateModelPart");
        HOST.put(FakeBlockStateModel.class, "net/minecraft/client/renderer/block/dispatch/BlockStateModel");
        HOST.put(FakeFabricBlockStateModel.class, "net/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel");
        HOST.put(FakeVertexConsumer.class, "com/mojang/blaze3d/vertex/VertexConsumer");
        HOST.put(FakeFluidState.class, "net/minecraft/world/level/material/FluidState");
        HOST.put(FakeFluidRenderer.class, "net/minecraft/client/renderer/block/FluidRenderer");
        HOST.put(FakeFluidRenderHandler.class, "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandler");
        REBUILT.put(FakeOldModelBase.class, R + "LegacyFabricBakedModel");
        REBUILT.put(FakeModelConsumer.class, R + "LegacyBakedModelConsumer");
        REBUILT.put(FakeContexts.class, R + "LegacyRenderContexts");
    }

    private ClassLoader game;

    @BeforeEach
    void loadGame() throws IOException {
        Map<String, String> rename = new HashMap<>();
        HOST.forEach((fake, name) -> rename.put(internal(fake), name));
        REBUILT.forEach((fake, name) -> rename.put(internal(fake), name));
        SimpleRemapper remapper = new SimpleRemapper(rename);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<Class<?>, String> fake : HOST.entrySet()) classes.put(fake.getValue(), remap(bytesOf(fake.getKey()), remapper));
        for (Class<?> fixture : FIXTURES) classes.put(internal(fixture), remap(bytesOf(fixture), remapper));
        for (String fixture : HIDDEN_FIXTURES) classes.put(fixture, remap(bytesOf(fixture), remapper));
        game = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String internal = name.replace('.', '/');
                byte[] bytes = classes.get(internal);
                try {
                    if (bytes == null && Files.exists(LEGACY.resolve(internal + ".class"))) bytes = Files.readAllBytes(LEGACY.resolve(internal + ".class"));
                    if (bytes == null && Files.exists(MAIN.resolve(internal + ".class"))) bytes = Files.readAllBytes(MAIN.resolve(internal + ".class"));
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    @Test
    @DisplayName("An old model's emitBlockQuads draws into the host's emitter, and the old context answers the host's cull test")
    void dynamicModelEmitsThroughTheOldContext() throws Exception {
        Object model = fixture("Dynamic");
        List<String> drawn = new ArrayList<>();
        emitQuads(model, emitter(drawn), face -> false);
        assertEquals(List.of("fromBakedQuad dynamic", "emit"), drawn);

        drawn.clear();
        emitQuads(model, emitter(drawn), face -> String.valueOf(face).equals("NORTH"));
        assertEquals(List.of(), drawn, "a culled north face leaves the old model's quad out");
    }

    @Test
    @DisplayName("A vanilla adapter draws its quads with their cull faces, skips culled sides, and keeps its ambient occlusion choice")
    void vanillaAdapterDrawsItsQuads() throws Exception {
        List<String> drawn = new ArrayList<>();
        emitQuads(fixture("Vanilla"), emitter(drawn), face -> false);
        assertEquals(List.of("fromBakedQuad north", "cullFace NORTH", "ambientOcclusion FALSE", "emit",
                "fromBakedQuad unculled", "cullFace null", "ambientOcclusion FALSE", "emit"), drawn);

        drawn.clear();
        emitQuads(fixture("Vanilla"), emitter(drawn), face -> String.valueOf(face).equals("NORTH"));
        assertEquals(List.of("fromBakedQuad unculled", "cullFace null", "ambientOcclusion FALSE", "emit"), drawn);
    }

    @Test
    @DisplayName("A model whose emit code fails draws its plain quads the host's way from then on instead of crashing the chunk")
    void brokenModelFallsBackToTheHostDraw() throws Exception {
        Object model = fixture("Broken");
        List<?> hostDraws = (List<?>) game.loadClass(HOST.get(FakeFabricBlockStateModel.class).replace('/', '.')).getField("HOST_DRAWS").get(null);
        List<String> drawn = new ArrayList<>();
        emitQuads(model, emitter(drawn), face -> false);
        emitQuads(model, emitter(drawn), face -> false);
        assertEquals(2, hostDraws.stream().filter(drawnModel -> drawnModel == model).count());
        assertEquals(List.of(), drawn);

        List<Object> parts = new ArrayList<>();
        Object random = game.loadClass(HOST.get(FakeRandom.class).replace('/', '.')).getMethod("create", long.class).invoke(null, 7L);
        method(model, "collectParts").invoke(model, random, parts);
        assertEquals(1, parts.size(), "the host's draw still gets the plain quads of a model whose emit failed");
    }

    @Test
    @DisplayName("A transform an old model pushed and left when its code failed comes off the emitter before the host's draw")
    void failedModelLeavesNoTransform() throws Exception {
        List<String> drawn = new ArrayList<>();
        emitQuads(fixture("LeavesTransform"), emitter(drawn), face -> false);
        assertEquals(List.of("pushTransform null", "popTransform"), drawn);
    }

    @Test
    @DisplayName("The old context's model consumer draws another model through that model's own 26.x draw")
    void modelConsumerDrawsAnotherModel() throws Exception {
        Object model = fixture("Delegating");
        Object inner = model.getClass().getField("inner").get(model);
        List<?> hostDraws = (List<?>) game.loadClass(HOST.get(FakeFabricBlockStateModel.class).replace('/', '.')).getField("HOST_DRAWS").get(null);
        emitQuads(model, emitter(new ArrayList<>()), face -> false);
        assertTrue(hostDraws.contains(inner), "the inner model drew through its own emitQuads");
    }

    @Test
    @DisplayName("Block model parts come from the old model's quads, so the host can read an old model without a level")
    void partsComeFromTheOldQuads() throws Exception {
        Object model = fixture("Vanilla");
        List<Object> parts = new ArrayList<>();
        Object random = game.loadClass(HOST.get(FakeRandom.class).replace('/', '.')).getMethod("create", long.class).invoke(null, 7L);
        method(model, "collectParts").invoke(model, random, parts);
        assertEquals(1, parts.size());
        Object part = parts.get(0);
        Class<?> direction = game.loadClass(HOST.get(FakeDirection.class).replace('/', '.'));
        Object north = direction.getField("NORTH").get(null);
        List<?> quads = (List<?>) method(part, "getQuads").invoke(part, north);
        assertEquals("north", quads.get(0).getClass().getField("name").get(quads.get(0)));
        assertEquals(false, method(part, "useAmbientOcclusion").invoke(part));
        assertEquals(1, method(model, "materialFlags").invoke(model), "the flags of its quads, one of them translucent");
        Object empty = fixture("LeavesTransform");
        assertEquals(3, method(empty, "materialFlags").invoke(empty), "a model with no quads to read reports every flag");
    }

    @Test
    @DisplayName("An old material sets the quad's layer, glow, occlusion and shading, with 26.3's shade override where diffuseShade is gone")
    void materialsSetQuadProperties() throws Exception {
        Class<?> finderType = game.loadClass((R + "LegacyRenderMaterials$Finder").replace('/', '.'));
        Object finder = finderType.getConstructor().newInstance();
        Class<?> blend = game.loadClass((R + "LegacyBlendMode").replace('/', '.'));
        finderType.getMethod("blendMode", blend).invoke(finder, blend.getField("CUTOUT_MIPPED").get(null));
        finderType.getMethod("emissive", boolean.class).invoke(finder, true);
        finderType.getMethod("disableDiffuse", boolean.class).invoke(finder, true);
        Object material = finderType.getMethod("find").invoke(finder);
        List<String> applied = new ArrayList<>();
        Class<?> materials = game.loadClass((R + "LegacyRenderMaterials").replace('/', '.'));
        Class<?> mutable = game.loadClass((MESH + "MutableQuadView").replace('/', '.'));
        materials.getMethod("apply", mutable, Object.class).invoke(null, emitter(applied), material);
        assertEquals(List.of("chunkLayer CUTOUT", "emissive true", "ambientOcclusion DEFAULT", "shadeMode ENHANCED",
                "shadeDirectionOverride UP"), applied);
        assertSame(material, finderType.getMethod("find").invoke(finder), "one material per set of properties");
    }

    @Test
    @DisplayName("A fluid quad drawn with stand-in sprites takes the own sprite of the stand-in that holds its center")
    void fluidCoordinatesMoveToTheOwnSprites() throws Exception {
        Class<?> sprite = game.loadClass(HOST.get(FakeSprite.class).replace('/', '.'));
        Object[] standIn = (Object[]) java.lang.reflect.Array.newInstance(sprite, 2);
        Object[] own = (Object[]) java.lang.reflect.Array.newInstance(sprite, 2);
        standIn[0] = sprite.getConstructor(float.class, float.class, float.class, float.class).newInstance(0f, 0.1f, 0f, 0.1f);
        standIn[1] = sprite.getConstructor(float.class, float.class, float.class, float.class).newInstance(0.1f, 0.2f, 0f, 0.1f);
        own[0] = sprite.getConstructor(float.class, float.class, float.class, float.class).newInstance(0.5f, 0.6f, 0.5f, 0.6f);
        own[1] = sprite.getConstructor(float.class, float.class, float.class, float.class).newInstance(0.7f, 0.9f, 0f, 0.1f);
        Class<?> consumer = game.loadClass(HOST.get(FakeVertexConsumer.class).replace('/', '.'));
        List<String> written = new ArrayList<>();
        Object out = Proxy.newProxyInstance(game, new Class<?>[]{consumer}, recorder(written));
        Class<?> quadsType = game.loadClass(FLUID + "LegacyFluidQuads");
        var constructor = quadsType.getDeclaredConstructor(consumer, standIn.getClass(), own.getClass());
        constructor.setAccessible(true);
        Object quads = constructor.newInstance(out, standIn, own);

        // A quad in the second stand-in whose left corners sit on the edge it shares with the first.
        quad(consumer, quads, 0.1f, 0f, 0.2f, 0f, 0.2f, 0.1f, 0.1f, 0.1f);
        // A quad outside both stand-ins keeps its coordinates.
        quad(consumer, quads, 0.9f, 0.9f, 0.95f, 0.9f, 0.95f, 0.95f, 0.9f, 0.95f);
        consumer.getMethod("addVertex", float.class, float.class, float.class).invoke(quads, 0f, 0f, 0f);
        assertTrue(written.isEmpty(), "nothing reaches the builder before the draw is done");
        Method commit = quadsType.getDeclaredMethod("commit");
        commit.setAccessible(true);
        assertEquals(true, commit.invoke(quads), "the vertex left unfinished at the end is dropped");

        assertEquals(8, written.stream().filter(line -> line.startsWith("addVertex")).count());
        assertEquals(List.of("setUv 0.7 0.0", "setUv 0.9 0.0", "setUv 0.9 0.1", "setUv 0.7 0.1",
                "setUv 0.9 0.9", "setUv 0.95 0.9", "setUv 0.95 0.95", "setUv 0.9 0.95"),
                written.stream().filter(line -> line.startsWith("setUv")).toList());
    }

    @Test
    @DisplayName("An old fluid handler that fails in the middle of a vertex leaves the section's builder clean")
    void failedFluidDrawLeavesTheBuilderClean() throws Exception {
        StrictBuilder builder = new StrictBuilder(game.loadClass(HOST.get(FakeVertexConsumer.class).replace('/', '.')));
        Object handler = fluidHandler("FailsMidVertex");
        renderFluid(handler, builder.proxy);
        // Once it failed, the handler's own draw is not asked again.
        renderFluid(handler, builder.proxy);
        assertEquals(0, builder.vertices, "a draw that stopped partway passes on none of its quads");
        builder.assertWhole();
    }

    @Test
    @DisplayName("An old fluid handler in a class Retromod's package cannot reach draws its own whole quads")
    void hiddenFluidHandlerDrawsItsOwnQuads() throws Exception {
        StrictBuilder builder = new StrictBuilder(game.loadClass(HOST.get(FakeVertexConsumer.class).replace('/', '.')));
        renderFluid(fluidHandler("TwoQuads"), builder.proxy);
        assertEquals(8, builder.vertices);
        builder.assertWhole();
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    private Object fluidHandler(String name) throws Exception {
        var constructor = game.loadClass(FakeFluidHandlers.class.getName() + "$" + name).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    // LegacyFluidRenders.renderFluid as a translated mod calls it on another handler, with no fluid draw in progress.
    private void renderFluid(Object handler, Object consumer) throws Exception {
        for (Method method : game.loadClass(FLUID + "LegacyFluidRenders").getMethods()) {
            if (method.getName().equals("renderFluid")) {
                method.invoke(null, handler, null, null, consumer, null, null);
                return;
            }
        }
        throw new AssertionError("LegacyFluidRenders has no renderFluid");
    }

    private static void quad(Class<?> consumer, Object quads, float... uvs) throws Exception {
        Method addVertex = consumer.getMethod("addVertex", float.class, float.class, float.class);
        Method setUv = consumer.getMethod("setUv", float.class, float.class);
        for (int vertex = 0; vertex < 4; vertex++) {
            addVertex.invoke(quads, (float) vertex, 0f, 0f);
            setUv.invoke(quads, uvs[vertex * 2], uvs[vertex * 2 + 1]);
        }
    }

    /** A builder that fails as 26.3's does when a vertex starts before the last one has color, texture coordinates and light. */
    private static final class StrictBuilder implements InvocationHandler {
        private static final List<String> NEEDED = List.of("setColor", "setUv", "setUv2");
        final Object proxy;
        int vertices;
        private final java.util.Set<String> open = new java.util.HashSet<>();

        StrictBuilder(Class<?> consumer) {
            proxy = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[]{consumer}, this);
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return method.getName().equals("hashCode") ? 0 : method.invoke(this, args);
            if (method.getName().equals("addVertex")) {
                if (vertices > 0 && !open.containsAll(NEEDED)) throw new IllegalStateException("Missing elements in vertex");
                vertices++;
                open.clear();
            } else {
                open.add(method.getName());
            }
            return self;
        }

        void assertWhole() {
            assertEquals(0, vertices % 4, "the builder holds whole quads");
            assertTrue(vertices == 0 || open.containsAll(NEEDED), "the last vertex is complete");
        }
    }

    private Object fixture(String name) throws Exception {
        return game.loadClass(FakeOldModels.class.getName() + "$" + name).getConstructor().newInstance();
    }

    private void emitQuads(Object model, Object emitter, Predicate<Object> cullTest) throws Exception {
        Object random = game.loadClass(HOST.get(FakeRandom.class).replace('/', '.')).getMethod("create", long.class).invoke(null, 1L);
        method(model, "emitQuads").invoke(model, emitter, null, null, null, random, cullTest);
    }

    private Object emitter(List<String> drawn) throws ClassNotFoundException {
        Class<?> emitter = game.loadClass((MESH + "QuadEmitter").replace('/', '.'));
        return Proxy.newProxyInstance(game, new Class<?>[]{emitter}, recorder(drawn));
    }

    // Records each call with its arguments, quads by name, and answers the proxy itself so calls chain.
    private static InvocationHandler recorder(List<String> calls) {
        return (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return method.getName().equals("hashCode") ? 0 : method.invoke(calls, args);
            StringBuilder line = new StringBuilder(method.getName());
            for (Object arg : args == null ? new Object[0] : args) line.append(' ').append(describe(arg));
            calls.add(line.toString());
            Class<?> result = method.getReturnType();
            if (result == boolean.class) return false;
            if (result == int.class) return 0;
            return result.isInstance(proxy) ? proxy : null;
        };
    }

    private static String describe(Object arg) {
        if (arg == null) return "null";
        if (arg instanceof Float f) return String.valueOf(Math.round(f * 1000f) / 1000f);
        try {
            return String.valueOf(arg.getClass().getField("name").get(arg));
        } catch (ReflectiveOperationException notAQuad) {
            return String.valueOf(arg);
        }
    }

    private static Method method(Object target, String name) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name)) continue;
            // A rebuilt part class is package-private, so its public methods are opened for the call.
            method.setAccessible(true);
            return method;
        }
        throw new AssertionError(target.getClass().getName() + " has no " + name);
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        return bytesOf(internal(type));
    }

    private static byte[] bytesOf(String internalName) throws IOException {
        try (InputStream in = LegacyRendererRuntimeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in.readAllBytes();
        }
    }
}
