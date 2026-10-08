/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.fabric.embedded.LegacyAttributeIds;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges UUID-keyed attribute modifiers to the identifier-keyed record Minecraft 1.21
 * introduced, for intermediary-namespace Fabric mods on pre-26.1 hosts.
 *
 * <p>Through 1.20.6 a modifier was built from a UUID and a name, and attribute instances looked
 * modifiers up and removed them by UUID. Since 1.21 a modifier is {@code (ResourceLocation, double,
 * Operation)} and every lookup takes the identifier. A 1.20.1 mod that builds a modifier in a
 * static initializer died with {@code NoSuchMethodError}. When that initializer belonged to a
 * mixin merged into {@code LivingEntity}, as Porting Lib's does, the whole server failed to start.
 * The host probe keys on the identifier record, so 1.20.5 and 1.20.6 hosts are left alone.
 *
 * <p>The UUID becomes the identifier {@code minecraft:<uuid>}, so the same UUID always maps to the
 * same modifier and a later lookup or removal by that UUID finds it. The old display name is
 * dropped, because the record has no name. A modifier built from a name alone gets a random UUID,
 * as it did before. {@code getId()} reads the UUID back from the identifier.
 */
public final class Pre1_20_5AttributeModifierBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String MODIFIER = "net/minecraft/class_1322";
    static final String OPERATION = "net/minecraft/class_1322$class_1323";
    static final String INSTANCE = "net/minecraft/class_1324";
    static final String ID = "net/minecraft/class_2960";
    static final String HELPER = "com/retromod/generated/LegacyAttributeModifiers";
    static final String IDS = "com/retromod/shim/fabric/embedded/LegacyAttributeIds";

    private static final String L_OP = "L" + OPERATION + ";";
    private static final String L_MODIFIER = "L" + MODIFIER + ";";
    private static final String L_ID = "L" + ID + ";";
    private static final String UUID = "Ljava/util/UUID;";
    static final String MODERN_CTOR = "(" + L_ID + "D" + L_OP + ")V";
    static final String UUID_NAME_CTOR = "(" + UUID + "Ljava/lang/String;D" + L_OP + ")V";
    static final String UUID_SUPPLIER_CTOR = "(" + UUID + "Ljava/util/function/Supplier;D" + L_OP + ")V";
    static final String NAME_CTOR = "(Ljava/lang/String;D" + L_OP + ")V";

    private static final String ID_NAME = "comp_2447";
    private static final String AMOUNT = "comp_2449";
    private static final String OPERATION_GETTER = "comp_2450";
    private static final String WITH_DEFAULT_NAMESPACE = "method_60656";
    private static final String GET_PATH = "method_12832";
    private static final String GET_MODIFIER = "method_6199";
    private static final String REMOVE_MODIFIER = "method_6200";

    private Pre1_20_5AttributeModifierBridge() {}

    /** Registers the bridge only when the host's modifier takes an identifier and no UUID. */
    public static void register(RetromodTransformer transformer) {
        ClassNode modifier = ClassResourceInspector.read(MODIFIER);
        if (modifier == null || !hasMethod(modifier, "<init>", MODERN_CTOR)
                || hasMethod(modifier, "<init>", UUID_NAME_CTOR)) {
            LOGGER.debug("The host still has UUID-keyed attribute modifiers");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for UUID-keyed attribute modifiers");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        SyntheticEmbedder.registerClassResource(transformer, IDS, LegacyAttributeIds.class);
        for (String ctor : new String[] {UUID_NAME_CTOR, UUID_SUPPLIER_CTOR, NAME_CTOR}) {
            transformer.registerConstructorRedirect(MODIFIER, ctor, HELPER, "create", factoryDesc(ctor));
        }
        transformer.registerMethodRedirect(MODIFIER, "method_6189", "()" + UUID,
                HELPER, "getId", "(" + L_MODIFIER + ")" + UUID);
        transformer.registerMethodRedirect(MODIFIER, "method_6185", "()Ljava/lang/String;",
                HELPER, "getName", "(" + L_MODIFIER + ")Ljava/lang/String;");
        transformer.registerMethodRedirect(MODIFIER, "method_6186", "()D", MODIFIER, AMOUNT, "()D");
        transformer.registerMethodRedirect(MODIFIER, "method_6182", "()" + L_OP, MODIFIER, OPERATION_GETTER,
                "()" + L_OP);
        transformer.registerMethodRedirect(INSTANCE, GET_MODIFIER, "(" + UUID + ")" + L_MODIFIER,
                HELPER, "getModifier", "(L" + INSTANCE + ";" + UUID + ")" + L_MODIFIER);
        transformer.registerMethodRedirect(INSTANCE, REMOVE_MODIFIER, "(" + UUID + ")V",
                HELPER, "removeModifier", "(L" + INSTANCE + ";" + UUID + ")V");
        transformer.registerMethodRedirect(INSTANCE, "method_27304", "(" + UUID + ")Z",
                HELPER, "removePermanentModifier", "(L" + INSTANCE + ";" + UUID + ")Z");
    }

    private static String factoryDesc(String ctorDesc) {
        return ctorDesc.substring(0, ctorDesc.length() - 1) + L_MODIFIER;
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);

        // idFor(uuid) = ResourceLocation.withDefaultNamespace(uuid.toString())
        MethodVisitor idFor = begin(cw, "idFor", "(" + UUID + ")" + L_ID);
        idFor.visitVarInsn(Opcodes.ALOAD, 0);
        idFor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/UUID", "toString", "()Ljava/lang/String;", false);
        idFor.visitMethodInsn(Opcodes.INVOKESTATIC, ID, WITH_DEFAULT_NAMESPACE, "(Ljava/lang/String;)" + L_ID, false);
        end(idFor, Opcodes.ARETURN);

        createFromUuid(cw, UUID_NAME_CTOR);
        createFromUuid(cw, UUID_SUPPLIER_CTOR);

        // create(name, amount, op): the old constructor drew a random UUID.
        MethodVisitor named = begin(cw, "create", factoryDesc(NAME_CTOR));
        named.visitTypeInsn(Opcodes.NEW, MODIFIER);
        named.visitInsn(Opcodes.DUP);
        named.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "randomUUID", "()" + UUID, false);
        named.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "idFor", "(" + UUID + ")" + L_ID, false);
        named.visitVarInsn(Opcodes.DLOAD, 1);
        named.visitVarInsn(Opcodes.ALOAD, 3);
        named.visitMethodInsn(Opcodes.INVOKESPECIAL, MODIFIER, "<init>", MODERN_CTOR, false);
        end(named, Opcodes.ARETURN);

        MethodVisitor getId = begin(cw, "getId", "(" + L_MODIFIER + ")" + UUID);
        getId.visitVarInsn(Opcodes.ALOAD, 0);
        getId.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIER, ID_NAME, "()" + L_ID, false);
        getId.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ID, GET_PATH, "()Ljava/lang/String;", false);
        getId.visitVarInsn(Opcodes.ALOAD, 0);
        getId.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIER, ID_NAME, "()" + L_ID, false);
        getId.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ID, "toString", "()Ljava/lang/String;", false);
        getId.visitMethodInsn(Opcodes.INVOKESTATIC, IDS, "uuidOf",
                "(Ljava/lang/String;Ljava/lang/String;)" + UUID, false);
        end(getId, Opcodes.ARETURN);

        MethodVisitor getName = begin(cw, "getName", "(" + L_MODIFIER + ")Ljava/lang/String;");
        getName.visitVarInsn(Opcodes.ALOAD, 0);
        getName.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODIFIER, ID_NAME, "()" + L_ID, false);
        getName.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ID, "toString", "()Ljava/lang/String;", false);
        end(getName, Opcodes.ARETURN);

        MethodVisitor getModifier = begin(cw, "getModifier", "(L" + INSTANCE + ";" + UUID + ")" + L_MODIFIER);
        loadInstanceAndId(getModifier);
        getModifier.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INSTANCE, GET_MODIFIER,
                "(" + L_ID + ")" + L_MODIFIER, false);
        end(getModifier, Opcodes.ARETURN);

        MethodVisitor remove = begin(cw, "removeModifier", "(L" + INSTANCE + ";" + UUID + ")V");
        loadInstanceAndId(remove);
        remove.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INSTANCE, REMOVE_MODIFIER, "(" + L_ID + ")Z", false);
        remove.visitInsn(Opcodes.POP);
        end(remove, Opcodes.RETURN);

        MethodVisitor removePermanent = begin(cw, "removePermanentModifier", "(L" + INSTANCE + ";" + UUID + ")Z");
        loadInstanceAndId(removePermanent);
        removePermanent.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INSTANCE, REMOVE_MODIFIER, "(" + L_ID + ")Z", false);
        end(removePermanent, Opcodes.IRETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code create(uuid, nameOrSupplier, amount, op) = new AttributeModifier(idFor(uuid), amount, op)}. */
    private static void createFromUuid(ClassWriter cw, String ctorDesc) {
        MethodVisitor mv = begin(cw, "create", factoryDesc(ctorDesc));
        mv.visitTypeInsn(Opcodes.NEW, MODIFIER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "idFor", "(" + UUID + ")" + L_ID, false);
        mv.visitVarInsn(Opcodes.DLOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MODIFIER, "<init>", MODERN_CTOR, false);
        end(mv, Opcodes.ARETURN);
    }

    private static void loadInstanceAndId(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "idFor", "(" + UUID + ")" + L_ID, false);
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
