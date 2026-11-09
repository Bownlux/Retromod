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
import org.objectweb.asm.Label;
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
    // it, and the static accessors that returned the instance, which now hand out the host's own object.
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
        // The accessors return the host's object so null checks pass; the instance calls ignore the receiver.
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

    /** {@code get()} handing out the host's mod list object, and each instance method calling its static replacement. */
    static byte[] generate(StaticApi api) {
        return generate(api, api == LOADING_MODS || hostListsLoadedMods());
    }

    /** Read from the host when it can be; offline, the Forge 26.2 shape this bridge is registered for. */
    static boolean hostListsLoadedMods() {
        ClassNode host = ClassResourceInspector.read(MOD_LIST);
        if (host == null) return true;
        return host.methods.stream().anyMatch(m -> m.name.equals("getLoadedMods")
                && m.desc.equals("()Ljava/util/List;")
                && (m.access & ACC_STATIC) != 0 && (m.access & ACC_PUBLIC) != 0);
    }

    /** Without {@code handsOutHostList}, {@code get()} keeps returning null, for a host that cannot say when its mods loaded. */
    static byte[] generate(StaticApi api, boolean handsOutHostList) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, api.helper(), null, "java/lang/Object", null);
        if (handsOutHostList) {
            cw.visitField(ACC_PRIVATE | ACC_STATIC, "instance", api.receiver(), null, null).visitEnd();
            writeGet(cw, api);
            writeFind(cw, api);
        } else {
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "()" + api.receiver(), null, null);
            get.visitCode();
            get.visitInsn(ACONST_NULL);
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
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

    /** {@code get()}: the cached host object, found on first use, and for the mod list only once mods have loaded. */
    private static void writeGet(ClassWriter cw, StaticApi api) {
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "()" + api.receiver(), null, null);
        get.visitCode();
        // Forge 1.20.1 returned null before its mod list was built, and the host's isLoaded fails until then.
        if (api == MODS) {
            Label loaded = new Label();
            get.visitMethodInsn(INVOKESTATIC, MOD_LIST, "getLoadedMods", "()Ljava/util/List;", false);
            get.visitJumpInsn(IFNONNULL, loaded);
            get.visitInsn(ACONST_NULL);
            get.visitInsn(ARETURN);
            get.visitLabel(loaded);
        }
        // A failed lookup leaves the cache empty, so the next call tries again.
        Label cached = new Label();
        get.visitFieldInsn(GETSTATIC, api.helper(), "instance", api.receiver());
        get.visitJumpInsn(IFNONNULL, cached);
        get.visitMethodInsn(INVOKESTATIC, api.helper(), "find", "()" + api.receiver(), false);
        get.visitFieldInsn(PUTSTATIC, api.helper(), "instance", api.receiver());
        get.visitLabel(cached);
        get.visitFieldInsn(GETSTATIC, api.helper(), "instance", api.receiver());
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
    }

    /** {@code find()}: the host's object through reflection, or null when the host does not allow it. */
    private static void writeFind(ClassWriter cw, StaticApi api) {
        MethodVisitor find = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "find", "()" + api.receiver(), null, null);
        find.visitCode();
        Label start = new Label(), end = new Label(), failed = new Label();
        find.visitTryCatchBlock(start, end, failed, "java/lang/ReflectiveOperationException");
        find.visitTryCatchBlock(start, end, failed, "java/lang/RuntimeException");
        find.visitLabel(start);
        find.visitLdcInsn(Type.getObjectType(api.owner()));
        if (api == MODS) {
            // ModList's private constructor does nothing; every instance call ignores the object anyway.
            find.visitInsn(ICONST_0);
            find.visitTypeInsn(ANEWARRAY, "java/lang/Class");
            find.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructor",
                    "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", false);
            find.visitInsn(DUP);
            find.visitInsn(ICONST_1);
            find.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Constructor", "setAccessible", "(Z)V", false);
            find.visitInsn(ICONST_0);
            find.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            find.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Constructor", "newInstance",
                    "([Ljava/lang/Object;)Ljava/lang/Object;", false);
        } else {
            // LoadingModList is sealed, so only the host's own object passes a cast; its private get() returns it.
            find.visitLdcInsn("get");
            find.visitInsn(ICONST_0);
            find.visitTypeInsn(ANEWARRAY, "java/lang/Class");
            find.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            find.visitInsn(DUP);
            find.visitInsn(ICONST_1);
            find.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible", "(Z)V", false);
            find.visitInsn(ACONST_NULL);
            find.visitInsn(ICONST_0);
            find.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            find.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
        find.visitTypeInsn(CHECKCAST, api.owner());
        find.visitLabel(end);
        find.visitInsn(ARETURN);
        // A host that closes its package or drops the member gets null instead of a crash.
        find.visitLabel(failed);
        find.visitInsn(POP);
        find.visitInsn(ACONST_NULL);
        find.visitInsn(ARETURN);
        find.visitMaxs(0, 0);
        find.visitEnd();
    }

    /** Frames for the branches above; the only merge is the two caught exception types, which the handler pops. */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }
}
