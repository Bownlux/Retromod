/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps 1.21.x cauldron interaction registration working on 26.1.
 *
 * <p>Through 1.21.11 the four cauldron tables were {@code CauldronInteraction.EMPTY}, {@code WATER},
 * {@code LAVA}, and {@code POWDER_SNOW}, each an {@code InteractionMap} record whose {@code map()}
 * a mod filled with {@code put(item, interaction)}. 26.1 moved the tables to
 * {@code CauldronInteractions} as {@code CauldronInteraction$Dispatcher} objects (verified against
 * the 26.1.2 jar). A mod adding a cauldron interaction then fails with {@code NoSuchFieldError} in
 * its entry point (Sophisticated Backpacks, #249).
 *
 * <p>The field reads move to the new tables, and {@code map()} returns the dispatcher's own item
 * map, so a {@code put} lands in the live table. The dispatcher keeps that map private, so the
 * helper reads it reflectively.
 */
public class LegacyCauldronInteractionShim implements MinecraftVersionedApiShim {

    static final String INTERACTION = "net/minecraft/core/cauldron/CauldronInteraction";
    static final String OLD_MAP = "net/minecraft/core/cauldron/CauldronInteraction$InteractionMap";
    static final String DISPATCHER = "net/minecraft/core/cauldron/CauldronInteraction$Dispatcher";
    static final String TABLES = "net/minecraft/core/cauldron/CauldronInteractions";
    static final List<String> TABLE_NAMES = List.of("EMPTY", "WATER", "LAVA", "POWDER_SNOW");
    public static final String HELPER = "com/retromod/shim/common/embedded/LegacyCauldronInteractionMap";

    @Override
    public String getShimName() {
        return "Legacy Cauldron Interaction Maps";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.11";
    }

    @Override
    public String getTargetVersion() {
        return "26.1";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerMethodRedirect(OLD_MAP, "map", "()Ljava/util/Map;",
                HELPER, "map", "(Ljava/lang/Object;)Ljava/util/Map;", true);
        for (String table : TABLE_NAMES) {
            transformer.registerFieldRedirect(INTERACTION, table, "L" + OLD_MAP + ";",
                    TABLES, table, "L" + DISPATCHER + ";");
        }
        transformer.registerClassRedirect(OLD_MAP, DISPATCHER);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    /** {@code static Map map(Object dispatcher)}: the dispatcher's private item map. */
    public static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "map",
                "(Ljava/lang/Object;)Ljava/util/Map;", null,
                new String[]{"java/lang/ReflectiveOperationException"});
        m.visitCode();
        m.visitLdcInsn(Type.getObjectType(DISPATCHER));
        m.visitLdcInsn("items");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, "java/util/Map");
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
