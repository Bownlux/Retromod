/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Sends 1.20.1's {@code LootContext.Builder.create(id)} to the {@code create(Optional)} form 1.20.2 replaced it with. */
public final class LegacyLootContextCreateBridge {

    static final String BUILDER = "net/minecraft/world/level/storage/loot/LootContext$Builder";
    static final String CONTEXT = "Lnet/minecraft/world/level/storage/loot/LootContext;";
    public static final String HELPER = "com/retromod/generated/LegacyLootContextCreate";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String OPTIONAL_DESC = "(Ljava/util/Optional;)" + CONTEXT;
    static final String HELPER_DESC = "(L" + BUILDER + ";L" + IDENTIFIER + ";)" + CONTEXT;

    private LegacyLootContextCreateBridge() {}

    /** Registers on 26.x hosts whose builder has only the Optional form; offline, the 26.1 chain that calls this answers. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    public static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode builder = host.apply(BUILDER);
        if (builder != null && (declares(builder, "(L" + IDENTIFIER + ";)" + CONTEXT) || !declares(builder, OPTIONAL_DESC))) return;
        t.registerSyntheticClass(HELPER, generateHelper());
        // A mod names the id type its own version had; the 26.1 rename makes both spellings Identifier.
        for (String id : new String[] {IDENTIFIER, "net/minecraft/resources/ResourceLocation"}) {
            t.registerMethodRedirect(BUILDER, "create", "(L" + id + ";)" + CONTEXT, HELPER, "create", HELPER_DESC, true);
        }
    }

    private static boolean declares(ClassNode node, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals("create") && m.desc.equals(desc));
    }

    /** {@code create(builder, id)}: {@code builder.create(Optional.ofNullable(id))}, where a null id meant no table. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", HELPER_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "create", OPTIONAL_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
