/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyEquipmentSupport;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps a 1.20.5 to 1.21.1 mod's registered armor materials working on 26.x, which made them plain records. */
public final class LegacyArmorMaterialRecordBridge {

    static final String CALLS = "com/retromod/generated/LegacyArmorMaterials";
    static final String LAYER = "com/retromod/generated/LegacyArmorLayer";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyEquipmentSupport";

    private static final String OLD_RECORD = "net/minecraft/world/item/ArmorMaterial";
    private static final String RECORD = "net/minecraft/world/item/equipment/ArmorMaterial";
    static final String OLD_LAYER = OLD_RECORD + "$Layer";
    private static final String KEY = "net/minecraft/resources/ResourceKey";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String ID = "net/minecraft/resources/Identifier";
    private static final String OBJECT = "Ljava/lang/Object;";
    static final String OLD_CTOR = "(Ljava/util/Map;ILnet/minecraft/core/Holder;Ljava/util/function/Supplier;Ljava/util/List;FF)V";

    private LegacyArmorMaterialRecordBridge() {}

    /** Registers on a 26.x host, whose armor materials are records with no registry. */
    public static void register(RetromodTransformer t) {
        if (!hostDroppedTheRegistry()) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyEquipmentSupport.class);
        t.registerSyntheticClass(CALLS, generateCalls());
        t.registerSyntheticClass(LAYER, generateLayer());
        // The registry is rebuilt under Retromod's own id, so both of its old names reach it.
        t.registerExactFieldRedirect("net/minecraft/core/registries/Registries", "ARMOR_MATERIAL", "L" + KEY + ";",
                CALLS, "armorMaterialKey", "()L" + KEY + ";");
        t.registerExactFieldRedirect("net/minecraft/core/registries/BuiltInRegistries", "ARMOR_MATERIAL", "L" + REGISTRY + ";",
                CALLS, "armorMaterialRegistry", "()L" + REGISTRY + ";");
        // The old record constructor, under its old name and the package 1.21.2 moved it to.
        String factory = OLD_CTOR.substring(0, OLD_CTOR.length() - 1) + "L" + RECORD + ";";
        for (String owner : List.of(OLD_RECORD, RECORD)) {
            t.registerConstructorRedirect(owner, OLD_CTOR, CALLS, "create", factory);
        }
        // A layer only named the texture, which is now the equipment asset.
        t.registerClassRedirect(OLD_LAYER, LAYER);
    }

    // Offline, where the host cannot be read, the version decides.
    private static boolean hostDroppedTheRegistry() {
        ClassNode registries = ClassResourceInspector.read("net/minecraft/core/registries/Registries");
        if (registries == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        return registries.fields.stream().noneMatch(field -> field.name.equals("ARMOR_MATERIAL"))
                && ClassResourceInspector.exists(ID)
                && ClassResourceInspector.exists("net/minecraft/world/item/equipment/EquipmentAssets");
    }

    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        forward(cw, "armorMaterialKey", "()L" + KEY + ";", "armorMaterialKey", "()" + OBJECT);
        forward(cw, "armorMaterialRegistry", "()L" + REGISTRY + ";", "armorMaterialRegistry", "()" + OBJECT);
        forward(cw, "create", OLD_CTOR.substring(0, OLD_CTOR.length() - 1) + "L" + RECORD + ";", "oldArmorMaterial",
                "(" + OBJECT + "I" + OBJECT + OBJECT + OBJECT + "FF)" + OBJECT);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static R name(args)}: the arguments, references passed as objects, to the helper, its result cast to R. */
    private static void forward(ClassWriter cw, String name, String desc, String helper, String helperDesc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        int slot = 0;
        for (Type argument : Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(argument.getOpcode(ILOAD), slot);
            slot += argument.getSize();
        }
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, helper, helperDesc, false);
        mv.visitTypeInsn(CHECKCAST, Type.getReturnType(desc).getInternalName());
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code ArmorMaterial.Layer}: its asset name, from either of its constructors. */
    static byte[] generateLayer() {
        String id = "L" + ID + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, LAYER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "assetName", id, null, null).visitEnd();
        for (String desc : List.of("(" + id + ")V", "(" + id + "Ljava/lang/String;Z)V")) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitFieldInsn(PUTFIELD, LAYER, "assetName", id);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        MethodVisitor getter = cw.visitMethod(ACC_PUBLIC, "assetName", "()" + id, null, null);
        getter.visitCode();
        getter.visitVarInsn(ALOAD, 0);
        getter.visitFieldInsn(GETFIELD, LAYER, "assetName", id);
        getter.visitInsn(ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
