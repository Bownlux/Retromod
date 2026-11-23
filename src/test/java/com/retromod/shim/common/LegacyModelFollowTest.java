/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.LegacyModelLocationAdapter;
import com.retromod.testsupport.fakemc.atlas.FakeAtlasClient;
import com.retromod.testsupport.fakemc.atlas.FakeAtlasId;
import com.retromod.testsupport.fakemc.atlas.FakeAtlasManager;
import com.retromod.testsupport.fakemc.atlas.FakeTexture;
import com.retromod.testsupport.fakemc.atlas.FakeTextureAtlas;
import com.retromod.testsupport.fakemc.atlas.FakeTextureManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The missing-model fields after the Fabric remap, and the old ModelManager.getAtlas, reach the rebuilt helpers. */
class LegacyModelFollowTest {

    private static final String BAKERY = "net/minecraft/client/resources/model/ModelBakery";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String LOCATIONS = "com/retromod/legacyapi/forge/client/model/LegacyModelLocations";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("after the remap, MISSING_MODEL_LOCATION reads the plain id and MISSING_MODEL_VARIANT the located form")
    void missingModelFieldsAfterRemap() {
        byte[] out = LegacyModelLocationAdapter.applyAfterRemap(readsMissingFields());

        List<String> calls = calls(out);
        assertEquals(List.of(LOCATIONS + ".missingModelId()" + ID, LOCATIONS + ".missingModelLocation()" + ID), calls);
        assertFalse(fields(out).contains(BAKERY), "no read of the removed ModelBakery fields may remain");
    }

