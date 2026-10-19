/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The multiloader template builds blocks into static fields of a common class and registers them
 * from a loader class. Longwings 0.9.5 (Fabric 1.20.1) on Fabric 26.3 died with
 * {@code NullPointerException: Block id not set} in {@code LongwingsBlocks.<clinit>}, because the
 * only class that names each block's id is {@code LongwingsBlocksFabric}. These cases read the id
 * off that other class.
 */
class StaticFieldRegistryIdsTest {

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String COMMON = "test/xclass/ModBlocks";
    private static final String LOADER = "test/xclass/ModBlocksLoader";
    private static final String REGISTER_DESC =
            "(L" + REGISTRY + ";L" + IDENTIFIER + ";Ljava/lang/Object;)Ljava/lang/Object;";

    @Test
    @DisplayName("a field registered by a loader class through its name helper gets that name (Longwings)")
    void readsALoaderHelperRegistration() {
        StaticFieldRegistryIds ids = index(commonBlocks("FOO", "BAR"),
                loader(helperCall("foo", "FOO"), helperCall("bar", "BAR")));

        assertArrayEquals(new String[]{"xclass", "foo"}, ids.fieldsOf(COMMON).get("FOO"));
        assertArrayEquals(new String[]{"xclass", "bar"}, ids.fieldsOf(COMMON).get("BAR"));
    }

