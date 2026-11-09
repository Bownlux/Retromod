/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.retromod.shim.common.LegacyRegistryNbtBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.20.1 block entity's NBT overrides against the 1.20.5 provider-taking methods. */
class LegacyRegistryNbtBridgeTest {

    private static final String L_COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String L_LIST = "Lnet/minecraft/core/NonNullList;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void oldOverridesGetModernOverridesThatRunThem() {
        ClassNode node = node(apply(modBlockEntity(BLOCK_ENTITY), Map.of()));

        MethodNode load = method(node, "loadAdditional", NEW_NBT);
        MethodNode save = method(node, "saveAdditional", NEW_NBT);
        assertTrue(calls(load).contains("test/Crate.load" + OLD_LOAD), "loadAdditional must run the old load");
        assertTrue(calls(save).contains("test/Crate.saveAdditional" + OLD_LOAD),
                "the provider overload must run the old save");
    }

    @Test
    void superCallsThatReachMinecraftTakeTheProvider() {
        ClassNode node = node(apply(modBlockEntity(BLOCK_ENTITY), Map.of()));

        List<String> loadCalls = calls(method(node, "load", OLD_LOAD));
        assertTrue(loadCalls.contains(HELPER + ".provider()Lnet/minecraft/core/HolderLookup$Provider;"), loadCalls.toString());
        assertTrue(loadCalls.contains(BLOCK_ENTITY + ".loadAdditional" + NEW_NBT),
                "super.load(tag) must become super.loadAdditional(tag, provider): " + loadCalls);
    }

    @Test
    void aSuperCallToAModParentThatStillDeclaresTheMethodIsKept() {
        byte[] parent = modBlockEntity(BLOCK_ENTITY);
        byte[] child = modBlockEntity("test/Crate", "test/BigCrate");

        ClassNode node = node(apply(child, Map.of("test/Crate", parent)));

        assertTrue(calls(method(node, "load", OLD_LOAD)).contains("test/Crate.load" + OLD_LOAD),
                "the parent's own old load still answers the super call");
    }

    @Test
    void aSuperCallToAModParentThatCannotBeReadIsKept() {
        // Moonlight's base block entity, in another jar this pass cannot read: it may declare load itself, and a
        // rewrite to its provider bridge would call the child's load again.
        ClassNode node = node(apply(modBlockEntity("lib/ItemDisplayTile", "test/BookPile"), Map.of()));

        assertEquals(List.of("lib/ItemDisplayTile.load" + OLD_LOAD), calls(method(node, "load", OLD_LOAD)));
        assertEquals(List.of("lib/ItemDisplayTile.saveAdditional" + OLD_LOAD), calls(method(node, "saveAdditional", OLD_LOAD)));
    }

    @Test
    void aSuperCallThroughAReadableModParentWithoutTheMethodReachesMinecraft() {
        // Balm's base block entity overrides neither method, so the call reaches Minecraft past it.
        byte[] base = plainClassExtending("lib/BalmBlockEntity", BLOCK_ENTITY);
        ClassNode node = node(apply(modBlockEntity("lib/BalmBlockEntity", "test/Waystone"), Map.of("lib/BalmBlockEntity", base)));

        assertTrue(calls(method(node, "load", OLD_LOAD)).contains("lib/BalmBlockEntity.loadAdditional" + NEW_NBT));
    }

    @Test
    void itemReadsTakeTheModsOwnDataVersion() {
        String members = LegacyRegistryNbtBridge.MEMBERS;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Reader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(" + L_COMPOUND + L_LIST + ")V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, members, "of", "(" + L_COMPOUND + ")Lnet/minecraft/world/item/ItemStack;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, members, "loadAllItems", "(" + L_COMPOUND + L_LIST + ")V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MethodNode read = method(node(LegacyRegistryNbtBridge.apply(cw.toByteArray(), (child, ancestor) -> false,
                name -> null, true, "1.16.5")), "read", "(" + L_COMPOUND + L_LIST + ")V");

        assertEquals(List.of(members + ".of(" + L_COMPOUND + "I)Lnet/minecraft/world/item/ItemStack;",
                members + ".loadAllItems(" + L_COMPOUND + L_LIST + "I)V"), calls(read));
        List<Object> versions = new ArrayList<>();
        for (AbstractInsnNode insn : read.instructions) {
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) versions.add(ldc.cst);
        }
        assertEquals(List.of(2586, 2586), versions, "a 1.16.5 mod's stacks are fixed from 1.16.5's data version");
    }

    @Test
    void dataVersionsFollowTheModsMinecraftVersion() {
        assertEquals(2586, dataVersionOf("1.16.5"));
        assertEquals(1343, dataVersionOf("1.12.2"));
        assertEquals(3337, dataVersionOf("1.19.4"));
        assertEquals(2975, dataVersionOf("1.18.3"), "an unlisted release takes the closest earlier one");
        assertEquals(99, dataVersionOf("1.8.9"));
        assertEquals(3465, dataVersionOf(null), "an unknown mod is read as 1.20.1's");
        assertEquals(3955, dataVersionOf("1.21.1"));
        assertEquals(4189, dataVersionOf("1.21.4"));
        assertEquals(4325, dataVersionOf("26.3"), "every version from 1.21.5 on reads as 1.21.5's");
    }

    @Test
    void aPrivateHelperWithTheOldShapeGetsNoModernOverride() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Tank", null, BLOCK_ENTITY, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "load", OLD_LOAD, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(apply(cw.toByteArray(), Map.of()));

        assertTrue(node.methods.stream().noneMatch(m -> m.name.equals("loadAdditional")),
                "a private load(tag) is the mod's own helper and must not replace the inherited loadAdditional");
    }

    @Test
    void containerHelperCallsGoThroughTheHelper() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, true);

        ClassNode node = node(transformer.transformClass(containerCaller(), "test/Inventory.class"));

        assertTrue(calls(method(node, "save", "(" + L_COMPOUND + L_LIST + ")V"))
                .contains(HELPER + ".saveAllItems(" + L_COMPOUND + L_LIST + ")" + L_COMPOUND));
    }

    @Test
    void oldWorldSavedDataSavesThroughTheProviderOverride() throws Exception {
        String desc = "(" + L_COMPOUND + ")" + L_COMPOUND;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/WorldState", null, SAVED_DATA, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "save", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(LegacyRegistryNbtBridge.apply(cw.toByteArray(),
                (child, ancestor) -> ancestor.equals(SAVED_DATA), name -> null));

        assertTrue(calls(method(node, "save", NEW_DATA_SAVE)).contains("test/WorldState.save" + desc),
                "the host's save(tag, provider) must run the old save(tag)");
        verify(node);
    }

    @Test
    void theOldLoaderLookupBuildsASavedDataFactory() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, false);
        registerSavedDataRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/WorldStateAccess", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "find",
                "(L" + STORAGE + ";Ljava/util/function/Function;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn("examplemod");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STORAGE, "get", OLD_GET, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = node(transformer.transformClass(cw.toByteArray(), "test/WorldStateAccess.class"));

        List<String> calls = calls(method(node, "find", "(L" + STORAGE + ";Ljava/util/function/Function;)Ljava/lang/Object;"));
        assertTrue(calls.contains(HELPER + ".dataGet(L" + STORAGE + ";" + OLD_GET.substring(1)),
                "the old lookup must build a factory through the helper: " + calls);
    }

    @Test
    void theHelperAndTheBridgedClassVerify() throws Exception {
        verify(node(generateHelper(true)));
        verify(node(generateHelper(false)));
        verify(node(apply(modBlockEntity(BLOCK_ENTITY), Map.of())));
    }

    @Test
    void aValueIoHostsHelperOnlyKeepsWhatStillLinks() throws Exception {
        ClassNode helper = node(generateHelper(false, false));
        verify(helper);
        List<String> methods = helper.methods.stream().map(m -> m.name).toList();
        assertTrue(methods.containsAll(List.of("enter", "exit", "provider", "dataComputeIfAbsent")), methods.toString());
        assertTrue(methods.stream().noneMatch(m -> m.equals("loadAllItems") || m.equals("dataGet")),
                "a 26.x host has none of the provider forms those called: " + methods);
        assertTrue(calls(method(helper, "provider", "()Lnet/minecraft/core/HolderLookup$Provider;"))
                .contains(CONTEXT + ".available()Lnet/minecraft/core/HolderLookup$Provider;"),
                "outside a save the running server's or the client world's registries come before the built-in ones");
    }

    @Test
    void aPlainOldGetUpdateTagCallTakesTheProvider() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Packets", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tag",
                "(Lnet/minecraft/world/level/block/entity/ChestBlockEntity;)" + L_COMPOUND, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/entity/ChestBlockEntity", "getUpdateTag",
                "()" + L_COMPOUND, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(method(node(apply(cw.toByteArray(), Map.of())), "tag",
                "(Lnet/minecraft/world/level/block/entity/ChestBlockEntity;)" + L_COMPOUND));

        assertEquals(List.of(HELPER + ".provider()Lnet/minecraft/core/HolderLookup$Provider;",
                "net/minecraft/world/level/block/entity/ChestBlockEntity.getUpdateTag("
                        + "Lnet/minecraft/core/HolderLookup$Provider;)" + L_COMPOUND), calls);
    }

    @Test
    void theHelperKeepsTheSaveInTheSharedContext() {
        ClassNode helper = node(generateHelper(true));

        assertTrue(helper.fields.stream().noneMatch(f -> f.desc.equals("Ljava/lang/ThreadLocal;")),
                "the helper holds no registries of its own");
        assertTrue(calls(method(helper, "enter", "(Lnet/minecraft/core/HolderLookup$Provider;)Ljava/lang/Object;"))
                .contains(CONTEXT + ".enter(Lnet/minecraft/core/HolderLookup$Provider;)Ljava/lang/Object;"));
        assertTrue(calls(method(helper, "provider", "()Lnet/minecraft/core/HolderLookup$Provider;"))
                .contains(CONTEXT + ".current()Lnet/minecraft/core/HolderLookup$Provider;"));

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, true);
        assertTrue(transformer.getSyntheticClasses().containsKey(CONTEXT), "the shared context travels with the helper");
    }

    @Test
    void aThrowingOldLoadLeavesNoRegistriesCurrent() throws Exception {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        registerRedirects(transformer, false);
        Map<String, byte[]> classes = new HashMap<>();
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.equals(HELPER) || name.startsWith(RebuiltApiClasses.ROOT)) classes.put(name, bytes);
        });
        classes.put(BLOCK_ENTITY, plainClass(BLOCK_ENTITY, Opcodes.ACC_PUBLIC));
        // The JVM checks the helper's calls that pass a CompoundTag as a Tag, so both types exist.
        classes.put(COMPOUND, plainClass(COMPOUND, Opcodes.ACC_PUBLIC, TAG));
        classes.put(TAG, plainClass(TAG, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT));
        classes.put(PROVIDER, plainClass(PROVIDER, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT));
        classes.put("test/Crate", apply(throwingLoad("test/Crate"), Map.of()));
        ClassLoader loader = new OwnFirst(classes);
        Class<?> providerType = loader.loadClass(PROVIDER.replace('/', '.'));
        Object registries = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{providerType},
                (proxy, method, args) -> method.getName().equals("hashCode") ? 1 : null);
        Method current = loader.loadClass(CONTEXT.replace('/', '.')).getMethod("current");
        try {
            // A block entity whose old load throws, as the host loads it.
            Object crate = loader.loadClass("test.Crate").getConstructor().newInstance();
            Method load = crate.getClass().getDeclaredMethod("loadAdditional", loader.loadClass(COMPOUND.replace('/', '.')),
                    providerType);
            load.setAccessible(true);
            InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                    () -> load.invoke(crate, null, registries));
            assertInstanceOf(IllegalStateException.class, failed.getCause());
            assertNull(current.invoke(null), "the failed load left its registries current for every mod");

            // Saved data whose old loader throws, as the factory loads it.
            Constructor<?> wrap = loader.loadClass(HELPER.replace('/', '.')).getDeclaredConstructor(Function.class);
            wrap.setAccessible(true);
            Function<Object, Object> oldLoader = tag -> {
                throw new IllegalStateException("old saved data load failed");
            };
            @SuppressWarnings("unchecked")
            BiFunction<Object, Object, Object> factoryLoader = (BiFunction<Object, Object, Object>) wrap.newInstance(oldLoader);
            assertThrows(IllegalStateException.class, () -> factoryLoader.apply(null, registries));
            assertNull(current.invoke(null), "the failed saved data load left its registries current");
        } finally {
            loader.loadClass(CONTEXT.replace('/', '.')).getMethod("exit", Object.class).invoke(null, (Object) null);
        }
    }

    /** {@code class name extends BlockEntity} whose old {@code load(tag)} throws. */
    private static byte[] throwingLoad(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, BLOCK_ENTITY, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_ENTITY, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor load = cw.visitMethod(Opcodes.ACC_PUBLIC, "load", OLD_LOAD, null, null);
        load.visitCode();
        load.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        load.visitInsn(Opcodes.DUP);
        load.visitLdcInsn("old load failed");
        load.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        load.visitInsn(Opcodes.ATHROW);
        load.visitMaxs(0, 0);
        load.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A host stand-in with only a public no-argument constructor, or an empty interface, implementing {@code interfaces}. */
    private static byte[] plainClass(String name, int access, String... interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, access, name, null, "java/lang/Object", interfaces);
        if ((access & Opcodes.ACC_INTERFACE) == 0) {
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Defines the given classes itself and asks the test's loader for everything else. */
    private static final class OwnFirst extends ClassLoader {
        private final Map<String, byte[]> own;

        OwnFirst(Map<String, byte[]> own) {
            super(LegacyRegistryNbtBridgeTest.class.getClassLoader());
            this.own = own;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                byte[] bytes = loaded == null ? own.get(name.replace('.', '/')) : null;
                if (loaded == null) loaded = bytes != null ? defineClass(name, bytes, 0, bytes.length) : super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    private static byte[] apply(byte[] bytes, Map<String, byte[]> modClasses) {
        return LegacyRegistryNbtBridge.apply(bytes, (child, ancestor) -> ancestor.equals(BLOCK_ENTITY),
                modClasses::get);
    }

    private static byte[] modBlockEntity(String parent) {
        return modBlockEntity(parent, "test/Crate");
    }

    private static byte[] plainClassExtending(String name, String parent) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code load(tag) { super.load(tag); }} and {@code saveAdditional(tag) { super.saveAdditional(tag); }}. */
    private static byte[] modBlockEntity(String parent, String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        for (String method : new String[]{"load", "saveAdditional"}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, OLD_LOAD, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, method, OLD_LOAD, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] containerCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Inventory", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "save",
                "(" + L_COMPOUND + L_LIST + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONTAINER_HELPER, "saveAllItems",
                "(" + L_COMPOUND + L_LIST + ")" + L_COMPOUND, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void verify(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            }
        }
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElseThrow(() -> new AssertionError("missing " + name + desc));
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
