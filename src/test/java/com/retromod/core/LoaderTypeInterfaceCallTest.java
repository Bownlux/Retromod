/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A call on a Forge or NeoForge type takes the interface form exactly where the target loader's host made the type an interface. */
class LoaderTypeInterfaceCallTest {

    static final String MOD_CONFIG_EVENT = "net/minecraftforge/fml/event/config/ModConfigEvent";
    static final String GET_CONFIG = "()Lnet/minecraftforge/fml/config/ModConfig;";
    private static final String NEO_EVENT = "net/neoforged/bus/api/ExampleEvent";
    private static final String MOD_CLASS = "test/loaderkind/ConfigListener";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private Field indexField;
    private FuzzyMethodResolver savedIndex;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        registerUnrelatedRedirect();
        indexField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        indexField.setAccessible(true);
        savedIndex = (FuzzyMethodResolver) indexField.get(transformer);
        indexField.set(transformer, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        indexField.set(transformer, savedIndex);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an indexed Forge host where ModConfigEvent is an interface gives the call and the method reference the interface form")
    void indexedForgeInterface() throws Exception {
        target("forge", "26.2");
        installIndex(Map.of(MOD_CONFIG_EVENT, true));

        ClassNode out = transform(listener(MOD_CONFIG_EVENT));

        assertCall(out, MOD_CONFIG_EVENT, Opcodes.INVOKEINTERFACE, true);
        assertHandle(out, MOD_CONFIG_EVENT, Opcodes.H_INVOKEINTERFACE, true);
    }

    @Test
    @DisplayName("an indexed Forge host where ModConfigEvent is still a class keeps INVOKEVIRTUAL, even at 26.2")
    void indexedForgeClassWinsOverTheTable() throws Exception {
        target("forge", "26.2");
        installIndex(Map.of(MOD_CONFIG_EVENT, false));

        ClassNode out = transform(listener(MOD_CONFIG_EVENT));

        assertCall(out, MOD_CONFIG_EVENT, Opcodes.INVOKEVIRTUAL, false);
        assertHandle(out, MOD_CONFIG_EVENT, Opcodes.H_INVOKEVIRTUAL, false);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("an unreadable Forge host from 26.2 takes the kind from the checked table")
    void offlineForgeUsesTheTable(String host) {
        target("forge", host);

        ClassNode out = transform(listener(MOD_CONFIG_EVENT));

        assertCall(out, MOD_CONFIG_EVENT, Opcodes.INVOKEINTERFACE, true);
        assertHandle(out, MOD_CONFIG_EVENT, Opcodes.H_INVOKEINTERFACE, true);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"1.20.1", "1.21.1", "26.1"})
    @DisplayName("an unreadable Forge host below the first checked version keeps the class form")
    void offlineForgeBelowTheTableKeepsTheClassForm(String host) {
        target("forge", host);

        ClassNode out = transform(listener(MOD_CONFIG_EVENT));

        assertCall(out, MOD_CONFIG_EVENT, Opcodes.INVOKEVIRTUAL, false);
        assertHandle(out, MOD_CONFIG_EVENT, Opcodes.H_INVOKEVIRTUAL, false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"neoforge", "fabric"})
    @DisplayName("another loader's target leaves a Forge type as written, whatever the index holds")
    void otherTargetsLeaveForgeTypes(String loader) throws Exception {
        target(loader, "26.2");
        installIndex(Map.of(MOD_CONFIG_EVENT, true));

        ClassNode out = transform(listener(MOD_CONFIG_EVENT));

        assertCall(out, MOD_CONFIG_EVENT, Opcodes.INVOKEVIRTUAL, false);
        assertHandle(out, MOD_CONFIG_EVENT, Opcodes.H_INVOKEVIRTUAL, false);
    }

    @Test
    @DisplayName("a NeoForge target reads its own types from an indexed NeoForge host")
    void indexedNeoForgeInterface() throws Exception {
        target("neoforge", "26.2");
        installIndex(Map.of(NEO_EVENT, true));

        ClassNode out = transform(listener(NEO_EVENT));

        assertCall(out, NEO_EVENT, Opcodes.INVOKEINTERFACE, true);
        assertHandle(out, NEO_EVENT, Opcodes.H_INVOKEINTERFACE, true);
    }

    @Test
    @DisplayName("an unreadable NeoForge host has no table, so NeoForge types keep the class form")
    void offlineNeoForgeKeepsTheClassForm() {
        target("neoforge", "26.3");

        ClassNode out = transform(listener(NEO_EVENT));

        assertCall(out, NEO_EVENT, Opcodes.INVOKEVIRTUAL, false);
    }

    @Test
    @DisplayName("a static call on a Forge type that became an interface keeps INVOKESTATIC and takes an InterfaceMethodref")
    void staticCallTakesTheInterfaceFlag() throws Exception {
        target("forge", "26.2");
        installIndex(Map.of(MOD_CONFIG_EVENT, true));

        ClassNode out = transform(staticCaller(MOD_CONFIG_EVENT));

        assertCall(out, MOD_CONFIG_EVENT, Opcodes.INVOKESTATIC, true);
    }

    @Test
    @DisplayName("the checked table lists ModConfigEvent from 26.2 for a Forge target only")
    void tableAnswersForForgeTargetsOnly() {
        target("forge", "26.2");
        assertTrue(LoaderTypeKinds.tabledInterface(MOD_CONFIG_EVENT));
        assertFalse(LoaderTypeKinds.tabledInterface("net/minecraftforge/event/entity/living/LivingHurtEvent"),
                "an event that became a record is a class, and the table does not list it");
        target("forge", "1.21.1");
        assertFalse(LoaderTypeKinds.tabledInterface(MOD_CONFIG_EVENT));
        target("neoforge", "26.2");
        assertFalse(LoaderTypeKinds.tabledInterface(MOD_CONFIG_EVENT));
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(strings = {"1.20.4", "1.20.5"})
    @DisplayName("an unreadable vanilla host turns DataResult method references into interface ones from 1.20.5 only")
    void dataResultHandlesFollowTheVersionGate(String host) {
        target("fabric", host);
        boolean becameInterface = host.equals("1.20.5");

        ClassNode out = transform(references(DATA_RESULT,
                new Handle(Opcodes.H_INVOKEVIRTUAL, DATA_RESULT, "error", "()Ljava/util/Optional;", false),
                new Handle(Opcodes.H_INVOKESTATIC, DATA_RESULT, "success", "(Ljava/lang/Object;)L" + DATA_RESULT + ";", false)));

        Handle instance = handleOn(out, DATA_RESULT, "error");
        assertEquals(becameInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL, instance.getTag(),
                "DataResult::error on " + host);
        assertEquals(becameInterface, instance.isInterface(), "DataResult::error on " + host);
        Handle factory = handleOn(out, DATA_RESULT, "success");
        assertEquals(Opcodes.H_INVOKESTATIC, factory.getTag(), "a static reference stays static on " + host);
        assertEquals(becameInterface, factory.isInterface(), "DataResult::success names an InterfaceMethodref only from 1.20.5");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"1.21.1", "26.2", "26.3"})
    @DisplayName("a static method reference on a Forge type keeps H_INVOKESTATIC and takes the interface flag where the type became one")
    void staticForgeHandleTakesTheInterfaceFlag(String host) {
        target("forge", host);
        boolean becameInterface = !host.equals("1.21.1");

        ClassNode out = transform(references(MOD_CONFIG_EVENT,
                new Handle(Opcodes.H_INVOKESTATIC, MOD_CONFIG_EVENT, "loading", "(Ljava/lang/Object;)L" + MOD_CONFIG_EVENT + ";", false)));

        Handle factory = handleOn(out, MOD_CONFIG_EVENT, "loading");
        assertEquals(Opcodes.H_INVOKESTATIC, factory.getTag(), "the reference stays static on Forge " + host);
        assertEquals(becameInterface, factory.isInterface(), "whether the reference names an InterfaceMethodref on Forge " + host);
    }

    // ---- fixtures ----

    static final String DATA_RESULT = "com/mojang/serialization/DataResult";

    /** One {@code Supplier} per handle, each made by LambdaMetafactory from the method reference as javac emits it. */
    private static byte[] references(String owner, Handle... handles) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        int index = 0;
        for (Handle handle : handles) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reference" + index++,
                    "()Ljava/util/function/Function;", null, null);
            mv.visitCode();
            Type instantiated = handle.getTag() == Opcodes.H_INVOKESTATIC
                    ? Type.getMethodType(handle.getDesc())
                    : Type.getMethodType(Type.getReturnType(handle.getDesc()), Type.getObjectType(owner));
            mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                    Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), handle, instantiated);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    static Handle handleOn(ClassNode node, String owner, String name) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle handle && handle.getOwner().equals(owner) && handle.getName().equals(name)) return handle;
                }
            }
        }
        return fail(node.name + " no longer refers to " + owner + "." + name);
    }

    private void target(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        // The memo of owner kinds belongs to one target, as it does at a registration boundary.
        registerUnrelatedRedirect();
    }

    /** Clears the registrations and adds a method redirect no fixture calls, so method bodies are visited. */
    private void registerUnrelatedRedirect() {
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect("test/loaderkind/Old", "old", "()V", "test/loaderkind/New", "now", "()V");
    }

    /** Indexes a host jar holding each type, as an interface or a class, beside one Minecraft class. */
    private void installIndex(Map<String, Boolean> types) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("net/minecraft/server/Main.class", hostType("net/minecraft/server/Main", false));
        types.forEach((name, isInterface) -> entries.put(name + ".class", hostType(name, isInterface)));
        Path jar = dir.resolve("host-" + System.nanoTime() + ".jar");
        Files.write(jar, LoaderApiFixtures.jar(entries));
        FuzzyMethodResolver index = new FuzzyMethodResolver();
        index.indexJar(jar);
        indexField.set(transformer, index);
    }

    static byte[] hostType(String name, boolean isInterface) {
        ClassWriter cw = new ClassWriter(0);
        int access = Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
        cw.visit(Opcodes.V17, access, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.20.1 config listener: {@code event.getConfig()} and {@code Function f = Event::getConfig}. */
    static byte[] listener(String event) {
        return listener(MOD_CLASS, event);
    }

    static byte[] listener(String name, String event) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor reload = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reload",
                "(L" + event + ";)V", null, null);
        reload.visitCode();
        reload.visitVarInsn(Opcodes.ALOAD, 0);
        reload.visitMethodInsn(Opcodes.INVOKEVIRTUAL, event, "getConfig", GET_CONFIG, false);
        reload.visitInsn(Opcodes.POP);
        reload.visitInsn(Opcodes.RETURN);
        reload.visitMaxs(0, 0);
        reload.visitEnd();

        MethodVisitor reference = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reference",
                "()Ljava/util/function/Function;", null, null);
        reference.visitCode();
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        reference.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_INVOKEVIRTUAL, event, "getConfig", GET_CONFIG, false),
                Type.getMethodType("(L" + event + ";)Lnet/minecraftforge/fml/config/ModConfig;"));
        reference.visitInsn(Opcodes.ARETURN);
        reference.visitMaxs(0, 0);
        reference.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A static call on the type, as a static factory on a type that became an interface is called. */
    private static byte[] staticCaller(String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reload", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, type, "loading", "(Ljava/lang/Object;)L" + type + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private ClassNode transform(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD_CLASS)).accept(node, 0);
        return node;
    }

    static void assertCall(ClassNode node, String owner, int opcode, boolean itf) {
        MethodInsnNode call = callOn(node, owner);
        assertEquals(opcode, call.getOpcode(), node.name + ": the opcode of the call on " + owner);
        assertEquals(itf, call.itf, node.name + ": whether the call on " + owner + " names an InterfaceMethodref");
    }

    static void assertHandle(ClassNode node, String owner, int tag, boolean itf) {
        Handle handle = handleOn(node, owner);
        assertEquals(tag, handle.getTag(), node.name + ": the kind of the method reference to " + owner);
        assertEquals(itf, handle.isInterface(), node.name + ": whether the method reference to " + owner + " is an interface one");
    }

    static MethodInsnNode callOn(ClassNode node, String owner) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) return call;
            }
        }
        return fail(node.name + " no longer calls " + owner);
    }

    static Handle handleOn(ClassNode node, String owner) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle handle && handle.getOwner().equals(owner)) return handle;
                }
            }
        }
        return fail(node.name + " no longer refers to " + owner);
    }
}
