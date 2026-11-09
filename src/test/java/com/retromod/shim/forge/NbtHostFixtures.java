/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2's NBT and item capability shapes for tests, and Forge 1.20.1 classes written against the old ones. */
final class NbtHostFixtures {

    static final String TAG = "net/minecraft/nbt/Tag";
    static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    static final String INBT = "net/minecraftforge/common/util/INBTSerializable";
    static final String CAP_PROVIDER = "net/minecraftforge/common/capabilities/ICapabilityProvider";
    static final String CAP_SERIALIZABLE = "net/minecraftforge/common/capabilities/ICapabilitySerializable";
    static final String DISPATCHER = "net/minecraftforge/common/capabilities/CapabilityDispatcher";
    static final String ITEM_HANDLER = "net/minecraftforge/items/ItemStackHandler";
    static final String ENERGY_STORAGE = "net/minecraftforge/energy/EnergyStorage";
    static final String GOAL = "net/minecraft/world/entity/ai/goal/Goal";
    static final String RECORDER = "host/RecordingSerializable";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String BUCKET_ITEM = "net/minecraft/world/item/BucketItem";
    static final String FORGE_ITEM = "net/minecraftforge/common/extensions/IForgeItem";

    static final String L_TAG = "L" + TAG + ";";
    static final String L_COMPOUND = "L" + COMPOUND + ";";
    static final String L_PROVIDER = "L" + PROVIDER + ";";

    private static final String STAND_INS = "com/retromod/shim/forge/nbthost/";
    // The stand-ins under the host names they serve.
    private static final Map<String, String> HOST_NAMES = Map.ofEntries(
            Map.entry(STAND_INS + "Tag", TAG),
            Map.entry(STAND_INS + "CompoundTag", COMPOUND),
            Map.entry(STAND_INS + "HolderLookup", "net/minecraft/core/HolderLookup"),
            Map.entry(STAND_INS + "HolderLookup$Provider", PROVIDER),
            Map.entry(STAND_INS + "RegistryAccess", "net/minecraft/core/RegistryAccess"),
            Map.entry(STAND_INS + "RegistryAccess$Frozen", "net/minecraft/core/RegistryAccess$Frozen"),
            Map.entry(STAND_INS + "MinecraftServer", "net/minecraft/server/MinecraftServer"),
            Map.entry(STAND_INS + "ServerLifecycleHooks", "net/minecraftforge/server/ServerLifecycleHooks"),
            Map.entry(STAND_INS + "INBTSerializable", INBT),
            Map.entry(STAND_INS + "ICapabilityProvider", CAP_PROVIDER),
            Map.entry(STAND_INS + "ICapabilitySerializable", CAP_SERIALIZABLE),
            Map.entry(STAND_INS + "CapabilityDispatcher", DISPATCHER),
            Map.entry(STAND_INS + "ItemStackHandler", ITEM_HANDLER),
            Map.entry(STAND_INS + "EnergyStorage", ENERGY_STORAGE),
            Map.entry(STAND_INS + "Goal", GOAL),
            Map.entry(STAND_INS + "RecordingSerializable", RECORDER),
            Map.entry(STAND_INS + "SimpleProvider", "host/Provider"),
            Map.entry(STAND_INS + "ItemStack", ITEM_STACK),
            Map.entry(STAND_INS + "IForgeItem", FORGE_ITEM),
            Map.entry(STAND_INS + "Item", ITEM),
            Map.entry(STAND_INS + "BucketItem", BUCKET_ITEM));

    private NbtHostFixtures() {}

    // ---- the host and a translated mod ----

