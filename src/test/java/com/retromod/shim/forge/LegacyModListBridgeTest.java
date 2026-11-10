/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1 mods ask {@code ModList.get().isLoaded(id)}; Forge 26.2 made those methods static. */
class LegacyModListBridgeTest {

    private static final String MOD_LIST = LegacyModListBridge.MOD_LIST;
    private static final String HELPER = LegacyModListBridge.MODS.helper();
    private static final String LOADING_MOD_LIST = LegacyModListBridge.LOADING_MOD_LIST;
    private static final String LOADING_MOD_LIST_IMPL = "net/minecraftforge/fml/loading/LoadingModListImpl";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old instance call reaches the helper, and a newer mod's static call is left alone")
    void instanceCallsReachTheHelper() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyModListBridge.registerBridge(t, LegacyModListBridge.MODS);

        List<String> calls = calls(t.transformClass(caller(), "mod/Compat.class"));

        assertTrue(calls.contains(INVOKESTATIC + " " + HELPER + ".get()L" + MOD_LIST + ";"), calls.toString());
        assertTrue(calls.contains(INVOKESTATIC + " " + HELPER + ".isLoaded(L" + MOD_LIST + ";Ljava/lang/String;)Z"), calls.toString());
        assertTrue(calls.contains(INVOKESTATIC + " " + MOD_LIST + ".isLoaded(Ljava/lang/String;)Z"),
                "the static call a newer mod makes has no receiver to pass: " + calls);
    }

    @Test
    @DisplayName("The generated helpers verify")
    void helpersVerify() {
        for (LegacyModListBridge.StaticApi api : List.of(LegacyModListBridge.MODS, LegacyModListBridge.LOADING_MODS)) {
            new ClassReader(LegacyModListBridge.generate(api)).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
            new ClassReader(LegacyModListBridge.generate(api, false)).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    @Test
    @DisplayName("ModList.get() is null until mods load, then hands out one host object")
    void modListIsNullUntilModsLoadThenOneInstance() throws Throwable {
        StubHost host = new StubHost(stubModList(), LegacyModListBridge.generate(LegacyModListBridge.MODS));
        Class<?> modList = host.loadClass(dotted(MOD_LIST));
        MethodHandle get = MethodHandles.publicLookup().findStatic(
                host.loadClass(dotted(HELPER)), "get", MethodType.methodType(modList));

        assertNull(get.invoke(), "Forge 1.20.1 returned null before its mod list was built, and so should the bridge");
        setLoadedMods(modList, List.of("puzzleslib"));
        Object first = get.invoke();
        assertInstanceOf(modList, first, "once mods load, a null check on ModList.get() must pass");
        assertSame(first, get.invoke(), "every call hands out the same object");
    }

    @Test
    @DisplayName("LoadingModList.get() returns the host's own object, which a sealed interface requires")
    void loadingModListReturnsTheHostInstance() throws Throwable {
        StubHost host = new StubHost(stubLoadingModList(), stubLoadingModListImpl(),
                LegacyModListBridge.generate(LegacyModListBridge.LOADING_MODS));
        Object hostList = host.loadClass(dotted(LOADING_MOD_LIST_IMPL)).getField("INSTANCE").get(null);
        MethodHandle get = MethodHandles.publicLookup().findStatic(
                host.loadClass(dotted(LegacyModListBridge.LOADING_MODS.helper())), "get",
                MethodType.methodType(host.loadClass(dotted(LOADING_MOD_LIST))));

        assertSame(hostList, get.invoke(), "the helper must return what the host's private get() returns");
    }

    @Test
    @DisplayName("A PuzzlesLib-shaped Objects.requireNonNull(ModList.get()) check passes once mods load")
    void puzzlesLibShapedCallerPassesNullCheck() throws Throwable {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyModListBridge.registerBridge(t, LegacyModListBridge.MODS);
        byte[] caller = t.transformClass(requireNonNullCaller(), "mod/ModContainerHelper.class");

        StubHost host = new StubHost(stubModList(), t.getSyntheticClasses().get(HELPER), caller);
        setLoadedMods(host.loadClass(dotted(MOD_LIST)), List.of("puzzleslib"));
        MethodHandle isModLoaded = MethodHandles.publicLookup().findStatic(host.loadClass("mod.ModContainerHelper"),
                "isModLoaded", MethodType.methodType(boolean.class, String.class));

        assertTrue((boolean) isModLoaded.invoke("puzzleslib"), "a loaded mod reads as loaded through the old instance call");
        assertFalse((boolean) isModLoaded.invoke("jei"), "a missing mod reads as missing");
    }

    @Test
    @DisplayName("A host whose getLoadedMods() is not public keeps the null ModList.get()")
    void nonPublicGetLoadedModsKeepsNullGet() throws Throwable {
        assertTrue(withContextLoader(new StubHost(stubModList(ACC_PUBLIC)), LegacyModListBridge::hostListsLoadedMods),
                "Forge 26.2's public static getLoadedMods() lets get() hand out the host object");
        byte[] modList = stubModList(0);
        byte[] helper = withContextLoader(new StubHost(modList), () -> LegacyModListBridge.generate(LegacyModListBridge.MODS));

        // The helper sits in another package, so calling a package-private getLoadedMods() would throw IllegalAccessError.
        StubHost host = new StubHost(modList, helper);
        Class<?> hostModList = host.loadClass(dotted(MOD_LIST));
        setLoadedMods(hostModList, List.of("puzzleslib"));
        MethodHandle get = MethodHandles.publicLookup().findStatic(
                host.loadClass(dotted(HELPER)), "get", MethodType.methodType(hostModList));
        assertNull(get.invoke(), "a host the helper cannot call must keep the old null answer");
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    private static void setLoadedMods(Class<?> modList, List<String> mods) throws Throwable {
        MethodHandles.publicLookup().findStatic(modList, "setLoadedMods",
                MethodType.methodType(void.class, List.class)).invoke(mods);
    }

    /** Defines the given classes itself, so stand-in host classes and generated helpers link to each other. */
    private static final class StubHost extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        StubHost(byte[]... classFiles) {
            super(LegacyModListBridgeTest.class.getClassLoader());
            for (byte[] classFile : classFiles) {
                classes.put(dotted(new ClassReader(classFile).getClassName()), classFile);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                byte[] bytes = classes.get(name);
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
            }
        }

        // Also serves the class files as resources, which is how the bridge reads the host's shape.
        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] bytes = name.endsWith(".class") ? classes.get(dotted(name.substring(0, name.length() - 6))) : null;
            return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
        }
    }

    private static <T> T withContextLoader(ClassLoader loader, Callable<T> body) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return body.call();
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    /** Forge 26.2's ModList: final, a private no-op constructor, and static methods over a list set at load. */
    private static byte[] stubModList() {
        return stubModList(ACC_PUBLIC);
    }

    /** The same ModList, with {@code getLoadedMods()} given {@code visibility} plus static. */
    private static byte[] stubModList(int visibility) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, MOD_LIST, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "loaded", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor getLoaded = cw.visitMethod(visibility | ACC_STATIC, "getLoadedMods", "()Ljava/util/List;", null, null);
        getLoaded.visitCode();
        getLoaded.visitFieldInsn(GETSTATIC, MOD_LIST, "loaded", "Ljava/util/List;");
        getLoaded.visitInsn(ARETURN);
        getLoaded.visitMaxs(0, 0);
        getLoaded.visitEnd();
        MethodVisitor setLoaded = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setLoadedMods", "(Ljava/util/List;)V", null, null);
        setLoaded.visitCode();
        setLoaded.visitVarInsn(ALOAD, 0);
        setLoaded.visitFieldInsn(PUTSTATIC, MOD_LIST, "loaded", "Ljava/util/List;");
        setLoaded.visitInsn(RETURN);
        setLoaded.visitMaxs(0, 0);
        setLoaded.visitEnd();
        // Like the host, isLoaded fails with a NullPointerException before mods load.
        MethodVisitor isLoaded = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "isLoaded", "(Ljava/lang/String;)Z", null, null);
        isLoaded.visitCode();
        isLoaded.visitFieldInsn(GETSTATIC, MOD_LIST, "loaded", "Ljava/util/List;");
        isLoaded.visitVarInsn(ALOAD, 0);
        isLoaded.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "contains", "(Ljava/lang/Object;)Z", true);
        isLoaded.visitInsn(IRETURN);
        isLoaded.visitMaxs(0, 0);
        isLoaded.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Forge 26.2's LoadingModList: a sealed interface whose private static get() returns its one implementation. */
    private static byte[] stubLoadingModList() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, LOADING_MOD_LIST, null, "java/lang/Object", null);
        cw.visitPermittedSubclass(LOADING_MOD_LIST_IMPL);
        MethodVisitor get = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "get", "()L" + LOADING_MOD_LIST_IMPL + ";", null, null);
        get.visitCode();
        get.visitFieldInsn(GETSTATIC, LOADING_MOD_LIST_IMPL, "INSTANCE", "L" + LOADING_MOD_LIST_IMPL + ";");
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The one class the stub LoadingModList permits, holding the host's single instance. */
    private static byte[] stubLoadingModListImpl() {
        String desc = "L" + LOADING_MOD_LIST_IMPL + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, LOADING_MOD_LIST_IMPL, null, "java/lang/Object",
                new String[]{LOADING_MOD_LIST});
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "INSTANCE", desc, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, LOADING_MOD_LIST_IMPL);
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, LOADING_MOD_LIST_IMPL, "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, LOADING_MOD_LIST_IMPL, "INSTANCE", desc);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** PuzzlesLib's {@code Objects.requireNonNull(ModList.get(), "mod list is null").isLoaded(id)}. */
    private static byte[] requireNonNullCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ModContainerHelper", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "isModLoaded", "(Ljava/lang/String;)Z", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, MOD_LIST, "get", "()L" + MOD_LIST + ";", false);
        m.visitLdcInsn("mod list is null");
        m.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "requireNonNull",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, MOD_LIST);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, MOD_LIST, "isLoaded", "(Ljava/lang/String;)Z", false);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code ModList.get().isLoaded("jei")}, then the static form a newer mod writes. */
    private static byte[] caller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Compat", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "check", "()Z", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, MOD_LIST, "get", "()L" + MOD_LIST + ";", false);
        m.visitLdcInsn("jei");
        m.visitMethodInsn(INVOKEVIRTUAL, MOD_LIST, "isLoaded", "(Ljava/lang/String;)Z", false);
        m.visitInsn(POP);
        m.visitLdcInsn("jei");
        m.visitMethodInsn(INVOKESTATIC, MOD_LIST, "isLoaded", "(Ljava/lang/String;)Z", false);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
