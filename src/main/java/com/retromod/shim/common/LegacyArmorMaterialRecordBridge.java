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
        // The registry is rebuilt under Retromod's own id, so both of its old names reach it. A Fabric mod
        // keeps the intermediary ids, which left before the 1.21.4 base of Retromod's intermediary table.
        for (String key : List.of("ARMOR_MATERIAL", "field_48977")) {
            t.registerExactFieldRedirect("net/minecraft/core/registries/Registries", key, "L" + KEY + ";",
                    CALLS, "armorMaterialKey", "()L" + KEY + ";");
        }
        for (String registry : List.of("ARMOR_MATERIAL", "field_48976")) {
            t.registerExactFieldRedirect("net/minecraft/core/registries/BuiltInRegistries", registry, "L" + REGISTRY + ";",
                    CALLS, "armorMaterialRegistry", "()L" + REGISTRY + ";");
        }
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

    /** {@code ArmorMaterial.Layer}: its asset name and suffix, from either of its constructors, and its old texture path. */
    static byte[] generateLayer() {
        String id = "L" + ID + ";";
        String string = "Ljava/lang/String;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, LAYER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "assetName", id, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "suffix", string, null, null).visitEnd();
        for (String desc : List.of("(" + id + ")V", "(" + id + string + "Z)V")) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitFieldInsn(PUTFIELD, LAYER, "assetName", id);
            // The one-argument layer had no suffix; a null one reads as none too.
            init.visitVarInsn(ALOAD, 0);
            if (desc.contains(string)) init.visitVarInsn(ALOAD, 2);
            else init.visitInsn(ACONST_NULL);
            init.visitLdcInsn("");
            init.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "toString", "(Ljava/lang/Object;" + string + ")" + string, false);
            init.visitFieldInsn(PUTFIELD, LAYER, "suffix", string);
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
        texture(cw, id, string);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code texture(inner)}: the old armor texture, {@code <ns>:textures/models/armor/<name>_layer_<1 or 2><suffix>.png}. */
    private static void texture(ClassWriter cw, String id, String string) {
        String builder = "java/lang/StringBuilder";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "texture", "(Z)" + id, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, LAYER, "assetName", id);
        mv.visitTypeInsn(NEW, builder);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("textures/models/armor/");
        mv.visitMethodInsn(INVOKESPECIAL, builder, "<init>", "(" + string + ")V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, LAYER, "assetName", id);
        mv.visitMethodInsn(INVOKEVIRTUAL, ID, "getPath", "()" + string, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "append", "(" + string + ")L" + builder + ";", false);
        mv.visitLdcInsn("_layer_");
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "append", "(" + string + ")L" + builder + ";", false);
        // The inner layer, used for leggings, is layer 2: the flag plus one, without a branch.
        mv.visitVarInsn(ILOAD, 1);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "append", "(I)L" + builder + ";", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, LAYER, "suffix", string);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "append", "(" + string + ")L" + builder + ";", false);
        mv.visitLdcInsn(".png");
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "append", "(" + string + ")L" + builder + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "toString", "()" + string, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, ID, "withPath", "(" + string + ")" + id, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
