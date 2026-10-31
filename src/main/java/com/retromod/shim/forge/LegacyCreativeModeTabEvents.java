/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyCreativeTabEvents;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps Forge 1.19.4's creative tab events and {@code CreativeModeTab} constants working on Forge 1.20 and newer. */
public final class LegacyCreativeModeTabEvents {

    static final String CALLS = "com/retromod/generated/LegacyCreativeModeTabs";
    static final String SUPPORT = "com/retromod/shim/forge/embedded/LegacyCreativeTabEvents";

    private static final String OLD_EVENT = "net/minecraftforge/event/CreativeModeTabEvent";
    static final String OLD_BUILD = OLD_EVENT + "$BuildContents";
    static final String OLD_REGISTER = OLD_EVENT + "$Register";
    static final String BUILD_EVENT = "net/minecraftforge/event/BuildCreativeModeTabContentsEvent";
    static final String REGISTER_EVENT = "net/minecraftforge/registries/RegisterEvent";
    private static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    private static final String BUILDER = TAB + "$Builder";
    private static final String TABS = "net/minecraft/world/item/CreativeModeTabs";
    private static final String ID = "net/minecraft/resources/ResourceLocation";
    private static final String KEY = "net/minecraft/resources/ResourceKey";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String L_TAB = "L" + TAB + ";";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String OLD_REGISTER_DESC = "(L" + ID + ";Ljava/util/function/Consumer;)" + L_TAB;
    private static final String OLD_ORDERED_DESC = "(L" + ID + ";Ljava/util/List;Ljava/util/List;Ljava/util/function/Consumer;)" + L_TAB;

    /** The 1.19.4 tab constants, which 1.20 turned from tabs into registry keys under the same names. */
    static final List<String> CONSTANTS = List.of("BUILDING_BLOCKS", "COLORED_BLOCKS", "NATURAL_BLOCKS",
            "FUNCTIONAL_BLOCKS", "REDSTONE_BLOCKS", "HOTBAR", "SEARCH", "TOOLS_AND_UTILITIES", "COMBAT",
            "FOOD_AND_DRINKS", "INGREDIENTS", "SPAWN_EGGS", "OP_BLOCKS", "INVENTORY");

    private LegacyCreativeModeTabEvents() {}

