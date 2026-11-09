/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.shim.forge.LegacyForgeBakedModelBridgeTest.calls;
import static com.retromod.shim.forge.LegacyForgeBakedModelBridgeTest.types;
import static com.retromod.shim.forge.LegacyForgeGeometryBridge.*;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.host;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.method;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.node;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old geometry loaders and the model baking calls old geometry makes point at classes rebuilt inside the mod, on Forge 26.2 and newer only. */
class LegacyForgeGeometryBridgeTest {

    static final String LOADER = "mod/BarrelLoader";
    static final String GEOMETRY = "mod/BarrelGeometry";
    static final String CALLS = "mod/GeometryCalls";
    static final String BOOK_OVERRIDES = "mod/BookOverrides";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String STATE = "Lnet/minecraft/client/renderer/block/dispatch/ModelState;";
    private static final String FUNCTION = "Ljava/util/function/Function;";
    private static final String OLD_MODEL = "L" + LegacyForgeBakedModelBridge.OLD_BAKED_MODEL + ";";
    private static final String MODEL = "L" + LegacyForgeBakedModelBridge.BAKED_MODEL + ";";
    private static final String BAKER = "L" + MODEL_BAKER + ";";
    private static final String UNBAKED = "L" + UNBAKED_MODEL + ";";
    private static final String BLOCK = "L" + BLOCK_MODEL + ";";
    private static final String OLD_OVERRIDES = LegacyForgeBakedModelBridge.OLD_ITEM_OVERRIDES;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
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

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim registers the rebuilt geometry API, which carries on to Forge 26.3")
    void forgeHostsFrom262TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(GEOMETRY_LOADER, redirects.get(OLD_GEOMETRY_LOADER));
        assertEquals(UNBAKED_GEOMETRY, redirects.get(OLD_UNBAKED_GEOMETRY));
        assertEquals(BAKING_CONTEXT, redirects.get(OLD_BAKING_CONTEXT));
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(GEOMETRY_LOADER, UNBAKED_GEOMETRY, BAKING_CONTEXT, BAKERS, LEGACY_BLOCK_MODEL, INSTALLER,
                ROOT + "LegacyGeometryHooks", ROOT + "LegacyBuiltinLoaders", ROOT + "LegacyGeometryBakes",
                ROOT + "LegacyGeometryLoaderAdapter", ROOT + "LegacyUnbakedGeometryAdapter", ROOT + "LegacyNestedModels",
                ROOT + "LegacyStaticModel", ROOT + "LegacyGeometryContext")) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding");
        }
    }

    @Test
    @DisplayName("NeoForge, other loaders, Forge 1.21.11 and 26.1, and a Forge host that still has old geometry take nothing")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyForgeModelBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_GEOMETRY_LOADER), "NeoForge keeps its own geometry API");

        McReflect.setForceNeoForge(false);
        LegacyForgeModelBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_GEOMETRY_LOADER), "a Fabric or Quilt run takes no Forge bridge");

        McReflect.setForceForge(true);
        for (String older : List.of("1.21.11", "26.1")) {
            RetromodVersion.TARGET_MC_VERSION = older;
            LegacyForgeModelBridge.register(transformer);
            assertNull(transformer.getClassRedirects().get(OLD_GEOMETRY_LOADER), "Forge " + older + " is not a checked host");
        }

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        String group = LegacyForgeModelBridge.RENDER_TYPE_GROUP;
        String modify = LegacyForgeBakedModelBridge.MODIFY_BAKING_RESULT;
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, group, modify, REGISTER_GEOMETRY_LOADERS,
                OLD_GEOMETRY_LOADER, OLD_UNBAKED_GEOMETRY));
        assertNull(transformer.getClassRedirects().get(OLD_GEOMETRY_LOADER), "a host that still has IUnbakedGeometry keeps it");
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, group, modify, OLD_GEOMETRY_LOADER));
        assertNull(transformer.getClassRedirects().get(OLD_GEOMETRY_LOADER), "a host with no geometry loader event");
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, group, modify, REGISTER_GEOMETRY_LOADERS,
                OLD_GEOMETRY_LOADER));
        assertEquals(GEOMETRY_LOADER, transformer.getClassRedirects().get(OLD_GEOMETRY_LOADER), "Forge 26.2's own shape");
    }

    @Test
    @DisplayName("A Forge 1.20.1 geometry loader, its geometry and its registration reach the rebuilt classes")
    void oldGeometryTakesTheRebuiltClasses() {
        register("1.20.1");
        ClassNode loader = node(transformer.transformClass(loader(), LOADER + ".class"));
        assertEquals(List.of(GEOMETRY_LOADER), loader.interfaces);
        ClassNode geometry = node(transformer.transformClass(geometry(), GEOMETRY + ".class"));
        assertEquals(List.of(UNBAKED_GEOMETRY), geometry.interfaces);
        MethodNode bake = method(geometry, "bake");
        assertEquals("(L" + BAKING_CONTEXT + ";" + BAKER + FUNCTION + STATE + "L" + ROOT + "ItemOverrides;" + ID + ")" + MODEL,
                bake.desc, "the bake the rebuilt interface declares");

        ClassNode out = node(transformer.transformClass(geometryCalls(), CALLS + ".class"));
        assertEquals(List.of(INSTALLER + ".register(L" + REGISTER_GEOMETRY_LOADERS + ";Ljava/lang/String;L" + GEOMETRY_LOADER + ";)V"),
                calls(method(out, "registerLoaders")), "register(String, loader) goes to the helper that finds the namespace");
        assertEquals(List.of(BAKERS + ".getModel(" + BAKER + ID + ")" + UNBAKED,
                BAKERS + ".resolveParents(" + UNBAKED + FUNCTION + ")V",
                BAKERS + ".bake(" + UNBAKED + BAKER + FUNCTION + STATE + ID + ")" + MODEL,
                BAKERS + ".bake(" + BAKER + ID + STATE + ")" + MODEL,
                BAKERS + ".getModelTextureGetter(" + BAKER + ")" + FUNCTION,
                BAKERS + ".getModel(L" + LegacyForgeBakedModelBridge.MODEL_BAKERY + ";" + ID + ")" + UNBAKED),
                calls(method(out, "nested")));
        String constructor = "(" + ID + "Ljava/util/List;Ljava/util/Map;Ljava/lang/Boolean;L" + UNBAKED_MODEL
                + "$GuiLight;Lnet/minecraft/client/resources/model/cuboid/ItemTransforms;Ljava/util/List;)";
        assertEquals(List.of(LEGACY_BLOCK_MODEL + ".create" + constructor + BLOCK,
                BAKERS + ".getElements(Ljava/lang/Object;)Ljava/util/List;",
                BAKERS + ".resolveParents(Ljava/lang/Object;" + FUNCTION + ")V",
                BAKERS + ".bake(Ljava/lang/Object;" + BAKER + FUNCTION + STATE + ID + ")" + MODEL),
                gameCalls(method(out, "jsonModel")), "the old JSON model is built and read through Retromod's own");
        assertFalse(types(method(out, "jsonModel")).contains(BLOCK_MODEL), "no new on the interface Minecraft 26.1 named BlockModel");
    }

    @Test
    @DisplayName("Calls spelled with the names a real 1.20.1 jar uses reach the helpers once the earlier renames ran")
    void oldSpellingsReachTheHelpers() {
        register("1.20.1");
        // The renames older shims register, which run in the same pass.
        transformer.registerClassRedirect("net/minecraft/resources/ResourceLocation", "net/minecraft/resources/Identifier");
        transformer.registerClassRedirect("net/minecraft/client/resources/model/ModelState",
                "net/minecraft/client/renderer/block/dispatch/ModelState");
        String location = "Lnet/minecraft/resources/ResourceLocation;";
        String oldState = "Lnet/minecraft/client/resources/model/ModelState;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/OldSpellings", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "nested", "(" + UNBAKED + BAKER + FUNCTION + oldState + location + ")V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, UNBAKED_MODEL, "bake", "(" + BAKER + FUNCTION + oldState + location + ")" + OLD_MODEL, true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = node(transformer.transformClass(cw.toByteArray(), "mod/OldSpellings.class"));
        assertEquals(List.of(BAKERS + ".bake(" + UNBAKED + BAKER + FUNCTION + STATE + ID + ")" + MODEL), calls(method(out, "nested")));
    }

    @Test
    @DisplayName("Forge 1.21's bakes without a location, and bakeUncached, reach the same helpers")
    void forge121BakesReachTheHelpers() {
        register("1.21.1");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Bakes121", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "bakes", "(" + UNBAKED + BAKER + FUNCTION + STATE + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, UNBAKED_MODEL, "bake", "(" + BAKER + FUNCTION + STATE + ")" + OLD_MODEL, true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, MODEL_BAKER, "bakeUncached", "(" + UNBAKED + STATE + ")" + OLD_MODEL, true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = node(transformer.transformClass(cw.toByteArray(), "mod/Bakes121.class"));
        assertEquals(List.of(BAKERS + ".bake(" + UNBAKED + BAKER + FUNCTION + STATE + ")" + MODEL,
                BAKERS + ".bakeUncached(" + BAKER + UNBAKED + STATE + ")" + MODEL), calls(method(out, "bakes")));
    }

    @Test
    @DisplayName("Patchouli's ItemOverrides(baker, model, list) super call reaches a constructor the rebuilt class has")
    void bookOverridesSuperCallLinks() throws Exception {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(bookOverrides(), BOOK_OVERRIDES + ".class"));
        String superCall = ROOT + "ItemOverrides.<init>(" + BAKER + BLOCK + "Ljava/util/List;)V";
        assertEquals(ROOT + "ItemOverrides", out.superName);
        assertTrue(calls(method(out, "<init>")).contains(superCall), calls(method(out, "<init>")).toString());
        ClassNode rebuilt = node(transformer.getSyntheticClasses().get(ROOT + "ItemOverrides"));
        assertTrue(rebuilt.methods.stream().anyMatch(m -> m.name.equals("<init>")
                && m.desc.equals("(" + BAKER + BLOCK + "Ljava/util/List;)V")), "the rebuilt ItemOverrides has that constructor");
    }

    @Test
    @DisplayName("A mod built for 26.2 keeps the host's geometry loader, context and register(Identifier, loader)")
    void newerModsKeepTheHostNames() {
        register("26.2");
        ClassNode loader = node(transformer.transformClass(loader(), LOADER + ".class"));
        assertEquals(List.of(OLD_GEOMETRY_LOADER), loader.interfaces);
        ClassNode out = node(transformer.transformClass(geometryCalls(), CALLS + ".class"));
        assertEquals(REGISTER_GEOMETRY_LOADERS + ".register(Ljava/lang/String;L" + OLD_GEOMETRY_LOADER + ";)V",
                calls(method(out, "registerLoaders")).get(0));
        assertEquals(MODEL_BAKER + ".getModel(" + ID + ")" + UNBAKED, calls(method(out, "nested")).get(0));

        register("26.1");
        ClassNode reused = node(transformer.transformClass(geometryCalls(), CALLS + ".class"));
        assertEquals(BLOCK_MODEL + ".getElements()Ljava/util/List;", gameCalls(method(reused, "jsonModel")).get(1),
                "a mod built for 26.1 means the block render model by that name");
    }

    // ---- helpers ----

    // The calls outside the JDK, so the arguments built with List.of and Map.of drop out.
    private static List<String> gameCalls(MethodNode method) {
        return calls(method).stream().filter(call -> !call.startsWith("java/")).toList();
    }

    private void register(String modVersion) {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(modVersion);
    }

    /** A Forge 1.20.1 geometry loader after the SRG remap. */
    static byte[] loader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, LOADER, null, "java/lang/Object", new String[]{OLD_GEOMETRY_LOADER});
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "read",
                "(Lcom/google/gson/JsonObject;Lcom/google/gson/JsonDeserializationContext;)L" + OLD_UNBAKED_GEOMETRY + ";", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge 1.20.1 geometry with its bake. */
    static byte[] geometry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, GEOMETRY, null, "java/lang/Object", new String[]{OLD_UNBAKED_GEOMETRY});
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "bake", "(L" + OLD_BAKING_CONTEXT + ";" + BAKER + FUNCTION + STATE
                + "L" + OLD_OVERRIDES + ";" + ID + ")" + OLD_MODEL, null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Old code around geometry: the loader registration, nested models looked up and baked as lootr and
     * TravelersBackpack do, and the JSON model Sophisticated Backpacks builds and reads.
     */
    static byte[] geometryCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CALLS, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerLoaders", "(L" + REGISTER_GEOMETRY_LOADERS + ";L"
                + OLD_GEOMETRY_LOADER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("barrel");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_GEOMETRY_LOADERS, "register", "(Ljava/lang/String;L" + OLD_GEOMETRY_LOADER + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        String bakery = "L" + LegacyForgeBakedModelBridge.MODEL_BAKERY + ";";
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "nested", "(" + BAKER + ID + FUNCTION + STATE + bakery + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, MODEL_BAKER, "getModel", "(" + ID + ")" + UNBAKED, true);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, UNBAKED_MODEL, "resolveParents", "(" + FUNCTION + ")V", true);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, UNBAKED_MODEL, "bake", "(" + BAKER + FUNCTION + STATE + ID + ")" + OLD_MODEL, true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, MODEL_BAKER, "bake", "(" + ID + STATE + ")" + OLD_MODEL, true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, MODEL_BAKER, "getModelTextureGetter", "()" + FUNCTION, true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyForgeBakedModelBridge.MODEL_BAKERY, "getModel", "(" + ID + ")" + UNBAKED, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        String guiLight = "L" + UNBAKED_MODEL + "$GuiLight;";
        String transforms = "Lnet/minecraft/client/resources/model/cuboid/ItemTransforms;";
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "jsonModel", "(" + BAKER + ID + FUNCTION + STATE + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, BLOCK_MODEL);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        mv.visitMethodInsn(INVOKESPECIAL, BLOCK_MODEL, "<init>", "(" + ID + "Ljava/util/List;Ljava/util/Map;Ljava/lang/Boolean;"
                + guiLight + transforms + "Ljava/util/List;)V", false);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK_MODEL, "getElements", "()Ljava/util/List;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK_MODEL, "resolveParents", "(" + FUNCTION + ")V", false);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK_MODEL, "bake", "(" + BAKER + FUNCTION + STATE + ID + ")" + OLD_MODEL, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Patchouli's book model overrides: an ItemOverrides subclass built from a baker, the missing model and no overrides. */
    static byte[] bookOverrides() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_SUPER, BOOK_OVERRIDES, null, OLD_OVERRIDES, null);
        MethodVisitor mv = cw.visitMethod(0, "<init>", "(" + BAKER + BLOCK + "Ljava/util/List;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, OLD_OVERRIDES, "<init>", "(" + BAKER + BLOCK + "Ljava/util/List;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
