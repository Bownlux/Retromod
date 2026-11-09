/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLootEntryTypeSupport;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Keeps the loot entry types code built before 26.1 reads, from the constants 26.1 removed and from {@code getType()}. */
public final class LegacyLootEntryTypeBridge {

    private static final String ENTRIES = "net/minecraft/world/level/storage/loot/entries/";
    static final String CONSTANTS_OWNER = ENTRIES + "LootPoolEntries";
    static final String CONTAINER = ENTRIES + "LootPoolEntryContainer";
    static final String OLD_TYPE = ENTRIES + "LootPoolEntryType";
    static final String STAND_IN = "com/retromod/shim/common/embedded/LootPoolEntryType";
    static final String TYPES = "com/retromod/generated/LegacyLootEntryTypes";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyLootEntryTypeSupport";
    static final String FIRST_HOST_WITHOUT = "26.1";
    static final String TYPE_OF_DESC = "(L" + CONTAINER + ";)L" + STAND_IN + ";";

    /** Each removed constant with the id of the type it held; 1.20.1 named the nested table type REFERENCE. */
    static final String[][] CONSTANTS = {
            {"EMPTY", "empty"}, {"ITEM", "item"}, {"LOOT_TABLE", "loot_table"}, {"REFERENCE", "loot_table"},
            {"DYNAMIC", "dynamic"}, {"TAG", "tag"}, {"SLOTS", "slots"}, {"ALTERNATIVES", "alternatives"},
            {"SEQUENCE", "sequence"}, {"GROUP", "group"}};

    private LegacyLootEntryTypeBridge() {}

    /** Registers on a host whose entry types are map codecs, after the type stand-in from the 26.1 class moves. */
    public static void register(RetromodTransformer t) {
        if (!hostDroppedTypes()) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyLootEntryTypeSupport.class);
        t.registerSyntheticClass(TYPES, generateTypes());
        // A read of a removed constant becomes a call that hands out the registered codec as the old type.
        for (String[] constant : CONSTANTS) {
            t.registerStaticFieldBridge(CONSTANTS_OWNER, constant[0], TYPES, constant[0], "()L" + STAND_IN + ";", null, null);
        }
        // getType() on any entry, vanilla or old. javac names the receiver's static type, often a subclass.
        t.registerInheritedMethodRedirect(CONTAINER, "getType", "()L" + OLD_TYPE + ";", TYPES, "typeOf", TYPE_OF_DESC);
    }

    /** Read from the host: 26.1 has no type constants. Offline, the target version decides. */
    static boolean hostDroppedTypes() {
        ClassNode constants = ClassResourceInspector.read(CONSTANTS_OWNER);
        if (constants == null) {
            String host = RetromodVersion.TARGET_MC_VERSION;
            return host != null && RetromodVersion.compareMcVersions(host, FIRST_HOST_WITHOUT) >= 0;
        }
        return constants.fields.stream().noneMatch(field -> field.name.equals("ITEM"));
    }

    /**
     * {@code LegacyLootEntryTypes}: one getter per removed constant and {@code typeOf(entry)}, each passing this
     * mod's copy of the type stand-in to the support class, which builds or finds the type.
     */
    static byte[] generateTypes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, TYPES, null, "java/lang/Object", null);
        for (String[] constant : CONSTANTS) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, constant[0], "()L" + STAND_IN + ";", null, null);
            mv.visitCode();
            mv.visitLdcInsn(Type.getObjectType(STAND_IN));
            mv.visitLdcInsn(constant[1]);
            mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "entryType", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, STAND_IN);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        MethodVisitor typeOf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "typeOf", TYPE_OF_DESC, null, null);
        typeOf.visitCode();
        typeOf.visitLdcInsn(Type.getObjectType(STAND_IN));
        typeOf.visitVarInsn(ALOAD, 0);
        typeOf.visitMethodInsn(INVOKESTATIC, SUPPORT, "typeOf", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
        typeOf.visitTypeInsn(CHECKCAST, STAND_IN);
        typeOf.visitInsn(ARETURN);
        typeOf.visitMaxs(0, 0);
        typeOf.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
