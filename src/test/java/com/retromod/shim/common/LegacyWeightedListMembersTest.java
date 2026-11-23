/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static com.retromod.shim.common.LegacyWeightedListBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** The two old weighted lists keep their meaning once both move onto WeightedList: values with weights, or weighted entries. */
class LegacyWeightedListMembersTest {

    private static final String LIST = "L" + NEW + ";";
    private static final String OLD_RETURN = "Lnet/minecraft/util/random/WeightedRandomList;";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A simple list's getRandom wraps the value, while an entry list's getRandom still returns the entry, with both class moves registered")
    void getRandomKeepsEachListsMeaning() {
        register();
        assertEquals(List.of(HELPER + ".wrappedRandom(" + LIST + RANDOM + ")Ljava/util/Optional;"),
                calls(OLD, "getRandom", "(" + RANDOM + ")Ljava/util/Optional;", false));
        assertEquals(List.of(NEW + ".getRandom(" + RANDOM + ")Ljava/util/Optional;"),
                calls(ENTRIES, "getRandom", "(" + RANDOM + ")Ljava/util/Optional;", false));
    }

    @Test
    @DisplayName("Entry lists are built, unwrapped and read from each entry's own weight, and the wrapped codec is the host's")
    void entryListsAndCodecs() {
        register();
        assertEquals(List.of(HELPER + ".fromEntries(Ljava/util/List;)" + LIST),
                calls(ENTRIES, "create", "(Ljava/util/List;)" + OLD_RETURN, true));
        assertEquals(List.of(HELPER + ".fromEntries([Ljava/lang/Object;)" + LIST),
                calls(OLD, "create", "([Lnet/minecraft/util/random/WeightedEntry;)" + OLD_RETURN, true));
        assertEquals(List.of(HELPER + ".entries(" + LIST + ")Ljava/util/List;"),
                calls(ENTRIES, "unwrap", "()Ljava/util/List;", false));
        assertEquals(List.of(HELPER + ".entryCodec(" + CODEC + ")" + CODEC),
                calls(ENTRIES, "codec", "(" + CODEC + ")" + CODEC, true));
        assertEquals(List.of(NEW + ".codec(" + CODEC + ")" + CODEC),
                calls(OLD, "wrappedCodec", "(" + CODEC + ")" + CODEC, true));
    }

    /** Registers this bridge with the existing weighted class moves, for a 1.21.1 mod on 26.3. */
    private void register() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD, NEW);
        transformer.registerClassRedirect(ENTRIES, NEW);
        transformer.registerClassRedirect(OLD + "$Builder", NEW + "$Builder");
        LegacyWeightedListBridge.register(transformer, name -> null);
        transformer.setModSourceVersion("1.21.1");
    }

    /** The calls in {@code mod/Lists.call} after one old call to {@code owner.name}, static or on a list argument. */
    private List<String> calls(String owner, String name, String desc, boolean isStatic) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Lists", null, "java/lang/Object", null);
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
        String callDesc = isStatic ? desc : "(L" + owner + ";" + desc.substring(1);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", callDesc, null, null);
        mv.visitCode();
        int slots = (isStatic ? 0 : 1) + args.length;
        for (int i = 0; i < slots; i++) mv.visitVarInsn(Opcodes.ALOAD, i);
        mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
        mv.visitInsn(org.objectweb.asm.Type.getReturnType(desc).getSort() == org.objectweb.asm.Type.VOID ? Opcodes.RETURN : Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return BridgeHarness.calls(transformer.transformClass(cw.toByteArray(), "mod/Lists"), "call");
    }
}
