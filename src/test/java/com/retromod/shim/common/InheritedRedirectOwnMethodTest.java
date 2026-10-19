/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A redirect that follows the class hierarchy must leave a call alone when the mod's own class
 * declares the old method, because the JVM links the call to that declaration. A bred MCreator
 * mob with an "on initial spawn" procedure overrides the five-argument {@code finalizeSpawn} and
 * calls it on its own baby; the redirect used to send that call past the override, so the
 * procedure never ran for bred babies.
 *
 * <p>A call that names the host type, such as MCreator's spawn procedure on a {@code Mob} it cast
 * to, still takes the redirect, so the helper itself runs the receiver's own override, and the
 * override's {@code super} call goes to a helper twin that only calls the host.
 */
class InheritedRedirectOwnMethodTest {

    private static final String MOB = "net/minecraft/world/entity/Mob";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String HAND = "net/minecraft/world/InteractionHand";
    private static final String MOB_SPAWN_TYPE = Legacy1205MemberBridge.MOB_SPAWN_TYPE;
    private static final String SPAWN_REASON = Legacy1205MemberBridge.ENTITY_SPAWN_REASON;
    private static final String SPAWN_MOB = "test/mod/SpawnProcedureMob";
    private static final String BABY_MOB = "test/mod/SpawnProcedureBaby";
    private static final String BUILDER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder";
    private static final String COMMAND = "test/mod/SpawnCommand";
    private static final String SWINGER = "test/mod/SwingingMob";

    private RetromodTransformer transformer;
    private final Map<String, byte[]> jar = new HashMap<>();

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a mob's own five-argument finalizeSpawn keeps the call it makes on its offspring")
    void ownFinalizeSpawnOverrideKeepsItsCall() {
        String oldDesc = finalizeSpawnDesc(SPAWN_REASON);
        registerFinalizeSpawnBridge();
        jar.put(SPAWN_MOB, mobOverridingFinalizeSpawn(SPAWN_MOB, MOB, "finalizeSpawn", oldDesc));

        List<String> calls = calls(transform(SPAWN_MOB), "breed");
        assertTrue(calls.contains(SPAWN_MOB + ".finalizeSpawn" + oldDesc),
                "the call links to the mob's own override, which runs its spawn procedure: " + calls);
        assertFalse(calls.toString().contains(Legacy1205MemberBridge.HELPER),
                "the bridge would forward to the host and skip the override: " + calls);
    }

    @Test
    @DisplayName("a subclass calling the override its parent declares keeps the call too")
    void overrideDeclaredByAParentClassKeepsTheCall() {
        String oldDesc = finalizeSpawnDesc(SPAWN_REASON);
        registerFinalizeSpawnBridge();
        jar.put(SPAWN_MOB, mobOverridingFinalizeSpawn(SPAWN_MOB, MOB, "finalizeSpawn", oldDesc));
        jar.put(BABY_MOB, mobCalling(BABY_MOB, SPAWN_MOB, "finalizeSpawn", oldDesc));

        List<String> calls = calls(transform(BABY_MOB), "breed");
        assertTrue(calls.contains(BABY_MOB + ".finalizeSpawn" + oldDesc), calls.toString());
    }

    @Test
    @DisplayName("a mob without its own override still has the call bridged")
    void mobWithoutOverrideIsStillBridged() {
        String oldDesc = finalizeSpawnDesc(SPAWN_REASON);
        registerFinalizeSpawnBridge();
        jar.put(BABY_MOB, mobCalling(BABY_MOB, MOB, "finalizeSpawn", oldDesc));

        List<String> calls = calls(transform(BABY_MOB), "breed");
        assertTrue(calls.contains(Legacy1205MemberBridge.HELPER + ".finalizeSpawn(L" + MOB + ";"
                + oldDesc.substring(1)), "Cake Cow's own shape: " + calls);
    }

