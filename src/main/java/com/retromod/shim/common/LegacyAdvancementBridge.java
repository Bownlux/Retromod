/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Keeps pre-1.20.2 advancement lookups, progress reads, and criterion awards and revokes working. */
public final class LegacyAdvancementBridge {

    static final String HELPER = "com/retromod/generated/LegacyAdvancements";

    static final String MANAGER = "net/minecraft/server/ServerAdvancementManager";
    static final String PLAYER_ADVANCEMENTS = "net/minecraft/server/PlayerAdvancements";
    static final String ADVANCEMENT = "net/minecraft/advancements/Advancement";
    static final String HOLDER = "net/minecraft/advancements/AdvancementHolder";
    static final String PROGRESS = "net/minecraft/advancements/AdvancementProgress";
    static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    private static final String L_ADVANCEMENT = "L" + ADVANCEMENT + ";";
    private static final String L_HOLDER = "L" + HOLDER + ";";
    private static final String L_MANAGER = "L" + MANAGER + ";";
    private static final String L_PLAYER_ADVANCEMENTS = "L" + PLAYER_ADVANCEMENTS + ";";

    static final String GET_OR_START_OLD = "(" + L_ADVANCEMENT + ")L" + PROGRESS + ";";
    static final String GET_OR_START_NEW = "(" + L_HOLDER + ")L" + PROGRESS + ";";
    static final String CRITERION_OLD = "(" + L_ADVANCEMENT + "Ljava/lang/String;)Z";
    static final String CRITERION_NEW = "(" + L_HOLDER + "Ljava/lang/String;)Z";

    /** Fields on the helper: the last holder found, and the manager the last lookup used. */
    static final String LAST = "last";
    static final String SERVER_MANAGER = "manager";

    private LegacyAdvancementBridge() {}

    /** Registers the bridge on hosts where {@code get(id)} replaced {@code getAdvancement(id)}. */
    public static void register(RetromodTransformer transformer) {
        String idClass = hostIdClass();
        if (idClass != null) registerRedirects(transformer, idClass);
    }

    /** Unconditional registration for transform-shape tests; {@code idClass} is the host's id type. */
    static void registerRedirects(RetromodTransformer transformer, String idClass) {
        transformer.registerSyntheticClass(HELPER, generateHelper(idClass));
        // 1.20.2 split the advancement into its data, Advancement, and an AdvancementHolder that pairs it
        // with its id. getAdvancement(id) became get(id) and returns the holder.
        String lookup = "(L" + idClass + ";)" + L_ADVANCEMENT;
        String helperLookup = "(" + L_MANAGER + "L" + idClass + ";)" + L_ADVANCEMENT;
        transformer.registerMethodRedirect(MANAGER, "getAdvancement", lookup,
                HELPER, "getAdvancement", helperLookup, true);
        // A Fabric mod's getAdvancement keeps its intermediary id, which the 26.x name table
        // names get, with the old return type.
        transformer.registerMethodRedirect(MANAGER, "get", lookup, HELPER, "getAdvancement", helperLookup, true);
        // PlayerAdvancements now takes the holder for progress and for each criterion.
        transformer.registerMethodRedirect(PLAYER_ADVANCEMENTS, "getOrStartProgress", GET_OR_START_OLD,
                HELPER, "getOrStartProgress", receiverFirst(PLAYER_ADVANCEMENTS, GET_OR_START_OLD), true);
        for (String criterion : new String[] {"award", "revoke"}) {
            transformer.registerMethodRedirect(PLAYER_ADVANCEMENTS, criterion, CRITERION_OLD,
                    HELPER, criterion, receiverFirst(PLAYER_ADVANCEMENTS, CRITERION_OLD), true);
        }
    }

    /** The id type {@code get(id)} takes, or null when the host has no {@code get(id)}. */
    static String hostIdClass() {
        ClassNode manager = ClassResourceInspector.read(MANAGER);
        // Offline the host version decides. 1.21.11 renamed ResourceLocation to Identifier.
        if (manager == null) {
            if (hostBefore("1.20.2")) return null;
            return hostBefore("1.21.11") ? RESOURCE_LOCATION : IDENTIFIER;
        }
        return idClassOf(manager);
    }

    static String idClassOf(ClassNode manager) {
        for (String idClass : new String[] {IDENTIFIER, RESOURCE_LOCATION}) {
            if (hasMethod(manager, "getAdvancement", "(L" + idClass + ";)" + L_ADVANCEMENT)) return null;
            if (hasMethod(manager, "get", "(L" + idClass + ";)" + L_HOLDER)) return idClass;
        }
        return null;
    }

