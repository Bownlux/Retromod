/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Answers Forge 1.20.1's {@code FMLEnvironment} fields on NeoForge, which turned two into getters and never had the third. */
public final class LegacyFmlEnvironmentBridge {

    // The Forge to NeoForge class redirect names NeoForge's class before these apply.
    static final String NEO = "net/neoforged/fml/loading/FMLEnvironment";
    static final String FORGE = "net/minecraftforge/fml/loading/FMLEnvironment";
    static final String LOADER = "net/neoforged/fml/loading/FMLLoader";
    static final String HELPER = "com/retromod/generated/LegacyFmlEnvironment";
    static final String DIST = "Lnet/neoforged/api/distmarker/Dist;";
    static final String STRING = "Ljava/lang/String;";
    /** The NeoForge release whose FMLEnvironment has only {@code getDist()} and {@code isProduction()}. */
    static final String GETTERS_FROM = "1.21.9";

    private LegacyFmlEnvironmentBridge() {}

    static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector.read(NEO));
        // NeoForge 1.21.9 made FMLLoader an instance. A Forge mod's FMLLoader becomes NeoForge's through
        // the rename table, and a multi-loader jar also names NeoForge's directly, so both need its statics.
        if (loaderIsInstance(ClassResourceInspector.read(LOADER))) {
            com.retromod.shim.neoforge.NeoForge_1_21_8_to_1_21_9.registerLoaderStatics(transformer);
        }
    }

    /** For a Forge mod of any version on NeoForge, from the API shim every such mod takes offline and in game. */
    public static void registerForForgeMods(RetromodTransformer transformer) {
        // The 1.20 to NeoForge shim moves the class only for mods it chains, so a 1.21.1 mod kept Forge's name offline.
        transformer.registerClassRedirect(FORGE, NEO);
        register(transformer);
    }

    /** {@code host} is NeoForge's FMLLoader, or null when it cannot be read, as offline. */
    static boolean loaderIsInstance(ClassNode host) {
        if (host == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, GETTERS_FROM) >= 0;
        }
        return host.methods.stream().anyMatch(method -> method.name.equals("getCurrent")
                && (method.access & Opcodes.ACC_STATIC) != 0);
    }

    /** {@code host} is NeoForge's FMLEnvironment, or null when it cannot be read, as offline. */
    static void register(RetromodTransformer transformer, ClassNode host) {
        // NeoForge 1.21.9 replaced the dist and production fields with static getters. The NeoForge
        // chain registers the same for NeoForge mods; a Forge mod's offline chain does not include it.
        if (onlyGetter(host, "dist", "getDist", "()" + DIST)) {
            transformer.registerFieldRedirect(NEO, "dist", DIST, NEO, "getDist", "()" + DIST);
        }
        if (onlyGetter(host, "production", "isProduction", "()Z")) {
            transformer.registerFieldRedirect(NEO, "production", "Z", NEO, "isProduction", "()Z");
        }
        // Forge's naming told a mod which member names the game ran. NeoForge runs Mojang's names,
        // which Forge called mcp, and has no such field.
        if (host == null || !hasField(host, "naming")) {
            transformer.registerSyntheticClass(HELPER, generateHelper());
            transformer.registerFieldRedirect(NEO, "naming", STRING, HELPER, "naming", "()" + STRING);
        }
    }

    private static boolean onlyGetter(ClassNode host, String field, String getter, String getterDesc) {
        if (host == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, GETTERS_FROM) >= 0;
        }
        if (hasField(host, field)) return false;
        for (MethodNode method : host.methods) {
            if (method.name.equals(getter) && method.desc.equals(getterDesc)
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasField(ClassNode host, String name) {
        for (FieldNode field : host.fields) {
            if (field.name.equals(name)) return true;
        }
        return false;
    }

    /** {@code naming()}, which answers {@code "mcp"}. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        MethodVisitor naming = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "naming", "()" + STRING,
                null, null);
        naming.visitCode();
        naming.visitLdcInsn("mcp");
        naming.visitInsn(Opcodes.ARETURN);
        naming.visitMaxs(0, 0);
        naming.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
