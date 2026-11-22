/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.fabric.FabricLegacyRegistrationApiShim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.*;

/**
 * Moonlight's block type swap ingredient implements Fabric API's 1.21.1 custom ingredient methods. Fabric API for 26.1
 * and newer calls getCodec() and getStreamCodec(), so writing Moonlight's generated recipes failed with AbstractMethodError.
 */
class FabricLegacyCustomIngredientTest {

    private static final String SERIALIZER = "net/fabricmc/fabric/api/recipe/v1/ingredient/CustomIngredientSerializer";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";

    @Test
    @DisplayName("getCodec() is the old codec that refuses empty ingredients, and getStreamCodec() the old packet codec")
    void currentMethodsAnswerFromTheOldOnes() throws Exception {
        HostStubs host = new HostStubs()
                .add(serializerInterface())
                .add(HostStubs.plainClass(MAP_CODEC, "java/lang/Object"))
                .add(FabricLegacyRegistrationApiShim.LEGACY_INGREDIENT_SERIALIZER,
                        FabricLegacyRegistrationApiShim.generateLegacyIngredientSerializer())
                .add(oldSerializer());
        Object serializer = host.load("mod/SwapSerializer").getConstructor().newInstance();
        Class<?> api = host.load(SERIALIZER);

        api.getMethod("getCodec").invoke(serializer);
        api.getMethod("getStreamCodec").invoke(serializer);

        Class<?> type = serializer.getClass();
        assertEquals(Boolean.FALSE, type.getField("allowEmpty").get(null), "the current codec refuses empty ingredients");
        assertEquals(1, type.getField("packets").get(null));
    }

    @Test
    @DisplayName("items() lists the item of each old matching stack, leaving out empty stacks, and passes 1.21.2's holders on")
    void itemsComeFromTheOldMatchingList() throws Exception {
        HostStubs host = new HostStubs()
                .add(HostStubs.iface(HOLDER))
                .add(HostStubs.plainClass("test/Item", "java/lang/Object", HOLDER))
                .add(stack())
                .add(ingredientInterface())
                .add(FabricLegacyRegistrationApiShim.LEGACY_INGREDIENT, FabricLegacyRegistrationApiShim.generateLegacyIngredient())
                .add(oldIngredient());
        Object diamond = host.load("test/Item").getConstructor().newInstance();
        Object stone = host.load("test/Item").getConstructor().newInstance();
        var newStack = host.load(STACK).getConstructor(host.load(HOLDER), boolean.class);
        Class<?> mod = host.load("mod/SwapIngredient");
        var items = host.load(INGREDIENT).getMethod("items");

        mod.getField("matching").set(null, List.of(newStack.newInstance(diamond, false), newStack.newInstance(stone, true)));
        assertEquals(List.of(diamond), ((java.util.stream.Stream<?>) items.invoke(mod.getConstructor().newInstance())).toList(),
                "a 1.21.1 ingredient lists stacks, and an empty one names no item");
        mod.getField("matching").set(null, List.of(diamond, stone));
        assertEquals(List.of(diamond, stone), ((java.util.stream.Stream<?>) items.invoke(mod.getConstructor().newInstance())).toList(),
                "a 1.21.2 ingredient already lists item holders");
    }

    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String INGREDIENT = "net/fabricmc/fabric/api/recipe/v1/ingredient/CustomIngredient";

    private static byte[] ingredientInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, INGREDIENT, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "items", "()Ljava/util/stream/Stream;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class ItemStack { ItemStack(Holder item, boolean empty); boolean isEmpty(); Holder typeHolder(); }} */
    private static byte[] stack() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, STACK, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "item", "L" + HOLDER + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "empty", "Z", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + HOLDER + ";Z)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, STACK, "item", "L" + HOLDER + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ILOAD, 2);
        init.visitFieldInsn(PUTFIELD, STACK, "empty", "Z");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor empty = cw.visitMethod(ACC_PUBLIC, "isEmpty", "()Z", null, null);
        empty.visitCode();
        empty.visitVarInsn(ALOAD, 0);
        empty.visitFieldInsn(GETFIELD, STACK, "empty", "Z");
        empty.visitInsn(IRETURN);
        empty.visitMaxs(0, 0);
        empty.visitEnd();
        MethodVisitor holder = cw.visitMethod(ACC_PUBLIC, "typeHolder", "()L" + HOLDER + ";", null, null);
        holder.visitCode();
        holder.visitVarInsn(ALOAD, 0);
        holder.visitFieldInsn(GETFIELD, STACK, "item", "L" + HOLDER + ";");
        holder.visitInsn(ARETURN);
        holder.visitMaxs(0, 0);
        holder.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An old ingredient whose {@code getMatchingStacks()} answers the public static {@code matching} list. */
    private static byte[] oldIngredient() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/SwapIngredient", null, "java/lang/Object",
                new String[]{FabricLegacyRegistrationApiShim.LEGACY_INGREDIENT});
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "matching", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor list = cw.visitMethod(ACC_PUBLIC, "getMatchingStacks", "()Ljava/util/List;", null, null);
        list.visitCode();
        list.visitFieldInsn(GETSTATIC, "mod/SwapIngredient", "matching", "Ljava/util/List;");
        list.visitInsn(ARETURN);
        list.visitMaxs(0, 0);
        list.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] serializerInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, SERIALIZER, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getCodec", "()L" + MAP_CODEC + ";", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getStreamCodec", "()L" + STREAM_CODEC + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.21.1 serializer that records the allowEmpty flag its getCodec gets and counts its getPacketCodec calls. */
    private static byte[] oldSerializer() {
        String self = "mod/SwapSerializer";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, self, null, "java/lang/Object",
                new String[]{FabricLegacyRegistrationApiShim.LEGACY_INGREDIENT_SERIALIZER});
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "allowEmpty", "Ljava/lang/Boolean;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "packets", "I", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor codec = cw.visitMethod(ACC_PUBLIC, "getCodec", "(Z)L" + MAP_CODEC + ";", null, null);
        codec.visitCode();
        codec.visitVarInsn(ILOAD, 1);
        codec.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        codec.visitFieldInsn(PUTSTATIC, self, "allowEmpty", "Ljava/lang/Boolean;");
        codec.visitInsn(ACONST_NULL);
        codec.visitInsn(ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        MethodVisitor packet = cw.visitMethod(ACC_PUBLIC, "getPacketCodec", "()L" + STREAM_CODEC + ";", null, null);
        packet.visitCode();
        packet.visitFieldInsn(GETSTATIC, self, "packets", "I");
        packet.visitInsn(ICONST_1);
        packet.visitInsn(IADD);
        packet.visitFieldInsn(PUTSTATIC, self, "packets", "I");
        packet.visitInsn(ACONST_NULL);
        packet.visitInsn(ARETURN);
        packet.visitMaxs(0, 0);
        packet.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
