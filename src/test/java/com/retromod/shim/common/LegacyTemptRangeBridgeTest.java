/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.retromod.shim.common.LegacyTemptRangeBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.21.1 or older mob builds attributes that lack the {@code minecraft:tempt_range} a 1.21.2+
 * {@code TemptGoal} reads, and the goal throws on the mob's first tick. Mobs built from
 * {@code Mob.createMobAttributes()}, {@code Monster.createMonsterAttributes()} and
 * {@code LivingEntity.createLivingAttributes()} all lack it, so the build is the hook.
 */
class LegacyTemptRangeBridgeTest {

    private static final String MONSTER = "net/minecraft/world/entity/monster/Monster";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String MOD = "test/mod/ModEntities";
    private static final String STUB_MOB_BUILDER = "test/stub/MobTypeBuilder";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("attributes built from any host factory go through the tempt range check")
    void everyFactoryIsBuiltThroughTheHelper() {
        registerRedirects(transformer);
        List<String> calls = calls(transform(modEntities()), "createAll");

        for (String factory : List.of("net/minecraft/world/entity/Mob.createMobAttributes",
                MONSTER + ".createMonsterAttributes", LIVING + ".createLivingAttributes")) {
            assertTrue(calls.contains(factory + "()" + L_BUILDER),
                    "the host factory itself is not redirected: " + calls);
        }
        assertEquals(3, calls.stream().filter((HELPER + ".build" + BUILD_HELPER)::equals).count(), calls.toString());
        assertFalse(calls.contains(BUILDER + ".build" + BUILD), calls.toString());
    }

    @Test
    @DisplayName("a builder without the tempt range gets it once, at the attribute's default")
    void missingTemptRangeIsAdded() throws Exception {
        HostStubs stubs = stubs();
        Object builder = stubs.load(BUILDER).getConstructor().newInstance();
        Object temptRange = temptRange(stubs);

        Object supplier = stubs.method(HELPER, "build").invoke(null, builder);

        assertEquals(List.of(temptRange), added(builder), "added once, without a value of its own");
        assertTrue((boolean) stubs.method(SUPPLIER, "hasAttribute").invoke(supplier, temptRange));
    }

    @Test
    @DisplayName("a mob that sets the tempt range itself keeps its own value")
    void explicitTemptRangeIsKept() throws Exception {
        HostStubs stubs = stubs();
        Object builder = stubs.load(BUILDER).getConstructor().newInstance();
        Object temptRange = temptRange(stubs);
        stubs.method(BUILDER, "add").invoke(builder, temptRange);

        stubs.method(HELPER, "build").invoke(null, builder);

        assertEquals(List.of(temptRange), added(builder),
                "a second add would replace the mod's value with the default");
    }

    @Test
    @DisplayName("Fabric API registration and entity type builders hand the builder through the check")
    void fabricApiCallsAreChecked() throws Exception {
        registerRedirects(transformer);
        List<String> calls = calls(transform(fabricEntities()), "register");
        assertTrue(calls.contains(FABRIC_HELPER + ".register" + FABRIC_REGISTER), calls.toString());
        assertTrue(calls.contains(FABRIC_HELPER + ".defaultAttributes(L" + FABRIC_MOB + ";"
                + MOB_DEFAULTS.substring(1)), calls.toString());
        assertTrue(calls.contains(FABRIC_HELPER + ".defaultAttributes(L" + FABRIC_LIVING + ";"
                + LIVING_DEFAULTS.substring(1)), calls.toString());

        HostStubs stubs = stubs();
        Object temptRange = temptRange(stubs);
        Object builder = stubs.load(BUILDER).getConstructor().newInstance();
        stubs.method(FABRIC_HELPER, "register").invoke(null, null, builder);
        assertSame(builder, stubs.load(FABRIC_REGISTRY).getField("registered").get(null));
        assertEquals(List.of(temptRange), added(builder), "registered with the tempt range");

        Object fresh = stubs.load(BUILDER).getConstructor().newInstance();
        Object typeBuilder = stubs.load(STUB_MOB_BUILDER).getConstructor().newInstance();
        Supplier<Object> modSupplier = () -> fresh;
        stubs.load(FABRIC_HELPER).getMethod("defaultAttributes", stubs.load(FABRIC_MOB), Supplier.class)
                .invoke(null, typeBuilder, modSupplier);
        Supplier<?> handed = (Supplier<?>) typeBuilder.getClass().getField("attributes").get(typeBuilder);
        assertSame(fresh, handed.get(), "Fabric API still gets the mod's own builder");
        assertEquals(List.of(temptRange), added(fresh), "checked when Fabric API asks for it");
    }