    @Test
    @DisplayName("a direct Registry.register with a literal id names the other class's field")
    void readsADirectRegistration() {
        StaticFieldRegistryIds ids = index(commonBlocks("FOO"), loader(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "BLOCK", "L" + REGISTRY + ";");
            mv.visitLdcInsn("xclass");
            mv.visitLdcInsn("foo");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            mv.visitInsn(Opcodes.POP);
        }));

        assertArrayEquals(new String[]{"xclass", "foo"}, ids.fieldsOf(COMMON).get("FOO"));
    }

    @Test
    @DisplayName("a field registered under two different names is left alone")
    void declinesTwoNames() {
        StaticFieldRegistryIds ids = index(commonBlocks("FOO"),
                loader(helperCall("foo", "FOO"), helperCall("foo_alias", "FOO")));

        assertTrue(ids.fieldsOf(COMMON).isEmpty(), "an ambiguous field must not be stamped");
    }

    @Test
    @DisplayName("a literal passed to the mod's own id helper names the field, directly or inside a register helper")
    void readsTheModsOwnIdHelper() {
        // static ResourceLocation id(String path) { return new ResourceLocation("xclass", path); }
        // registerBlocks() { Registry.register(BLOCK, id("foo"), ModBlocks.FOO); registerVia("bar", ModBlocks.BAR); }
        // with registerVia(name, block) registering under id(name).
        String idDesc = "(Ljava/lang/String;)L" + RESOURCE_LOCATION + ";";
        String viaDesc = "(Ljava/lang/String;L" + BLOCK + ";)L" + BLOCK + ";";
        byte[] loader = classWith(LOADER, cw -> {
            idHelper(cw, "id", idDesc, null);
            MethodVisitor via = cw.visitMethod(Opcodes.ACC_STATIC, "registerVia", viaDesc, null, null);
            via.visitCode();
            via.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "BLOCK", "L" + REGISTRY + ";");
            via.visitVarInsn(Opcodes.ALOAD, 0);
            via.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "id", idDesc, false);
            via.visitVarInsn(Opcodes.ALOAD, 1);
            via.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                    "(L" + REGISTRY + ";L" + RESOURCE_LOCATION + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
            via.visitTypeInsn(Opcodes.CHECKCAST, BLOCK);
            via.visitInsn(Opcodes.ARETURN);
            via.visitMaxs(0, 0);
            via.visitEnd();
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "registerBlocks", "()V", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "BLOCK", "L" + REGISTRY + ";");
            register.visitLdcInsn("foo");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "id", idDesc, false);
            register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                    "(L" + REGISTRY + ";L" + RESOURCE_LOCATION + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
            register.visitInsn(Opcodes.POP);
            register.visitLdcInsn("bar");
            register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "BAR", "L" + BLOCK + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "registerVia", viaDesc, false);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        // The common class is read first, before the loader that holds the helper.
        StaticFieldRegistryIds ids = index(commonBlocks("FOO", "BAR"), loader);

        assertArrayEquals(new String[]{"xclass", "foo"}, ids.fieldsOf(COMMON).get("FOO"));
        assertArrayEquals(new String[]{"xclass", "bar"}, ids.fieldsOf(COMMON).get("BAR"));
    }

    @Test
    @DisplayName("a String-to-id method that changes the path is not taken for the mod's id helper")
    void declinesAnIdMethodThatChangesThePath() {
        // static ResourceLocation texture(String path) { return new ResourceLocation("xclass", "block/".concat(path)); }
        String idDesc = "(Ljava/lang/String;)L" + RESOURCE_LOCATION + ";";
        byte[] loader = classWith(LOADER, cw -> {
            idHelper(cw, "texture", idDesc, "block/");
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "registerBlocks", "()V", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "BLOCK", "L" + REGISTRY + ";");
            register.visitLdcInsn("foo");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "texture", idDesc, false);
            register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                    "(L" + REGISTRY + ";L" + RESOURCE_LOCATION + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        StaticFieldRegistryIds ids = index(commonBlocks("FOO"), loader);

        assertTrue(ids.isEmpty(), "FOO is registered as xclass:block/foo, which the index cannot read");
    }

    /** {@code static ResourceLocation name(String path) { return new ResourceLocation("xclass", [prefix +] path); }} */
    private static void idHelper(ClassWriter cw, String name, String desc, String prefix) {
        MethodVisitor id = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        id.visitCode();
        id.visitTypeInsn(Opcodes.NEW, RESOURCE_LOCATION);
        id.visitInsn(Opcodes.DUP);
        id.visitLdcInsn("xclass");
        if (prefix != null) id.visitLdcInsn(prefix);
        id.visitVarInsn(Opcodes.ALOAD, 0);
        if (prefix != null) {
            id.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false);
        }
        id.visitMethodInsn(Opcodes.INVOKESPECIAL, RESOURCE_LOCATION, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
        id.visitInsn(Opcodes.ARETURN);
        id.visitMaxs(0, 0);
        id.visitEnd();
    }

    @Test
    @DisplayName("a helper that rewrites its name before registering proves nothing")
    void declinesAHelperThatRewritesItsName() {
        // registerPrefixed(name, block) { name = "prefixed_" + name; ... } and
        // registerMaybeWood(name, block, wood) { if (wood) name = "wood_" + name; ... } register
        // under adv:prefixed_bar and adv:wood_cond, not under the literal their callers pass.
        String prefixedDesc = "(Ljava/lang/String;L" + BLOCK + ";)L" + BLOCK + ";";
        String maybeWoodDesc = "(Ljava/lang/String;L" + BLOCK + ";Z)L" + BLOCK + ";";
        byte[] loader = classWith(LOADER, cw -> {
            MethodVisitor prefixed = cw.visitMethod(Opcodes.ACC_STATIC, "registerPrefixed", prefixedDesc, null, null);
            prefixed.visitCode();
            prependToName(prefixed, "prefixed_");
            registerUnderName(prefixed);
            MethodVisitor maybeWood = cw.visitMethod(Opcodes.ACC_STATIC, "registerMaybeWood", maybeWoodDesc, null, null);
            maybeWood.visitCode();
            org.objectweb.asm.Label plain = new org.objectweb.asm.Label();
            maybeWood.visitVarInsn(Opcodes.ILOAD, 2);
            maybeWood.visitJumpInsn(Opcodes.IFEQ, plain);
            prependToName(maybeWood, "wood_");
            maybeWood.visitLabel(plain);
            registerUnderName(maybeWood);
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "registerBlocks", "()V", null, null);
            register.visitCode();
            register.visitLdcInsn("bar");
            register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "registerPrefixed", prefixedDesc, false);
            register.visitInsn(Opcodes.POP);
            register.visitLdcInsn("cond");
            register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "BAR", "L" + BLOCK + ";");
            register.visitInsn(Opcodes.ICONST_1);
            register.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "registerMaybeWood", maybeWoodDesc, false);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        StaticFieldRegistryIds ids = index(commonBlocks("FOO", "BAR"), loader);

        assertNull(ids.fieldsOf(COMMON).get("FOO"), "FOO is registered as xclass:prefixed_bar, not xclass:bar");
        assertNull(ids.fieldsOf(COMMON).get("BAR"), "BAR is registered as xclass:wood_cond when the flag is set");
    }

    /** {@code name = prefix + name;} for the helper's first parameter. */
    private static void prependToName(MethodVisitor mv, String prefix) {
        mv.visitLdcInsn(prefix);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
    }

    /** {@code return Registry.register(BLOCK, Identifier.fromNamespaceAndPath("xclass", name), block);} */
    private static void registerUnderName(MethodVisitor mv) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "BLOCK", "L" + REGISTRY + ";");
        mv.visitLdcInsn("xclass");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, BLOCK);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    @Test
    @DisplayName("a registration outside the block and item registries is ignored")
    void ignoresOtherRegistries() {
        StaticFieldRegistryIds ids = index(commonBlocks("FOO"), loader(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "SOUND_EVENT", "L" + REGISTRY + ";");
            mv.visitLdcInsn("xclass");
            mv.visitLdcInsn("foo");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            mv.visitInsn(Opcodes.POP);
        }));

        assertTrue(ids.isEmpty(), "only a block or item registration can supply a Properties id");
    }

    @Test
    @DisplayName("a field of a class that is not in the jar is never indexed")
    void ignoresFieldsOutsideTheJar() {
        StaticFieldRegistryIds ids = StaticFieldRegistryIds.builder(Set.of(LOADER))
                .add(node(loader(helperCall("foo", "FOO"))))
                .build();

        assertTrue(ids.isEmpty(), "the field's class is not part of this jar");
    }

    @Test
    @DisplayName("a helper whose namespace is not a literal proves nothing")
    void declinesANonLiteralNamespace() {
        byte[] loader = classWith(LOADER, cw -> {
            MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerBlock",
                    "(Ljava/lang/String;Ljava/lang/String;L" + BLOCK + ";)L" + BLOCK + ";", null, null);
            helper.visitCode();
            helper.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "BLOCK", "L" + REGISTRY + ";");
            helper.visitVarInsn(Opcodes.ALOAD, 0);
            helper.visitVarInsn(Opcodes.ALOAD, 1);
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
            helper.visitVarInsn(Opcodes.ALOAD, 2);
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            helper.visitTypeInsn(Opcodes.CHECKCAST, BLOCK);
            helper.visitInsn(Opcodes.ARETURN);
            helper.visitMaxs(0, 0);
            helper.visitEnd();
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "registerBlocks", "()V", null, null);
            register.visitCode();
            register.visitLdcInsn("xclass");
            register.visitLdcInsn("foo");
            register.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "registerBlock",
                    "(Ljava/lang/String;Ljava/lang/String;L" + BLOCK + ";)L" + BLOCK + ";", false);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        assertTrue(index(commonBlocks("FOO"), loader).isEmpty());
    }

    @Test
    @DisplayName("NeoForge: RegisterEvent's block consumer names the common class's field")
    void readsARegisterEventConsumer() {
        String event = "net/neoforged/neoforge/registries/RegisterEvent";
        String helper = "net/neoforged/neoforge/registries/RegisterEvent$RegisterHelper";
        byte[] loader = classWith(LOADER, cw -> {
            MethodVisitor onRegister = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onRegister",
                    "(L" + event + ";)V", null, null);
            onRegister.visitCode();
            onRegister.visitVarInsn(Opcodes.ALOAD, 0);
            onRegister.visitFieldInsn(Opcodes.GETSTATIC, REGISTRIES, "BLOCK", "L" + RESOURCE_KEY + ";");
            onRegister.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory(),
                    Type.getType("(Ljava/lang/Object;)V"),
                    new Handle(Opcodes.H_INVOKESTATIC, LOADER, "lambda$onRegister$0", "(L" + helper + ";)V", false),
                    Type.getType("(L" + helper + ";)V"));
            onRegister.visitMethodInsn(Opcodes.INVOKEVIRTUAL, event, "register",
                    "(L" + RESOURCE_KEY + ";Ljava/util/function/Consumer;)V", false);
            onRegister.visitInsn(Opcodes.RETURN);
            onRegister.visitMaxs(0, 0);
            onRegister.visitEnd();
            MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    "lambda$onRegister$0", "(L" + helper + ";)V", null, null);
            lambda.visitCode();
            lambda.visitVarInsn(Opcodes.ALOAD, 0);
            lambda.visitTypeInsn(Opcodes.NEW, RESOURCE_LOCATION);
            lambda.visitInsn(Opcodes.DUP);
            lambda.visitLdcInsn("xclass");
            lambda.visitLdcInsn("foo");
            lambda.visitMethodInsn(Opcodes.INVOKESPECIAL, RESOURCE_LOCATION, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            lambda.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            lambda.visitMethodInsn(Opcodes.INVOKEINTERFACE, helper, "register",
                    "(L" + RESOURCE_LOCATION + ";Ljava/lang/Object;)V", true);
            lambda.visitInsn(Opcodes.RETURN);
            lambda.visitMaxs(0, 0);
            lambda.visitEnd();
        });

        assertArrayEquals(new String[]{"xclass", "foo"}, index(commonBlocks("FOO"), loader).fieldsOf(COMMON).get("FOO"));
    }

    @Test
    @DisplayName("NeoForge: a deferred register's supplier that returns the common class's field")
    void readsADeferredRegisterSupplier() {
        String deferred = "net/neoforged/neoforge/registries/DeferredRegister";
        String holder = "net/neoforged/neoforge/registries/DeferredHolder";
        byte[] loader = classWith(LOADER, cw -> {
            cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BLOCKS", "L" + deferred + ";", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitFieldInsn(Opcodes.GETSTATIC, REGISTRIES, "BLOCK", "L" + RESOURCE_KEY + ";");
            init.visitLdcInsn("xclass");
            init.visitMethodInsn(Opcodes.INVOKESTATIC, deferred, "create",
                    "(L" + RESOURCE_KEY + ";Ljava/lang/String;)L" + deferred + ";", false);
            init.visitFieldInsn(Opcodes.PUTSTATIC, LOADER, "BLOCKS", "L" + deferred + ";");
            init.visitFieldInsn(Opcodes.GETSTATIC, LOADER, "BLOCKS", "L" + deferred + ";");
            init.visitLdcInsn("foo");
            init.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", metafactory(),
                    Type.getType("()Ljava/lang/Object;"),
                    new Handle(Opcodes.H_INVOKESTATIC, LOADER, "lambda$static$0", "()L" + BLOCK + ";", false),
                    Type.getType("()L" + BLOCK + ";"));
            init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, deferred, "register",
                    "(Ljava/lang/String;Ljava/util/function/Supplier;)L" + holder + ";", false);
            init.visitInsn(Opcodes.POP);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    "lambda$static$0", "()L" + BLOCK + ";", null, null);
            lambda.visitCode();
            lambda.visitFieldInsn(Opcodes.GETSTATIC, COMMON, "FOO", "L" + BLOCK + ";");
            lambda.visitInsn(Opcodes.ARETURN);
            lambda.visitMaxs(0, 0);
            lambda.visitEnd();
        });

        assertArrayEquals(new String[]{"xclass", "foo"}, index(commonBlocks("FOO"), loader).fieldsOf(COMMON).get("FOO"));
    }

    @Test
    @DisplayName("only a class that names an indexed field's class can store into it")
    void prefiltersOnTheOwnerName() {
        StaticFieldRegistryIds ids = index(commonBlocks("FOO"), loader(helperCall("foo", "FOO")));

        assertTrue(ids.mayStoreInto(commonBlocks("FOO")));
        assertFalse(ids.mayStoreInto(classWith("test/xclass/Unrelated", cw -> {})));
    }

    // Fixtures, named as the remap leaves them: Mojang names, with ResourceLocation where a 1.20
    // jar's constructor call survives.

    private static StaticFieldRegistryIds index(byte[]... classes) {
        Set<String> names = new HashSet<>();
        List<ClassNode> nodes = new ArrayList<>();
        for (byte[] bytes : classes) {
            ClassNode node = node(bytes);
            names.add(node.name);
            nodes.add(node);
        }
        StaticFieldRegistryIds.Builder builder = StaticFieldRegistryIds.builder(names);
        nodes.forEach(builder::add);
        return builder.build();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** {@code public static final Block NAME = new Block(Properties.of());} for each name. */
    static byte[] commonBlocks(String... fields) {
        String props = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
        return classWith(COMMON, cw -> {
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            for (String field : fields) {
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, field,
                        "L" + BLOCK + ";", null, null).visitEnd();
                init.visitTypeInsn(Opcodes.NEW, BLOCK);
                init.visitInsn(Opcodes.DUP);
                init.visitMethodInsn(Opcodes.INVOKESTATIC, props, "of", "()L" + props + ";", false);
                init.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "<init>", "(L" + props + ";)V", false);
                init.visitFieldInsn(Opcodes.PUTSTATIC, COMMON, field, "L" + BLOCK + ";");
            }
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        });
    }

    /**
     * The loader class: {@code registerBlock(String name, Block block)} registers
     * {@code new ResourceLocation("xclass", name)}, and {@code registerBlocks()} runs the body given.
     */
    @SafeVarargs
    private static byte[] loader(Consumer<MethodVisitor>... body) {
        String helperDesc = "(Ljava/lang/String;L" + BLOCK + ";)L" + BLOCK + ";";
        return classWith(LOADER, cw -> {
            MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "registerBlock",
                    helperDesc, null, null);
            helper.visitCode();
            helper.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "BLOCK", "L" + REGISTRY + ";");
            helper.visitTypeInsn(Opcodes.NEW, RESOURCE_LOCATION);
            helper.visitInsn(Opcodes.DUP);
            helper.visitLdcInsn("xclass");
            helper.visitVarInsn(Opcodes.ALOAD, 0);
            helper.visitMethodInsn(Opcodes.INVOKESPECIAL, RESOURCE_LOCATION, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            helper.visitVarInsn(Opcodes.ALOAD, 1);
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                    "(L" + REGISTRY + ";L" + RESOURCE_LOCATION + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
            helper.visitTypeInsn(Opcodes.CHECKCAST, BLOCK);
            helper.visitInsn(Opcodes.ARETURN);
            helper.visitMaxs(0, 0);
            helper.visitEnd();
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "registerBlocks", "()V", null, null);
            register.visitCode();
            for (Consumer<MethodVisitor> statement : body) statement.accept(register);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });
    }

    /** {@code registerBlock("name", ModBlocks.FIELD);} */
    private static Consumer<MethodVisitor> helperCall(String name, String field) {
        return mv -> {
            mv.visitLdcInsn(name);
            mv.visitFieldInsn(Opcodes.GETSTATIC, COMMON, field, "L" + BLOCK + ";");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "registerBlock",
                    "(Ljava/lang/String;L" + BLOCK + ";)L" + BLOCK + ";", false);
            mv.visitInsn(Opcodes.POP);
        };
    }

    private static Handle metafactory() {
        return new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    private static byte[] classWith(String name, Consumer<ClassWriter> members) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        members.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