    @Test
    @DisplayName("an override still named by its Forge SRG id and old spawn type is recognized")
    void srgNamedOverrideIsRecognized() {
        transformer.registerSrgNameMappings(Map.of("m_6518_", "finalizeSpawn"), Map.of());
        transformer.registerClassRedirect(MOB_SPAWN_TYPE, SPAWN_REASON);
        registerFinalizeSpawnBridge();
        String shippedDesc = finalizeSpawnDesc(MOB_SPAWN_TYPE);
        jar.put(SPAWN_MOB, mobOverridingFinalizeSpawn(SPAWN_MOB, MOB, "m_6518_", shippedDesc));

        List<String> calls = calls(transform(SPAWN_MOB), "breed");
        assertTrue(calls.contains(SPAWN_MOB + ".finalizeSpawn" + finalizeSpawnDesc(SPAWN_REASON)),
                "the jar holds the override as m_6518_ with MobSpawnType, which the remap renames: "
                + calls);
    }

    @Test
    @DisplayName("an entity's own swing(hand, send) keeps the call it makes on itself")
    void ownSwingOverrideKeepsItsCall() {
        String swingSend = LegacySwingBridge.SWING_HAND_SEND;
        LegacySwingBridge.registerRedirects(transformer,
                List.of(LegacySwingBridge.SWING_HAND, swingSend));
        jar.put(SPAWN_MOB, classDeclaringAndCalling(SPAWN_MOB, LIVING, "swing", swingSend, false));

        List<String> calls = calls(transform(SPAWN_MOB), "call");
        assertTrue(calls.contains(SPAWN_MOB + ".swing" + swingSend), calls.toString());
    }

    @Test
    @DisplayName("a class's own static method keeps a static call that names it")
    void ownStaticMethodKeepsItsCall() {
        String desc = "()L" + BUILDER + ";";
        transformer.registerInheritedMethodRedirect(MOB, "createMobAttributes", desc,
                "test/helper/Attributes", "createMobAttributes", desc);
        jar.put(SPAWN_MOB, classDeclaringAndCalling(SPAWN_MOB, MOB, "createMobAttributes", desc, true));
        jar.put(BABY_MOB, mobCalling(BABY_MOB, MOB, "createMobAttributes", desc, true));

        List<String> own = calls(transform(SPAWN_MOB), "call");
        assertTrue(own.contains(SPAWN_MOB + ".createMobAttributes" + desc),
                "the class declares its own static factory, so that is what the call runs: " + own);
        List<String> inherited = calls(transform(BABY_MOB), "breed");
        assertTrue(inherited.contains("test/helper/Attributes.createMobAttributes" + desc),
                "without a declaration the static call still takes the redirect: " + inherited);
    }

    @Test
    @DisplayName("a call that names Mob runs the mob's own finalizeSpawn, and its super call runs the host's once")
    void callNamingMobRunsTheOwnOverride() throws Exception {
        String oldDesc = finalizeSpawnDesc(SPAWN_REASON);
        String helperDesc = "(L" + MOB + ";" + oldDesc.substring(1);
        registerFinalizeSpawnBridge();
        jar.put(SPAWN_MOB, overridingWithSuperCall(SPAWN_MOB, MOB, "finalizeSpawn", oldDesc));
        jar.put(COMMAND, callerThrough(MOB, "finalizeSpawn", oldDesc));

        byte[] mob = transform(SPAWN_MOB);
        byte[] command = transform(COMMAND);
        assertEquals(List.of(Legacy1205MemberBridge.HELPER + ".finalizeSpawn" + helperDesc), calls(command, "call"),
                "MCreator's spawn procedure calls it on a Mob it cast to, so only the helper can find the override");
        assertTrue(calls(mob, "finalizeSpawn").contains(
                Legacy1205MemberBridge.HELPER + ".super$finalizeSpawn" + helperDesc),
                "the override's super call must not ask the mob for its override again: " + calls(mob, "finalizeSpawn"));

        HostStubs stubs = new HostStubs()
                .add(HostStubs.recorder(MOB, "finalizeSpawn", oldDesc.replace("Lnet/minecraft/nbt/CompoundTag;)", ")")))
                .add(Legacy1205MemberBridge.HELPER, transformer.getSyntheticClasses().get(Legacy1205MemberBridge.HELPER))
                .add(SPAWN_MOB, mob)
                .add(COMMAND, command);
        Object instance = stubs.load(SPAWN_MOB).getConstructor().newInstance();
        stubs.method(COMMAND, "call").invoke(null, instance);

        assertEquals(1, stubs.load(SPAWN_MOB).getField("ownCalls").get(null), "the mob's own override ran once");
        assertEquals(4, ((Object[]) instance.getClass().getField("call").get(instance)).length,
                "its super call reached the host's four-argument finalizeSpawn");
    }

