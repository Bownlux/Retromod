/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static com.retromod.shim.neoforge.LegacyNeoForgeBakedModelBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** NeoForge 1.21.1's and Forge 1.20.1's dynamic model API on NeoForge 26.x: the names resolve and the model data calls reach the helpers. */
class LegacyNeoForgeBakedModelBridgeTest {

    private static final String LIBRARIES = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/";
    private static final String OLD_BAKED_MODEL = "net/minecraft/client/resources/model/BakedModel";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String OLD_RENDER_TYPE = "Lnet/minecraft/client/renderer/RenderType;";
    private static final String NEO_DATA = "Lnet/neoforged/neoforge/client/model/data/ModelData;";
    private static final String FORGE_DATA = "Lnet/minecraftforge/client/model/data/ModelData;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForgeTarget() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("A NeoForge 1.21.1 backpack model implements the rebuilt IDynamicBakedModel and its model data calls reach the helpers")
    void neoForgeModel(String host) {
        registerHostShims(host);
        Shape neo = new Shape("net/neoforged/neoforge/", NEO_DATA, "Lnet/minecraft/resources/ResourceLocation;");
        ClassNode model = transform(neo, "1.21.1");

        assertEquals(List.of(DYNAMIC), model.interfaces, "the backpack model implements the rebuilt interface, which extends the block model");
        assertEquals(expectedCalls(), calls(model, "draw"));
        assertEquals(List.of(GEOMETRY_EVENT + ".register(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V"),
                calls(model, "onLoaders"), "the geometry loader listener links against the stand-in event");
        assertEquals("(L" + GEOMETRY_EVENT + ";)V", method(model, "onLoaders").desc);
        assertEquals("[" + DYNAMIC + "]", instanceChecks(model, "shapes").toString(), "Sophisticated Backpacks' shape check");
        for (String rebuilt : List.of(DYNAMIC, WRAPPER, CALLS, GEOMETRY_EVENT, SET, LAYERS)) {
            assertTrue(transformer.getSyntheticClasses().containsKey(rebuilt), rebuilt + " is embedded");
        }
    }

    @ParameterizedTest(name = "NeoForge {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("A Forge 1.20.1 model on NeoForge takes the same classes, with NeoForge's model data")
    void forgeModelOnNeoForge(String host) {
        registerHostShims(host);
        Shape forge = new Shape("net/minecraftforge/", FORGE_DATA, "Ljava/lang/String;");
        ClassNode model = transform(forge, "1.20.1");

        assertEquals(List.of(DYNAMIC), model.interfaces);
        assertEquals(expectedCalls(), calls(model, "draw"));
        assertEquals(List.of(GEOMETRY_EVENT + ".register(Ljava/lang/String;Ljava/lang/Object;)V"), calls(model, "onLoaders"));
        assertTrue(model.methods.stream().noneMatch(m -> m.desc.contains("net/minecraftforge/")), "no Forge model name is left");
    }

    @Test
    @DisplayName("Only a NeoForge 26.x host without the old model API takes the bridge")
    void gate() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Function<String, ClassNode> neo263 = host(ANCHOR, MODEL_DATA, "net/minecraft/client/renderer/item/ItemModel$Unbaked");
        assertTrue(hostTakesBridge(neo263), "a NeoForge 26.x client");
        assertTrue(hostTakesBridge(host(ANCHOR, MODEL_DATA)), "a NeoForge 26.x server, which reads no client class");
        assertFalse(hostTakesBridge(host(ANCHOR, MODEL_DATA, OLD_DYNAMIC)), "a NeoForge host that still has the old API");
        assertFalse(hostTakesBridge(host(ANCHOR, MODEL_DATA, "net/minecraft/client/renderer/item/ItemModel$Unbaked",
                "net/minecraft/client/renderer/entity/ItemRenderer")), "NeoForge 1.21.11, whose old BakedModel is not the block model");

        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertTrue(hostTakesBridge(name -> null), "offline: a NeoForge target from 26.1");
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertFalse(hostTakesBridge(name -> null), "offline: an older NeoForge target");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyNeoForgeBakedModelBridge.register(transformer, neo263);
        assertNull(transformer.getClassRedirects().get(OLD_DYNAMIC), "a Forge host keeps Retromod's Forge rebuild");
        assertFalse(transformer.getSyntheticClasses().containsKey(DYNAMIC));
    }