    @Test
    @DisplayName("Architectury's attribute registration hands the builder through the check")
    void architecturyRegistrationIsChecked() throws Exception {
        registerRedirects(transformer);
        List<String> calls = calls(transform(architecturyEntities()), "register");
        assertEquals(List.of(ARCHITECTURY_HELPER + ".register" + ARCHITECTURY_REGISTER), calls,
                "Architectury builds the builder inside the library, where no build() call is rewritten");

        HostStubs stubs = stubs().add(architecturyRegistry());
        Object temptRange = temptRange(stubs);
        Object fresh = stubs.load(BUILDER).getConstructor().newInstance();
        Supplier<Object> type = () -> "entity type";
        Supplier<Object> modSupplier = () -> fresh;
        stubs.method(ARCHITECTURY_HELPER, "register").invoke(null, type, modSupplier);

        Class<?> registry = stubs.load(ARCHITECTURY_REGISTRY);
        assertSame(type, registry.getField("type").get(null), "the entity type supplier is passed as is");
        Supplier<?> handed = (Supplier<?>) registry.getField("attributes").get(null);
        assertSame(fresh, handed.get(), "Architectury still gets the mod's own builder");
        assertEquals(List.of(temptRange), added(fresh), "checked when Architectury asks for it");
    }

    @Test
    @DisplayName("a builder subclass's super.build() keeps reaching the host")
    void superBuildIsLeftAlone() {
        registerRedirects(transformer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/ModBuilder", null, BUILDER, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "build", BUILD, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, BUILDER, "build", BUILD, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(transformer.transformClass(cw.toByteArray(), "test/mod/ModBuilder"), "build");
        assertEquals(List.of(BUILDER + ".build" + BUILD), calls,
                "the helper calls build() virtually, which would recurse into the override");
    }

    @ParameterizedTest(name = "a mod for {0} is rewritten: {1}")
    @DisplayName("only mods built before 1.21.2 are rewritten")
    @CsvSource(delimiter = ';', value = {
            "1.20.1;true", "~1.21.1;true", "[1.20.1,1.21);true", "unknown;true",
            "1.21.2;false", ">=1.21.4;false", "[1.21.2,);false", "26.1.2;false"})
    void onlyOlderModsAreRewritten(String declared, boolean rewritten) {
        registerRedirects(transformer);
        transformer.setModSourceVersion(declared);
        List<String> built = calls(transform(modEntities()), "createAll");
        List<String> fabric = calls(transform(fabricEntities()), "register");
        List<String> architectury = calls(transform(architecturyEntities()), "register");
        transformer.setModSourceVersion(null);

        assertEquals(rewritten, built.contains(HELPER + ".build" + BUILD_HELPER), built.toString());
        assertEquals(rewritten, !built.contains(BUILDER + ".build" + BUILD), built.toString());
        assertEquals(rewritten, fabric.contains(FABRIC_HELPER + ".register" + FABRIC_REGISTER), fabric.toString());
        assertEquals(rewritten, architectury.contains(ARCHITECTURY_HELPER + ".register" + ARCHITECTURY_REGISTER),
                architectury.toString());
    }

    @Test
    @DisplayName("offline, only a 1.21.2 or newer host gets the bridge")
    void offlineHostVersionDecides() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertFalse(hostTemptGoalReadsAttribute());
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(hostTemptGoalReadsAttribute());
    }

    // --- the mod under test ---------------------------------------------------------------------

    private byte[] transform(byte[] mod) {
        String name = new ClassReader(mod).getClassName();
        try (var scope = transformer.pushJarClassBytesProvider(Map.of(name, mod)::get)) {
            return transformer.transformClass(mod, name + ".class");
        }
    }