    @Test
    @DisplayName("calls that name LivingEntity run the entity's own swing overrides, as dispatch did")
    void callsNamingLivingEntityRunTheOwnSwing() throws Exception {
        String swingHand = LegacySwingBridge.SWING_HAND;
        String swingSend = LegacySwingBridge.SWING_HAND_SEND;
        LegacySwingBridge.registerRedirects(transformer, List.of(swingHand, swingSend));
        jar.put(SWINGER, swingOverrides());
        jar.put(COMMAND, swingCaller());

        byte[] entity = transform(SWINGER);
        byte[] command = transform(COMMAND);
        String helper = LegacySwingBridge.HELPER;
        assertEquals(List.of(helper + ".swing(L" + LIVING + ";L" + HAND + ";Z)V",
                helper + ".swing(L" + LIVING + ";L" + HAND + ";)V"), calls(command, "call"));
        assertTrue(calls(entity, "swing").contains(helper + ".super$swing(L" + LIVING + ";L" + HAND + ";)V"),
                calls(entity, "swing").toString());

        HostStubs stubs = new HostStubs()
                .add(HostStubs.plainClass(HAND, "java/lang/Object"))
                .add(HostStubs.singleton(LegacySwingBridge.ANIMATION))
                .add(HostStubs.recorder(LIVING, "swing", LegacySwingBridge.SWING_ANIMATED))
                .add(helper, transformer.getSyntheticClasses().get(helper))
                .add(SWINGER, entity)
                .add(COMMAND, command);
        Object instance = stubs.load(SWINGER).getConstructor().newInstance();
        Object hand = stubs.load(HAND).getConstructor().newInstance();
        stubs.method(COMMAND, "call").invoke(null, instance, hand);

        Class<?> swinger = stubs.load(SWINGER);
        assertEquals(1, swinger.getField("handCalls").get(null), "swing(hand) ran the entity's own override");
        assertEquals(2, swinger.getField("sendCalls").get(null),
                "swing(hand, send) ran for the direct call and again for the old swing(hand, false)");
        assertEquals(1, swinger.getField("sentToPlayer").get(null), "only the direct call sent the swing");
        Object[] hostCall = (Object[]) instance.getClass().getField("call").get(instance);
        assertEquals(false, hostCall[2], "the last super call reached the host with the override's argument");
    }

    @Test
    @DisplayName("an override in a library base class on the class path keeps the call")
    void libraryBaseClassOverrideKeepsTheCall() {
        String host = org.objectweb.asm.Type.getInternalName(HostBase.class);
        transformer.registerInheritedMethodRedirect(host, "oldSwing", "(Z)V",
                "test/helper/OldSwing", "oldSwing", "(L" + host + ";Z)V");
        String overriding = "test/mod/LibraryChild";
        String plain = "test/mod/PlainLibraryChild";
        jar.put(overriding, mobCalling(overriding, org.objectweb.asm.Type.getInternalName(LibraryMob.class),
                "oldSwing", "(Z)V"));
        jar.put(plain, mobCalling(plain, org.objectweb.asm.Type.getInternalName(PlainLibraryMob.class),
                "oldSwing", "(Z)V"));

        assertEquals(List.of(overriding + ".oldSwing(Z)V"), calls(transform(overriding), "breed"),
                "the library class that proves the hierarchy declares the method the call runs");
        assertEquals(List.of("test/helper/OldSwing.oldSwing(L" + host + ";Z)V"), calls(transform(plain), "breed"),
                "a library base without its own copy still has the call bridged");
    }

    /** Stands in for the host class a removed method was registered on. */
    public static class HostBase {
    }

    /** A base entity from a library on the class path, declaring the removed method itself. */
    public static class LibraryMob extends HostBase {
        public void oldSwing(boolean send) {
        }
    }

    /** A library base entity that only inherits. */
    public static class PlainLibraryMob extends HostBase {
    }

    @Test
    @DisplayName("26.3 lost both old swing forms, so both are bridged there")
    void bothSwingFormsAreBridgedOnTwentySixThree() {
        ClassNode living = living(LegacySwingBridge.SWING_ANIMATED);
        assertEquals(List.of(LegacySwingBridge.SWING_HAND, LegacySwingBridge.SWING_HAND_SEND),
                LegacySwingBridge.removedForms(living, swingAnimation()));
    }