    private static boolean hostBefore(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) < 0;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** The static helper's descriptor for an instance method: the receiver, then the parameters. */
    private static String receiverFirst(String owner, String desc) {
        return "(L" + owner + ";" + desc.substring(1);
    }

    /** Writes the helper class for a host whose ids are {@code idClass}. */
    static byte[] generateHelper(String idClass) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        // The helper remembers the last holder and manager, so it can find a holder from its data.
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, LAST, L_HOLDER, null, null)
                .visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, SERVER_MANAGER, L_MANAGER,
                null, null).visitEnd();
        emitLookup(cw, idClass);
        emitHolderFor(cw);
        emitGetOrStartProgress(cw);
        emitCriterion(cw, "award");
        emitCriterion(cw, "revoke");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes {@code getAdvancement(manager, id)}: the holder's data, or null for an unknown id. */
    private static void emitLookup(ClassWriter cw, String idClass) {
        MethodVisitor mv = begin(cw, Opcodes.ACC_PUBLIC, "getAdvancement",
                "(" + L_MANAGER + "L" + idClass + ";)" + L_ADVANCEMENT);
        Label found = new Label();
        // Remember the manager, so a later call can search it for a holder.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, SERVER_MANAGER, L_MANAGER);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MANAGER, "get", "(L" + idClass + ";)" + L_HOLDER, false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, found);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        // An old mod keeps the result in an Advancement local, so it gets the data, and the holder
        // is remembered for when the mod passes that data back.
        mv.visitLabel(found);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, LAST, L_HOLDER);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOLDER, "value", "()" + L_ADVANCEMENT, false);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** Writes {@code holderFor(advancement)}, which finds the holder for an advancement's data. */
    private static void emitHolderFor(ClassWriter cw) {
        MethodVisitor mv = begin(cw, Opcodes.ACC_PRIVATE, "holderFor", "(" + L_ADVANCEMENT + ")" + L_HOLDER);
        Label known = new Label();
        Label search = new Label();
        Label loop = new Label();
        Label none = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNULL, none);
        // First try the last holder found, when it holds this exact data.
        mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER, LAST, L_HOLDER);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNULL, search);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOLDER, "value", "()" + L_ADVANCEMENT, false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, known);
        // Else search the remembered manager for the holder of this exact data.
        mv.visitLabel(search);
        mv.visitFieldInsn(Opcodes.GETSTATIC, HELPER, SERVER_MANAGER, L_MANAGER);
        mv.visitInsn(Opcodes.DUP);
        Label scan = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, scan);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, none);
        mv.visitLabel(scan);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MANAGER, "getAllAdvancements", "()Ljava/util/Collection;", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, none);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, HOLDER);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOLDER, "value", "()" + L_ADVANCEMENT, false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, loop);
        // Remember the match, then return it. Without one, the answer is null.
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, HELPER, LAST, L_HOLDER);
        mv.visitLabel(known);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** Writes {@code getOrStartProgress(advancements, advancement)}, empty when there is no holder. */
    private static void emitGetOrStartProgress(ClassWriter cw) {
        MethodVisitor mv = begin(cw, Opcodes.ACC_PUBLIC, "getOrStartProgress",
                receiverFirst(PLAYER_ADVANCEMENTS, GET_OR_START_OLD));
        Label found = new Label();
        // An advancement the server does not have, such as one whose file failed to load, has no
        // holder. It gets a fresh progress, so a mod that checks it every tick does not fail.
        loadHolder(mv);
        mv.visitJumpInsn(Opcodes.IFNONNULL, found);
        mv.visitTypeInsn(Opcodes.NEW, PROGRESS);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PROGRESS, "<init>", "()V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(found);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PLAYER_ADVANCEMENTS, "getOrStartProgress", GET_OR_START_NEW, false);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    /** Writes {@code award} or {@code revoke}: the host's answer, or false without a holder. */
    private static void emitCriterion(ClassWriter cw, String name) {
        MethodVisitor mv = begin(cw, Opcodes.ACC_PUBLIC, name, receiverFirst(PLAYER_ADVANCEMENTS, CRITERION_OLD));
        Label found = new Label();
        // Awarding or revoking an advancement the server does not have does nothing.
        loadHolder(mv);
        mv.visitJumpInsn(Opcodes.IFNONNULL, found);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(found);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PLAYER_ADVANCEMENTS, name, CRITERION_NEW, false);
        mv.visitInsn(Opcodes.IRETURN);
        end(mv);
    }

    /** Stores the holder for the advancement in local 1 into local 3 and leaves it on the stack. */
    private static void loadHolder(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "holderFor", "(" + L_ADVANCEMENT + ")" + L_HOLDER, false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
    }

    private static MethodVisitor begin(ClassWriter cw, int access, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(access | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
