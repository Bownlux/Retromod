/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.LoaderApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Batches D1, D2, V0 and R2: old Fabric renderer, model loading and fluid handler code, as an old mod compiled it, on 26.x. */
class ModelRenderRowsTest {

    private static final String OLD = "net/fabricmc/fabric/api/renderer/v1/";
    private static final String LOADING = "net/fabricmc/fabric/api/client/model/loading/v1/";
    private static final String REBUILT = "com/retromod/legacyapi/fabric/client/";
    private static final String BAKED_MODEL = "net/minecraft/class_1087";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    @DisplayName("An old model that implements BakedModel and FabricBakedModel gets the rebuilt model defaults and the host's model interface")
    void oldModelGetsTheRebuiltDefaults(String host) {
        prepare(host, true);
        transformer.setModSourceVersion("1.21.1");
        ClassNode model = translate(oldModel("fixture/frapi/OldModel"));
        assertTrue(model.interfaces.contains(REBUILT + "renderer/LegacyFabricBakedModel"), model.interfaces.toString());
        assertTrue(model.interfaces.contains("net/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel"),
                model.interfaces.toString());
        // The old context calls inside go to the rebuilt context helper, which counts the model's transforms.
        assertTrue(calls(model).contains(REBUILT + "renderer/LegacyRenderContexts.getEmitter"), calls(model).toString());
        assertTrue(calls(model).contains(REBUILT + "renderer/LegacyRenderContexts.pushTransform"), calls(model).toString());
        assertTrue(calls(model).contains(REBUILT + "renderer/LegacyRenderContexts.popTransform"), calls(model).toString());
    }

    @Test
    @DisplayName("A model from a mod built for 1.21.5 or newer keeps the host's model interface as it is")
    void currentModelKeepsTheHostInterface() {
        prepare("26.3", true);
        transformer.setModSourceVersion("1.21.11");
        ClassNode model = translate(oldModel("fixture/frapi/CurrentModel"));
        assertFalse(model.interfaces.contains(REBUILT + "renderer/LegacyFabricBakedModel"), model.interfaces.toString());
        assertTrue(calls(model).contains("net/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter.pushTransform"),
                "a current mod's transforms stay the emitter's own: " + calls(model));
    }

    @Test
    @DisplayName("Old renderer, model loading and fluid calls reach the rebuilt helpers")
    void callsReachTheRebuiltHelpers() {
        prepare("26.3", true);
        transformer.setModSourceVersion("1.20.1");
        ClassNode calls = translate(caller("fixture/frapi/Calls"));
        List<String> targets = calls(calls);
        assertTrue(targets.contains(REBUILT + "renderer/LegacyRenderer.materialFinder"), targets.toString());
        assertTrue(targets.contains(REBUILT + "model/LegacyModelLoading.addModels"), targets.toString());
        assertTrue(targets.contains(REBUILT + "model/LegacyModelLoading.modifyModelAfterBake"), targets.toString());
        assertTrue(fields(calls).contains(REBUILT + "renderer/LegacyRendererAccess.INSTANCE"), fields(calls).toString());
        assertTrue(fields(calls).contains(REBUILT + "render/fluid/LegacyFluidHandlerRegistry.INSTANCE"), fields(calls).toString());
        for (String name : List.of(REBUILT + "renderer/LegacyFabricBakedModel", REBUILT + "model/LegacyModelLoading",
                REBUILT + "render/fluid/LegacyFluidHandlerRegistry")) {
            assertTrue(transformer.getSyntheticClasses().containsKey(name), name + " is registered to travel with the mod");
        }
    }

    @Test
    @DisplayName("A Fabric 1.20.x bake listener's id() reaches the rebuilt bake contexts, which declare it")
    void bakeContextId() throws Exception {
        prepare("26.3", true);
        transformer.setModSourceVersion("1.20.1");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/patchouli/Client", null, "java/lang/Object", null);
        for (String phase : List.of("AfterBake", "BeforeBake")) {
            String context = LOADING + "ModelModifier$" + phase + "$Context";
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "id" + phase, "(L" + context + ";)Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, context, "id", "()Lnet/minecraft/class_2960;", true);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        List<String> calls = calls(translate(cw.toByteArray()));
        for (String phase : List.of("AfterBake", "BeforeBake")) {
            String rebuilt = REBUILT + "model/Legacy" + phase + "$Context";
            assertTrue(calls.contains(rebuilt + ".id"), calls.toString());
            ClassNode context = new ClassNode();
            try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(rebuilt + ".class")) {
                assertNotNull(in, rebuilt + " is built");
                new ClassReader(in).accept(context, 0);
            }
            assertTrue(context.methods.stream().anyMatch(m -> m.name.equals("id") && m.desc.equals("()Lnet/minecraft/resources/Identifier;")),
                    rebuilt + " declares the 1.20.x id()");
        }
    }

    @Test
    @DisplayName("An old plugin class gets initialize from its onInitializeModelLoader")
    void oldPluginGetsInitialize() {
        prepare("26.3", true);
        transformer.setModSourceVersion("1.21.1");
        ClassNode plugin = translate(plugin("fixture/frapi/OldPlugin"));
        assertTrue(plugin.interfaces.contains(REBUILT + "model/LegacyModelLoadingPlugin"), plugin.interfaces.toString());
    }