    @Test
    @DisplayName("a host that kept the one-argument swing keeps its own method for it")
    void keptSwingFormIsNotRedirected() {
        ClassNode living = living(LegacySwingBridge.SWING_ANIMATED, LegacySwingBridge.SWING_HAND);
        assertEquals(List.of(LegacySwingBridge.SWING_HAND_SEND),
                LegacySwingBridge.removedForms(living, swingAnimation()));
    }

    @Test
    @DisplayName("a host without the animated swing keeps both old forms")
    void hostWithoutAnimatedSwingKeepsBoth() {
        ClassNode living = living(LegacySwingBridge.SWING_HAND, LegacySwingBridge.SWING_HAND_SEND);
        assertEquals(List.of(), LegacySwingBridge.removedForms(living, swingAnimation()));
    }

    // --- registrations and classes under test ---------------------------------------------------

    private void registerFinalizeSpawnBridge() {
        Legacy1205MemberBridge.registerRedirects(transformer,
                List.of(Legacy1205MemberBridge.finalizeSpawn(SPAWN_REASON)), List.of(), false,
                Legacy1205MemberBridge.HostShape.AT_26_3);
    }

    private byte[] transform(String name) {
        try (var scope = transformer.pushJarClassBytesProvider(jar::get)) {
            return transformer.transformClass(jar.get(name), name + ".class");
        }
    }

    private static String finalizeSpawnDesc(String spawnReason) {
        return Legacy1205MemberBridge.finalizeSpawn(spawnReason).desc();
    }