    /** Every stand-in under its host name. */
    static Map<String, byte[]> host() throws IOException {
        Remapper names = new SimpleRemapper(HOST_NAMES);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<String, String> entry : HOST_NAMES.entrySet()) {
            classes.put(entry.getValue(), remap(read(entry.getKey()), names));
        }
        return classes;
    }

    /** The rebuilt classes the transformer registered, moved under {@code prefix} as the embedder moves one mod's copy. */
    static Map<String, byte[]> rebuilt(RetromodTransformer transformer, String prefix) {
        Map<String, byte[]> classes = new HashMap<>();
        Remapper relocation = relocation(transformer, prefix);
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith(RebuiltApiClasses.ROOT)) classes.put(prefix + name, remap(bytes, relocation));
        });
        return classes;
    }

    /** A mod class after the transform, its rebuilt-class references moved under {@code prefix}. */
    static byte[] translated(RetromodTransformer transformer, String prefix, String name, byte[] bytes) {
        return remap(transformer.transformClass(bytes, name + ".class"), relocation(transformer, prefix));
    }

    private static Remapper relocation(RetromodTransformer transformer, String prefix) {
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith(RebuiltApiClasses.ROOT) && synthetics.containsKey(internalName)
                        ? prefix + internalName : internalName;
            }
        };
    }

    static ClassLoader loader(Map<String, byte[]> classes) {
        return new LegacyEventRuntimeTest.Loader(NbtHostFixtures.class.getClassLoader(), classes);
    }

    // ---- Forge 1.20.1 classes ----

    /**
     * {@code class name implements ICapabilitySerializable<CompoundTag>} (or INBTSerializable) as javac built it for
     * Forge 1.20.1: {@code serializeNBT()} saves {@code value} and the {@code inner} host object through Forge's
     * old interface method, {@code deserializeNBT(tag)} reads both back, and the erased bridges call them.
     */
    static byte[] oldProvider(String name, String superName, String iface) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, "L" + superName + ";L" + iface + "<" + L_COMPOUND + ">;",
                superName, new String[]{iface});
        cw.visitField(ACC_PUBLIC, "value", L_TAG, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "inner", "L" + INBT + ";", null, null).visitEnd();
        constructor(cw, superName);

        MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "serializeNBT", "()" + L_COMPOUND, null, null);
        save.visitCode();
        save.visitTypeInsn(NEW, COMPOUND);
        save.visitInsn(DUP);
        save.visitMethodInsn(INVOKESPECIAL, COMPOUND, "<init>", "()V", false);
        save.visitVarInsn(ASTORE, 1);
        put(save, "value", () -> {
            save.visitVarInsn(ALOAD, 0);
            save.visitFieldInsn(GETFIELD, name, "value", L_TAG);
        });
        put(save, "inner", () -> {
            save.visitVarInsn(ALOAD, 0);
            save.visitFieldInsn(GETFIELD, name, "inner", "L" + INBT + ";");
            save.visitMethodInsn(INVOKEINTERFACE, INBT, "serializeNBT", "()" + L_TAG, true);
        });
        save.visitVarInsn(ALOAD, 1);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();

        MethodVisitor load = cw.visitMethod(ACC_PUBLIC, "deserializeNBT", "(" + L_COMPOUND + ")V", null, null);
        load.visitCode();
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitLdcInsn("value");
        load.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "get", "(Ljava/lang/String;)" + L_TAG, false);
        load.visitFieldInsn(PUTFIELD, name, "value", L_TAG);
        load.visitVarInsn(ALOAD, 0);
        load.visitFieldInsn(GETFIELD, name, "inner", "L" + INBT + ";");
        load.visitVarInsn(ALOAD, 1);
        load.visitLdcInsn("inner");
        load.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "get", "(Ljava/lang/String;)" + L_TAG, false);
        load.visitMethodInsn(INVOKEINTERFACE, INBT, "deserializeNBT", "(" + L_TAG + ")V", true);
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();

        erasedBridges(cw, name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** javac's erased {@code Tag serializeNBT()} and {@code deserializeNBT(Tag)} that call the CompoundTag pair. */
    static void erasedBridges(ClassWriter cw, String name) {
        MethodVisitor save = cw.visitMethod(ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, "serializeNBT", "()" + L_TAG, null, null);
        save.visitCode();
        save.visitVarInsn(ALOAD, 0);
        save.visitMethodInsn(INVOKEVIRTUAL, name, "serializeNBT", "()" + L_COMPOUND, false);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        MethodVisitor load = cw.visitMethod(ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, "deserializeNBT", "(" + L_TAG + ")V", null, null);
        load.visitCode();
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitTypeInsn(CHECKCAST, COMPOUND);
        load.visitMethodInsn(INVOKEVIRTUAL, name, "deserializeNBT", "(" + L_COMPOUND + ")V", false);
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
    }

    /**
     * {@code class name extends ItemStackHandler}, overriding the old pair as Quark and Create do:
     * {@code serializeNBT()} adds {@code extra} to the super save and {@code deserializeNBT(tag)} reads it back.
     */
    static byte[] oldHandler(String name, String... interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, ITEM_HANDLER, interfaces);
        cw.visitField(ACC_PUBLIC, "extra", L_TAG, null, null).visitEnd();
        constructor(cw, ITEM_HANDLER);
        MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "serializeNBT", "()" + L_COMPOUND, null, null);
        save.visitCode();
        save.visitVarInsn(ALOAD, 0);
        save.visitMethodInsn(INVOKESPECIAL, ITEM_HANDLER, "serializeNBT", "()" + L_COMPOUND, false);
        save.visitVarInsn(ASTORE, 1);
        put(save, "extra", () -> {
            save.visitVarInsn(ALOAD, 0);
            save.visitFieldInsn(GETFIELD, name, "extra", L_TAG);
        });
        save.visitVarInsn(ALOAD, 1);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        MethodVisitor load = cw.visitMethod(ACC_PUBLIC, "deserializeNBT", "(" + L_COMPOUND + ")V", null, null);
        load.visitCode();
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitMethodInsn(INVOKESPECIAL, ITEM_HANDLER, "deserializeNBT", "(" + L_COMPOUND + ")V", false);
        load.visitVarInsn(ALOAD, 0);
        load.visitVarInsn(ALOAD, 1);
        load.visitLdcInsn("extra");
        load.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "get", "(Ljava/lang/String;)" + L_TAG, false);
        load.visitFieldInsn(PUTFIELD, name, "extra", L_TAG);
        load.visitInsn(RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        erasedBridges(cw, name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with one public static method {@code name(desc)} whose body {@code body} writes. */
    static byte[] caller(String className, String name, String desc, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, className, null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        MethodVisitor method = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        method.visitCode();
        body.accept(method);
        method.visitMaxs(0, 0);
        method.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    // tag.put(key, <value>), with the tag in local 1.
    private static void put(MethodVisitor method, String key, Runnable value) {
        method.visitVarInsn(ALOAD, 1);
        method.visitLdcInsn(key);
        value.run();
        method.visitMethodInsn(INVOKEVIRTUAL, COMPOUND, "put", "(Ljava/lang/String;" + L_TAG + ")" + L_TAG, false);
        method.visitInsn(POP);
    }

    // ---- reading results ----

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** CheckClassAdapter's report on a class, empty when it verifies. */
    static String verify(byte[] bytes, ClassLoader loader) {
        StringWriter report = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), loader, false, new PrintWriter(report));
        return report.toString();
    }

    static List<String> interfaces(byte[] bytes) {
        return node(bytes).interfaces;
    }

    static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] read(String internalName) throws IOException {
        try (InputStream in = NbtHostFixtures.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing test class " + internalName);
            return in.readAllBytes();
        }
    }
}