    @Test
    @DisplayName("With the loader API runtime off, old renderer types keep the stand-ins they had")
    void runtimeOffKeepsTheStandIns() {
        prepare("26.3", false);
        transformer.setModSourceVersion("1.21.1");
        ClassNode model = translate(oldModel("fixture/frapi/OffModel"));
        assertFalse(model.interfaces.contains(REBUILT + "renderer/LegacyFabricBakedModel"), model.interfaces.toString());
        assertFalse(calls(model).contains(REBUILT + "renderer/LegacyRenderContexts.getEmitter"), calls(model).toString());
    }

    @Test
    @DisplayName("Another Fabric host takes back the model interface replacement and keeps the Minecraft side's old model calls")
    void anotherHostTakesBackOnlyTheImplementsList() {
        prepare("26.3", true);
        String blockModel = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
        List<String> minecraftSide = callsKeyedOn(blockModel);
        assertFalse(minecraftSide.isEmpty(), "the Minecraft side redirects old model calls keyed on BlockStateModel");
        assertEquals(2, interfaceLines(blockModel).size(), interfaceLines(blockModel).toString());

        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", "1.21.8", LoaderApiContext.TransformPath.TEST, null));
        assertEquals(List.of(), interfaceLines(blockModel));
        assertEquals(minecraftSide, callsKeyedOn(blockModel), "taking back the implements list leaves calls on the type alone");
    }

    @Test
    @DisplayName("A later registration outside the runtime cannot replace the runtime's model interface replacement")
    void laterReplacementCannotWin() {
        prepare("26.3", true);
        String blockModel = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
        List<String> owned = interfaceLines(blockModel);
        transformer.registerInterfaceReplacement(blockModel, "fixture/frapi/OtherModelBridge");
        transformer.limitInterfaceReplacementToModsBefore(blockModel, "1.20");
        assertEquals(owned, interfaceLines(blockModel));
        transformer.setModSourceVersion("1.21.1");
        ClassNode model = translate(oldModel("fixture/frapi/ClaimedModel"));
        assertTrue(model.interfaces.contains(REBUILT + "renderer/LegacyFabricBakedModel"), model.interfaces.toString());
    }

    // ---- Setup --------------------------------------------------------------------------------------------------

    // Call redirects keyed on owner that go somewhere other than the rebuilt Fabric classes.
    private List<String> callsKeyedOn(String owner) {
        List<String> keys = new ArrayList<>();
        transformer.getMethodRedirects().forEach((key, target) -> {
            if (key.owner().equals(owner) && !target.owner().startsWith(REBUILT)) keys.add(key.name() + key.desc());
        });
        keys.sort(null);
        return keys;
    }

    private List<String> interfaceLines(String iface) {
        return transformer.registrationLines().stream()
                .filter(line -> line.contains("InterfaceRewrites") && line.contains(iface)).sorted().toList();
    }

    private void prepare(String host, boolean on) {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        runtime = on ? LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider())
                : LoaderApiTestSupport.runtimeOff(LoaderApiTestSupport.fabricProvider());
        RetromodPreLaunch.registerShimsForTransform(host);
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", host, LoaderApiContext.TransformPath.TEST, null));
    }

    private ClassNode translate(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, "fixture")).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
            }
        }
        return calls;
    }

    private static List<String> fields(ClassNode node) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name);
            }
        }
        return fields;
    }

    // ---- Fixtures, written as a 1.20.1 or 1.21.1 Fabric mod compiled them -----------------------------------------

    /** A baked model with an emitBlockQuads that asks its context for the emitter. */
    private static byte[] oldModel(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object",
                new String[]{BAKED_MODEL, OLD + "model/FabricBakedModel"});
        constructor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "emitBlockQuads", "(Lnet/minecraft/class_1920;Lnet/minecraft/class_2680;"
                + "Lnet/minecraft/class_2338;Ljava/util/function/Supplier;L" + OLD + "render/RenderContext;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD + "render/RenderContext", "getEmitter", "()L" + OLD + "mesh/QuadEmitter;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD + "render/RenderContext", "pushTransform",
                "(L" + OLD + "render/RenderContext$QuadTransform;)V", true);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD + "render/RenderContext", "popTransform", "()V", true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A client initializer that reaches the renderer, a plugin context and the fluid registry. */
    private static byte[] caller(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + LOADING + "ModelLoadingPlugin$Context;)V",
                null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, OLD + "RendererAccess", "INSTANCE", "L" + OLD + "RendererAccess;");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD + "RendererAccess", "getRenderer", "()L" + OLD + "Renderer;", true);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OLD + "Renderer", "materialFinder", "()L" + OLD + "material/MaterialFinder;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/class_2960");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOADING + "ModelLoadingPlugin$Context", "addModels", "([Lnet/minecraft/class_2960;)V", true);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOADING + "ModelLoadingPlugin$Context", "modifyModelAfterBake",
                "()Lnet/fabricmc/fabric/api/event/Event;", true);
        mv.visitInsn(Opcodes.POP);
        String registry = "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry";
        mv.visitFieldInsn(Opcodes.GETSTATIC, registry, "INSTANCE", "L" + registry + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A model loading plugin class with the 1.21.1 method name. */
    private static byte[] plugin(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[]{LOADING + "ModelLoadingPlugin"});
        constructor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitializeModelLoader", "(L" + LOADING + "ModelLoadingPlugin$Context;)V",
                null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