    /** Attributes built from the three host factories that lack the tempt range on 1.21.2+. */
    private static byte[] modEntities() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "createAll", "()V", null, null);
        mv.visitCode();
        for (String[] factory : new String[][] {
                {"net/minecraft/world/entity/Mob", "createMobAttributes"},
                {MONSTER, "createMonsterAttributes"},
                {LIVING, "createLivingAttributes"}}) {
            mv.visitMethodInsn(INVOKESTATIC, factory[0], factory[1], "()" + L_BUILDER, false);
            mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "build", BUILD, false);
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The Fabric API calls that build a mod's builder inside Fabric API. */
    private static byte[] fabricEntities() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/FabricEntities", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(L" + ENTITY_TYPE + ";" + L_BUILDER + "L" + FABRIC_MOB + ";L" + FABRIC_LIVING + ";"
                        + L_SUPPLIER_FN + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, FABRIC_REGISTRY, "register", FABRIC_REGISTER, false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, FABRIC_MOB, "defaultAttributes", MOB_DEFAULTS, true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, FABRIC_LIVING, "defaultAttributes", LIVING_DEFAULTS, true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A multi-loader mod registering its mob's attributes through Architectury. */
    private static byte[] architecturyEntities() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/ArchitecturyEntities", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(" + L_SUPPLIER_FN + L_SUPPLIER_FN + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, ARCHITECTURY_REGISTRY, "register", ARCHITECTURY_REGISTER, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // --- stand-ins for the host classes the helpers call ----------------------------------------

    private static HostStubs stubs() {
        return new HostStubs()
                .add(attributes())
                .add(supplier())
                .add(builder())
                .add(fabricRegistry())
                .add(HostStubs.iface(FABRIC_LIVING))
                .add(mobTypeBuilderInterface())
                .add(mobTypeBuilder())
                .add(HELPER, generateHelper())
                .add(FABRIC_HELPER, generateFabricHelper())
                .add(ARCHITECTURY_HELPER, generateArchitecturyHelper());
    }

    private static Object temptRange(HostStubs stubs) throws Exception {
        return stubs.load(ATTRIBUTES).getField("TEMPT_RANGE").get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> added(Object builder) throws Exception {
        return (List<Object>) builder.getClass().getField("added").get(builder);
    }

    /** {@code Attributes.TEMPT_RANGE}, a holder instance. */
    private static byte[] attributes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ATTRIBUTES, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "TEMPT_RANGE", L_HOLDER, null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, HOLDER);
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, ATTRIBUTES, "TEMPT_RANGE", L_HOLDER);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code AttributeSupplier} over a list of holders, answering {@code hasAttribute}. */
    private static byte[] supplier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, SUPPLIER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "holders", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, SUPPLIER, "holders", "Ljava/util/List;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor has = cw.visitMethod(ACC_PUBLIC, "hasAttribute", HAS_ATTRIBUTE, null, null);
        has.visitCode();
        has.visitVarInsn(ALOAD, 0);
        has.visitFieldInsn(GETFIELD, SUPPLIER, "holders", "Ljava/util/List;");
        has.visitVarInsn(ALOAD, 1);
        has.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "contains", "(Ljava/lang/Object;)Z", true);
        has.visitInsn(IRETURN);
        has.visitMaxs(0, 0);
        has.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code AttributeSupplier.Builder} keeping each {@code add} in the public {@code added} list.
     * Like the host's, it can be built more than once.
     */
    private static byte[] builder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, BUILDER, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "added", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitTypeInsn(NEW, "java/util/ArrayList");
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        init.visitFieldInsn(PUTFIELD, BUILDER, "added", "Ljava/util/List;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor add = cw.visitMethod(ACC_PUBLIC, "add", ADD, null, null);
        add.visitCode();
        add.visitVarInsn(ALOAD, 0);
        add.visitFieldInsn(GETFIELD, BUILDER, "added", "Ljava/util/List;");
        add.visitVarInsn(ALOAD, 1);
        add.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        add.visitInsn(POP);
        add.visitVarInsn(ALOAD, 0);
        add.visitInsn(ARETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        MethodVisitor build = cw.visitMethod(ACC_PUBLIC, "build", BUILD, null, null);
        build.visitCode();
        build.visitTypeInsn(NEW, SUPPLIER);
        build.visitInsn(DUP);
        build.visitTypeInsn(NEW, "java/util/ArrayList");
        build.visitInsn(DUP);
        build.visitVarInsn(ALOAD, 0);
        build.visitFieldInsn(GETFIELD, BUILDER, "added", "Ljava/util/List;");
        build.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V", false);
        build.visitMethodInsn(INVOKESPECIAL, SUPPLIER, "<init>", "(Ljava/util/List;)V", false);
        build.visitInsn(ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code FabricDefaultAttributeRegistry} keeping the last registered builder. */
    private static byte[] fabricRegistry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, FABRIC_REGISTRY, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "registered", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", FABRIC_REGISTER, null, null);
        register.visitCode();
        register.visitVarInsn(ALOAD, 1);
        register.visitFieldInsn(PUTSTATIC, FABRIC_REGISTRY, "registered", "Ljava/lang/Object;");
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Architectury's {@code EntityAttributeRegistry}, keeping the last pair of suppliers. */
    private static byte[] architecturyRegistry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, ARCHITECTURY_REGISTRY, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "type", L_SUPPLIER_FN, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "attributes", L_SUPPLIER_FN, null, null).visitEnd();
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", ARCHITECTURY_REGISTER,
                null, null);
        register.visitCode();
        register.visitVarInsn(ALOAD, 0);
        register.visitFieldInsn(PUTSTATIC, ARCHITECTURY_REGISTRY, "type", L_SUPPLIER_FN);
        register.visitVarInsn(ALOAD, 1);
        register.visitFieldInsn(PUTSTATIC, ARCHITECTURY_REGISTRY, "attributes", L_SUPPLIER_FN);
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code FabricEntityType.Builder.Mob}, with its {@code defaultAttributes(Supplier)}. */
    private static byte[] mobTypeBuilderInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, FABRIC_MOB, null, "java/lang/Object",
                new String[] {FABRIC_LIVING});
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "defaultAttributes", MOB_DEFAULTS, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An entity type builder keeping the attribute supplier it was given. */
    private static byte[] mobTypeBuilder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, STUB_MOB_BUILDER, null, "java/lang/Object", new String[] {FABRIC_MOB});
        cw.visitField(ACC_PUBLIC, "attributes", L_SUPPLIER_FN, null, null).visitEnd();
        HostStubs.defaultConstructor(cw, "java/lang/Object");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "defaultAttributes", MOB_DEFAULTS, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, STUB_MOB_BUILDER, "attributes", L_SUPPLIER_FN);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
