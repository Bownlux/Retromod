/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * World saved data written the 1.20.2 to 1.21.4 way, in the shape Sophisticated Backpacks uses
 * for its backpack storage (#249):
 * {@code level.getDataStorage().computeIfAbsent(new SavedData.Factory(...), "name")}.
 */
public class LegacySavedDataFactoryShimTest {

    private static final String SAVED_DATA = "net/minecraft/world/level/saveddata/SavedData";
    private static final String OLD_CALL_DESC = "(L" + LegacySavedDataFactoryShim.OLD_FACTORY
            + ";Ljava/lang/String;)L" + SAVED_DATA + ";";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Factory construction and the storage lookup route to the stand-in")
    void legacyLookupRoutesToStandIn() {
        LegacySavedDataFactoryShim.register(transformer);
        List<AbstractInsnNode> insns = transformBody(mv -> {
            mv.visitInsn(ACONST_NULL);
            mv.visitTypeInsn(CHECKCAST, LegacySavedDataFactoryShim.STORAGE);
            mv.visitTypeInsn(NEW, LegacySavedDataFactoryShim.OLD_FACTORY);
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, LegacySavedDataFactoryShim.OLD_FACTORY, "<init>",
                    "(Ljava/util/function/Supplier;Ljava/util/function/BiFunction;"
                            + "Lnet/minecraft/util/datafix/DataFixTypes;)V", false);
            mv.visitLdcInsn("sophisticatedbackpacks");
            mv.visitMethodInsn(INVOKEVIRTUAL, LegacySavedDataFactoryShim.STORAGE,
                    "computeIfAbsent", OLD_CALL_DESC, false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        });

        assertTrue(insns.stream().anyMatch(i -> i instanceof TypeInsnNode type
                        && type.getOpcode() == NEW
                        && type.desc.equals(LegacySavedDataFactoryShim.FACTORY)),
                "SavedData.Factory is gone on 26.1, so the stand-in must be constructed");
        MethodInsnNode lookup = insns.stream()
                .filter(i -> i instanceof MethodInsnNode call && call.name.equals("computeIfAbsent"))
                .map(MethodInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals(LegacySavedDataFactoryShim.FACTORY, lookup.owner,
                "the name-based lookup no longer exists and must go through the stand-in");
        assertEquals(INVOKESTATIC, lookup.getOpcode());
    }

    @Test
    @DisplayName("The codec reads through the mod's loader and writes through its save method")
    void codecUsesModLoaderAndSave() throws Exception {
        Map<String, String> toStubs = Map.of(
                "net/minecraft/nbt/CompoundTag", StubTag.class.getName().replace('.', '/'),
                "net/minecraft/core/HolderLookup$Provider",
                StubProvider.class.getName().replace('.', '/'));
        StubProvider provider = new StubProvider();

        BiFunction<Object, Object, Object> loader = (tag, registries) ->
                new StubData(((StubTag) tag).value, registries);
        Function<Object, Object> load = newFunction(LegacySavedDataFactoryShim.LOAD,
                LegacySavedDataFactoryShim.generateLoad(), toStubs,
                new Class<?>[]{BiFunction.class, Object.class}, loader, provider);
        StubData loaded = (StubData) load.apply(new StubTag("stored"));
        assertEquals("stored", loaded.value);
        assertSame(provider, loaded.registries, "the loader needs the storage's registries");

        Function<Object, Object> save = newFunction(LegacySavedDataFactoryShim.SAVE,
                LegacySavedDataFactoryShim.generateSave(), toStubs,
                new Class<?>[]{Object.class}, provider);
        StubTag written = (StubTag) save.apply(new StubData("current", null));
        assertEquals("current", written.value, "save must write the mod's current state");
    }

    @Test
    @DisplayName("Saving ignores a client-only method on the mod class, as a dedicated server must")
    void saveResolvesOnlyTheSaveMethod() throws Exception {
        Map<String, String> toStubs = Map.of(
                "net/minecraft/nbt/CompoundTag", StubTag.class.getName().replace('.', '/'),
                "net/minecraft/core/HolderLookup$Provider",
                StubProvider.class.getName().replace('.', '/'));
        Function<Object, Object> save = newFunction(LegacySavedDataFactoryShim.SAVE,
                LegacySavedDataFactoryShim.generateSave(), toStubs,
                new Class<?>[]{Object.class}, new StubProvider());
        Object data = dataWithClientOnlyMethod().getConstructor().newInstance();

        StubTag written = (StubTag) save.apply(data);

        assertEquals("server", written.value,
                "Sophisticated's storage also declares onClientWorldLoad(Minecraft, ClientLevel)");
    }

    /** A saved data class whose other public method names a class this JVM cannot load. */
    private Class<?> dataWithClientOnlyMethod() {
        String name = "test/ServerSideData";
        String tag = StubTag.class.getName().replace('.', '/');
        String provider = StubProvider.class.getName().replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "save",
                "(L" + tag + ";L" + provider + ";)L" + tag + ";", null, null);
        save.visitCode();
        save.visitVarInsn(ALOAD, 1);
        save.visitLdcInsn("server");
        save.visitFieldInsn(PUTFIELD, tag, "value", "Ljava/lang/String;");
        save.visitVarInsn(ALOAD, 1);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        MethodVisitor client = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onClientWorldLoad",
                "(Lnet/minecraft/client/Minecraft;)V", null, null);
        client.visitCode();
        client.visitInsn(RETURN);
        client.visitMaxs(0, 0);
        client.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("test.ServerSideData", bytes, 0, bytes.length);
            }
        }.define();
    }

    @Test
    @DisplayName("Every generated class passes ASM's structural checks")
    void generatedClassesAreWellFormed() {
        for (byte[] bytes : List.of(LegacySavedDataFactoryShim.generateFactory(),
                LegacySavedDataFactoryShim.generateLoad(), LegacySavedDataFactoryShim.generateSave())) {
            assertDoesNotThrow(() -> new ClassReader(bytes).accept(
                    new CheckClassAdapter(new ClassWriter(0), false), 0));
        }
    }

    /** Stand-in for CompoundTag: public no-argument constructor, one value. */
    public static final class StubTag {
        public String value;

        public StubTag() {
        }

        StubTag(String value) {
            this.value = value;
        }
    }

    /** Stand-in for HolderLookup.Provider. */
    public static final class StubProvider {
    }

    /** A mod's saved data with the 1.21.1 {@code save(CompoundTag, Provider)} method. */
    public static final class StubData {
        final String value;
        final Object registries;

        StubData(String value, Object registries) {
            this.value = value;
            this.registries = registries;
        }

        public StubTag save(StubTag tag, StubProvider provider) {
            tag.value = value;
            return tag;
        }
    }

    @SuppressWarnings("unchecked")
    private Function<Object, Object> newFunction(String internalName, byte[] bytes,
            Map<String, String> mapping, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(mapping)), 0);
        byte[] remapped = writer.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(internalName.replace('/', '.'), remapped, 0, remapped.length);
            }
        }.define();
        return (Function<Object, Object>) type.getConstructor(parameterTypes).newInstance(arguments);
    }

    private List<AbstractInsnNode> transformBody(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Caller")).accept(cn, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("call")) m.instructions.forEach(insns::add);
        }
        return insns;
    }
}
