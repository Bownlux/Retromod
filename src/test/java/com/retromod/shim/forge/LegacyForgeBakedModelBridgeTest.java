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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.shim.forge.LegacyForgeBakedModelBridge.*;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.host;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.node;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old BakedModel, model location and baking-result calls point at classes rebuilt inside the mod, on Forge 26.2 and newer only. */
class LegacyForgeBakedModelBridgeTest {

    static final String CALLER = "mod/BakedModelCalls";
    static final String WRAPPER = "mod/GlowingWrapper";
    static final String DYNAMIC = "mod/DynamicModel";
    static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    // Old code names Identifier by the time these redirects run: Retromod's 1.21.11 rename moved ResourceLocation.
    static final String OLD_ID = IDENTIFIER;
    private static final String MODEL = "L" + OLD_BAKED_MODEL + ";";
    private static final String LOCATION = "L" + OLD_MODEL_LOCATION + ";";

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
    @DisplayName("The Forge 26.2 shim registers the rebuilt model API, which carries on to Forge 26.3")
    void forgeHostsFrom262TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(BAKED_MODEL, redirects.get(OLD_BAKED_MODEL));
        assertEquals(BAKED_MODEL, redirects.get(OLD_FORGE_BAKED_MODEL));
        assertEquals(ROOT + "IDynamicBakedModel", redirects.get(OLD_DYNAMIC_MODEL));
        assertEquals(ROOT + "BakedModelWrapper", redirects.get(OLD_WRAPPER));
        assertEquals(ROOT + "ItemOverrides", redirects.get(OLD_ITEM_OVERRIDES));
        assertEquals(IDENTIFIER, redirects.get(OLD_MODEL_LOCATION), "an old model location is an Identifier again");
        assertEquals(REGISTER_ADDITIONAL, redirects.get(OLD_REGISTER_ADDITIONAL));
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(BAKED_MODEL, LOCATIONS, MAPS, REGISTER_ADDITIONAL, INSTALLER, ROOT + "LegacyBlockStateModel",
                ROOT + "LegacyBlockStateModelView", ROOT + "LegacyItemModel", ROOT + "LegacyItemModelView",
                ROOT + "LegacyModelEventHooks")) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding");
        }
    }

    @Test
    @DisplayName("NeoForge, other loaders, Forge 1.21.11 and 26.1, and a Forge host that still has the old model API take nothing")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyForgeModelBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_BAKED_MODEL), "NeoForge keeps its own model API");

        McReflect.setForceNeoForge(false);
        LegacyForgeModelBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_BAKED_MODEL), "a Fabric or Quilt run takes no Forge bridge");

        McReflect.setForceForge(true);
        for (String older : List.of("1.21.11", "26.1")) {
            RetromodVersion.TARGET_MC_VERSION = older;
            LegacyForgeModelBridge.register(transformer);
            assertNull(transformer.getClassRedirects().get(OLD_BAKED_MODEL), "Forge " + older + " is not a checked host");
        }

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        String group = LegacyForgeModelBridge.RENDER_TYPE_GROUP;
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, group, MODIFY_BAKING_RESULT, OLD_BAKED_MODEL));
        assertNull(transformer.getClassRedirects().get(OLD_BAKED_MODEL), "a host that still has the old BakedModel keeps it");
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, group, MODIFY_BAKING_RESULT,
                OLD_REGISTER_ADDITIONAL));
        assertNull(transformer.getClassRedirects().get(OLD_MODEL_LOCATION), "a host with RegisterAdditional keeps the old API");
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, group));
        assertNull(transformer.getClassRedirects().get(OLD_BAKED_MODEL), "a host with no baking-result event");
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, group, MODIFY_BAKING_RESULT));
        assertEquals(BAKED_MODEL, transformer.getClassRedirects().get(OLD_BAKED_MODEL), "Forge 26.2's own shape");
    }

    @Test
    @DisplayName("A Forge 1.20.1 model class reaches the rebuilt model, locations, maps and RegisterAdditional")
    void oldModelCodeTakesTheRebuiltClasses() {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(modelCalls(), CALLER + ".class"));

        String id = "L" + IDENTIFIER + ";";
        assertEquals(List.of(LOCATIONS + ".of(" + id + "Ljava/lang/String;)" + id,
                LOCATIONS + ".getVariant(" + id + ")Ljava/lang/String;",
                LOCATIONS + ".path(" + id + ")Ljava/lang/String;",
                LOCATIONS + ".isLocation(Ljava/lang/Object;)Z",
                LOCATIONS + ".of(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)" + id,
                LOCATIONS + ".vanilla(Ljava/lang/String;Ljava/lang/String;)" + id,
                LOCATIONS + ".forState(L" + BLOCK_STATE + ";)" + id,
                LOCATIONS + ".forState(" + id + "L" + BLOCK_STATE + ";)" + id,
                LOCATIONS + ".missingModelLocation()" + id), calls(method(out, "locations")));
        assertTrue(fields(method(out, "locations")).isEmpty(), "the missing model location is a call now");
        String model = "L" + BAKED_MODEL + ";";
        String manager = "L" + MODEL_MANAGER + ";";
        assertEquals(List.of(MAPS + ".getModels(L" + MODIFY_BAKING_RESULT + ";)Ljava/util/Map;",
                MAPS + ".getModels(L" + BAKING_COMPLETED + ";)Ljava/util/Map;",
                MAPS + ".getModel(" + manager + id + ")" + model,
                MAPS + ".getModel(" + manager + id + ")" + model,
                MAPS + ".getMissingModel(" + manager + ")" + model,
                BAKED_MODEL + ".getQuads(L" + BLOCK_STATE + ";Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;",
                REGISTER_ADDITIONAL + ".register(" + id + ")V"), calls(method(out, "maps")));
        assertEquals(List.of(ROOT + "ItemOverrides.EMPTY:L" + ROOT + "ItemOverrides;"), fields(method(out, "overrides")));

        ClassNode wrapper = node(transformer.transformClass(wrapper(), WRAPPER + ".class"));
        assertEquals(ROOT + "BakedModelWrapper", wrapper.superName);
        assertEquals("(" + model + ")V", method(wrapper, "<init>").desc);
        assertTrue(calls(method(wrapper, "<init>")).contains(ROOT + "BakedModelWrapper.<init>(" + model + ")V"));
        // The inherited protected field resolves through the rebuilt superclass, now typed as the rebuilt model.
        assertEquals(List.of(WRAPPER + ".originalModel:" + model), fields(method(wrapper, "getQuads")));

        ClassNode dynamic = node(transformer.transformClass(dynamicModel(), DYNAMIC + ".class"));
        assertEquals(List.of(ROOT + "IDynamicBakedModel"), dynamic.interfaces);
    }

    @Test
    @DisplayName("Forge 1.21's record location accessors and RegisterAdditional.register(location) reach the same helpers")
    void forge121LocationsReachTheHelpers() {
        register("1.21.1");
        ClassNode out = node(transformer.transformClass(recordLocations(), "mod/RecordLocations.class"));
        String id = "L" + IDENTIFIER + ";";
        assertEquals(List.of(LOCATIONS + ".inventory(" + id + ")" + id, LOCATIONS + ".id(" + id + ")" + id,
                LOCATIONS + ".getVariant(" + id + ")Ljava/lang/String;", REGISTER_ADDITIONAL + ".register(" + id + ")V"),
                calls(method(out, "record")));
    }

    @Test
    @DisplayName("Calls on the old location type answer from its path, instanceof tells it from a plain id, and each"
            + " missing-model field reads its own form")
    void locationTypeCallsAndMissingModelFields() {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(locationTypeCalls(), "mod/LocationTypeCalls.class"));
        String id = "L" + IDENTIFIER + ";";
        assertEquals(List.of(LOCATIONS + ".path(" + id + ")Ljava/lang/String;",
                LOCATIONS + ".withPrefix(" + id + "Ljava/lang/String;)" + id,
                LOCATIONS + ".toLanguageKey(" + id + ")Ljava/lang/String;",
                LOCATIONS + ".isLocation(Ljava/lang/Object;)Z",
                IDENTIFIER + ".getPath()Ljava/lang/String;",
                LOCATIONS + ".missingModelLocation()" + id,
                LOCATIONS + ".missingModelId()" + id), calls(method(out, "calls")),
                "a call made on a plain Identifier stays Identifier's own");
        assertTrue(types(method(out, "calls")).isEmpty(), "no instanceof Identifier is left to answer true for every id");

        transformer.setModSourceVersion("26.2");
        ClassNode newer = node(transformer.transformClass(locationTypeCalls(), "mod/LocationTypeCalls.class"));
        assertEquals(OLD_MODEL_LOCATION + ".getPath()Ljava/lang/String;", calls(method(newer, "calls")).get(0),
                "a mod built for 26.2 keeps every name as written");
    }

    @Test
    @DisplayName("An SRG-named Forge 1.20.1 mod's getPath and missing-model read on the old type reach the same helpers")
    void srgNamedLocationCalls() {
        register("1.20.1");
        transformer.registerSrgNameMappings(Map.of("m_135815_", "getPath"), Map.of("f_119230_", "MISSING_MODEL_LOCATION"));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/SrgLocationCalls", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "calls", "(" + LOCATION + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "m_135815_", "()Ljava/lang/String;", false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, MODEL_BAKERY, "f_119230_", LOCATION);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = node(transformer.transformClass(cw.toByteArray(), "mod/SrgLocationCalls.class"));
        String id = "L" + IDENTIFIER + ";";
        assertEquals(List.of(LOCATIONS + ".path(" + id + ")Ljava/lang/String;", LOCATIONS + ".missingModelLocation()" + id),
                calls(method(out, "calls")));
    }

    /**
     * Calls made on Forge 1.20.1's location type: getPath, withPrefix and toLanguageKey, an instanceof check,
     * a plain Identifier's getPath, and Forge 1.20.1's and Forge 1.21's missing-model fields.
     */
    static byte[] locationTypeCalls() {
        String id = "L" + IDENTIFIER + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/LocationTypeCalls", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "calls", "(" + LOCATION + id + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "getPath", "()Ljava/lang/String;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("item/");
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "withPrefix", "(Ljava/lang/String;)" + id, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "toLanguageKey", "()Ljava/lang/String;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(INSTANCEOF, OLD_MODEL_LOCATION);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, IDENTIFIER, "getPath", "()Ljava/lang/String;", false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, MODEL_BAKERY, "MISSING_MODEL_LOCATION", LOCATION);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, MODEL_BAKERY, "MISSING_MODEL_LOCATION", id);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("A mod built for 26.2 keeps the host's baking-result members and ModelManager")
    void newerModsKeepTheHostNames() {
        register("26.2");
        ClassNode out = node(transformer.transformClass(modelCalls(), CALLER + ".class"));
        List<String> maps = calls(method(out, "maps"));
        assertEquals(MODIFY_BAKING_RESULT + ".getModels()Ljava/util/Map;", maps.get(0));
        assertEquals(BAKING_COMPLETED + ".getModels()Ljava/util/Map;", maps.get(1));
        assertTrue(maps.get(2).startsWith(MODEL_MANAGER + ".getModel("), maps.get(2));
    }

    @Test
    @DisplayName("Patchouli's new ModelResourceLocation(ResourceLocation, variant) reaches the helper, though only the remap names its id Identifier")
    void constructorWithTheOldIdTypeReachesTheHelper() {
        register("1.20.1");
        transformer.registerClassRedirect("net/minecraft/resources/ResourceLocation", IDENTIFIER);

        ClassNode out = node(transformer.transformClass(bookLocation(), "mod/BookModels.class"));

        String id = "L" + IDENTIFIER + ";";
        assertEquals(List.of(LOCATIONS + ".of(" + id + "Ljava/lang/String;)" + id), calls(method(out, "book")));
        assertTrue(types(method(out, "book")).isEmpty(), "no Identifier is constructed with the old two-argument form");
    }

    @Test
    @DisplayName("A ModelResourceLocation::new reference with the 1.20.1 id type takes the same helper as the call")
    void constructorReferenceReachesTheHelper() {
        register("1.20.1");
        transformer.registerClassRedirect("net/minecraft/resources/ResourceLocation", IDENTIFIER);

        ClassNode out = node(transformer.transformClass(bookLocationReference(), "mod/BookReferences.class"));

        org.objectweb.asm.Handle target = null;
        for (AbstractInsnNode insn : method(out, "factory").instructions) {
            if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) target = (org.objectweb.asm.Handle) indy.bsmArgs[1];
        }
        assertNotNull(target);
        assertEquals(H_INVOKESTATIC, target.getTag());
        assertEquals(LOCATIONS + ".of(L" + IDENTIFIER + ";Ljava/lang/String;)L" + IDENTIFIER + ";",
                target.getOwner() + "." + target.getName() + target.getDesc());
    }

    // ---- helpers ----

    /** {@code BiFunction<ResourceLocation, String, ModelResourceLocation> factory = ModelResourceLocation::new}. */
    static byte[] bookLocationReference() {
        String oldId = "Lnet/minecraft/resources/ResourceLocation;";
        String ctor = "(" + oldId + "Ljava/lang/String;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/BookReferences", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "factory", "()Ljava/util/function/BiFunction;", null, null);
        mv.visitCode();
        org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/BiFunction;", metafactory,
                org.objectweb.asm.Type.getType("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                new org.objectweb.asm.Handle(H_NEWINVOKESPECIAL, OLD_MODEL_LOCATION, "<init>", ctor, false),
                org.objectweb.asm.Type.getType("(" + oldId + "Ljava/lang/String;)" + LOCATION));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Patchouli's replaceBookModel key: {@code new ModelResourceLocation(BOOK_ID, "inventory")} with the 1.20.1 id type. */
    static byte[] bookLocation() {
        String oldId = "Lnet/minecraft/resources/ResourceLocation;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/BookModels", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "book", "(" + oldId + ")" + LOCATION, null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, OLD_MODEL_LOCATION);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("inventory");
        mv.visitMethodInsn(INVOKESPECIAL, OLD_MODEL_LOCATION, "<init>", "(" + oldId + "Ljava/lang/String;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private void register(String modVersion) {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(modVersion);
    }

    static MethodNode method(ClassNode node, String name) {
        return LegacyForgeRenderBridgeTest.method(node, name);
    }

    static List<String> calls(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) found.add(call.owner + "." + call.name + call.desc);
        }
        return found;
    }

    static List<String> fields(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) found.add(field.owner + "." + field.name + ":" + field.desc);
        }
        return found;
    }

    static List<String> types(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type) found.add(type.desc);
        }
        return found;
    }

    /**
     * A Forge 1.20.1 model helper after the SRG remap: model locations built and read, the baking-result maps,
     * ModelManager lookups, a quad read, RegisterAdditional and ItemOverrides.EMPTY.
     */
    static byte[] modelCalls() {
        String id = "L" + OLD_ID + ";";
        String string = "Ljava/lang/String;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CALLER, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "locations", "(" + id + "L" + BLOCK_STATE + ";)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, OLD_MODEL_LOCATION);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("inventory");
        mv.visitMethodInsn(INVOKESPECIAL, OLD_MODEL_LOCATION, "<init>", "(" + id + string + ")V", false);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "getVariant", "()" + string, false);
        mv.visitInsn(POP);
        // getPath and instanceof on the old type, which the remap would otherwise make Identifier's own.
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "getPath", "()" + string, false);
        mv.visitInsn(POP);
        mv.visitTypeInsn(INSTANCEOF, OLD_MODEL_LOCATION);
        mv.visitInsn(POP);
        mv.visitTypeInsn(NEW, OLD_MODEL_LOCATION);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("mod");
        mv.visitLdcInsn("block");
        mv.visitLdcInsn("facing=north");
        mv.visitMethodInsn(INVOKESPECIAL, OLD_MODEL_LOCATION, "<init>", "(" + string + string + string + ")V", false);
        mv.visitInsn(POP);
        mv.visitLdcInsn("item/trident");
        mv.visitLdcInsn("inventory");
        mv.visitMethodInsn(INVOKESTATIC, OLD_MODEL_LOCATION, "vanilla", "(" + string + string + ")" + LOCATION, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, BLOCK_MODEL_SHAPER, "stateToModelLocation", "(L" + BLOCK_STATE + ";)" + LOCATION, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, BLOCK_MODEL_SHAPER, "stateToModelLocation", "(" + id + "L" + BLOCK_STATE + ";)" + LOCATION,
                false);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, MODEL_BAKERY, "MISSING_MODEL_LOCATION", LOCATION);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        String manager = "L" + MODEL_MANAGER + ";";
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "maps", "(L" + MODIFY_BAKING_RESULT + ";L" + BAKING_COMPLETED + ";" + manager
                + LOCATION + "L" + OLD_REGISTER_ADDITIONAL + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, MODIFY_BAKING_RESULT, "getModels", "()Ljava/util/Map;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, BAKING_COMPLETED, "getModels", "()Ljava/util/Map;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, MODEL_MANAGER, "getModel", "(" + LOCATION + ")" + MODEL, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, MODEL_MANAGER, "getModel", "(" + id + ")" + MODEL, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, MODEL_MANAGER, "getMissingModel", "()" + MODEL, false);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BAKED_MODEL, "getQuads", "(L" + BLOCK_STATE
                + ";Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 3);
        // A location handed where Forge 1.20.1 took an Identifier, as old code did.
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_REGISTER_ADDITIONAL, "register", "(" + id + ")V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "overrides", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, OLD_ITEM_OVERRIDES, "EMPTY", "L" + OLD_ITEM_OVERRIDES + ";");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class GlowingWrapper extends BakedModelWrapper<BakedModel>} whose getQuads reads {@code originalModel}. */
    static byte[] wrapper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, WRAPPER, null, OLD_WRAPPER, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + MODEL + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, OLD_WRAPPER, "<init>", "(" + MODEL + ")V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        String quads = "(L" + BLOCK_STATE + ";Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;";
        mv = cw.visitMethod(ACC_PUBLIC, "getQuads", quads, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, WRAPPER, "originalModel", MODEL);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BAKED_MODEL, "getQuads", quads, true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code abstract class DynamicModel implements IDynamicBakedModel}. */
    static byte[] dynamicModel() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, DYNAMIC, null, "java/lang/Object", new String[]{OLD_DYNAMIC_MODEL});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Forge 1.21's record location: inventory(id), id(), variant(), and RegisterAdditional.register(location). */
    static byte[] recordLocations() {
        String id = "L" + OLD_ID + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/RecordLocations", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "record", "(" + id + "L" + OLD_REGISTER_ADDITIONAL + ";)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, OLD_MODEL_LOCATION, "inventory", "(" + id + ")" + LOCATION, false);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "id", "()" + id, false);
        mv.visitInsn(POP);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_MODEL_LOCATION, "variant", "()Ljava/lang/String;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(SWAP);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_REGISTER_ADDITIONAL, "register", "(" + LOCATION + ")V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
