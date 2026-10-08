/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the five-argument {@code ModNioResourcePack.create} from Fabric API's resource loader.
 *
 * <p>The method is Fabric API internals, but mods with their own pack folders call it directly.
 * Palladium's addon packs are one example. Newer Fabric API added a trailing {@code modBundled}
 * flag, so the old call dies with {@code NoSuchMethodError} while the server lists its packs. The
 * old method treated every pack it created as part of the mod, so the bridge passes {@code true}.
 */
public final class FabricModNioResourcePackBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String PACK = "net/fabricmc/fabric/impl/resource/loader/ModNioResourcePack";
    static final String FACTORY = "com/retromod/generated/LegacyModNioResourcePacks";
    static final String OLD_PARAMS = "(Ljava/lang/String;Lnet/fabricmc/loader/api/ModContainer;"
            + "Ljava/lang/String;Lnet/minecraft/class_3264;"
            + "Lnet/fabricmc/fabric/api/resource/ResourcePackActivationType;";
    static final String OLD_DESC = OLD_PARAMS + ")L" + PACK + ";";
    static final String NEW_DESC = OLD_PARAMS + "Z)L" + PACK + ";";

    private FabricModNioResourcePackBridge() {}

    /** Registers only when the installed Fabric API has the new signature and not the old one. */
    public static void register(RetromodTransformer transformer) {
        ClassNode pack = ClassResourceInspector.read(PACK);
        if (pack == null || hasMethod(pack, OLD_DESC) || !hasMethod(pack, NEW_DESC)) {
            LOGGER.debug("ModNioResourcePack.create support is not needed on this Fabric API");
            return;
        }
        registerRedirects(transformer);
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        transformer.registerMethodRedirect(PACK, "create", OLD_DESC, FACTORY, "create", OLD_DESC);
    }

    private static boolean hasMethod(ClassNode owner, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals("create") && method.desc.equals(desc)) return true;
        }
        return false;
    }

    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                FACTORY, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                OLD_DESC, null, null);
        mv.visitCode();
        for (int slot = 0; slot < 5; slot++) {
            mv.visitVarInsn(Opcodes.ALOAD, slot);
        }
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PACK, "create", NEW_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
