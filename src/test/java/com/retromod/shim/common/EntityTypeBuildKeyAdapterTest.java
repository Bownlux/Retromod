/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Pre1_21_2EntityTypeBuildBridge;
import com.retromod.testsupport.fakemc.FakeMinecraftHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The old {@code EntityType.Builder.build(String)} call learns the id its type is registered
 * under, or the mod's namespace, because 1.21.2 names the type's translation key and loot table
 * from the key it builds with. Cake Cow (Forge 1.20.1) built with a bare {@code "cake_cow"} inside
 * a deferred register created for {@code "cake_cow"}, and on NeoForge 26.3 showed
 * {@code entity.minecraft.cake_cow} and dropped nothing.
 */
class EntityTypeBuildKeyAdapterTest {

    private static final String BUILDER = "net/minecraft/world/entity/EntityType$Builder";
    private static final String ENTITY_TYPE = "net/minecraft/world/entity/EntityType";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String BUILD_DESC = "(Ljava/lang/String;)L" + ENTITY_TYPE + ";";
    private static final String REGISTER_DESC =
            "(L" + REGISTRY + ";L" + IDENTIFIER + ";Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String ID_DESC = "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String host = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
    }

    @Test
    @DisplayName("a bare name built in a deferred register's supplier gets the register's namespace (Cake Cow)")
    void deferredRegisterSupplierGetsTheRegistersNamespace() {
        officialBridge();
        byte[] entities = classWith("test/cake/CakeEntities", cw -> {
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/Registries", "ENTITY_TYPE",
                    "Lnet/minecraft/resources/ResourceKey;");
            init.visitLdcInsn("cake_cow");
            init.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/neoforge/registries/DeferredRegister", "create",
                    "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/String;)Lnet/neoforged/neoforge/registries/DeferredRegister;",
                    false);
            init.visitInsn(Opcodes.POP);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            // private static EntityType lambda$register$0(EntityType.Builder builder, String name) { return builder.build(name); }
            MethodVisitor supplier = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$register$0",
                    "(L" + BUILDER + ";Ljava/lang/String;)L" + ENTITY_TYPE + ";", null, null);
            supplier.visitCode();
            supplier.visitVarInsn(Opcodes.ALOAD, 0);
            supplier.visitVarInsn(Opcodes.ALOAD, 1);
            supplier.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
            supplier.visitInsn(Opcodes.ARETURN);
            supplier.visitMaxs(0, 0);
            supplier.visitEnd();
        });

        MethodNode supplier = method(transform(entities, "test/cake/CakeEntities"), "lambda$register$0");

        MethodInsnNode call = keyedBridgeCall(supplier, "buildOfficial");
        assertNull(registeredIdArgument(call), "nothing names the registered id at this call");
        assertEquals("cake_cow", namespaceArgument(call));
        verifies(supplier, "test/cake/CakeEntities");
    }

    @Test
    @DisplayName("a type registered straight under a literal id is built under it (intermediary hosts before 26.1)")
    void directIntermediaryRegistrationUsesItsLiteralId() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        transformer.clearRedirectsForTesting();
        Pre1_21_2EntityTypeBuildBridge.registerRedirects(transformer);
        String builder = "net/minecraft/class_1299$class_1300";
        String identifier = "net/minecraft/class_2960";
        byte[] entities = classWith("test/lw/Entities", cw -> {
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                    "(L" + builder + ";)V", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_7923", "field_41177",
                    "Lnet/minecraft/class_7922;");
            register.visitTypeInsn(Opcodes.NEW, identifier);
            register.visitInsn(Opcodes.DUP);
            register.visitLdcInsn("longwings");
            register.visitLdcInsn("moth");
            register.visitMethodInsn(Opcodes.INVOKESPECIAL, identifier, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            register.visitVarInsn(Opcodes.ALOAD, 0);
            register.visitLdcInsn("old_moth_name");
            register.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "method_5905",
                    "(Ljava/lang/String;)Lnet/minecraft/class_1299;", false);
            register.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/class_2378", "method_10230",
                    "(Lnet/minecraft/class_2378;L" + identifier + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        MethodNode register = method(transform(entities, "test/lw/Entities"), "register");

        MethodInsnNode call = keyedBridgeCall(register, "build");
        assertEquals("longwings:moth", registeredIdArgument(call));
        assertEquals("longwings", namespaceArgument(call));
    }

    @Test
    @DisplayName("a type registered under a computed id is built under that id, which runs on the host")
    void directRegistrationUnderAComputedIdIsCaptured() throws Exception {
        officialBridge();
        // static Object register(String name, EntityType.Builder builder) {
        //     return Registry.register(BuiltInRegistries.ENTITY_TYPE,
        //             Identifier.fromNamespaceAndPath("mod", name), builder.build("unrelated")); }
        byte[] entities = classWith("test/fab/Entities", cw -> {
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                    "(Ljava/lang/String;L" + BUILDER + ";)Ljava/lang/Object;", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "ENTITY_TYPE", "L" + REGISTRY + ";");
            register.visitLdcInsn("mod");
            register.visitVarInsn(Opcodes.ALOAD, 0);
            register.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath", ID_DESC, false);
            register.visitVarInsn(Opcodes.ALOAD, 1);
            register.visitLdcInsn("unrelated");
            register.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            register.visitInsn(Opcodes.ARETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        byte[] transformed = transform(entities, "test/fab/Entities");
        MethodNode register = method(transformed, "register");
        MethodInsnNode call = keyedBridgeCall(register, "buildOfficial");
        AbstractInsnNode id = call.getPrevious().getPrevious();
        assertInstanceOf(VarInsnNode.class, id, "the registered id is read back from a local");
        assertEquals("mod", namespaceArgument(call));

        // Run it: the stand-in registry hands back the built type, which shows its key.
        FakeMinecraftHost host = new FakeMinecraftHost(IDENTIFIER, "", Map.of("test/fab/Entities", transformed));
        Object built = host.type("test/fab/Entities")
                .getMethod("register", String.class, host.type(BUILDER))
                .invoke(null, "moth", host.builder());
        assertEquals("ResourceKey[minecraft:entity_type / mod:moth]", String.valueOf(built));
    }

    @Test
    @DisplayName("on 26.x the id still reads through the register bridge and the Fabric id stamp")
    void readsThroughTheTwentySixRegistrationShape() {
        officialBridge();
        // On 26.x Registry.register goes to the worldgen type bridge, and on Fabric the id bridge
        // copies the id with a dup to stamp it. Both run before this adapter.
        WorldgenTypeBridgeSynthetic.registerPre26_2(transformer);
        transformer.registerSyntheticClass(com.retromod.shim.fabric.FabricRegistryIdSynthetic.INTERNAL,
                com.retromod.shim.fabric.FabricRegistryIdSynthetic.generate());
        byte[] entities = classWith("test/fab26/Entities", cw -> {
            MethodVisitor direct = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "direct",
                    "(L" + BUILDER + ";)Ljava/lang/Object;", null, null);
            direct.visitCode();
            direct.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "ENTITY_TYPE", "L" + REGISTRY + ";");
            direct.visitLdcInsn("mod");
            direct.visitLdcInsn("moth");
            direct.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath", ID_DESC, false);
            direct.visitVarInsn(Opcodes.ALOAD, 0);
            direct.visitLdcInsn("moth");
            direct.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
            direct.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            direct.visitInsn(Opcodes.ARETURN);
            direct.visitMaxs(0, 0);
            direct.visitEnd();
            MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "helper",
                    "(Ljava/lang/String;L" + BUILDER + ";)Ljava/lang/Object;", null, null);
            helper.visitCode();
            helper.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "ENTITY_TYPE", "L" + REGISTRY + ";");
            helper.visitLdcInsn("mod");
            helper.visitVarInsn(Opcodes.ALOAD, 0);
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath", ID_DESC, false);
            helper.visitVarInsn(Opcodes.ALOAD, 1);
            helper.visitLdcInsn("unrelated");
            helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            helper.visitInsn(Opcodes.ARETURN);
            helper.visitMaxs(0, 0);
            helper.visitEnd();
        });

        ClassNode transformed = read(transform(entities, "test/fab26/Entities"));

        MethodNode direct = method(transformed, "direct");
        assertTrue(calls(direct, WorldgenTypeBridgeSynthetic.INTERNAL, "register"),
                "the registration must go through the worldgen type bridge, as it does on a host");
        assertTrue(calls(direct, com.retromod.shim.fabric.FabricRegistryIdSynthetic.INTERNAL, "push"),
                "the Fabric id bridge must stamp the id, as it does on a host");
        assertEquals("mod:moth", registeredIdArgument(keyedBridgeCall(direct, "buildOfficial")));
        MethodNode helper = method(transformed, "helper");
        assertInstanceOf(VarInsnNode.class, keyedBridgeCall(helper, "buildOfficial").getPrevious().getPrevious(),
                "the computed id is read back from a local");
        verifies(direct, "test/fab26/Entities");
        verifies(helper, "test/fab26/Entities");
    }

    @Test
    @DisplayName("a type built inside another registry's registration takes no id from it")
    void anotherRegistrysRegistrationIsNotTheEntitysId() {
        officialBridge();
        byte[] items = classWith("test/egg/Items", cw -> {
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                    "(L" + BUILDER + ";)V", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "ITEM", "L" + REGISTRY + ";");
            register.visitLdcInsn("mod");
            register.visitLdcInsn("moth_spawn_egg");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath", ID_DESC, false);
            register.visitVarInsn(Opcodes.ALOAD, 0);
            register.visitLdcInsn("moth");
            register.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        MethodInsnNode call = keyedBridgeCall(method(transform(items, "test/egg/Items"), "register"), "buildOfficial");
        assertNull(registeredIdArgument(call), "the spawn egg's id must not name the entity");
        assertEquals("mod", namespaceArgument(call));
    }

    @Test
    @DisplayName("a type built into a static field that another class registers is built under that id")
    void staticFieldRegisteredByAnotherClassUsesItsId() {
        officialBridge();
        String common = "test/xclass/ModEntities";
        byte[] entities = classWith(common, cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MOTH", "L" + ENTITY_TYPE + ";", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitInsn(Opcodes.ACONST_NULL);
            init.visitTypeInsn(Opcodes.CHECKCAST, BUILDER);
            init.visitLdcInsn("old_moth_name");
            init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
            init.visitFieldInsn(Opcodes.PUTSTATIC, common, "MOTH", "L" + ENTITY_TYPE + ";");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        });
        byte[] loader = classWith("test/xclass/ModEntitiesFabric", cw -> {
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()V", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "ENTITY_TYPE", "L" + REGISTRY + ";");
            register.visitLdcInsn("xclass");
            register.visitLdcInsn("moth");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath", ID_DESC, false);
            register.visitFieldInsn(Opcodes.GETSTATIC, common, "MOTH", "L" + ENTITY_TYPE + ";");
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register", REGISTER_DESC, true);
            register.visitInsn(Opcodes.POP);
            register.visitInsn(Opcodes.RETURN);
            register.visitMaxs(0, 0);
            register.visitEnd();
        });

        StaticFieldRegistryIds ids = transformer.indexJarRegistryIds(List.of(entities, loader));
        ClassNode transformed;
        try (var scope = transformer.pushJarRegistryIds(ids)) {
            transformed = read(transformer.transformClass(entities, common));
        }

        MethodInsnNode call = keyedBridgeCall(method(transformed, "<clinit>"), "buildOfficial");
        assertEquals("xclass:moth", registeredIdArgument(call));
    }

    @Test
    @DisplayName("a class that names no namespace takes the one its jar registers under")
    void classWithoutANamespaceTakesTheJars() {
        officialBridge();
        byte[] entities = classWith("test/jar/Builders", cw -> {
            MethodVisitor build = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                    "(L" + BUILDER + ";)L" + ENTITY_TYPE + ";", null, null);
            build.visitCode();
            build.visitVarInsn(Opcodes.ALOAD, 0);
            build.visitLdcInsn("moth");
            build.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
            build.visitInsn(Opcodes.ARETURN);
            build.visitMaxs(0, 0);
            build.visitEnd();
        });
        byte[] registers = classWith("test/jar/Registers", cw -> {
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitLdcInsn("jarmod");
            init.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/Registries", "ENTITY_TYPE",
                    "Lnet/minecraft/resources/ResourceKey;");
            init.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/architectury/registry/registries/DeferredRegister", "create",
                    "(Ljava/lang/String;Lnet/minecraft/resources/ResourceKey;)Ldev/architectury/registry/registries/DeferredRegister;",
                    false);
            init.visitInsn(Opcodes.POP);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        });

        StaticFieldRegistryIds ids = transformer.indexJarRegistryIds(List.of(entities, registers));
        assertEquals("jarmod", ids.namespace());
        ClassNode transformed;
        try (var scope = transformer.pushJarRegistryIds(ids)) {
            transformed = read(transformer.transformClass(entities, "test/jar/Builders"));
        }

        assertEquals("jarmod", namespaceArgument(keyedBridgeCall(method(transformed, "build"), "buildOfficial")));
    }

    @Test
    @DisplayName("NeoForge and Forge hosts from 1.21.2 to 1.21.11 bridge build(String) as well")
    void loaderShimsBeforeTwentySixBridgeTheBuild() {
        for (com.retromod.core.VersionShim shim : new com.retromod.core.VersionShim[]{
                new com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2(),
                new com.retromod.shim.forge.Forge_1_21_1_to_1_21_2()}) {
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            byte[] entities = classWith("test/loader/Entities", cw -> {
                MethodVisitor build = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                        "(L" + BUILDER + ";)L" + ENTITY_TYPE + ";", null, null);
                build.visitCode();
                build.visitVarInsn(Opcodes.ALOAD, 0);
                build.visitLdcInsn("example:moth");
                build.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "build", BUILD_DESC, false);
                build.visitInsn(Opcodes.ARETURN);
                build.visitMaxs(0, 0);
                build.visitEnd();
            });

            MethodNode build = method(transform(entities, "test/loader/Entities"), "build");
            boolean bridged = false;
            for (AbstractInsnNode insn : build.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(EntityTypeBuildKeyAdapter.BRIDGE)
                        && call.name.equals("buildOfficial")) {
                    bridged = true;
                }
            }
            assertTrue(bridged, shim.getShimName() + " must bridge EntityType.Builder.build(String)");
        }
    }

    private void officialBridge() {
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.registerOfficialEntityTypeBuildBridge(transformer);
    }

    private byte[] transform(byte[] classBytes, String name) {
        return transformer.transformClass(classBytes, name);
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }

    private static MethodInsnNode keyedBridgeCall(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(EntityTypeBuildKeyAdapter.BRIDGE)
                    && call.name.equals(name)) {
                assertEquals(EntityTypeBuildKeyAdapter.KEYED_DESC, call.desc,
                        "the bridge call must carry the registered id and namespace");
                return call;
            }
        }
        throw new AssertionError("no " + name + " bridge call in " + method.name);
    }

    /** The literal registered id pushed for the call, or null for {@code aconst_null}. */
    private static String registeredIdArgument(MethodInsnNode call) {
        return constant(call.getPrevious().getPrevious());
    }

    private static String namespaceArgument(MethodInsnNode call) {
        return constant(call.getPrevious());
    }

    private static String constant(AbstractInsnNode insn) {
        if (insn instanceof InsnNode none && none.getOpcode() == Opcodes.ACONST_NULL) return null;
        assertInstanceOf(LdcInsnNode.class, insn);
        return (String) ((LdcInsnNode) insn).cst;
    }

    private static void assertNull(String value, String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message);
    }

    private static void verifies(MethodNode method, String owner) {
        try {
            new Analyzer<>(new BasicVerifier()).analyze(owner, method);
        } catch (Exception e) {
            throw new AssertionError(method.name + " does not verify: " + e.getMessage(), e);
        }
    }

    private static MethodNode method(byte[] classBytes, String name) {
        return method(read(classBytes), name);
    }

    private static MethodNode method(ClassNode cn, String name) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name)) return m;
        }
        throw new AssertionError("no method " + name);
    }

    private static ClassNode read(byte[] classBytes) {
        assertNotNull(classBytes);
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        return cn;
    }

    private static byte[] classWith(String name, Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