    @Test
    @DisplayName("a Fabric mod's MISSING_MODEL_VARIANT, still named field_52276 after the remap, reads the located form")
    void fabricMissingVariantAfterRemap() {
        byte[] reads = method("test/model/FabricMissing", mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, BAKERY, "field_52276", "Lnet/minecraft/client/resources/model/ModelResourceLocation;");
            mv.visitInsn(Opcodes.POP);
        });

        byte[] out = LegacyModelLocationAdapter.applyAfterRemap(reads);

        assertEquals(List.of(LOCATIONS + ".missingModelLocation()Lnet/minecraft/client/resources/model/ModelResourceLocation;"),
                calls(out), "the class redirect renames the location type afterwards");
        assertFalse(fields(out).contains(BAKERY));
    }

    @Test
    @DisplayName("an atlas texture location reaches its atlas, an atlas id still works, and an unknown id fails with a clear error")
    void atlasTextureLocationsAndIds() throws Exception {
        Class<?> atlases = rebuiltAtlases();
        Class<?> id = atlases.getClassLoader().loadClass("net.minecraft.resources.Identifier");
        Method parse = id.getMethod("parse", String.class);
        Method getAtlas = null;
        for (Method candidate : atlases.getMethods()) if (candidate.getName().equals("getAtlas")) getAtlas = candidate;

        assertEquals("blocks atlas", String.valueOf(getAtlas.invoke(null, null, parse.invoke(null, "minecraft:textures/atlas/blocks.png"))),
                "TextureAtlas.LOCATION_BLOCKS must reach the blocks atlas");
        assertEquals("chests atlas", String.valueOf(getAtlas.invoke(null, null, parse.invoke(null, "minecraft:chests"))),
                "an atlas id goes to the atlas manager");
        Method unknownCall = getAtlas;
        InvocationTargetException unknown = assertThrows(InvocationTargetException.class,
                () -> unknownCall.invoke(null, null, parse.invoke(null, "minecraft:textures/atlas/nothing.png")));
        assertInstanceOf(IllegalStateException.class, unknown.getCause());
        assertTrue(unknown.getCause().getMessage().contains("minecraft:textures/atlas/nothing.png"), unknown.getCause().getMessage());
    }

    @Test
    @DisplayName("a class without the missing-model fields is returned unchanged")
    void unrelatedClassIsUntouched() {
        byte[] plain = method("test/model/Plain", mv -> {});
        assertSame(plain, LegacyModelLocationAdapter.applyAfterRemap(plain));
    }

    @Test
    @DisplayName("ModelManager.getAtlas by Identifier or ResourceLocation goes to the atlas helper")
    void getAtlasIsRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyModelAtlasBridge.register(t);

        List<String> calls = calls(t.transformClass(callsGetAtlas(), "test/model/Atlas"));

        String helper = LegacyModelAtlasBridge.HELPER + ".getAtlas(L" + LegacyModelAtlasBridge.MODEL_MANAGER + ";" + ID + ")"
                + LegacyModelAtlasBridge.ATLAS;
        assertEquals(List.of(helper), calls);
    }

    @Test
    @DisplayName("the atlas bridge registers only where ModelManager lost getAtlas and Minecraft has an atlas manager")
    void atlasHostGate() {
        ClassNode oldManager = withMethod("getAtlas", "(" + ID + ")" + LegacyModelAtlasBridge.ATLAS);
        ClassNode newManager = withMethod("getItemModel", "(" + ID + ")Lnet/minecraft/client/renderer/item/ItemModel;");
        ClassNode client = withMethod("getAtlasManager", "()Lnet/minecraft/client/resources/model/sprite/AtlasManager;");

        assertTrue(LegacyModelAtlasBridge.hostMatches(newManager, client));
        assertFalse(LegacyModelAtlasBridge.hostMatches(oldManager, client), "a host that still has getAtlas keeps it");
        assertFalse(LegacyModelAtlasBridge.hostMatches(newManager, new ClassNode()), "no atlas manager, nothing to call");
        assertTrue(LegacyModelAtlasBridge.hostMatches(null, null), "offline the shim's host gate decides");
    }

    private static byte[] readsMissingFields() {
        return method("test/model/Missing", mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, BAKERY, "MISSING_MODEL_LOCATION", ID);
            mv.visitInsn(Opcodes.POP);
            mv.visitFieldInsn(Opcodes.GETSTATIC, BAKERY, "MISSING_MODEL_VARIANT", ID);
            mv.visitInsn(Opcodes.POP);
        });
    }

    private static byte[] callsGetAtlas() {
        return method("test/model/Atlas", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyModelAtlasBridge.MODEL_MANAGER, "getAtlas",
                    "(" + ID + ")" + LegacyModelAtlasBridge.ATLAS, false);
            mv.visitInsn(Opcodes.POP);
        });
    }

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");

    /** The compiled atlas helper beside stand-ins renamed to the host classes it calls; other host types come from the stubs. */
    private static Class<?> rebuiltAtlases() throws IOException, ClassNotFoundException {
        Map<Class<?>, String> fakes = Map.of(
                FakeAtlasId.class, "net/minecraft/resources/Identifier",
                FakeTexture.class, "net/minecraft/client/renderer/texture/AbstractTexture",
                FakeTextureAtlas.class, "net/minecraft/client/renderer/texture/TextureAtlas",
                FakeTextureManager.class, "net/minecraft/client/renderer/texture/TextureManager",
                FakeAtlasClient.class, "net/minecraft/client/Minecraft",
                FakeAtlasManager.class, "test/fakemc/AtlasManager");
        Map<String, String> rename = new HashMap<>();
        fakes.forEach((fake, host) -> rename.put(fake.getName().replace('.', '/'), host));
        SimpleRemapper remapper = new SimpleRemapper(rename);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<Class<?>, String> fake : fakes.entrySet()) {
            ClassWriter writer = new ClassWriter(0);
            try (InputStream in = fake.getKey().getResourceAsStream(fake.getKey().getSimpleName() + ".class")) {
                new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, remapper), 0);
            }
            classes.put(fake.getValue().replace('/', '.'), writer.toByteArray());
        }
        String helper = "com/retromod/legacyapi/minecraft/client/resources/model/LegacyModelAtlases";
        classes.put(helper.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(helper + ".class")));
        ClassLoader loader = new ClassLoader(LegacyModelFollowTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    byte[] bytes = classes.get(name);
                    // Host types only named in signatures come from the compile-only stubs.
                    Path stub = COMPILED.resolve(name.replace('.', '/') + ".class");
                    try {
                        if (bytes == null && name.startsWith("net.minecraft.") && Files.exists(stub)) bytes = Files.readAllBytes(stub);
                    } catch (IOException unreadable) {
                        throw new ClassNotFoundException(name, unreadable);
                    }
                    if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
                    return super.loadClass(name, resolve);
                }
            }
        };
        return loader.loadClass(helper.replace('/', '.'));
    }

    private static ClassNode withMethod(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    private static byte[] method(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static List<String> fields(byte[] bytes) {
        List<String> owners = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) owners.add(field.owner);
            }
        }
        return owners;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
