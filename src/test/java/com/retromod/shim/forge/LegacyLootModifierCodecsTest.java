/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.shim.forge.embedded.LegacyLootModifierCodecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #283: Scorched Guns registers its global loot modifier serializers as Forge 1.20.1 {@code Codec}s.
 * NeoForge 1.21 reads that registry as {@code MapCodec}s, so the serializers are converted where the
 * mod registers them.
 */
class LegacyLootModifierCodecsTest {

    private static final String LOOT_KEY = "ResourceKey[minecraft:root / neoforge:global_loot_modifier_serializers]";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String MAP_CODEC_CODEC = MAP_CODEC + "$MapCodecCodec";

    @Test
    @DisplayName("a record codec registered as a loot modifier serializer becomes its map codec")
    void recordCodecIsUnwrapped() throws Exception {
        Dfu dfu = new Dfu();
        Object mapCodec = dfu.load("test/RecordMap").getConstructor().newInstance();
        Object recordCodec = dfu.load(MAP_CODEC_CODEC).getConstructor(dfu.load(MAP_CODEC)).newInstance(mapCodec);

        Object registered = LegacyLootModifierCodecs.serializer(LOOT_KEY, () -> recordCodec).get();

        assertSame(mapCodec, registered, "RecordCodecBuilder.create wraps the map codec NeoForge needs");
    }

    @Test
    @DisplayName("any other codec is treated as a map codec")
    void otherCodecIsAssumedToBeAMap() throws Exception {
        Dfu dfu = new Dfu();
        Object codec = dfu.load("test/PlainCodec").getConstructor().newInstance();

        Object registered = LegacyLootModifierCodecs.serializer(LOOT_KEY, () -> codec).get();

        assertEquals("test.AssumedMap", registered.getClass().getName());
        assertSame(codec, registered.getClass().getField("codec").get(registered));
    }

    @Test
    @DisplayName("a biome modifier serializer is converted too (#377)")
    void biomeModifierSerializerIsUnwrapped() throws Exception {
        // Saint's Dragons registers its conditional feature modifier's record codec here.
        Dfu dfu = new Dfu();
        Object mapCodec = dfu.load("test/RecordMap").getConstructor().newInstance();
        Object recordCodec = dfu.load(MAP_CODEC_CODEC).getConstructor(dfu.load(MAP_CODEC)).newInstance(mapCodec);

        Object registered = LegacyLootModifierCodecs.serializer(
                "ResourceKey[minecraft:root / neoforge:biome_modifier_serializers]", () -> recordCodec).get();

        assertSame(mapCodec, registered);
    }

    @Test
    @DisplayName("other registries keep the mod's supplier")
    void otherRegistriesAreUntouched() {
        Supplier<Object> blocks = Object::new;
        assertSame(blocks, LegacyLootModifierCodecs.serializer("ResourceKey[minecraft:root / minecraft:block]", blocks));
    }

    @Test
    @DisplayName("both register wrappers convert the supplier before NeoForge sees it")
    void wrappersConvertBeforeRegistering() {
        for (byte[] wrapper : new byte[][] {RegistryIdBridgeSynthetic.generate(),
                RegistryIdBridgeSynthetic.generateBiomeRecorder(true)}) {
            ClassNode node = new ClassNode();
            new ClassReader(wrapper).accept(node, 0);
            MethodNode register = node.methods.stream().filter(m -> m.name.equals("register")).findFirst().orElseThrow();
            int converted = -1;
            int registered = -1;
            AbstractInsnNode[] insns = register.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof MethodInsnNode call) {
                    if (call.owner.equals(RegistryIdBridgeSynthetic.LOOT_MODIFIER_CODECS)
                            && insns[i + 1] instanceof VarInsnNode store && store.var == 2) {
                        converted = i;
                    }
                    if (call.name.equals("register") && call.owner.endsWith("DeferredRegister") && registered < 0) {
                        registered = i;
                    }
                }
            }
            assertTrue(converted >= 0, node.name + " must replace the supplier with the converted one");
            assertTrue(converted < registered, node.name + " must convert before DeferredRegister.register");
        }
    }

    /** Stand-ins for DFU's codec types, in their own loader. */
    private static final class Dfu extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, Class<?>> defined = new HashMap<>();

        Dfu() {
            super(LegacyLootModifierCodecsTest.class.getClassLoader());
            classes.put(CODEC, iface(CODEC));
            classes.put(MAP_CODEC, mapCodec());
            classes.put(MAP_CODEC_CODEC, mapCodecCodec());
            classes.put("test/RecordMap", subclass("test/RecordMap", MAP_CODEC));
            classes.put("test/PlainCodec", implementer("test/PlainCodec", CODEC));
            classes.put("test/AssumedMap", assumedMap());
        }

        Class<?> load(String internalName) throws ClassNotFoundException {
            return loadClass(internalName.replace('/', '.'));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) return super.loadClass(name, resolve);
            Class<?> type = defined.get(name);
            if (type == null) {
                type = defineClass(name, bytes, 0, bytes.length);
                defined.put(name, type);
            }
            return type;
        }
    }

    private static byte[] iface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code abstract class MapCodec} with {@code static MapCodec assumeMapUnsafe(Codec)}. */
    private static byte[] mapCodec() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, MAP_CODEC, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "assumeMapUnsafe",
                "(L" + CODEC + ";)L" + MAP_CODEC + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "test/AssumedMap");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "test/AssumedMap", "<init>", "(L" + CODEC + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code MapCodec$MapCodecCodec(MapCodec codec) implements Codec}, as RecordCodecBuilder.create returns. */
    private static byte[] mapCodecCodec() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, MAP_CODEC_CODEC, null, "java/lang/Object", new String[] {CODEC});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "codec", "L" + MAP_CODEC + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + MAP_CODEC + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, MAP_CODEC_CODEC, "codec", "L" + MAP_CODEC + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "codec", "()L" + MAP_CODEC + ";", null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, MAP_CODEC_CODEC, "codec", "L" + MAP_CODEC + ";");
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code AssumedMap extends MapCodec}, keeping the codec it was assumed from. */
    private static byte[] assumedMap() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/AssumedMap", null, MAP_CODEC, null);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "codec", "L" + CODEC + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + CODEC + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, MAP_CODEC, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, "test/AssumedMap", "codec", "L" + CODEC + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] subclass(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        constructor(cw, superName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] implementer(String name, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", new String[] {iface});
        constructor(cw, "java/lang/Object");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
