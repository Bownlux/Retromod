/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps {@code ModList.get().isLoaded(id)} and the other mod list instance calls working on Forge hosts that made them static. */
public final class LegacyModListBridge {

    static final String MOD_LIST = "net/minecraftforge/fml/ModList";
    static final String LOADING_MOD_LIST = "net/minecraftforge/fml/loading/LoadingModList";

    /** A Forge class whose instance methods became static ones with the same descriptors. */
    record StaticApi(String owner, String helper, String[][] accessors, String[][] methods) {
        String receiver() {
            return "L" + owner + ";";
        }
    }

    // Forge 1.20.1's instance methods, each with the descriptor of the static method that replaced
    // it, and the static accessors that returned the instance, which now have nothing to return.
    static final StaticApi MODS = new StaticApi(MOD_LIST, "com/retromod/generated/LegacyModList",
            new String[][]{{MOD_LIST, "get"}},
            new String[][]{
                    {"getModFiles", "()Ljava/util/List;"},
                    {"getModFileById", "(Ljava/lang/String;)Lnet/minecraftforge/forgespi/language/IModFileInfo;"},
                    {"getModObjectById", "(Ljava/lang/String;)Ljava/util/Optional;"},
                    {"getModContainerById", "(Ljava/lang/String;)Ljava/util/Optional;"},
                    {"getModContainerByObject", "(Ljava/lang/Object;)Ljava/util/Optional;"},
                    {"getMods", "()Ljava/util/List;"},
                    {"isLoaded", "(Ljava/lang/String;)Z"},
                    {"size", "()I"},
                    {"getAllScanData", "()Ljava/util/List;"},
                    {"forEachModFile", "(Ljava/util/function/Consumer;)V"},
                    {"applyForEachModFile", "(Ljava/util/function/Function;)Ljava/util/stream/Stream;"},
                    {"forEachModContainer", "(Ljava/util/function/BiConsumer;)V"},
                    {"forEachModInOrder", "(Ljava/util/function/Consumer;)V"},
                    {"applyForEachModContainer", "(Ljava/util/function/Function;)Ljava/util/stream/Stream;"}});
    static final StaticApi LOADING_MODS = new StaticApi(LOADING_MOD_LIST, "com/retromod/generated/LegacyLoadingModList",
            new String[][]{{LOADING_MOD_LIST, "get"}, {"net/minecraftforge/fml/loading/FMLLoader", "getLoadingModList"}},
            new String[][]{
                    {"getModFiles", "()Ljava/util/List;"},
                    {"findResource", "(Ljava/lang/String;)Ljava/nio/file/Path;"},
                    {"getModFileById", "(Ljava/lang/String;)Lnet/minecraftforge/fml/loading/moddiscovery/ModFileInfo;"},
                    {"getMods", "()Ljava/util/List;"},
                    {"getErrors", "()Ljava/util/List;"},
                    {"getBrokenFiles", "()Ljava/util/List;"}});

    private LegacyModListBridge() {}

    public static void register(RetromodTransformer t) {
        for (StaticApi api : List.of(MODS, LOADING_MODS)) {
            if (hostMadeStatic(api, "getModFiles")) registerBridge(t, api);
        }
        // The active mod's container kept its job under the shorter name.
        String context = "net/minecraftforge/fml/ModLoadingContext";
        if (hostMadeStatic(MODS, "isLoaded") && !declares(ClassResourceInspector.read(context), "getActiveContainer")) {
            t.registerMethodRedirect(context, "getActiveContainer", "()Lnet/minecraftforge/fml/ModContainer;",
                    context, "getContainer", "()Lnet/minecraftforge/fml/ModContainer;");
        }
    }

    /** Read from the host when it can be; offline, a Forge target from 26.2, the first checked to have it. */
    static boolean hostMadeStatic(StaticApi api, String method) {
        ClassNode host = ClassResourceInspector.read(api.owner());
        if (host == null) {
            return !McReflect.isNeoForge()
                    && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0;
        }
        for (MethodNode candidate : host.methods) {
            if (candidate.name.equals(method)) return (candidate.access & ACC_STATIC) != 0;
        }
        return false;
    }

    private static boolean declares(ClassNode node, String method) {
        return node != null && node.methods.stream().anyMatch(m -> m.name.equals(method));
    }

    static void registerBridge(RetromodTransformer t, StaticApi api) {
        t.registerSyntheticClass(api.helper(), generate(api));
        // The accessors have nothing to return; the instance calls made on the result ignore it.
        for (String[] accessor : api.accessors()) {
            t.registerMethodRedirect(accessor[0], accessor[1], "()" + api.receiver(), api.helper(), "get", "()" + api.receiver());
        }
        for (String[] method : api.methods()) {
            t.registerMethodRedirect(api.owner(), method[0], method[1], api.helper(), method[0],
                    withReceiver(api, method[1]), true);
        }
    }

    private static String withReceiver(StaticApi api, String desc) {
        return "(" + api.receiver() + desc.substring(1);
    }

    /** {@code get()} returning null, and each instance method calling its static replacement. */
    static byte[] generate(StaticApi api) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, api.helper(), null, "java/lang/Object", null);
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "()" + api.receiver(), null, null);
        get.visitCode();
        get.visitInsn(ACONST_NULL);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        boolean isInterface = api == LOADING_MODS;
        for (String[] method : api.methods()) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, method[0], withReceiver(api, method[1]), null, null);
            mv.visitCode();
            // Slot 0 is the old receiver; the arguments after it pass straight through.
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(method[1])) {
                mv.visitVarInsn(argument.getOpcode(ILOAD), slot);
                slot += argument.getSize();
            }
            mv.visitMethodInsn(INVOKESTATIC, api.owner(), method[0], method[1], isInterface);
            mv.visitInsn(Type.getReturnType(method[1]).getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
