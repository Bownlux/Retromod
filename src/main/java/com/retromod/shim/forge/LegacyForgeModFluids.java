/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Sends Forge 1.20.1 {@code ForgeMod} fluid constants and {@code enableMilkFluid} to NeoForge's {@code NeoForgeMod}. */
public final class LegacyForgeModFluids {

    static final String FORGE_MOD = "net/minecraftforge/common/ForgeMod";
    static final String NEO_MOD = "net/neoforged/neoforge/common/NeoForgeMod";
    static final String HELPER = "com/retromod/generated/LegacyForgeModFluids";
    static final String REGISTRY_OBJECT = "Lnet/minecraftforge/registries/RegistryObject;";
    static final String DEFERRED_HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";
    static final String HOLDER = "Lnet/minecraft/core/Holder;";

    /** NeoForge declares these as {@code Holder}, though each is the {@code DeferredHolder} its register call made. */
    static final String[] FLUID_TYPES = {"EMPTY_TYPE", "WATER_TYPE", "LAVA_TYPE"};
    /** These kept their {@code DeferredHolder} type, so a plain owner change is enough. */
    static final String[] SAME_TYPE = {"MILK", "FLOWING_MILK", "MILK_TYPE", "BUCKET_EMPTY_MILK", "BUCKET_FILL_MILK"};

    private LegacyForgeModFluids() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode neoMod = host.apply(NEO_MOD);
        List<String> types = new ArrayList<>();
        for (String field : FLUID_TYPES) {
            if (hasField(neoMod, field, HOLDER)) types.add(field);
        }
        if (!types.isEmpty()) {
            t.registerSyntheticClass(HELPER, generateHelper(types));
            // The mod calls get() on what it read, so the getter hands back the holder as a DeferredHolder.
            for (String field : types) {
                t.registerFieldRedirect(FORGE_MOD, field, REGISTRY_OBJECT, HELPER, field, "()L" + DEFERRED_HOLDER + ";");
            }
        }
        for (String field : SAME_TYPE) {
            if (hasField(neoMod, field, "L" + DEFERRED_HOLDER + ";")) {
                t.registerFieldRedirect(FORGE_MOD, field, REGISTRY_OBJECT, NEO_MOD, field, "L" + DEFERRED_HOLDER + ";");
            }
        }
        if (neoMod == null || neoMod.methods.stream().anyMatch(m -> m.name.equals("enableMilkFluid") && m.desc.equals("()V"))) {
            t.registerMethodRedirect(FORGE_MOD, "enableMilkFluid", "()V", NEO_MOD, "enableMilkFluid", "()V");
        }
    }

    /** Offline without host jars, the NeoForge 21.1 and 26.3 shape (checked with javap) answers. */
    private static boolean hasField(ClassNode node, String name, String desc) {
        return node == null || node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc));
    }

    static byte[] generateHelper(List<String> fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        for (String field : fields) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, field, "()L" + DEFERRED_HOLDER + ";", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, NEO_MOD, field, HOLDER);
            mv.visitTypeInsn(CHECKCAST, DEFERRED_HOLDER);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
