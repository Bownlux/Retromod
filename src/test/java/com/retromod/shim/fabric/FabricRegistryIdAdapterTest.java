/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.x refuses to build a block whose Properties carry no registry id.
 *
 * <p>A Fabric mod builds the block inside the {@code Registry.register} argument list, so nothing
 * Retromod owns runs before the constructor. Every Fabric mod that adds a block failed at its own
 * class init with {@code NullPointerException: Block id not set}, found by launching Hearth and Home
 * on 26.3. The id is reachable because arguments evaluate left to right, so it is already on the
 * stack when the block expression starts.
 */
class FabricRegistryIdAdapterTest {

    private static final String BRIDGE = FabricRegistryIdSynthetic.INTERNAL;
    private static final String PROPS =
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** The shape a Fabric mod emits: id computed, then the block built, then registered. */
    private static byte[] fabricRegistration() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Registrar", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "registerBlock", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries",
                "BLOCK", "Lnet/minecraft/core/Registry;");
        mv.visitLdcInsn("testmod");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        // the block expression starts here, after the id is already on the stack
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/world/level/block/Block");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROPS, "of", "()L" + PROPS + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/world/level/block/Block",
                "<init>", "(L" + PROPS + ";)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/core/Registry", "register",
                "(Lnet/minecraft/core/Registry;L" + IDENTIFIER + ";Ljava/lang/Object;)"
                        + "Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private record Call(int opcode, String owner, String name) {}

    private static List<Call> callsIn(byte[] bytes) {
        List<Call> calls = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String nm, String de, boolean i) {
                        calls.add(new Call(op, o, nm));
                    }
                };
            }
        }, 0);
        return calls;
    }

    @Test
    @DisplayName("the id is stamped before the block is built and cleared after registration")
    void stampsAroundRegistration() {
        List<Call> calls = callsIn(FabricRegistryIdAdapter.apply(fabricRegistration()));

        int push = indexOf(calls, "push");
        int construct = indexOf(calls, "<init>");
        int register = indexOf(calls, "register");
        int pop = indexOf(calls, "pop");

        assertTrue(push >= 0, "the id must be stamped, or the block constructor still sees none");
        assertTrue(pop >= 0, "the id must be cleared, or it leaks into the next registration");
        assertTrue(push < construct,
                "stamping after the constructor is useless: the constructor is what reads the id");
        assertTrue(register < pop, "the id has to stay live until the registration returns");
    }

    @Test
    @DisplayName("a Properties handed to a constructor is stamped where it is used")
    void stampsPropertiesAtTheConstructor() {
        List<Call> calls = callsIn(FabricRegistryIdAdapter.apply(fabricRegistration()));

        int stamp = indexOf(calls, "stampBlock");
        int construct = indexOf(calls, "<init>");
        assertTrue(stamp >= 0,
                "a mod can build one Properties into a shared static long before any registration "
                + "runs, so stamping only where Properties is created misses it");
        assertTrue(stamp < construct, "the stamp has to land before the constructor reads the id");
    }

    @Test
    @DisplayName("a class with no registration is returned untouched")
    void leavesUnrelatedClassesAlone() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Plain", null, "java/lang/Object", null);
        cw.visitEnd();
        byte[] plain = cw.toByteArray();
        assertSame(plain, FabricRegistryIdAdapter.apply(plain),
                "an untouched class must come back as the same array, not a reserialized copy");
    }

    /**
     * Utami's Futon's shape (#338): the block is built first and handed to the mod's own helper with
     * a literal name, and the helper builds the id into a local before registering.
     */
    private static byte[] helperRegistration() {
        return helperRegistration(false);
    }

    /** @param rewritesName whether the helper registers under {@code "white_" + name} instead */
    private static byte[] helperRegistration(boolean rewritesName) {
        String block = "net/minecraft/world/level/block/Block";
        String helperDesc = "(L" + block + ";Ljava/lang/String;)L" + block + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/ModBlocks", null, "java/lang/Object", null);
        MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", helperDesc, null, null);
        helper.visitCode();
        if (rewritesName) {
            helper.visitLdcInsn("white_");
            helper.visitVarInsn(Opcodes.ALOAD, 1);
            helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false);
            helper.visitVarInsn(Opcodes.ASTORE, 1);
        }
        helper.visitLdcInsn("futonsmod");
        helper.visitVarInsn(Opcodes.ALOAD, 1);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        helper.visitVarInsn(Opcodes.ASTORE, 2);
        helper.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries",
                "BLOCK", "Lnet/minecraft/core/Registry;");
        helper.visitVarInsn(Opcodes.ALOAD, 2);
        helper.visitVarInsn(Opcodes.ALOAD, 0);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/core/Registry", "register",
                "(Lnet/minecraft/core/Registry;L" + IDENTIFIER + ";Ljava/lang/Object;)Ljava/lang/Object;", false);
        helper.visitTypeInsn(Opcodes.CHECKCAST, block);
        helper.visitInsn(Opcodes.ARETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, block);
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, PROPS, "of", "()L" + PROPS + ";", false);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, block, "<init>", "(L" + PROPS + ";)V", false);
        init.visitLdcInsn("white_futon");
        init.visitMethodInsn(Opcodes.INVOKESTATIC, "test/mod/ModBlocks", "register", helperDesc, false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("a block built before the mod's own helper names it still gets its id first")
    void stampsBeforeABlockHandedToAHelper() {
        byte[] out = FabricRegistryIdAdapter.apply(helperRegistration());
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(out).accept(node, 0);
        org.objectweb.asm.tree.MethodNode clinit = node.methods.stream()
                .filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        List<String> code = new ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && !isIsolation(call)) code.add(call.name);
            if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type && type.getOpcode() == Opcodes.NEW) code.add("new");
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) code.add("ldc " + ldc.cst);
        }
        assertEquals(List.of("ldc futonsmod", "ldc white_futon", "fromNamespaceAndPath", "push", "new", "of",
                "stampBlock", "<init>", "ldc white_futon", "register", "pop"), code);
    }

    @Test
    @DisplayName("a helper that rewrites its name before registering is not stamped with the caller's literal")
    void declinesAHelperThatRewritesItsName() {
        List<String> code = code(FabricRegistryIdAdapter.apply(helperRegistration(true)), "<clinit>");

        assertFalse(code.contains("push"),
                "the helper registers futonsmod:white_white_futon, so futonsmod:white_futon would be wrong: " + code);
    }

    @Test
    @DisplayName("a class initializer sets the caller's id aside first and gives it back on every way out")
    void isolatesTheClassInitializer() {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(FabricRegistryIdAdapter.apply(helperRegistration())).accept(node, 0);
        org.objectweb.asm.tree.MethodNode clinit = node.methods.stream()
                .filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals(BRIDGE)) calls.add(call.name);
            if (insn.getOpcode() == Opcodes.RETURN || insn.getOpcode() == Opcodes.ATHROW) calls.add("exit");
        }

        assertEquals(List.of("isolate", "push", "stampBlock", "pop", "restore", "exit", "restore", "exit"), calls,
                "isolate before anything runs, restore before the return and in the handler that rethrows");
        assertTrue(clinit.tryCatchBlocks.stream().anyMatch(block -> block.type == null),
                "an exception leaving the initializer has to restore the id too");
    }

    @Test
    @DisplayName("every mod class initializer is isolated, but never the bridge's own")
    void isolatesModClassesButNotTheBridge() {
        // A mod can live under any package, Retromod's own test mod included.
        byte[] modClass = commonBlocks();
        org.objectweb.asm.tree.ClassNode renamed = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(modClass).accept(new org.objectweb.asm.commons.ClassRemapper(renamed,
                new org.objectweb.asm.commons.SimpleRemapper(COMMON, "com/retromod/testmod/ModBlocks")), 0);
        ClassWriter cw = new ClassWriter(0);
        renamed.accept(cw);
        assertTrue(callsIn(FabricRegistryIdAdapter.apply(cw.toByteArray())).stream()
                .anyMatch(call -> call.name().equals("isolate")), "a mod class in a com.retromod package");

        byte[] relocated = relocate(FabricRegistryIdSynthetic.generate(), "com/retromod/embedded/mod/" + BRIDGE);
        assertTrue(callsIn(FabricRegistryIdAdapter.apply(relocated)).stream()
                        .noneMatch(call -> call.name().equals("isolate")),
                "the bridge's initializer creates the list isolate needs, so calling it there would fail");
    }

    private static byte[] relocate(byte[] bytes, String name) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new org.objectweb.asm.commons.ClassRemapper(cw,
                new org.objectweb.asm.commons.SimpleRemapper(BRIDGE, name)), 0);
        return cw.toByteArray();
    }

    /** Class initializer isolation, which {@code ClassInitializerIsolationTest} covers on its own. */
    private static boolean isIsolation(org.objectweb.asm.tree.MethodInsnNode call) {
        return call.owner.equals(BRIDGE) && (call.name.equals("isolate") || call.name.equals("restore"));
    }

    @Test
    @DisplayName("an item built into a static field and registered later by field gets its id at construction")
    void stampsAStaticFieldRegisteredByName() {
        assertEquals(List.of("ldc amethystequipment", "ldc amethyst_sword", "fromNamespaceAndPath", "enter",
                "<init>", "stampItem", "<init>", "putstatic", "leave"), clinitCode(itemInit(false)));
    }

    @Test
    @DisplayName("an item built only when a flag is on gets its id on that path too (#332)")
    void stampsAConditionalStaticFieldBeforeTheCondition() {
        // SWORD = ENABLED ? new Item(new Item.Properties()) : null;
        List<String> code = clinitCode(itemInit(true));
        assertEquals("ldc amethystequipment", code.get(0), "the id is set before the branch: " + code);
        assertEquals(1, code.stream().filter("enter"::equals).count(), code.toString());
        assertEquals("leave", code.get(code.size() - 1), "and restored once after the store: " + code);
    }

    @Test
    @DisplayName("a block class that names no id is stamped with the id another class registers it under (Longwings)")
    void stampsAFieldAnotherClassRegisters() {
        com.retromod.shim.common.StaticFieldRegistryIds ids = jarIds(loaderRegistering("foo"));

        byte[] blocks = commonBlocks();
        assertEquals(List.of("ldc xclass", "ldc foo", "fromNamespaceAndPath", "enter", "of", "stampBlock",
                "<init>", "putstatic", "leave"), code(FabricRegistryIdAdapter.apply(blocks, ids), "<clinit>"));
        assertFalse(code(FabricRegistryIdAdapter.apply(blocks), "<clinit>").contains("enter"),
                "without the jar index the class has nothing that names an id");
    }

    @Test
    @DisplayName("a loader class that fills the common class's field itself stamps that store")
    void stampsAStoreIntoAnotherClassesField() {
        byte[] loader = loaderRegistering("foo", true);
        com.retromod.shim.common.StaticFieldRegistryIds ids = jarIds(loader);

        List<String> code = code(FabricRegistryIdAdapter.apply(loader, ids), "registerBlocks");
        int enter = code.indexOf("enter");
        assertEquals(List.of("ldc xclass", "ldc foo", "fromNamespaceAndPath", "enter"),
                code.subList(Math.max(0, enter - 3), enter + 1), "the store is built under xclass:foo");
        assertTrue(code.indexOf("leave") > code.indexOf("putstatic"), code.toString());
    }

    @Test
    @DisplayName("a field its own class and another class register under different names is left alone")
    void declinesDisagreeingSources() {
        com.retromod.shim.common.StaticFieldRegistryIds ids = jarIds(loaderRegistering("bar"));
        byte[] blocks = commonBlocksRegisteringItselfAs("foo");

        List<String> code = code(FabricRegistryIdAdapter.apply(blocks, ids), "<clinit>");
        assertFalse(code.contains("enter"), "two names for one field prove neither: " + code);
    }

    private static final String COMMON = "test/xclass/ModBlocks";
    private static final String LOADER = "test/xclass/ModBlocksLoader";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String REGISTER_DESC =
            "(Lnet/minecraft/core/Registry;L" + IDENTIFIER + ";Ljava/lang/Object;)Ljava/lang/Object;";

    /** The jar index over the common class and one loader class, as the transformer builds it. */
    private static com.retromod.shim.common.StaticFieldRegistryIds jarIds(byte[] loader) {
        var builder = com.retromod.shim.common.StaticFieldRegistryIds.builder(java.util.Set.of(COMMON, LOADER));
        for (byte[] bytes : List.of(commonBlocks(), loader)) {
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(bytes).accept(node, 0);
            builder.add(node);
        }
        return builder.build();
    }

    /** {@code public static Block FOO = new Block(Properties.of());} and nothing else. */
    private static byte[] commonBlocks() {
        return commonBlocksRegisteringItselfAs(null);
    }

    private static byte[] commonBlocksRegisteringItselfAs(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, COMMON, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FOO", "L" + BLOCK + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        newBlock(init);
        init.visitFieldInsn(Opcodes.PUTSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        if (name != null) {
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()V", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "BLOCK",
                    "Lnet/minecraft/core/Registry;");
            register.visitLdcInsn("xclass");
            register.visitLdcInsn(name);
            register.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
            register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/core/Registry", "register", REGISTER_DESC, true);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] loaderRegistering(String name) {
        return loaderRegistering(name, false);
    }

    /**
     * {@code registerBlocks() { [ModBlocks.FOO = new Block(...);] registerBlock(name, ModBlocks.FOO); }}
     * with {@code registerBlock} registering under {@code xclass:<name>}.
     */
    private static byte[] loaderRegistering(String name, boolean fillsTheField) {
        String helperDesc = "(Ljava/lang/String;L" + BLOCK + ";)L" + BLOCK + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, LOADER, null, "java/lang/Object", null);
        MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerBlock", helperDesc, null, null);
        helper.visitCode();
        helper.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "BLOCK",
                "Lnet/minecraft/core/Registry;");
        helper.visitLdcInsn("xclass");
        helper.visitVarInsn(Opcodes.ALOAD, 0);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        helper.visitVarInsn(Opcodes.ALOAD, 1);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/core/Registry", "register", REGISTER_DESC, true);
        helper.visitTypeInsn(Opcodes.CHECKCAST, BLOCK);
        helper.visitInsn(Opcodes.ARETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerBlocks", "()V", null, null);
        register.visitCode();
        if (fillsTheField) {
            newBlock(register);
            register.visitFieldInsn(Opcodes.PUTSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
        }
        register.visitLdcInsn(name);
        register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
        register.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "registerBlock", helperDesc, false);
        register.visitInsn(Opcodes.POP);
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void newBlock(MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, BLOCK);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROPS, "of", "()L" + PROPS + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "<init>", "(L" + PROPS + ";)V", false);
    }

    /** The calls, constants, and static stores in one method, in order. */
    private static List<String> code(byte[] classBytes, String methodName) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        org.objectweb.asm.tree.MethodNode method = node.methods.stream()
                .filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        List<String> code = new ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && !isIsolation(call)) code.add(call.name);
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) code.add("ldc " + ldc.cst);
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC) code.add("putstatic");
        }
        return code;
    }

    private static byte[] itemInit(boolean conditional) {
        String item = "net/minecraft/world/item/Item";
        String itemProps = "net/minecraft/world/item/Item$Properties";
        String helperDesc = "(L" + item + ";Ljava/lang/String;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/ItemInit", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "SWORD", "L" + item + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ENABLED", "Z", null, null).visitEnd();
        MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerItem", helperDesc, null, null);
        helper.visitCode();
        helper.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "ITEM",
                "Lnet/minecraft/core/Registry;");
        helper.visitLdcInsn("amethystequipment");
        helper.visitVarInsn(Opcodes.ALOAD, 1);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        helper.visitVarInsn(Opcodes.ALOAD, 0);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/core/Registry", "register",
                "(Lnet/minecraft/core/Registry;L" + IDENTIFIER + ";Ljava/lang/Object;)Ljava/lang/Object;", false);
        helper.visitInsn(Opcodes.POP);
        helper.visitInsn(Opcodes.RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registerItems", "()V", null, null);
        register.visitCode();
        register.visitFieldInsn(Opcodes.GETSTATIC, "test/mod/ItemInit", "SWORD", "L" + item + ";");
        register.visitLdcInsn("amethyst_sword");
        register.visitMethodInsn(Opcodes.INVOKESTATIC, "test/mod/ItemInit", "registerItem", helperDesc, false);
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        org.objectweb.asm.Label disabled = new org.objectweb.asm.Label();
        org.objectweb.asm.Label store = new org.objectweb.asm.Label();
        if (conditional) {
            init.visitFieldInsn(Opcodes.GETSTATIC, "test/mod/ItemInit", "ENABLED", "Z");
            init.visitJumpInsn(Opcodes.IFEQ, disabled);
        }
        init.visitTypeInsn(Opcodes.NEW, item);
        init.visitInsn(Opcodes.DUP);
        init.visitTypeInsn(Opcodes.NEW, itemProps);
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, itemProps, "<init>", "()V", false);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, item, "<init>", "(L" + itemProps + ";)V", false);
        if (conditional) {
            init.visitJumpInsn(Opcodes.GOTO, store);
            init.visitLabel(disabled);
            init.visitInsn(Opcodes.ACONST_NULL);
            init.visitLabel(store);
        }
        init.visitFieldInsn(Opcodes.PUTSTATIC, "test/mod/ItemInit", "SWORD", "L" + item + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The calls, constants, and the field store in the transformed {@code <clinit>}, in order. */
    private static List<String> clinitCode(byte[] classBytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(FabricRegistryIdAdapter.apply(classBytes)).accept(node, 0);
        org.objectweb.asm.tree.MethodNode clinit = node.methods.stream()
                .filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        List<String> code = new ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && !isIsolation(call)) code.add(call.name);
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) code.add("ldc " + ldc.cst);
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC) code.add("putstatic");
        }
        return code;
    }

    private static int indexOf(List<Call> calls, String name) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).name().equals(name)
                    && (name.equals("<init>") || calls.get(i).owner().equals(BRIDGE)
                        || calls.get(i).owner().equals("net/minecraft/core/Registry"))) {
                return i;
            }
        }
        return -1;
    }
}
