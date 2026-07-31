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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Forge 26.2 EventBus 6 -> 7 bridge (#85): the old IEventBus construction idiom
 * (getModEventBus / DeferredRegister.register(bus) / MinecraftForge.EVENT_BUS.register(this) /
 * addListener) must be rewritten onto the LegacyEventBus synthetic over BusGroup.
 */
class ForgeEventBusBridgeTest {

    private static final String OLD_BUS = "net/minecraftforge/eventbus/api/IEventBus";
    private static final String L_OLD_BUS = "L" + OLD_BUS + ";";
    private static final String FMLJMLC = "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext";
    private static final String DR = "net/minecraftforge/registries/DeferredRegister";
    private static final String MF = "net/minecraftforge/common/MinecraftForge";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("both synthetics parse and have the bridge surface")
    void syntheticShape() {
        ClassNode itf = new ClassNode();
        new ClassReader(ForgeEventBusSynthetics.generateInterface()).accept(itf, 0);
        assertTrue((itf.access & ACC_INTERFACE) != 0,
                "LegacyEventBus must be an interface so INVOKEINTERFACE call sites stay valid");
        var names = itf.methods.stream().map(m -> m.name).toList();
        for (String required : new String[]{"group", "register", "unregister", "addListener"}) {
            assertTrue(names.contains(required), "interface missing: " + required);
        }
        ClassNode impl = new ClassNode();
        new ClassReader(ForgeEventBusSynthetics.generateImpl()).accept(impl, 0);
        assertTrue(impl.interfaces.contains(ForgeEventBusSynthetics.LEB));
        assertTrue(impl.fields.stream().anyMatch(f -> f.name.equals("GAME_BUS")),
                "impl must carry GAME_BUS for the MinecraftForge.EVENT_BUS field redirect");
        var implNames = impl.methods.stream().map(m -> m.name).toList();
        for (String required : new String[]{"of", "modBus", "registerDeferred", "addListenerHelper",
                "registerFallback"}) {
            // statics live on the CLASS: the devirtualizer emits a plain INVOKESTATIC Methodref,
            // which on an interface target throws IncompatibleClassChangeError at runtime
            assertTrue(implNames.contains(required), "impl missing static: " + required);
        }
        // the single-listener fallback consumer parses too
        ClassNode rc = new ClassNode();
        new ClassReader(ForgeEventBusSynthetics.generateReflectedConsumer()).accept(rc, 0);
        assertTrue(rc.interfaces.contains("java/util/function/Consumer"));
    }

    /** The canonical old Forge @Mod constructor idiom, plus an EB6-annotated handler. */
    private static byte[] fixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "com/example/OldForgeMod", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "construct", "(L" + DR + ";)V", null, null);
        mv.visitCode();
        // IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        mv.visitMethodInsn(INVOKESTATIC, FMLJMLC, "get", "()L" + FMLJMLC + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, FMLJMLC, "getModEventBus", "()" + L_OLD_BUS, false);
        mv.visitVarInsn(ASTORE, 2);
        // BLOCKS.register(bus);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, DR, "register", "(" + L_OLD_BUS + ")V", false);
        // bus.addListener(consumer)  (consumer stands in as null Consumer)
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ACONST_NULL);
        mv.visitTypeInsn(CHECKCAST, "java/util/function/Consumer");
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "addListener", "(Ljava/util/function/Consumer;)V", true);
        // MinecraftForge.EVENT_BUS.register(this);
        mv.visitFieldInsn(GETSTATIC, MF, "EVENT_BUS", L_OLD_BUS);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "register", "(Ljava/lang/Object;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // @SubscribeEvent (EventBus 6 annotation, bare) on a handler
        MethodVisitor h = cw.visitMethod(ACC_PUBLIC, "onThing", "(Ljava/lang/Object;)V", null, null);
        h.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        h.visitCode();
        h.visitInsn(RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("the old IEventBus construction idiom is fully rewritten onto the bridge")
    void oldIdiomIsBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ForgeEventBusSynthetics.register(t);

        byte[] out = t.transformClass(fixture(), "com/example/OldForgeMod.class");
        String text = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(OLD_BUS), "no reference to the deleted IEventBus may survive");

        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        var insns = cn.methods.stream().filter(m -> m.name.equals("construct"))
                .findFirst().orElseThrow().instructions;

        boolean modBus = false, deferred = false, gameBus = false, addListener = false, register = false;
        for (AbstractInsnNode in : insns.toArray()) {
            if (in instanceof MethodInsnNode mi) {
                if (mi.owner.equals(ForgeEventBusSynthetics.LEB_IMPL)) {
                    // static bridge targets must be on the impl class, not the interface (a plain
                    // INVOKESTATIC Methodref on an interface = IncompatibleClassChangeError)
                    if (mi.name.equals("modBus")) { modBus = true; assertEquals(INVOKESTATIC, mi.getOpcode()); }
                    if (mi.name.equals("registerDeferred")) { deferred = true; assertEquals(INVOKESTATIC, mi.getOpcode()); }
                }
                if (mi.owner.equals(ForgeEventBusSynthetics.LEB)) {
                    if (mi.name.equals("addListener")) { addListener = true; assertEquals(INVOKEINTERFACE, mi.getOpcode()); }
                    if (mi.name.equals("register")) { register = true; assertEquals(INVOKEINTERFACE, mi.getOpcode()); }
                }
                assertFalse(mi.name.equals("getModEventBus"), "getModEventBus call must be devirtualized away");
            }
            if (in instanceof FieldInsnNode fi && fi.name.equals("GAME_BUS")) {
                gameBus = true;
                assertEquals(ForgeEventBusSynthetics.LEB_IMPL, fi.owner);
            }
        }
        assertTrue(modBus, "getModEventBus -> LegacyEventBusImpl.modBus");
        assertTrue(deferred, "DeferredRegister.register(bus) -> registerDeferred");
        assertTrue(gameBus, "MinecraftForge.EVENT_BUS -> LegacyEventBusImpl.GAME_BUS");
        assertTrue(addListener, "addListener stays an interface call on the bridge");
        assertTrue(register, "register(Object) stays an interface call on the bridge");

        // the EB6 @SubscribeEvent must be rewritten to EventBus 7's, or the scan finds nothing
        AnnotationNode ann = cn.methods.stream().filter(m -> m.name.equals("onThing"))
                .findFirst().orElseThrow().visibleAnnotations.get(0);
        assertEquals("Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;", ann.desc,
                "old @SubscribeEvent must be redirected to the EventBus 7 annotation");
    }

    private static final String OLD_PRI = "net/minecraftforge/eventbus/api/EventPriority";
    private static final String L_CONSUMER = "Ljava/util/function/Consumer;";

    @Test
    @DisplayName("#101: EventPriority stand-in is a REAL enum carrying EventBus 7 bits")
    void priorityEnumShape() {
        ClassNode cn = new ClassNode();
        new ClassReader(ForgeEventBusSynthetics.generatePriorityEnum()).accept(cn, 0);
        assertEquals("java/lang/Enum", cn.superName, "must be a real enum: mods switch on it");
        assertTrue((cn.access & ACC_ENUM) != 0);
        for (String c : new String[]{"HIGHEST", "HIGH", "NORMAL", "LOW", "LOWEST"}) {
            assertTrue(cn.fields.stream().anyMatch(f -> f.name.equals(c)), "missing constant " + c);
        }
        assertTrue(cn.methods.stream().anyMatch(m -> m.name.equals("bits")));
        assertTrue(cn.methods.stream().anyMatch(m -> m.name.equals("bitsOf")));
        assertTrue(cn.methods.stream().anyMatch(m -> m.name.equals("values")));
        assertTrue(cn.methods.stream().anyMatch(m -> m.name.equals("valueOf")));
    }

    @Test
    @DisplayName("#101: the bridge interface has all three priority addListener overloads")
    void priorityOverloadsExist() {
        ClassNode cn = new ClassNode();
        new ClassReader(ForgeEventBusSynthetics.generateInterface()).accept(cn, 0);
        String pri = "L" + ForgeEventBusSynthetics.PRI + ";";
        for (String desc : new String[]{
                "(" + pri + L_CONSUMER + ")V",
                "(" + pri + "Z" + L_CONSUMER + ")V",
                "(" + pri + "ZLjava/lang/Class;" + L_CONSUMER + ")V"}) {
            assertTrue(cn.methods.stream().anyMatch(
                            m -> m.name.equals("addListener") && m.desc.equals(desc)),
                    "missing default addListener" + desc);
        }
    }

    private static final org.objectweb.asm.Handle METAFACTORY = new org.objectweb.asm.Handle(
            H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
            + "Ljava/lang/invoke/CallSite;", false);

    /**
     * A mod class doing {@code bus.addListener(Mod::onSetup)} (indy immediately before the
     * call) and {@code bus.addListener(EventPriority.HIGH, Mod::onSetup)}, exactly as javac
     * emits them.
     */
    private static byte[] lambdaFixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "com/example/LambdaMod", null, "java/lang/Object", null);
        org.objectweb.asm.Handle impl = new org.objectweb.asm.Handle(H_INVOKESTATIC,
                "com/example/LambdaMod", "onSetup", "(Lcom/example/SetupEvent;)V", false);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "wire",
                "(" + L_OLD_BUS + ")V", null, null);
        mv.visitCode();
        // bus.addListener(LambdaMod::onSetup)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInvokeDynamicInsn("accept", "()" + L_CONSUMER, METAFACTORY,
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)V"), impl,
                org.objectweb.asm.Type.getMethodType("(Lcom/example/SetupEvent;)V"));
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "addListener", "(" + L_CONSUMER + ")V", true);
        // bus.addListener(EventPriority.HIGH, LambdaMod::onSetup)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, OLD_PRI, "HIGH", "L" + OLD_PRI + ";");
        mv.visitInvokeDynamicInsn("accept", "()" + L_CONSUMER, METAFACTORY,
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)V"), impl,
                org.objectweb.asm.Type.getMethodType("(Lcom/example/SetupEvent;)V"));
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "addListener",
                "(L" + OLD_PRI + ";" + L_CONSUMER + ")V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor on = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onSetup",
                "(Lcom/example/SetupEvent;)V", null, null);
        on.visitCode();
        on.visitInsn(RETURN);
        on.visitMaxs(0, 0);
        on.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("#101: addListener(lambda) is retargeted with the indy's reified event type")
    void lambdaListenersGetTypedRewrite() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ForgeEventBusSynthetics.register(t);

        ClassNode cn = new ClassNode();
        new ClassReader(t.transformClass(lambdaFixture(), "com/example/LambdaMod.class"))
                .accept(cn, 0);
        var insns = cn.methods.stream().filter(m -> m.name.equals("wire"))
                .findFirst().orElseThrow().instructions;

        boolean plainTyped = false, priTyped = false, priRead = false;
        AbstractInsnNode prev = null;
        for (AbstractInsnNode in : insns.toArray()) {
            if (in instanceof MethodInsnNode mi
                    && mi.owner.equals(ForgeEventBusSynthetics.LEB_IMPL)) {
                if (mi.name.equals("addListenerTypedIndy")) plainTyped = true;
                if (mi.name.equals("addListenerPriTypedIndy")) priTyped = true;
                assertTrue(prev instanceof org.objectweb.asm.tree.LdcInsnNode ldc
                                && ldc.cst instanceof org.objectweb.asm.Type ty
                                && ty.getInternalName().equals("com/example/SetupEvent"),
                        "expected LDC SetupEvent.class right before " + mi.name);
            }
            if (in instanceof FieldInsnNode fi && fi.owner.equals(ForgeEventBusSynthetics.PRI)
                    && fi.name.equals("HIGH")) {
                priRead = true;
            }
            if (!(in instanceof org.objectweb.asm.tree.LabelNode
                    || in instanceof org.objectweb.asm.tree.LineNumberNode
                    || in instanceof org.objectweb.asm.tree.FrameNode)) {
                prev = in;
            }
        }
        assertTrue(plainTyped, "addListener(Consumer) fed by an indy must be retargeted typed");
        assertTrue(priTyped, "addListener(EventPriority, Consumer) fed by an indy must be "
                + "retargeted typed (priority passed through)");
        assertTrue(priRead, "EventPriority.HIGH must be redirected to the stand-in enum");
    }

    @Test
    @DisplayName("#101: a NON-adjacent Consumer does NOT get a stale indy type attached")
    void nonAdjacentConsumerFallsThrough() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ForgeEventBusSynthetics.register(t);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "com/example/StoredMod", null, "java/lang/Object", null);
        org.objectweb.asm.Handle impl = new org.objectweb.asm.Handle(H_INVOKESTATIC,
                "com/example/StoredMod", "onSetup", "(Lcom/example/SetupEvent;)V", false);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "wire",
                "(" + L_OLD_BUS + L_CONSUMER + ")V", null, null);
        mv.visitCode();
        // Consumer unused = Mod::onSetup;  (indy then ASTORE: adjacency broken)
        mv.visitInvokeDynamicInsn("accept", "()" + L_CONSUMER, METAFACTORY,
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)V"), impl,
                org.objectweb.asm.Type.getMethodType("(Lcom/example/SetupEvent;)V"));
        mv.visitVarInsn(ASTORE, 2);
        // bus.addListener(someOtherConsumer)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "addListener", "(" + L_CONSUMER + ")V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor on = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onSetup",
                "(Lcom/example/SetupEvent;)V", null, null);
        on.visitCode();
        on.visitInsn(RETURN);
        on.visitMaxs(0, 0);
        on.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(t.transformClass(cw.toByteArray(), "com/example/StoredMod.class"))
                .accept(cn, 0);
        var insns = cn.methods.stream().filter(m -> m.name.equals("wire"))
                .findFirst().orElseThrow().instructions;
        for (AbstractInsnNode in : insns.toArray()) {
            if (in instanceof MethodInsnNode mi) {
                assertNotEquals("addListenerTypedIndy", mi.name,
                        "a stored/unrelated Consumer must NOT get the stale indy type; it falls "
                        + "through to the interface default (runtime generics recovery)");
            }
        }
    }
}