    /** A mob that overrides the old method under {@code declaredName} and calls it in {@code breed}. */
    private static byte[] mobOverridingFinalizeSpawn(String name, String superName, String declaredName,
            String declaredDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor override = cw.visitMethod(ACC_PUBLIC, declaredName, declaredDesc, null, null);
        override.visitCode();
        override.visitVarInsn(ALOAD, 4);
        override.visitInsn(ARETURN);
        override.visitMaxs(0, 0);
        override.visitEnd();
        emitBreed(cw, name, declaredName, declaredDesc, false);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A mob that overrides {@code method}, counts its calls in {@code ownCalls}, and passes them
     * on with {@code super}, as an MCreator spawn procedure does.
     */
    private static byte[] overridingWithSuperCall(String name, String superName, String method, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "ownCalls", "I", null, null).visitEnd();
        HostStubs.defaultConstructor(cw, superName);
        MethodVisitor override = cw.visitMethod(ACC_PUBLIC, method, desc, null, null);
        override.visitCode();
        count(override, name, "ownCalls");
        loadArguments(override, desc);
        override.visitMethodInsn(INVOKESPECIAL, superName, method, desc, false);
        override.visitInsn(ARETURN);
        override.visitMaxs(0, 0);
        override.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static call(owner receiver)} calling {@code owner.method} with nulls, the way a procedure does. */
    private static byte[] callerThrough(String owner, String method, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, COMMAND, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", "(L" + owner + ";)V", null, null);
        mv.visitCode();
        emitCall(mv, owner, method, desc, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * An entity overriding both old swing forms, each passing on with {@code super}. It counts
     * {@code swing(hand)} in {@code handCalls}, {@code swing(hand, send)} in {@code sendCalls}, and
     * the sends in {@code sentToPlayer}.
     */
    private static byte[] swingOverrides() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, SWINGER, null, LIVING, null);
        for (String counter : List.of("handCalls", "sendCalls", "sentToPlayer")) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, counter, "I", null, null).visitEnd();
        }
        HostStubs.defaultConstructor(cw, LIVING);

        MethodVisitor hand = cw.visitMethod(ACC_PUBLIC, "swing", LegacySwingBridge.SWING_HAND, null, null);
        hand.visitCode();
        count(hand, SWINGER, "handCalls");
        loadArguments(hand, LegacySwingBridge.SWING_HAND);
        hand.visitMethodInsn(INVOKESPECIAL, LIVING, "swing", LegacySwingBridge.SWING_HAND, false);
        hand.visitInsn(RETURN);
        hand.visitMaxs(0, 0);
        hand.visitEnd();

        MethodVisitor send = cw.visitMethod(ACC_PUBLIC, "swing", LegacySwingBridge.SWING_HAND_SEND, null, null);
        send.visitCode();
        count(send, SWINGER, "sendCalls");
        send.visitFieldInsn(GETSTATIC, SWINGER, "sentToPlayer", "I");
        send.visitVarInsn(ILOAD, 2);
        send.visitInsn(IADD);
        send.visitFieldInsn(PUTSTATIC, SWINGER, "sentToPlayer", "I");
        loadArguments(send, LegacySwingBridge.SWING_HAND_SEND);
        send.visitMethodInsn(INVOKESPECIAL, LIVING, "swing", LegacySwingBridge.SWING_HAND_SEND, false);
        send.visitInsn(RETURN);
        send.visitMaxs(0, 0);
        send.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static call(LivingEntity entity, InteractionHand hand)}: {@code swing(hand, true)}, then {@code swing(hand)}. */
    private static byte[] swingCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, COMMAND, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call",
                "(L" + LIVING + ";L" + HAND + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIVING, "swing", LegacySwingBridge.SWING_HAND_SEND, false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIVING, "swing", LegacySwingBridge.SWING_HAND, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void count(MethodVisitor mv, String owner, String counter) {
        mv.visitFieldInsn(GETSTATIC, owner, counter, "I");
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTSTATIC, owner, counter, "I");
    }

    /** Loads {@code this} and every parameter of an instance method with {@code desc}. */
    private static void loadArguments(MethodVisitor mv, String desc) {
        mv.visitVarInsn(ALOAD, 0);
        int local = 1;
        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            mv.visitVarInsn(arg.getOpcode(ILOAD), local);
            local += arg.getSize();
        }
    }

    /** A class that declares nothing and calls {@code method} on itself in {@code breed}. */
    private static byte[] mobCalling(String name, String superName, String method, String desc) {
        return mobCalling(name, superName, method, desc, false);
    }

    private static byte[] mobCalling(String name, String superName, String method, String desc,
            boolean isStatic) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        emitBreed(cw, name, method, desc, isStatic);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pushes nulls (and a receiver) for {@code desc} and calls {@code owner.method}, as javac does. */
    private static void emitBreed(ClassWriter cw, String owner, String method, String desc, boolean isStatic) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "breed", "()V", null, null);
        mv.visitCode();
        emitCall(mv, owner, method, desc, isStatic);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** A class declaring {@code method} and calling it on itself from {@code call}. */
    private static byte[] classDeclaringAndCalling(String name, String superName, String method,
            String desc, boolean isStatic) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor declared = cw.visitMethod(ACC_PUBLIC | (isStatic ? ACC_STATIC : 0), method, desc,
                null, null);
        declared.visitCode();
        if (desc.endsWith(")V")) {
            declared.visitInsn(RETURN);
        } else {
            declared.visitInsn(ACONST_NULL);
            declared.visitInsn(ARETURN);
        }
        declared.visitMaxs(0, 0);
        declared.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "call", "()V", null, null);
        mv.visitCode();
        emitCall(mv, name, method, desc, isStatic);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitCall(MethodVisitor mv, String owner, String method, String desc, boolean isStatic) {
        if (!isStatic) mv.visitVarInsn(ALOAD, 0);
        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(desc)) {
            mv.visitInsn(arg.getSort() == org.objectweb.asm.Type.BOOLEAN ? ICONST_1 : ACONST_NULL);
        }
        mv.visitMethodInsn(isStatic ? INVOKESTATIC : INVOKEVIRTUAL, owner, method, desc, false);
        String result = desc.substring(desc.indexOf(')') + 1);
        if (!result.equals("V")) mv.visitInsn(POP);
    }

    /** {@code LivingEntity} declaring the given {@code swing} forms. */
    private static ClassNode living(String... swingForms) {
        ClassNode living = new ClassNode();
        living.name = LIVING;
        for (String desc : swingForms) {
            living.methods.add(new MethodNode(ACC_PUBLIC, "swing", desc, null, null));
        }
        return living;
    }

    /** {@code SwingAnimation} with its {@code DEFAULT} constant. */
    private static ClassNode swingAnimation() {
        ClassNode animation = new ClassNode();
        animation.name = LegacySwingBridge.ANIMATION;
        animation.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "DEFAULT",
                "L" + LegacySwingBridge.ANIMATION + ";", null, null));
        return animation;
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