    @Test
    @DisplayName("Mixins into the rebuilt classes are dropped on NeoForge, and Forge keeps its own rule")
    void mixinTargets() {
        registerHostShims("26.3");
        assertEquals(OLD_SET, removedMixinTarget(List.of("mod/Other", OLD_SET), transformer::classRedirectForCurrentMod),
                "Embeddium's ChunkRenderTypeSet mixin would land on the mod's own copy");
        assertEquals(OLD_EXTENSION, removedMixinTarget(List.of(OLD_EXTENSION), transformer::classRedirectForCurrentMod),
                "an extension mixin would inject into Minecraft's block model");
        assertNull(removedMixinTarget(List.of("net/minecraft/client/renderer/block/dispatch/BlockStateModel"),
                transformer::classRedirectForCurrentMod), "a mixin into the host's own block model is not this bridge's");
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        assertNull(removedMixinTarget(List.of(FORGE_SET), transformer::classRedirectForCurrentMod));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.1.2.60-beta", "26.2.0.0-beta", "26.3.0.43-beta"})
    @DisplayName("Every NeoForge 26.x jar on this machine lost the old model API and takes the bridge")
    void realHosts(String version) throws Exception {
        Path client = Path.of(LIBRARIES + "net/neoforged/minecraft-client-patched/" + version + "/minecraft-client-patched-" + version + ".jar");
        Path universal = Path.of(LIBRARIES + "net/neoforged/neoforge/" + version + "/neoforge-" + version + "-universal.jar");
        Assumptions.assumeTrue(Files.isRegularFile(client) && Files.isRegularFile(universal), "no NeoForge " + version);
        try (ZipFile mc = new ZipFile(client.toFile()); ZipFile neo = new ZipFile(universal.toFile())) {
            Function<String, ClassNode> read = name -> {
                try {
                    var entry = mc.getEntry(name + ".class");
                    ZipFile from = mc;
                    if (entry == null) {
                        entry = neo.getEntry(name + ".class");
                        from = neo;
                    }
                    if (entry == null) return null;
                    ClassNode node = new ClassNode();
                    new ClassReader(from.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                    return node;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            for (String removed : REBUILT.keySet()) {
                if (removed.startsWith("net/neoforged/")) assertNull(read.apply(removed), version + " " + removed);
            }
            assertNotNull(read.apply(MODEL_DATA), version + " keeps NeoForge's model data");
            ClassNode blockModel = read.apply(BLOCK_STATE_MODEL);
            for (String member : List.of("getQuads", "getRenderTypes", "getParticleIcon", "getModelData", "useAmbientOcclusion")) {
                assertTrue(blockModel.methods.stream().noneMatch(m -> m.name.equals(member)), version + " BlockStateModel." + member);
            }
            assertTrue(hostTakesBridge(read), version);
        }
    }

    @Test
    @DisplayName("NeoForge 1.21.11 keeps old BakedModel apart from the block model, so it takes no bridge")
    void realNeoForge12111() throws Exception {
        Path client = Path.of(LIBRARIES + "net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar");
        Path universal = Path.of(LIBRARIES + "net/neoforged/neoforge/21.11.42/neoforge-21.11.42-universal.jar");
        Assumptions.assumeTrue(Files.isRegularFile(client) && Files.isRegularFile(universal), "no NeoForge 21.11.42");
        try (ZipFile mc = new ZipFile(client.toFile()); ZipFile neo = new ZipFile(universal.toFile())) {
            assertFalse(hostTakesBridge(name -> mc.getEntry(name + ".class") != null || neo.getEntry(name + ".class") != null
                    ? new ClassNode() : null));
        }
    }

    private static List<String> expectedCalls() {
        String model = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;";
        String data = "L" + MODEL_DATA + ";";
        String renderType = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
        return List.of(
                CALLS + ".getQuads(" + model + STATE + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;" + data + renderType
                        + ")Ljava/util/List;",
                CALLS + ".getRenderTypes(" + model + STATE + "Lnet/minecraft/util/RandomSource;" + data + ")L" + SET + ";",
                CALLS + ".getParticleIcon(" + model + data + ")Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;",
                CALLS + ".getModelData(" + model + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
                        + STATE + data + ")" + data,
                SET + ".of([" + renderType + ")L" + SET + ";",
                LAYERS + ".getRenderLayers(" + STATE + ")L" + SET + ";");
    }

    // What RetromodNeoForge registers on the host, with the Forge chain NeoForge also runs for Forge mods.
    private void registerHostShims(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private ClassNode transform(Shape shape, String sourceVersion) {
        transformer.setModSourceVersion(sourceVersion);
        try {
            ClassNode node = new ClassNode();
            new ClassReader(transformer.transformClass(shape.model(), "mod/Backpack.class")).accept(node, 0);
            return node;
        } finally {
            transformer.setModSourceVersion(null);
        }
    }

    /** One loader's names for the old model API: its package root, its model data and the geometry register's first argument. */
    private record Shape(String root, String data, String registerKey) {

        // Sophisticated Backpacks' model in one class: a dynamic model, the model data calls it makes on its parts, the
        // geometry loader listener, and the shape provider's check.
        byte[] model() {
            String set = "L" + root + "client/ChunkRenderTypeSet;";
            String event = root + "client/event/ModelEvent$RegisterGeometryLoaders";
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Backpack", null, "java/lang/Object",
                    new String[]{root + "client/model/IDynamicBakedModel"});
            MethodVisitor draw = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw", "()V", null, null);
            draw.visitCode();
            invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getQuads", "(" + STATE + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;"
                    + data + OLD_RENDER_TYPE + ")Ljava/util/List;");
            invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getRenderTypes", "(" + STATE + "Lnet/minecraft/util/RandomSource;" + data + ")" + set);
            invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getParticleIcon", "(" + data + ")Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;");
            invoke(draw, INVOKEINTERFACE, OLD_BAKED_MODEL, "getModelData", "(Lnet/minecraft/world/level/BlockAndTintGetter;"
                    + "Lnet/minecraft/core/BlockPos;" + STATE + data + ")" + data);
            invoke(draw, INVOKESTATIC, root + "client/ChunkRenderTypeSet", "of", "([" + OLD_RENDER_TYPE + ")" + set);
            invoke(draw, INVOKESTATIC, "net/minecraft/client/renderer/ItemBlockRenderTypes", "getRenderLayers", "(" + STATE + ")" + set);
            draw.visitInsn(RETURN);
            draw.visitMaxs(0, 0);
            draw.visitEnd();

            MethodVisitor loaders = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "onLoaders", "(L" + event + ";)V", null, null);
            loaders.visitCode();
            loaders.visitVarInsn(ALOAD, 0);
            loaders.visitInsn(ACONST_NULL);
            loaders.visitInsn(ACONST_NULL);
            loaders.visitMethodInsn(INVOKEVIRTUAL, event, "register", "(" + registerKey + "L" + root + "client/model/geometry/IGeometryLoader;)V",
                    false);
            loaders.visitInsn(RETURN);
            loaders.visitMaxs(0, 0);
            loaders.visitEnd();

            MethodVisitor shapes = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "shapes", "(L" + OLD_BAKED_MODEL + ";)Z", null, null);
            shapes.visitCode();
            shapes.visitVarInsn(ALOAD, 0);
            shapes.visitTypeInsn(INSTANCEOF, root + "client/model/IDynamicBakedModel");
            shapes.visitInsn(IRETURN);
            shapes.visitMaxs(0, 0);
            shapes.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        // Pushes a receiver when the call has one, a null or zero per argument, then drops the result.
        private static void invoke(MethodVisitor mv, int opcode, String owner, String name, String desc) {
            if (opcode != INVOKESTATIC) mv.visitInsn(ACONST_NULL);
            for (Type argument : Type.getArgumentTypes(desc)) {
                mv.visitInsn(argument.getSort() == Type.OBJECT || argument.getSort() == Type.ARRAY ? ACONST_NULL : ICONST_0);
            }
            mv.visitMethodInsn(opcode, owner, name, desc, opcode == INVOKEINTERFACE);
            Type result = Type.getReturnType(desc);
            if (result.getSort() != Type.VOID) mv.visitInsn(result.getSize() == 2 ? POP2 : POP);
        }
    }

    private static List<String> calls(ClassNode node, String name) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, name).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static List<String> instanceChecks(ClassNode node, String name) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : method(node, name).instructions) {
            if (insn instanceof TypeInsnNode type && insn.getOpcode() == INSTANCEOF) types.add(type.desc);
        }
        return types;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static Function<String, ClassNode> host(String... present) {
        Set<String> names = Set.of(present);
        return name -> names.contains(name) ? new ClassNode() : null;
    }
}