    public static void register(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyCreativeTabEvents.class);
        t.registerSyntheticClass(CALLS, generateCalls(RetromodVersion.TARGET_MC_VERSION));
        // BuildContents kept its members as Forge 1.20's BuildCreativeModeTabContentsEvent.
        t.registerClassRedirect(OLD_BUILD, BUILD_EVENT);
        // Register has no successor: tabs are registry entries, so its handler runs on RegisterEvent.
        t.registerClassRedirect(OLD_REGISTER, REGISTER_EVENT);
        String event = "(L" + REGISTER_EVENT + ";";
        for (String owner : List.of(OLD_REGISTER, REGISTER_EVENT)) {
            t.registerMethodRedirect(owner, "registerCreativeModeTab", OLD_REGISTER_DESC,
                    CALLS, "registerCreativeModeTab", event + OLD_REGISTER_DESC.substring(1), true);
            t.registerMethodRedirect(owner, "registerCreativeModeTab", OLD_ORDERED_DESC,
                    CALLS, "registerCreativeModeTab", event + OLD_ORDERED_DESC.substring(1), true);
        }
        // Exact descriptors: read as a tab it is a 1.19.4 constant, read as a key it is current code.
        for (String constant : CONSTANTS) {
            t.registerExactFieldRedirect(TABS, constant, L_TAB, CALLS, constant, "()" + L_TAB);
        }
    }

    static byte[] generateCalls(String host) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        // 1.21.2 made Registry.getOrThrow return a holder and added getValueOrThrow for the value.
        String lookup = RetromodVersion.compareMcVersions(host, "1.21.2") >= 0 ? "getValueOrThrow" : "getOrThrow";
        for (String constant : CONSTANTS) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, constant, "()" + L_TAB, null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "CREATIVE_MODE_TAB", "L" + REGISTRY + ";");
            mv.visitFieldInsn(GETSTATIC, TABS, constant, "L" + KEY + ";");
            mv.visitMethodInsn(INVOKEINTERFACE, REGISTRY, lookup, "(L" + KEY + ";)" + OBJECT, true);
            mv.visitTypeInsn(CHECKCAST, TAB);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        simpleCall(cw, "builder", "()L" + BUILDER + ";", INVOKESTATIC, TAB, "builder", "()L" + BUILDER + ";", false);
        simpleCall(cw, "build", "(L" + BUILDER + ";)" + L_TAB, INVOKEVIRTUAL, BUILDER, "build", "()" + L_TAB, false);
        writeIsTabRegistry(cw);
        writeId(cw);
        writeRegister(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static R name(arg?)}: one call on the argument, or with none. */
    private static void simpleCall(ClassWriter cw, String name, String desc, int opcode, String owner, String target,
            String targetDesc, boolean isInterface) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        if (!desc.startsWith("()")) mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(opcode, owner, target, targetDesc, isInterface);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code isTabRegistry(key)}: whether a RegisterEvent is the creative tab registry's turn. */
    private static void writeIsTabRegistry(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "isTabRegistry", "(" + OBJECT + ")Z", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", "CREATIVE_MODE_TAB", "L" + KEY + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals", "(" + OBJECT + ")Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code id(entry)}: an ordering entry, which Forge 1.19.4 took as an id, a tab, or id text. */
    private static void writeId(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "id", "(" + OBJECT + ")L" + ID + ";", null, null);
        mv.visitCode();
        Label notId = new Label();
        Label notTab = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, ID);
        mv.visitJumpInsn(IFEQ, notId);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, ID);
        mv.visitInsn(ARETURN);
        mv.visitLabel(notId);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, TAB);
        mv.visitJumpInsn(IFEQ, notTab);
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "CREATIVE_MODE_TAB", "L" + REGISTRY + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, REGISTRY, "getKey", "(" + OBJECT + ")L" + ID + ";", true);
        mv.visitInsn(ARETURN);
        mv.visitLabel(notTab);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(" + OBJECT + ")Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKESTATIC, ID, "tryParse", "(Ljava/lang/String;)L" + ID + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The old {@code registerCreativeModeTab} forms, the RegisterEvent first, forwarded to the helper. */
    private static void writeRegister(ClassWriter cw) {
        String event = "L" + REGISTER_EVENT + ";";
        MethodVisitor plain = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerCreativeModeTab",
                "(" + event + OLD_REGISTER_DESC.substring(1), null, null);
        plain.visitCode();
        plain.visitVarInsn(ALOAD, 0);
        plain.visitVarInsn(ALOAD, 1);
        plain.visitInsn(ACONST_NULL);
        plain.visitInsn(ACONST_NULL);
        plain.visitVarInsn(ALOAD, 2);
        plain.visitMethodInsn(INVOKESTATIC, CALLS, "registerCreativeModeTab", "(" + event + OLD_ORDERED_DESC.substring(1), false);
        plain.visitInsn(ARETURN);
        plain.visitMaxs(0, 0);
        plain.visitEnd();

        MethodVisitor ordered = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerCreativeModeTab",
                "(" + event + OLD_ORDERED_DESC.substring(1), null, null);
        ordered.visitCode();
        ordered.visitLdcInsn(org.objectweb.asm.Type.getObjectType(CALLS));
        for (int i = 0; i < 5; i++) ordered.visitVarInsn(ALOAD, i);
        ordered.visitMethodInsn(INVOKESTATIC, SUPPORT, "register",
                "(Ljava/lang/Class;" + OBJECT + OBJECT + "Ljava/util/List;Ljava/util/List;Ljava/util/function/Consumer;)" + OBJECT, false);
        ordered.visitTypeInsn(CHECKCAST, TAB);
        ordered.visitInsn(ARETURN);
        ordered.visitMaxs(0, 0);
        ordered.visitEnd();
    }
}
