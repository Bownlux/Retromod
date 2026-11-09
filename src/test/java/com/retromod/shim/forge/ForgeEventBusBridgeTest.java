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

import java.util.List;

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
        // the one-handler companion calls this exact signature
        assertTrue(impl.methods.stream().anyMatch(m -> m.name.equals("hookStatic")
                        && m.desc.equals("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;B)V")
                        && (m.access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC)),
                "impl missing public static hookStatic(String, String, String, byte)");
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

    @Test
    @DisplayName("A type without a static BUS is looked up in the receiving bus's group, not always the mod's")
    void busOfUsesTheReceivingGroup() {
        for (boolean legacy : new boolean[]{true, false}) {
            byte[] impl = ForgeEventBusSynthetics.generateImpl(legacy);
            new ClassReader(impl).accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), true), 0);
            ClassNode node = new ClassNode();
            new ClassReader(impl).accept(node, 0);
            org.objectweb.asm.tree.MethodNode busOf = node.methods.stream().filter(m -> m.name.equals("busOf"))
                    .findFirst().orElseThrow();
            assertEquals("(Lnet/minecraftforge/eventbus/api/bus/BusGroup;Ljava/lang/Class;)Ljava/lang/Object;", busOf.desc);
            String expected = legacy ? LegacyEventBaseBridge.LEGACY_EVENTS + ".busOf"
                    : "net/minecraftforge/fml/event/IModBusEvent.getBus";
            assertTrue(body(busOf).stream().anyMatch(line -> line.contains(expected)), body(busOf).toString());
            assertFalse(body(busOf).stream().anyMatch(line -> line.contains("modGroup")), "the group comes from the caller");
        }
    }

    private static final String SERVER_TICK = "net/minecraftforge/event/TickEvent$ServerTickEvent";
    private static final String OWN_BUS_EVENT = "com/example/OwnBusEvent";

    @Test
    @DisplayName("A tick listener added with a lambda reaches the Pre and the Post bus at normal priority")
    void tickListenerReachesPreAndPost() throws Exception {
        EventHost host = new EventHost();
        java.util.function.Consumer<Object> listener = event -> {};
        host.invoke("addListenerTypedIndy", host.bus(), listener, host.type(SERVER_TICK));
        assertEquals(List.of(SERVER_TICK + "$Pre at 0", SERVER_TICK + "$Post at 0"), host.hooksOf(listener),
                "Forge 26.2 tick events are interfaces with no bus, so the listener belongs on both records");
    }

    @Test
    @DisplayName("A tick listener added with a priority keeps that priority on both halves")
    void tickListenerKeepsItsPriority() throws Exception {
        EventHost host = new EventHost();
        java.util.function.Consumer<Object> listener = event -> {};
        host.invoke("addListenerPriTypedIndy", host.bus(), host.priority("HIGH"), listener, host.type(SERVER_TICK));
        assertEquals(List.of(SERVER_TICK + "$Pre at 64", SERVER_TICK + "$Post at 64"), host.hooksOf(listener));
    }

    @Test
    @DisplayName("A Consumer class typed by its generics reaches the Pre and the Post bus of a tick event")
    void reifiedTickConsumerReachesPreAndPost() throws Exception {
        EventHost host = new EventHost();
        Object listener = host.type(EventHost.TYPED_LISTENER).getConstructor().newInstance();
        host.invoke("addListenerHelper", host.bus(), listener);
        assertEquals(List.of(SERVER_TICK + "$Pre at 0", SERVER_TICK + "$Post at 0"), host.hooksOf(listener));
    }

    @Test
    @DisplayName("An event with its own bus is hooked once, on that bus")
    void eventWithItsOwnBusIsHookedOnce() throws Exception {
        EventHost host = new EventHost();
        java.util.function.Consumer<Object> listener = event -> {};
        host.invoke("addListenerTypedIndy", host.bus(), listener, host.type(OWN_BUS_EVENT));
        assertEquals(List.of(OWN_BUS_EVENT + " at 0"), host.hooksOf(listener));
    }

    @Test
    @DisplayName("On a cancellable bus a lambda listener becomes a predicate that answers whether it cancelled")
    void cancellableBusTakesAPredicate() throws Exception {
        EventHost host = new EventHost();
        Object event = host.cancelEvent();
        java.util.function.Consumer<Object> canceller = e -> host.cancel("setCanceled", e, true);
        host.invoke("addListenerPriTypedIndy", host.bus(), host.priority("HIGH"), canceller, host.type(EventHost.CANCEL_EVENT));

        assertEquals(List.of("addListener(byte,Predicate) at 64"), host.cancelHooks(),
                "EventBus 7 reads a cancel only from a listener's return value");
        @SuppressWarnings("unchecked")
        java.util.function.Predicate<Object> predicate = (java.util.function.Predicate<Object>) host.cancelListener(0);
        assertTrue(predicate.test(event));
        assertEquals(true, host.cancel("isCanceled", event), "a read after the post still sees the cancel");

        java.util.function.Consumer<Object> plain = e -> {};
        host.invoke("addListenerTypedIndy", host.bus(), plain, host.type(OWN_BUS_EVENT));
        assertSame(plain, host.listenersOn(OWN_BUS_EVENT).get(0), "a plain bus still gets the Consumer itself");

        // Forge fires the name tag event on a cancellable bus without reading a cancel, so its listener stays a Consumer.
        java.util.function.Consumer<Object> nameTag = e -> {};
        host.invoke("addListenerTypedIndy", host.bus(), nameTag, host.type(EventHost.NAME_TAG_EVENT));
        assertEquals("addListener(byte,Consumer) at 0", host.cancelHooks().get(1));
        assertSame(nameTag, host.cancelListener(1));
    }

    @Test
    @DisplayName("A lambda on ScreenEvent.Init, which nothing posts, reaches Init.Pre as a predicate and Init.Post as a listener, and unregister takes both off")
    void listenerOnABuslessParentReachesItsChildren() throws Exception {
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        // The bridge names the children of each parent with no bus when it is built for the target.
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
        try {
            EventHost host = new EventHost();
            Object bus = host.bus();
            java.util.function.Consumer<Object> listener = e -> {};
            host.invoke("addListenerPriTypedIndy", bus, host.priority("HIGH"), listener, host.type(EventHost.SCREEN_INIT));

            assertEquals(List.of("addListener(byte,Predicate) at 64"), host.cancelHooks(), "Init.Pre takes a cancel");
            assertEquals(List.of(EventHost.SCREEN_INIT + "$Post at 64"), host.hooksOf(listener));
            host.unregister(bus, listener);
            assertEquals(List.of(), host.cancelHooks());
            assertEquals(List.of(), host.hooksOf(listener));
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("receiveCanceled on a cancellable bus adds a monitor, which runs the listener when an earlier one cancelled")
    void receiveCanceledAddsAMonitor() throws Exception {
        EventHost host = new EventHost();
        Object event = host.cancelEvent();
        List<Object> seen = new java.util.ArrayList<>();
        java.util.function.Consumer<Object> watcher = e -> seen.add(host.cancel("isCanceled", e));
        host.invoke("addListenerPriZTypedIndy", host.bus(), host.priority("LOW"), true, watcher,
                host.type(EventHost.CANCEL_EVENT));

        assertEquals(List.of("addListener(byte,Predicate) at -64", "addListener(ObjBooleanBiConsumer)"), host.cancelHooks());
        Object monitor = host.cancelListener(1);
        host.type("net/minecraftforge/eventbus/api/listener/ObjBooleanBiConsumer")
                .getMethod("accept", Object.class, boolean.class).invoke(monitor, event, true);
        assertEquals(List.of(true), seen, "it sees the event an earlier listener cancelled, at monitor time");
    }

    @Test
    @DisplayName("unregister(consumer) removes a cancel-aware listener's predicate and its monitor together")
    void unregisterRemovesPredicateAndMonitor() throws Exception {
        EventHost host = new EventHost();
        Object bus = host.bus();
        java.util.function.Consumer<Object> watcher = e -> {};
        java.util.function.Consumer<Object> other = e -> {};
        host.invoke("addListenerPriZTypedIndy", bus, host.priority("LOW"), true, watcher, host.type(EventHost.CANCEL_EVENT));
        host.invoke("addListenerTypedIndy", bus, other, host.type(EventHost.CANCEL_EVENT));
        assertEquals(List.of("addListener(byte,Predicate) at -64", "addListener(ObjBooleanBiConsumer)",
                "addListener(byte,Predicate) at 0"), host.cancelHooks());

        host.unregister(bus, watcher);
        assertEquals(List.of("addListener(byte,Predicate) at 0"), host.cancelHooks(),
                "the watcher's predicate and monitor leave, and the other listener stays");
    }

    @Test
    @DisplayName("post on a host event returns its bus's answer and keeps it, and an event cancelled first is not posted")
    void postReturnsAndRemembersTheBusAnswer() throws Exception {
        EventHost host = new EventHost();
        Object bus = host.bus();
        java.lang.reflect.Method post = host.type(ForgeEventBusSynthetics.LEB).getMethod("post", Object.class);

        Object cancelled = host.cancelEvent();
        host.postAnswer = true;
        assertEquals(true, post.invoke(bus, cancelled));
        assertEquals(true, host.cancel("isCanceled", cancelled), "isCanceled() after the post answers as the post did");

        Object passed = host.cancelEvent();
        host.postAnswer = false;
        assertEquals(false, post.invoke(bus, passed));
        assertEquals(false, host.cancel("isCanceled", passed));

        Object early = host.cancelEvent();
        host.cancel("setCanceled", early, true);
        int posts = host.posts;
        assertEquals(true, post.invoke(bus, early));
        assertEquals(posts, host.posts, "EventBus 7 has no flag to carry the cancel, so the event is not posted");
    }

    @Test
    @DisplayName("A listener added for the base TickEvent reaches the Pre and the Post bus of all five tick events")
    void baseTickListenerReachesAllTen() throws Exception {
        EventHost host = new EventHost();
        java.util.function.Consumer<Object> listener = event -> {};
        host.invoke("addListenerPriTypedIndy", host.bus(), host.priority("LOW"), listener,
                host.type("net/minecraftforge/event/TickEvent"));
        List<String> expected = new java.util.ArrayList<>();
        for (String tick : EventHost.TICKS) {
            expected.add(tick + "$Pre at -64");
            expected.add(tick + "$Post at -64");
        }
        assertEquals(expected, host.hooksOf(listener), "Forge 1.20.1 delivered every tick event to a base listener");
    }

    @Test
    @DisplayName("unregister(consumer) removes both halves of a split tick listener and leaves other listeners alone")
    void unregisterRemovesBothTickHalves() throws Exception {
        EventHost host = new EventHost();
        Object bus = host.bus();
        java.util.function.Consumer<Object> leaving = event -> {};
        java.util.function.Consumer<Object> staying = event -> {};
        host.invoke("addListenerTypedIndy", bus, leaving, host.type(SERVER_TICK));
        host.invoke("addListenerTypedIndy", bus, staying, host.type(SERVER_TICK));
        host.invoke("addListenerTyped", bus, host.priority("NORMAL"), false, host.type("net/minecraftforge/event/TickEvent"),
                leaving);
        assertEquals(12, host.hooksOf(leaving).size(), "two halves, then ten for the base event");

        host.unregister(bus, leaving);
        assertEquals(List.of(), host.hooksOf(leaving), "every bus the consumer went onto lets it go");
        assertEquals(List.of(SERVER_TICK + "$Pre at 0", SERVER_TICK + "$Post at 0"), host.hooksOf(staying));
        host.unregister(bus, leaving);
        assertEquals(2, host.hooksOn(SERVER_TICK + "$Pre").size() + host.hooksOn(SERVER_TICK + "$Post").size(),
                "a second unregister finds nothing left to remove");
    }

    @Test
    @DisplayName("unregister(object) hands EventBus 7 back what its registration added, and removes listeners hooked here")
    void unregisterRemovesWhatRegisterAdded() throws Exception {
        EventHost host = new EventHost();
        Object bus = host.bus();
        Object pair = host.listener(EventHost.PAIR);
        Object lone = host.listener(EventHost.LONE);
        host.register(bus, pair);
        host.register(bus, pair);
        host.register(bus, lone);
        assertEquals(List.of(pair), host.groupRegistrations, "EventBus 6 ignored an object it already held");
        assertEquals(List.of(OWN_BUS_EVENT + " at 64"), host.hooksOn(OWN_BUS_EVENT), "the lone listener is hooked here");

        host.unregister(bus, lone);
        assertEquals(List.of(), host.hooksOn(OWN_BUS_EVENT));
        assertEquals(List.of(), host.groupUnregistrations, "EventBus 7 never held the lone listener");
        host.unregister(bus, pair);
        assertEquals(1, host.groupUnregistrations.size(), "the handles EventBus 7's registration returned go back to it");
        assertTrue(host.groupUnregistrations.get(0).toString().endsWith(EventHost.PAIR.replace('/', '.')),
                host.groupUnregistrations.toString());
        host.register(bus, pair);
        assertEquals(List.of(pair, pair), host.groupRegistrations, "after unregister, a register reaches EventBus 7 again");
    }

    @Test
    @DisplayName("unregister finds an equal but distinct object, and registering an equal one again adds nothing, as EventBus 6 keyed by equals")
    void unregisterMatchesAnEqualObject() throws Exception {
        EventHost host = new EventHost();
        Object bus = host.bus();
        host.register(bus, host.listener(EventHost.EQUAL));
        host.register(bus, host.listener(EventHost.EQUAL));
        assertEquals(List.of(OWN_BUS_EVENT + " at 0"), host.hooksOn(OWN_BUS_EVENT), "the bus already held an equal object");

        host.unregister(bus, host.listener(EventHost.EQUAL));
        assertEquals(List.of(), host.hooksOn(OWN_BUS_EVENT), "a distinct but equal object unregisters it");
        host.register(bus, host.listener(EventHost.EQUAL));
        assertEquals(List.of(OWN_BUS_EVENT + " at 0"), host.hooksOn(OWN_BUS_EVENT), "and a later register hooks it again");
    }

    @Test
    @DisplayName("unregister takes an object's own-event handler and its tick halves off together, and register puts both back")
    void unregisterCoversLegacyAndTickHandlers() throws Exception {
        onForge262(t -> {
            EventBus7Host host = new EventBus7Host();
            String tick = "net/minecraftforge/event/TickEvent";
            host.define(tick, tickInterface(tick, null));
            host.define(SERVER_TICK, tickInterface(SERVER_TICK, tick));
            for (String half : List.of("$Pre", "$Post")) host.define(SERVER_TICK + half, tickRecord(SERVER_TICK + half));
            // A class EventBus 7 cannot reach, so the bridge hooks the split tick listeners itself and they run here.
            byte[] mixed = packagePrivate(LegacyEventFixtures.listener("mod/Mixed",
                    LegacyEventFixtures.Handler.of("own", "mod/CancelEvent", null),
                    LegacyEventFixtures.Handler.of("tick", SERVER_TICK, null)));
            ClassLoader mod = host.mod(host, t, "", java.util.Map.of(
                    "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"), "mod/Mixed", mixed));
            host.completeLoading();
            Object bus = EventBus7Host.gameBus(mod);
            java.lang.reflect.Constructor<?> constructor = mod.loadClass("mod.Mixed").getDeclaredConstructor();
            constructor.setAccessible(true);
            Object listener = constructor.newInstance();
            java.lang.reflect.Field notes = mod.loadClass("mod.Mixed").getField("NOTES");
            notes.setAccessible(true);
            Runnable postBoth = () -> {
                try {
                    EventBus7Host.post(bus, EventBus7Host.create(mod, "mod/CancelEvent"));
                    EventBus7Host.post(bus, host.type(SERVER_TICK + "$Pre").getConstructor().newInstance());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            };

            EventBus7Host.register(bus, listener);
            postBoth.run();
            assertEquals(List.of("own", "tick"), notes.get(null));
            EventBus7Host.unregister(bus, listener);
            postBoth.run();
            assertEquals(List.of("own", "tick"), notes.get(null), "both handlers left");
            EventBus7Host.register(bus, listener);
            EventBus7Host.register(bus, listener);
            postBoth.run();
            assertEquals(List.of("own", "tick", "own", "tick"), notes.get(null), "both came back, once each");
        });
    }

    @Test
    @DisplayName("A class unregistered and registered again gets its static handler back, once")
    void staticHandlerReturnsAfterUnregister() throws Exception {
        EventHost host = new EventHost();
        Object bus = host.bus();
        Class<?> statics = host.type(EventHost.STATIC_BASE);
        host.register(bus, statics);
        assertEquals(List.of(OWN_BUS_EVENT + " at 127"), host.hooksOn(OWN_BUS_EVENT));

        host.unregister(bus, statics);
        assertEquals(List.of(), host.hooksOn(OWN_BUS_EVENT), "unregister(Class) takes the static handler off");
        host.register(bus, statics);
        host.register(bus, statics);
        assertEquals(List.of(OWN_BUS_EVENT + " at 127"), host.hooksOn(OWN_BUS_EVENT),
                "a class registered twice still gives its handler once");
    }

    // ---- entry points of the rebuilt dispatcher, where Forge 26.2 and newer removed the old event base ----

    @Test
    @DisplayName("The bridge declares post(Object) and addGenericListener, which reach the rebuilt dispatcher, only where the base is rebuilt")
    void postIsDeclaredForTheRebuiltBase() {
        ClassNode legacy = node(ForgeEventBusSynthetics.generateInterface());
        org.objectweb.asm.tree.MethodNode post = legacy.methods.stream()
                .filter(m -> m.name.equals("post") && m.desc.equals("(Ljava/lang/Object;)Z")).findFirst()
                .orElseThrow(() -> new AssertionError("LegacyEventBus has no post(Object)Z"));
        assertTrue(body(post).contains("INVOKESTATIC " + LegacyEventBaseBridge.LEGACY_EVENTS
                + ".post(Lnet/minecraftforge/eventbus/api/bus/BusGroup;Ljava/lang/Object;)Z"), body(post).toString());
        assertEquals(4, legacy.methods.stream().filter(m -> m.name.equals("addGenericListener")).count(),
                "all four EventBus 6 addGenericListener forms");
        ClassNode plain = node(ForgeEventBusSynthetics.generateInterface(false));
        assertTrue(plain.methods.stream().noneMatch(m -> m.name.equals("post") || m.name.equals("addGenericListener")));
    }

    @Test
    @DisplayName("register(Class) hooks the class's static handlers, legacy and EventBus 7 alike, and none of its instance ones")
    void registerClassHooksStatics() throws Exception {
        onForge262(t -> {
            EventBus7Host host = new EventBus7Host();
            host.define(LegacyEventFixtures.HOST_EVENT, LegacyEventFixtures.hostEvent(LegacyEventFixtures.HOST_EVENT));
            ClassLoader mod = host.mod(host, t, "", java.util.Map.of(
                    "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                    "mod/Statics", LegacyEventFixtures.listener("mod/Statics",
                            LegacyEventFixtures.Handler.of("legacy-static", "mod/CancelEvent", null).asStatic(),
                            LegacyEventFixtures.Handler.of("legacy-instance", "mod/CancelEvent", null),
                            LegacyEventFixtures.Handler.of("host-static", LegacyEventFixtures.HOST_EVENT, "HIGH").asStatic())));
            host.completeLoading();
            Object bus = EventBus7Host.gameBus(mod);
            EventBus7Host.register(bus, mod.loadClass("mod.Statics"));

            EventBus7Host.post(bus, EventBus7Host.create(mod, "mod/CancelEvent"));
            EventBus7Host.post(bus, host.type(LegacyEventFixtures.HOST_EVENT).getConstructor().newInstance());
            assertEquals(List.of("legacy-static", "host-static"), EventBus7Host.notes(mod, "mod/Statics"));
            assertEquals(List.of(), host.registrations(host.gameGroup()),
                    "EventBus 7's register(Object) would read the class as an instance of Class and find nothing");
        });
    }

    @Test
    @DisplayName("register(Class) leaves a kept @Mod.EventBusSubscriber class to FML on its side, and hooks it on the other side")
    void registerClassLeavesAnAutoSubscribedClassToFml() throws Exception {
        onForge262(t -> {
            for (String side : List.of("CLIENT", "DEDICATED_SERVER")) {
                EventBus7Host host = new EventBus7Host();
                host.define(LegacyEventFixtures.HOST_EVENT, LegacyEventFixtures.hostEvent(LegacyEventFixtures.HOST_EVENT));
                host.define(FML_ENVIRONMENT, fmlEnvironment(side));
                // Highlighter's shape: two static handlers, bus = FORGE, client only, so the class keeps its annotation.
                ClassLoader mod = host.mod(host, t, "", java.util.Map.of("mod/Tooltips", autoSubscribed(
                        LegacyEventFixtures.listener("mod/Tooltips",
                                LegacyEventFixtures.Handler.of("first", LegacyEventFixtures.HOST_EVENT, null).asStatic(),
                                LegacyEventFixtures.Handler.of("second", LegacyEventFixtures.HOST_EVENT, null).asStatic()),
                        "FORGE", "CLIENT")));
                host.completeLoading();
                Class<?> tooltips = mod.loadClass("mod.Tooltips");
                Object gameBus = EventBus7Host.gameBus(mod);
                // The mod's constructor registers the class, then FML subscribes it on the side it names.
                EventBus7Host.register(gameBus, tooltips);
                if (side.equals("CLIENT")) subscribeAsFml(host, tooltips);

                EventBus7Host.post(gameBus, host.type(LegacyEventFixtures.HOST_EVENT).getConstructor().newInstance());
                assertEquals(List.of("first", "second"), sortedNotes(mod, "mod/Tooltips"),
                        "each handler runs once on " + side + ", where Forge 1.20.1 took the class once per bus");
            }
        });
    }

    @Test
    @DisplayName("A lone static handler that its companion and register(Class) both hook runs once, in either order")
    void companionAndRegisterClassHookOnce() throws Exception {
        onForge262(t -> {
            EventBus7Host host = new EventBus7Host();
            host.define(LegacyEventFixtures.HOST_EVENT, LegacyEventFixtures.hostEvent(LegacyEventFixtures.HOST_EVENT));
            ClassLoader mod = host.mod(host, t, "", java.util.Map.of(
                    "mod/IcebergClient", LegacyEventFixtures.listener("mod/IcebergClient",
                            LegacyEventFixtures.Handler.of("tooltip", LegacyEventFixtures.HOST_EVENT, null).asStatic()),
                    "mod/LateClient", LegacyEventFixtures.listener("mod/LateClient",
                            LegacyEventFixtures.Handler.of("late", LegacyEventFixtures.HOST_EVENT, null).asStatic())));
            host.completeLoading();
            Class<?> impl = EventBus7Host.implOf(mod);
            java.lang.reflect.Field modGroup = impl.getDeclaredField("modGroup");
            modGroup.setAccessible(true);
            modGroup.set(null, host.modGroup("mod"));
            Object gameBus = EventBus7Host.gameBus(mod);
            // Iceberg's shape: the constructor registers the class, then the companion hooks it on the construct event.
            EventBus7Host.register(gameBus, mod.loadClass("mod.IcebergClient"));
            hookStatic(impl, "mod.IcebergClient", "onTooltip");
            // A later register(Class) after the companion is skipped the same way.
            hookStatic(impl, "mod.LateClient", "onLate");
            EventBus7Host.register(gameBus, mod.loadClass("mod.LateClient"));

            EventBus7Host.post(gameBus, host.type(LegacyEventFixtures.HOST_EVENT).getConstructor().newInstance());
            assertEquals(List.of("tooltip"), EventBus7Host.notes(mod, "mod/IcebergClient"));
            assertEquals(List.of("late"), EventBus7Host.notes(mod, "mod/LateClient"));
        });
    }

    @Test
    @DisplayName("register(Class) called again, or on another bus that reaches the same event bus, hooks each static handler once")
    void registerClassTwiceHooksOnce() throws Exception {
        onForge262(t -> {
            EventBus7Host host = new EventBus7Host();
            host.define(LegacyEventFixtures.HOST_EVENT, LegacyEventFixtures.hostEvent(LegacyEventFixtures.HOST_EVENT));
            ClassLoader mod = host.mod(host, t, "", java.util.Map.of("mod/Handlers", LegacyEventFixtures.listener(
                    "mod/Handlers", LegacyEventFixtures.Handler.of("handled", LegacyEventFixtures.HOST_EVENT, null).asStatic())));
            host.completeLoading();
            Class<?> handlers = mod.loadClass("mod.Handlers");
            Object gameBus = EventBus7Host.gameBus(mod);
            EventBus7Host.register(gameBus, handlers);
            EventBus7Host.register(gameBus, handlers);
            // A game event's handler on the mod's bus lands on the same game event bus.
            EventBus7Host.register(EventBus7Host.modBus(mod, host.modGroup("mod")), handlers);

            EventBus7Host.post(gameBus, host.type(LegacyEventFixtures.HOST_EVENT).getConstructor().newInstance());
            assertEquals(List.of("handled"), EventBus7Host.notes(mod, "mod/Handlers"));
        });
    }

    @Test
    @DisplayName("register(Class) hooks only public static handlers, as EventBus 6's registerClass read getMethods()")
    void registerClassHooksOnlyPublicStatics() throws Exception {
        onForge262(t -> {
            EventBus7Host host = new EventBus7Host();
            host.define(LegacyEventFixtures.HOST_EVENT, LegacyEventFixtures.hostEvent(LegacyEventFixtures.HOST_EVENT));
            byte[] handlers = LegacyEventFixtures.listener("mod/Handlers",
                    LegacyEventFixtures.Handler.of("shown", LegacyEventFixtures.HOST_EVENT, null).asStatic(),
                    LegacyEventFixtures.Handler.of("hidden", LegacyEventFixtures.HOST_EVENT, null).asStatic());
            // ModernUI's shape: a package-private static handler, which Forge 1.20.1 never registered.
            ClassLoader mod = host.mod(host, t, "", java.util.Map.of("mod/Handlers", withAccess(handlers, "onHidden", ACC_STATIC)));
            host.completeLoading();
            Object gameBus = EventBus7Host.gameBus(mod);
            EventBus7Host.register(gameBus, mod.loadClass("mod.Handlers"));

            EventBus7Host.post(gameBus, host.type(LegacyEventFixtures.HOST_EVENT).getConstructor().newInstance());
            assertEquals(List.of("shown"), EventBus7Host.notes(mod, "mod/Handlers"));
        });
    }

    private static final String FML_ENVIRONMENT = "net/minecraftforge/fml/loading/FMLEnvironment";

    /** FML's environment with the given side as {@code dist}. */
    private static byte[] fmlEnvironment(String side) {
        String dist = "Lnet/minecraftforge/api/distmarker/Dist;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, FML_ENVIRONMENT, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "dist", dist, null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(GETSTATIC, "net/minecraftforge/api/distmarker/Dist", side, dist);
        clinit.visitFieldInsn(PUTSTATIC, FML_ENVIRONMENT, "dist", dist);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The class with {@code @Mod.EventBusSubscriber(bus = bus, value = side)} and no mod id. */
    private static byte[] autoSubscribed(byte[] classBytes, String bus, String side) {
        ClassNode node = node(classBytes);
        AnnotationNode subscriber = new AnnotationNode(AUTO_SUB);
        subscriber.visitEnum("bus", "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;", bus);
        org.objectweb.asm.AnnotationVisitor sides = subscriber.visitArray("value");
        sides.visitEnum(null, "Lnet/minecraftforge/api/distmarker/Dist;", side);
        sides.visitEnd();
        node.visibleAnnotations = new java.util.ArrayList<>(List.of(subscriber));
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** The class without its public flag. */
    private static byte[] packagePrivate(byte[] classBytes) {
        ClassNode node = node(classBytes);
        node.access &= ~ACC_PUBLIC;
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** A Forge 26.2 tick interface, extending {@code parent} when it has one. */
    private static byte[] tickInterface(String name, String parent) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object",
                parent == null ? null : new String[]{parent});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A tick record as Forge 26.2 fires it: a host event with its own BUS that implements the server tick interface. */
    private static byte[] tickRecord(String name) {
        ClassNode node = node(LegacyEventFixtures.hostEvent(name));
        node.interfaces = List.of(SERVER_TICK);
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** The class with the named method's access flags replaced. */
    private static byte[] withAccess(byte[] classBytes, String method, int access) {
        ClassNode node = node(classBytes);
        node.methods.stream().filter(m -> m.name.equals(method)).forEach(m -> m.access = access);
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** What FML 26.2 does for a kept subscriber class: each EventBus 7 static handler onto its event's game bus. */
    private static void subscribeAsFml(EventBus7Host host, Class<?> subscriber) throws Exception {
        for (java.lang.reflect.Method handler : subscriber.getDeclaredMethods()) {
            boolean listens = java.util.Arrays.stream(handler.getDeclaredAnnotations()).anyMatch(annotation ->
                    annotation.annotationType().getName().equals("net.minecraftforge.eventbus.api.listener.SubscribeEvent"));
            if (!listens || !java.lang.reflect.Modifier.isStatic(handler.getModifiers())) continue;
            Object bus = host.type(EventBus7Host.EVENT_BUS).getMethod("create", host.type(EventBus7Host.BUS_GROUP), Class.class)
                    .invoke(null, host.gameGroup(), handler.getParameterTypes()[0]);
            java.util.function.Consumer<Object> call = event -> {
                try {
                    handler.invoke(null, event);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            };
            bus.getClass().getMethod("addListener", byte.class, java.util.function.Consumer.class).invoke(bus, (byte) 0, call);
        }
    }

    /** The call a one-handler subscriber's companion makes when FML fires the construct event. */
    private static void hookStatic(Class<?> impl, String owner, String handler) throws Exception {
        impl.getMethod("hookStatic", String.class, String.class, String.class, byte.class)
                .invoke(null, owner, handler, LegacyEventFixtures.HOST_EVENT.replace('/', '.'), (byte) 0);
    }

    private static List<String> sortedNotes(ClassLoader mod, String listener) throws Exception {
        return EventBus7Host.notes(mod, listener).stream().sorted().toList();
    }

    @Test
    @DisplayName("A legacy event's listener lands on its bus in the receiving bus's group, and keeps the old cancel rule")
    void legacyEventListenersUseTheReceivingGroup() throws Exception {
        onForge262(t -> {
            EventBus7Host host = new EventBus7Host();
            ClassLoader mod = host.mod(host, t, "", java.util.Map.of(
                    "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                    "mod/ModEvent", LegacyEventFixtures.modBusEvent("mod/ModEvent")));
            host.completeLoading();
            Class<?> impl = EventBus7Host.implOf(mod);
            Class<?> cancelEvent = mod.loadClass("mod.CancelEvent");
            java.lang.reflect.Method busOf = impl.getMethod("busOf", host.type(EventBus7Host.BUS_GROUP), Class.class);
            Object onGame = busOf.invoke(null, host.gameGroup(), cancelEvent);
            assertSame(host.gameGroup(), onGame.getClass().getField("group").get(onGame),
                    "a game-bus event with no static BUS gets its bus in the game group, not the mod's");
            Object modGroup = host.modGroup("mod");
            Object onMod = busOf.invoke(null, modGroup, mod.loadClass("mod.ModEvent"));
            assertSame(modGroup, onMod.getClass().getField("group").get(onMod));

            Object gameBus = EventBus7Host.gameBus(mod);
            List<String> seen = new java.util.ArrayList<>();
            impl.getMethod("addListenerTypedIndy", mod.loadClass(ForgeEventBusSynthetics.LEB.replace('/', '.')),
                    java.util.function.Consumer.class, Class.class)
                    .invoke(null, gameBus, (java.util.function.Consumer<Object>) event -> seen.add("lambda"), cancelEvent);
            Object cancelled = cancelEvent.getConstructor().newInstance();
            cancelEvent.getMethod("setCanceled", boolean.class).invoke(cancelled, true);
            assertTrue(EventBus7Host.post(gameBus, cancelled));
            assertEquals(List.of(), seen, "a listener without receiveCanceled skips an event cancelled before the post");
            EventBus7Host.post(gameBus, cancelEvent.getConstructor().newInstance());
            assertEquals(List.of("lambda"), seen);
        });
    }

    @Test
    @DisplayName("addGenericListener filters a legacy generic event by type and skips a host event that reports no generic type")
    void addGenericListenerFilters() throws Exception {
        onForge262(t -> {
            EventBus7Host host = new EventBus7Host();
            host.define(LegacyEventFixtures.HOST_EVENT, LegacyEventFixtures.hostEvent(LegacyEventFixtures.HOST_EVENT));
            ClassLoader mod = host.mod(host, t, "", java.util.Map.of(
                    "mod/RegistryEvent", LegacyEventFixtures.genericEvent("mod/RegistryEvent")));
            host.completeLoading();
            Object gameBus = EventBus7Host.gameBus(mod);
            Class<?> leb = mod.loadClass(ForgeEventBusSynthetics.LEB.replace('/', '.'));
            Class<?> priority = mod.loadClass(ForgeEventBusSynthetics.PRI.replace('/', '.'));
            Class<?> registry = mod.loadClass("mod.RegistryEvent");
            List<String> seen = new java.util.ArrayList<>();
            // A lambda's call, which the transform gives its event type, and the form that names the type itself.
            EventBus7Host.implOf(mod).getMethod("addGenericListenerTypedIndy", leb, Class.class,
                    java.util.function.Consumer.class, Class.class)
                    .invoke(null, gameBus, String.class, (java.util.function.Consumer<Object>) event -> seen.add("string"), registry);
            java.lang.reflect.Method typed = leb.getMethod("addGenericListener", Class.class, priority, boolean.class,
                    Class.class, java.util.function.Consumer.class);
            typed.invoke(gameBus, Integer.class, priority.getField("HIGH").get(null), false, registry,
                    (java.util.function.Consumer<Object>) event -> seen.add("integer"));

            EventBus7Host.post(gameBus, registry.getConstructor(Class.class).newInstance(String.class));
            EventBus7Host.post(gameBus, registry.getConstructor(Class.class).newInstance(Integer.class));
            assertEquals(List.of("string", "integer"), seen);

            typed.invoke(gameBus, String.class, priority.getField("NORMAL").get(null), false,
                    host.type(LegacyEventFixtures.HOST_EVENT), (java.util.function.Consumer<Object>) event -> seen.add("host"));
            Object hostBus = host.type(LegacyEventFixtures.HOST_EVENT).getField("BUS").get(null);
            assertEquals(List.of(), EventBus7Host.priorities(hostBus),
                    "Forge 26.2's events report no generic type, so the listener is skipped with a warning");
        });
    }

    @Test
    @DisplayName("A post through the old Event descriptor and a lambda's generic listener reach the dispatcher's entry points")
    void oldPostDescriptorAndGenericLambda() throws Exception {
        onForge262(t -> {
            new Forge_26_1_to_26_2().registerRedirects(t);
            ClassNode out = node(t.transformClass(oldPoster(), "mod/OldPoster.class"));
            org.objectweb.asm.tree.MethodNode fire = out.methods.stream().filter(m -> m.name.equals("fire")).findFirst().orElseThrow();
            assertTrue(body(fire).contains("INVOKE" + INVOKEINTERFACE + " " + ForgeEventBusSynthetics.LEB
                    + ".post(Ljava/lang/Object;)Z"), body(fire).toString());
            org.objectweb.asm.tree.MethodNode listen = out.methods.stream().filter(m -> m.name.equals("listen")).findFirst().orElseThrow();
            assertTrue(body(listen).contains("LDC class Lmod/RegistryEvent;"), body(listen).toString());
            assertTrue(body(listen).contains("INVOKESTATIC " + ForgeEventBusSynthetics.LEB_IMPL + ".addGenericListenerTypedIndy("
                    + "L" + ForgeEventBusSynthetics.LEB + ";Ljava/lang/Class;Ljava/util/function/Consumer;Ljava/lang/Class;)V"),
                    body(listen).toString());
        });
    }

    /** Runs {@code check} with a fresh transformer set up as RetromodForge sets it up on a Forge 26.2 host. */
    private static void onForge262(ForgeCheck check) throws Exception {
        String savedTarget = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        boolean savedForge = com.retromod.util.McReflect.isForceForge();
        boolean savedNeoForge = com.retromod.util.McReflect.isForceNeoForge();
        RetromodTransformer t = RetromodTransformer.getInstance();
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
            com.retromod.util.McReflect.setForceNeoForge(false);
            com.retromod.util.McReflect.setForceForge(true);
            t.clearRedirectsForTesting();
            new Forge_26_1_to_26_2().registerRedirects(t);
            check.run(t);
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = savedTarget;
            com.retromod.util.McReflect.setForceForge(savedForge);
            com.retromod.util.McReflect.setForceNeoForge(savedNeoForge);
            t.clearRedirectsForTesting();
        }
    }

    @FunctionalInterface
    private interface ForgeCheck {
        void run(RetromodTransformer transformer) throws Exception;
    }

    /**
     * A class the event base pass leaves on the old base, because two of its methods would share a
     * descriptor, posting through the old descriptor; and a lambda's generic listener on a mod event.
     */
    private static byte[] oldPoster() {
        String event = "L" + LegacyEventBaseAdapter.EVENT + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/OldPoster", null, "java/lang/Object", null);
        for (String param : List.of(event, "Ljava/lang/Object;")) {
            MethodVisitor handle = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "handle", "(" + param + ")V", null, null);
            handle.visitCode();
            handle.visitInsn(RETURN);
            handle.visitMaxs(0, 0);
            handle.visitEnd();
        }
        MethodVisitor fire = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fire", "(" + L_OLD_BUS + event + ")Z", null, null);
        fire.visitCode();
        fire.visitVarInsn(ALOAD, 0);
        fire.visitVarInsn(ALOAD, 1);
        fire.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "post", "(" + event + ")Z", true);
        fire.visitInsn(IRETURN);
        fire.visitMaxs(0, 0);
        fire.visitEnd();
        MethodVisitor listen = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "listen", "(" + L_OLD_BUS + ")V", null, null);
        listen.visitCode();
        listen.visitVarInsn(ALOAD, 0);
        listen.visitLdcInsn(org.objectweb.asm.Type.getType(String.class));
        listen.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;",
                new org.objectweb.asm.Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)V"),
                new org.objectweb.asm.Handle(H_INVOKESTATIC, "mod/OldPoster", "onRegistry", "(Lmod/RegistryEvent;)V", false),
                org.objectweb.asm.Type.getMethodType("(Lmod/RegistryEvent;)V"));
        listen.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "addGenericListener",
                "(Ljava/lang/Class;Ljava/util/function/Consumer;)V", true);
        listen.visitInsn(RETURN);
        listen.visitMaxs(0, 0);
        listen.visitEnd();
        MethodVisitor on = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "onRegistry", "(Lmod/RegistryEvent;)V", null, null);
        on.visitCode();
        on.visitInsn(RETURN);
        on.visitMaxs(0, 0);
        on.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String AUTO_SUB = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
    private static final String SUB7 = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";
    private static final String TOOLTIPS = "com/example/Tooltips";

    /** One handler of a fixture subscriber class: its name, whether it is static, and its old priority or null. */
    private record Handler(String name, boolean isStatic, String priority) {}

    @Test
    @DisplayName("A subscriber class with one static handler loses its annotation and gets a companion that hooks it")
    void oneStaticHandlerGetsACompanion() {
        ForgeEventBusSynthetics.register(RetromodTransformer.getInstance());
        java.util.Map<String, byte[]> companions = new java.util.HashMap<>();

        byte[] stripped = ForgeEventBusSynthetics.stripLenientAutoSubscriber(
                subscriberClass(new Handler("onTooltip", true, null)), companions::put);

        ClassNode owner = node(stripped);
        assertNull(annotation(owner.visibleAnnotations, AUTO_SUB), "FML must never reflect on the original class");
        assertNotNull(annotation(owner.methods.stream().filter(m -> m.name.equals("onTooltip")).findFirst()
                .orElseThrow().visibleAnnotations, SUB7), "the handler itself is left as it was");
        byte[] companionBytes = companions.get(TOOLTIPS + "$RetromodSubscriber");
        assertNotNull(companionBytes, "expected a companion, got " + companions.keySet());
        new ClassReader(companionBytes).accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(0), true), 0);

        ClassNode companion = node(companionBytes);
        AnnotationNode subscriber = annotation(companion.visibleAnnotations, AUTO_SUB);
        assertNotNull(subscriber, "the companion subscribes in the original's place");
        assertEquals(List.of("modid", "value"), elementNames(subscriber),
                "the bus element is dropped, so FML puts the construct event on the mod's group");
        assertEquals(1, companion.methods.size(), "only the hook listener, so FML finds exactly one");
        org.objectweb.asm.tree.MethodNode hook = companion.methods.get(0);
        assertEquals("hook(Lnet/minecraftforge/fml/event/lifecycle/FMLConstructModEvent;)V", hook.name + hook.desc);
        assertNotNull(annotation(hook.visibleAnnotations, SUB7));
        assertEquals(List.of("LDC com.example.Tooltips", "LDC onTooltip", "LDC com.example.TooltipEvent", "BIPUSH 0",
                "INVOKESTATIC " + ForgeEventBusSynthetics.LEB_IMPL + ".hookStatic(Ljava/lang/String;Ljava/lang/String;"
                        + "Ljava/lang/String;B)V", "RETURN"), body(hook),
                "only string constants and one static call, so nothing in the companion can fail to link");
    }

    @Test
    @DisplayName("The companion passes the handler's old priority as EventBus 7's byte")
    void companionCarriesTheOldPriority() {
        ForgeEventBusSynthetics.register(RetromodTransformer.getInstance());
        java.util.Map<String, byte[]> companions = new java.util.HashMap<>();

        ForgeEventBusSynthetics.stripLenientAutoSubscriber(
                subscriberClass(new Handler("onTooltip", true, "HIGH")), companions::put);

        assertTrue(body(node(companions.get(TOOLTIPS + "$RetromodSubscriber")).methods.get(0)).contains("BIPUSH 64"),
                "HIGH is 64 on EventBus 7");
    }

    @Test
    @DisplayName("The companion reads the byte the class pass wrote, and names a private static handler")
    void companionReadsTheNormalizedPriority() {
        ForgeEventBusSynthetics.register(RetromodTransformer.getInstance());
        java.util.Map<String, byte[]> companions = new java.util.HashMap<>();

        ForgeEventBusSynthetics.stripLenientAutoSubscriber(normalizedPrivateSubscriber(), companions::put);

        List<String> hook = body(node(companions.get(TOOLTIPS + "$RetromodSubscriber")).methods.get(0));
        assertTrue(hook.contains("LDC onTooltip"), "hookStatic reaches private handlers by name: " + hook);
        assertTrue(hook.contains("BIPUSH 64"), "the normalized HIGH must not fall back to NORMAL: " + hook);
    }

    /** {@code @Mod.EventBusSubscriber class Tooltips { @SubscribeEvent(priority = (byte) 64) private static void onTooltip(TooltipEvent e) {} }}. */
    private static byte[] normalizedPrivateSubscriber() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, TOOLTIPS, null, "java/lang/Object", null);
        cw.visitAnnotation(AUTO_SUB, true).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "onTooltip", "(Lcom/example/TooltipEvent;)V", null, null);
        org.objectweb.asm.AnnotationVisitor subscribe = mv.visitAnnotation(SUB7, true);
        subscribe.visit("priority", (byte) 64);
        subscribe.visitEnd();
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("A subscriber class with only instance handlers loses its annotation and gets no companion")
    void instanceOnlyClassGetsNoCompanion() {
        ForgeEventBusSynthetics.register(RetromodTransformer.getInstance());
        java.util.Map<String, byte[]> companions = new java.util.HashMap<>();

        byte[] stripped = ForgeEventBusSynthetics.stripLenientAutoSubscriber(
                subscriberClass(new Handler("onTooltip", false, null)), companions::put);

        assertNull(annotation(node(stripped).visibleAnnotations, AUTO_SUB));
        assertTrue(companions.isEmpty(), "instance handlers were never auto-subscribed: " + companions.keySet());
    }

    @Test
    @DisplayName("A subscriber class with one boolean cancel twin gets a companion that names the twin at its priority")
    void loneCancelTwinGetsACompanion() {
        ForgeEventBusSynthetics.register(RetromodTransformer.getInstance());
        java.util.Map<String, byte[]> companions = new java.util.HashMap<>();

        ForgeEventBusSynthetics.stripLenientAutoSubscriber(twinSubscriber(false), companions::put);

        List<String> hook = body(node(companions.get(TOOLTIPS + "$RetromodSubscriber")).methods.get(0));
        assertTrue(hook.contains("LDC retromod$cancellable$onTooltip"), hook.toString());
        assertTrue(hook.contains("BIPUSH 64"), hook.toString());
    }

    @Test
    @DisplayName("A cancel twin and its (event, boolean) monitor count as two handlers, so FML subscribes the class itself")
    void cancelTwinAndMonitorStayWithFml() {
        ForgeEventBusSynthetics.register(RetromodTransformer.getInstance());
        java.util.Map<String, byte[]> companions = new java.util.HashMap<>();
        byte[] original = twinSubscriber(true);

        assertSame(original, ForgeEventBusSynthetics.stripLenientAutoSubscriber(original, companions::put));
        assertTrue(companions.isEmpty(), "FML takes both, the monitor too: " + companions.keySet());
    }

    /** {@code @Mod.EventBusSubscriber class Tooltips} with a static boolean cancel twin at 64, and its monitor when asked. */
    private static byte[] twinSubscriber(boolean withMonitor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, TOOLTIPS, null, "java/lang/Object", null);
        cw.visitAnnotation(AUTO_SUB, true).visitEnd();
        MethodVisitor twin = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "retromod$cancellable$onTooltip",
                "(Lcom/example/TooltipEvent;)Z", null, null);
        org.objectweb.asm.AnnotationVisitor subscribe = twin.visitAnnotation(SUB7, true);
        subscribe.visit("priority", (byte) 64);
        subscribe.visitEnd();
        twin.visitCode();
        twin.visitInsn(ICONST_0);
        twin.visitInsn(IRETURN);
        twin.visitMaxs(0, 0);
        twin.visitEnd();
        if (withMonitor) {
            MethodVisitor monitor = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "retromod$cancelled$onTooltip",
                    "(Lcom/example/TooltipEvent;Z)V", null, null);
            org.objectweb.asm.AnnotationVisitor monitorSubscribe = monitor.visitAnnotation(SUB7, true);
            monitorSubscribe.visit("priority", (byte) -128);
            monitorSubscribe.visitEnd();
            monitor.visitCode();
            monitor.visitInsn(RETURN);
            monitor.visitMaxs(0, 0);
            monitor.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("A subscriber class with two static handlers is left for FML and gets no companion")
    void twoStaticHandlersStayWithFml() {
        ForgeEventBusSynthetics.register(RetromodTransformer.getInstance());
        java.util.Map<String, byte[]> companions = new java.util.HashMap<>();
        byte[] original = subscriberClass(new Handler("onTooltip", true, null), new Handler("onOther", true, null));

        assertSame(original, ForgeEventBusSynthetics.stripLenientAutoSubscriber(original, companions::put));
        assertTrue(companions.isEmpty());
    }

    @Test
    @DisplayName("hookStatic hooks the handler onto its event's bus at the given priority, and the listener calls it")
    void hookStaticHooksTheHandler() throws Exception {
        EventHost host = new EventHost();
        host.invoke("hookStatic", "com.example.Handlers", "onOwn", OWN_BUS_EVENT.replace('/', '.'), (byte) 64);

        assertEquals(List.of(OWN_BUS_EVENT + " at 64"), host.hooksOn(OWN_BUS_EVENT));
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Object> listener = (java.util.function.Consumer<Object>) host.listenersOn(OWN_BUS_EVENT).get(0);
        listener.accept(null);
        assertEquals(1, host.type(EventHost.HANDLERS).getField("calls").getInt(null),
                "the private static handler runs when the event fires");
    }

    @Test
    @DisplayName("hookStatic skips a handler whose event the host no longer has, without throwing")
    void hookStaticSkipsAMissingEvent() throws Exception {
        EventHost host = new EventHost();
        host.invoke("hookStatic", "com.example.Gone", "onGone", "com.example.RemovedEvent", (byte) 0);

        assertTrue(host.hooksOn(OWN_BUS_EVENT).isEmpty());
        assertTrue(host.cancelHooks().isEmpty());
    }

    @Test
    @DisplayName("hookStatic finds a handler by its name when its event's name does not load, as for an event the embed moved")
    void hookStaticFindsAHandlerWhoseEventMoved() throws Exception {
        EventHost host = new EventHost();
        // The companion names a Retromod-built event as it was before the embed moved it into the mod.
        host.invoke("hookStatic", "com.example.Handlers", "onOwn", "com.retromod.generated.MovedEvent", (byte) 0);

        assertEquals(List.of(OWN_BUS_EVENT + " at 0"), host.hooksOn(OWN_BUS_EVENT));
    }

    @Test
    @DisplayName("hookStatic adds a lone boolean cancel twin to its cancellable bus as a predicate, at the companion's priority")
    void hookStaticAddsACancelTwinAsAPredicate() throws Exception {
        EventHost host = new EventHost();
        host.invoke("hookStatic", EventHost.STATIC_TWINS.replace('/', '.'), "retromod$cancellable$onClick",
                EventHost.CANCEL_EVENT.replace('/', '.'), (byte) 64);

        assertEquals(List.of("addListener(byte,Predicate) at 64"), host.cancelHooks());
        @SuppressWarnings("unchecked")
        java.util.function.Predicate<Object> predicate = (java.util.function.Predicate<Object>) host.cancelListener(0);
        assertTrue(predicate.test(host.cancelEvent()), "EventBus 7 reads the twin's answer");
        assertEquals(1, host.type(EventHost.HANDLERS).getField("calls").getInt(null));
    }

    @Test
    @DisplayName("register(object) hooks a boolean cancel twin as a predicate and its (event, boolean) twin as a monitor, and unregister removes both")
    void registerFallbackHooksCancelTwins() throws Exception {
        EventHost host = new EventHost();
        Object bus = host.bus();
        Object listener = host.listener(EventHost.TWINS);
        host.register(bus, listener);

        assertEquals(List.of("addListener(ObjBooleanBiConsumer)", "addListener(byte,Predicate) at 64"),
                host.cancelHooks().stream().sorted().toList(), "a class EventBus 7 cannot see into is hooked here");
        Object event = host.cancelEvent();
        for (int i = 0; i < 2; i++) {
            Object hooked = host.cancelListener(i);
            if (hooked instanceof java.util.function.Predicate<?> predicate) {
                @SuppressWarnings("unchecked")
                boolean cancelled = ((java.util.function.Predicate<Object>) predicate).test(event);
                assertTrue(cancelled);
            } else {
                host.type("net/minecraftforge/eventbus/api/listener/ObjBooleanBiConsumer")
                        .getMethod("accept", Object.class, boolean.class).invoke(hooked, event, true);
            }
        }
        assertEquals(11, host.type(EventHost.HANDLERS).getField("calls").getInt(null), "each twin ran once");

        host.unregister(bus, listener);
        assertEquals(List.of(), host.cancelHooks());
    }

    @Test
    @DisplayName("register(lone) hooks the one listener once, at its priority, and never hands it to EventBus 7")
    void loneListenerIsHookedOnce() throws Exception {
        EventHost host = new EventHost();
        host.register(host.listener(EventHost.LONE));

        assertEquals(List.of(), host.groupRegistrations,
                "EventBus 7 adds a lone listener before it throws, so a second registration would run it twice");
        assertEquals(List.of(OWN_BUS_EVENT + " at 64"), host.hooksOn(OWN_BUS_EVENT));
    }

    @Test
    @DisplayName("register(object) reaches a private handler, at its priority, and the handler runs")
    void privateHandlerIsReached() throws Exception {
        EventHost host = new EventHost();
        host.register(host.listener(EventHost.PRIVATE_ONE));

        assertEquals(List.of(OWN_BUS_EVENT + " at -127"), host.hooksOn(OWN_BUS_EVENT));
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Object> listener = (java.util.function.Consumer<Object>) host.listenersOn(OWN_BUS_EVENT).get(0);
        listener.accept(null);
        assertEquals(1, host.type(EventHost.HANDLERS).getField("calls").getInt(null));
    }

    @Test
    @DisplayName("Two public listeners go to EventBus 7; a class it cannot see into is hooked here, each listener once")
    void registerRoutesByWhatEventBus7Accepts() throws Exception {
        EventHost host = new EventHost();
        Object pair = host.listener(EventHost.PAIR);
        host.register(pair);
        host.register(host.listener(EventHost.HIDDEN));

        assertEquals(List.of(pair), host.groupRegistrations, "EventBus 7 registers a public class with two listeners itself");
        assertEquals(List.of(OWN_BUS_EVENT + " at 0", OWN_BUS_EVENT + " at 64"), host.hooksOn(OWN_BUS_EVENT).stream().sorted().toList(),
                "the package-private class's two listeners, which EventBus 7 could not unreflect");
    }

    @Test
    @DisplayName("A listener inherited from a public superclass is hooked once, as EventBus 6 registered it")
    void inheritedListenerIsHookedOnce() throws Exception {
        EventHost host = new EventHost();
        host.register(host.listener(EventHost.CHILD));

        assertEquals(List.of(OWN_BUS_EVENT + " at 0"), host.hooksOn(OWN_BUS_EVENT));
        assertEquals(List.of(), host.groupRegistrations);
    }

    @Test
    @DisplayName("A listener object with a static handler hooks only its instance handlers, as EventBus 6 did")
    void staticHandlerOfAListenerObjectIsNotHooked() throws Exception {
        EventHost host = new EventHost();
        host.register(host.listener(EventHost.MIXED));

        assertEquals(List.of(), host.groupRegistrations,
                "EventBus 7 would add the static handler for an instance, so it would run twice");
        assertEquals(List.of(OWN_BUS_EVENT + " at 0", OWN_BUS_EVENT + " at 64"), host.hooksOn(OWN_BUS_EVENT).stream().sorted().toList(),
                "only the two instance handlers; the static one at 127 belongs to register(Class) or the auto-subscriber");
    }

    @Test
    @DisplayName("A lone instance listener next to a static one hooks only the instance listener")
    void loneInstanceListenerBesideAStaticOne() throws Exception {
        EventHost host = new EventHost();
        host.register(host.listener(EventHost.LONE_WITH_STATIC));

        assertEquals(List.of(), host.groupRegistrations);
        assertEquals(List.of(OWN_BUS_EVENT + " at 64"), host.hooksOn(OWN_BUS_EVENT),
                "the static handler at 127 must not be hooked for the instance");
    }

    @Test
    @DisplayName("A static handler inherited from a superclass is not hooked for the instance")
    void inheritedStaticHandlerIsNotHooked() throws Exception {
        EventHost host = new EventHost();
        host.register(host.listener(EventHost.STATIC_CHILD));

        assertEquals(List.of(OWN_BUS_EVENT + " at 0"), host.hooksOn(OWN_BUS_EVENT),
                "only the child's own instance handler; EventBus 6 dropped the inherited static one");
    }

    /**
     * {@code @Mod.EventBusSubscriber(modid = "tooltips", bus = MOD, value = CLIENT) class Tooltips}
     * with the given handlers on {@code TooltipEvent}, as the class pass leaves it.
     */
    private static byte[] subscriberClass(Handler... handlers) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, TOOLTIPS, null, "java/lang/Object", null);
        org.objectweb.asm.AnnotationVisitor subscriber = cw.visitAnnotation(AUTO_SUB, true);
        subscriber.visit("modid", "tooltips");
        subscriber.visitEnum("bus", "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;", "MOD");
        org.objectweb.asm.AnnotationVisitor sides = subscriber.visitArray("value");
        sides.visitEnum(null, "Lnet/minecraftforge/api/distmarker/Dist;", "CLIENT");
        sides.visitEnd();
        subscriber.visitEnd();
        for (Handler handler : handlers) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | (handler.isStatic() ? ACC_STATIC : 0), handler.name(),
                    "(Lcom/example/TooltipEvent;)V", null, null);
            org.objectweb.asm.AnnotationVisitor subscribe = mv.visitAnnotation(SUB7, true);
            if (handler.priority() != null) {
                subscribe.visitEnum("priority", "L" + ForgeEventBusSynthetics.PRI + ";", handler.priority());
            }
            subscribe.visitEnd();
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        return annotations == null ? null : annotations.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static List<String> elementNames(AnnotationNode annotation) {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < annotation.values.size(); i += 2) names.add((String) annotation.values.get(i));
        return names;
    }

    /** The method's instructions as short text, skipping labels, lines, and frames. */
    private static List<String> body(org.objectweb.asm.tree.MethodNode method) {
        List<String> body = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
                body.add((ldc.cst instanceof String ? "LDC " : "LDC class ") + ldc.cst);
            } else if (insn instanceof org.objectweb.asm.tree.IntInsnNode push) {
                body.add((push.getOpcode() == BIPUSH ? "BIPUSH " : "SIPUSH ") + push.operand);
            } else if (insn instanceof MethodInsnNode call) {
                body.add((call.getOpcode() == INVOKESTATIC ? "INVOKESTATIC " : "INVOKE" + call.getOpcode() + " ")
                        + call.owner + "." + call.name + call.desc);
            } else if (insn.getOpcode() == RETURN) {
                body.add("RETURN");
            } else if (insn.getOpcode() >= 0) {
                body.add("opcode " + insn.getOpcode());
            }
        }
        return body;
    }

    /**
     * Defines the generated bridge next to stand-ins for EventBus 7 and Forge 26.2's server tick
     * event. The stand-ins live only in this loader, so the test classpath never has a TickEvent.
     */
    private static final class EventHost extends ClassLoader {
        static final String TYPED_LISTENER = "com/example/ServerTickListener";
        static final String HANDLERS = "com/example/Handlers";
        static final String LONE = "com/example/Lone";
        static final String PRIVATE_ONE = "com/example/PrivateOne";
        static final String PAIR = "com/example/Pair";
        static final String HIDDEN = "com/example/Hidden";
        static final String BASE = "com/example/Base";
        static final String CHILD = "com/example/Child";
        static final String MIXED = "com/example/Mixed";
        static final String LONE_WITH_STATIC = "com/example/LoneWithStatic";
        static final String STATIC_BASE = "com/example/StaticBase";
        static final String STATIC_CHILD = "com/example/StaticChild";
        static final String EQUAL = "com/example/Equal";
        /** A listener class EventBus 7 cannot see into, with a boolean cancel twin and its (event, boolean) monitor. */
        static final String TWINS = "com/example/Twins";
        /** A class with one static boolean cancel twin, as a lone handler's companion names it. */
        static final String STATIC_TWINS = "com/example/StaticTwins";
        /** A class whose handler takes an event the host no longer has. */
        static final String GONE = "com/example/Gone";
        /** Forge 26.2's ScreenEvent.Init, which has no bus; Init.Pre has a cancellable one and Init.Post a plain one. */
        static final String SCREEN_INIT = "net/minecraftforge/client/event/ScreenEvent$Init";
        /** A host event with a CancellableEventBus, as Forge 26.2's RightClickBlock has. */
        static final String CANCEL_EVENT = "com/example/CancelHostEvent";
        static final String NAME_TAG_EVENT = "net/minecraftforge/client/event/RenderNameTagEvent";
        private static final String CANCELLABLE_BUS = "net/minecraftforge/eventbus/api/bus/CancellableEventBus";
        private static final String SUBSCRIBE = "net/minecraftforge/eventbus/api/listener/SubscribeEvent";
        private static final String TICK = "net/minecraftforge/event/TickEvent";
        // Forge 26.2's five tick interfaces, each over a Pre and a Post record.
        static final List<String> TICKS = LegacyTickEventBridge.EVENTS.stream().map(event -> TICK + "$" + event).toList();
        private static final String EVENT_BUS = "net/minecraftforge/eventbus/api/bus/EventBus";
        private static final String LISTENER = "net/minecraftforge/eventbus/api/listener/EventListener";
        private static final String BUS_GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";
        private static final String PRIORITY = "net/minecraftforge/eventbus/api/listener/Priority";
        private static final java.nio.file.Path COMPILED = java.nio.file.Path.of("target", "legacyapi-classes");

        private final java.util.Map<String, byte[]> classes = new java.util.HashMap<>();
        private final List<Object[]> hooks = new java.util.ArrayList<>();
        private final List<Object[]> cancelHooks = new java.util.ArrayList<>();
        /** What the cancellable bus's post answers, and how often it was posted to. */
        boolean postAnswer;
        int posts;

        EventHost() throws Exception {
            super(ForgeEventBusBridgeTest.class.getClassLoader());
            classes.put(ForgeEventBusSynthetics.LEB, ForgeEventBusSynthetics.generateInterface());
            classes.put(ForgeEventBusSynthetics.LEB_IMPL, ForgeEventBusSynthetics.generateImpl());
            classes.put(ForgeEventBusSynthetics.PRI, ForgeEventBusSynthetics.generatePriorityEnum());
            classes.put(ForgeEventBusSynthetics.RC, ForgeEventBusSynthetics.generateReflectedConsumer());
            classes.put(BUS_GROUP, standIn(ACC_INTERFACE | ACC_ABSTRACT, BUS_GROUP, null, cw -> {
                cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "DEFAULT", "L" + BUS_GROUP + ";", null, null);
                cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "register",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Object;)Ljava/util/Collection;", null, null);
                cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "unregister", "(Ljava/util/Collection;)V", null, null);
            }));
            classes.put(SUBSCRIBE, subscribeAnnotation());
            // Listener classes in the mod's own package, which the bridge's lookup cannot unreflect privately.
            classes.put(LONE, listenerClass(LONE, ACC_PUBLIC, "java/lang/Object", new Listener("onOwn", ACC_PUBLIC, 64)));
            classes.put(PRIVATE_ONE, listenerClass(PRIVATE_ONE, ACC_PUBLIC, "java/lang/Object",
                    new Listener("onOwn", ACC_PRIVATE, -127)));
            classes.put(PAIR, listenerClass(PAIR, ACC_PUBLIC, "java/lang/Object",
                    new Listener("onFirst", ACC_PUBLIC, 0), new Listener("onSecond", ACC_PUBLIC, 64)));
            classes.put(HIDDEN, listenerClass(HIDDEN, 0, "java/lang/Object",
                    new Listener("onFirst", ACC_PUBLIC, 0), new Listener("onSecond", ACC_PUBLIC, 64)));
            classes.put(BASE, listenerClass(BASE, ACC_PUBLIC, "java/lang/Object", new Listener("onOwn", ACC_PUBLIC, 0)));
            classes.put(CHILD, listenerClass(CHILD, ACC_PUBLIC, BASE));
            // Public listener classes that also declare or inherit a static handler.
            Listener staticHandler = new Listener("onStatic", ACC_PUBLIC | ACC_STATIC, 127);
            classes.put(MIXED, listenerClass(MIXED, ACC_PUBLIC, "java/lang/Object",
                    new Listener("onFirst", ACC_PUBLIC, 0), new Listener("onSecond", ACC_PUBLIC, 64), staticHandler));
            classes.put(LONE_WITH_STATIC, listenerClass(LONE_WITH_STATIC, ACC_PUBLIC, "java/lang/Object",
                    new Listener("onOwn", ACC_PUBLIC, 64), staticHandler));
            classes.put(STATIC_BASE, listenerClass(STATIC_BASE, ACC_PUBLIC, "java/lang/Object", staticHandler));
            classes.put(STATIC_CHILD, listenerClass(STATIC_CHILD, ACC_PUBLIC, STATIC_BASE, new Listener("onOwn", ACC_PUBLIC, 0)));
            classes.put(EQUAL, equalListener());
            classes.put(TWINS, cancelTwins(TWINS, 0, false));
            classes.put(STATIC_TWINS, cancelTwins(STATIC_TWINS, ACC_PUBLIC, true));
            classes.put(GONE, standIn(0, GONE, null, cw -> {
                MethodVisitor gone = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "onGone", "(Lcom/example/RemovedEvent;)V", null, null);
                gone.visitCode();
                gone.visitInsn(RETURN);
                gone.visitMaxs(0, 0);
                gone.visitEnd();
            }));
            classes.put(LISTENER, standIn(ACC_INTERFACE | ACC_ABSTRACT, LISTENER, null, cw -> {}));
            classes.put(EVENT_BUS, standIn(ACC_INTERFACE | ACC_ABSTRACT, EVENT_BUS, null, cw -> {
                cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "addListener", "(B" + L_CONSUMER + ")L" + LISTENER + ";", null, null);
                cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "addListener", "(" + L_CONSUMER + ")L" + LISTENER + ";", null, null);
                cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "post", "(Lnet/minecraftforge/eventbus/internal/Event;)Z", null, null);
                cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "removeListener", "(L" + LISTENER + ";)V", null, null);
            }));
            classes.put(SCREEN_INIT, standIn(ACC_ABSTRACT, SCREEN_INIT, new String[]{"net/minecraftforge/eventbus/internal/Event"},
                    cw -> {}));
            classes.put(SCREEN_INIT + "$Post", eventWithBus(SCREEN_INIT + "$Post", null));
            for (String event : List.of(CANCEL_EVENT, NAME_TAG_EVENT, SCREEN_INIT + "$Pre")) {
                classes.put(event, standIn(ACC_FINAL, event, new String[]{"net/minecraftforge/eventbus/internal/Event"}, cw -> {
                    defaultConstructor(cw);
                    cw.visitField(ACC_PUBLIC | ACC_STATIC, "BUS", "L" + CANCELLABLE_BUS + ";", null, null);
                }));
            }
            classes.put(PRIORITY, standIn(ACC_FINAL, PRIORITY, null, cw -> {
                String[] names = {"HIGHEST", "HIGH", "NORMAL", "LOW", "LOWEST"};
                int[] bits = {127, 64, 0, -64, -127};
                for (int i = 0; i < names.length; i++) {
                    cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, names[i], "B", null, bits[i]);
                }
            }));
            classes.put("com/mojang/logging/LogUtils", logUtils());
            classes.put(FMLJMLC, modLoadingContext());
            // Named only in helper signatures, which reflection resolves.
            classes.put(DR, standIn(0, DR, null, cw -> {}));
            classes.put(HANDLERS, handlers());
            classes.put(TICK, standIn(ACC_INTERFACE | ACC_ABSTRACT, TICK, null, cw -> {}));
            for (String tick : TICKS) {
                classes.put(tick, standIn(ACC_INTERFACE | ACC_ABSTRACT, tick, new String[]{TICK}, cw -> {}));
                for (String half : List.of("$Pre", "$Post")) {
                    classes.put(tick + half, eventWithBus(tick + half, new String[]{tick}));
                }
            }
            classes.put(OWN_BUS_EVENT, eventWithBus(OWN_BUS_EVENT, null));
            classes.put(TYPED_LISTENER, typedListener());
            // The bridge hands old events to Retromod's rebuilt dispatcher, which names the compiled stubs.
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(COMPILED)) {
                for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = COMPILED.relativize(file).toString().replace('\\', '/');
                    classes.putIfAbsent(name.substring(0, name.length() - ".class".length()),
                            java.nio.file.Files.readAllBytes(file));
                }
            }
            // Each event's BUS records what is hooked onto it.
            List<String> withBus = new java.util.ArrayList<>(List.of(OWN_BUS_EVENT));
            for (String tick : TICKS) {
                withBus.add(tick + "$Pre");
                withBus.add(tick + "$Post");
            }
            for (String event : withBus) {
                type(event).getField("BUS").set(null, recordingBus(event));
            }
            type(CANCEL_EVENT).getField("BUS").set(null, cancellableBus());
            type(NAME_TAG_EVENT).getField("BUS").set(null, cancellableBus());
            type(SCREEN_INIT + "$Pre").getField("BUS").set(null, cancellableBus());
            type(SCREEN_INIT + "$Post").getField("BUS").set(null, recordingBus(SCREEN_INIT + "$Post"));
        }

        Object cancelEvent() throws Exception {
            return type(CANCEL_EVENT).getConstructor().newInstance();
        }

        /** Calls this host's copy of LegacyCancel, as a translated mod's rewritten call does. */
        Object cancel(String member, Object... arguments) {
            try {
                for (java.lang.reflect.Method method : type(LegacyCancelBridge.CANCEL).getMethods()) {
                    if (method.getName().equals(member) && method.getParameterCount() == arguments.length) {
                        return method.invoke(null, arguments);
                    }
                }
                throw new AssertionError("LegacyCancel has no " + member);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        /** Each listener added to the cancellable bus: the overload, and the priority when it has one. */
        List<String> cancelHooks() {
            return cancelHooks.stream().map(hook -> hook[0] + (hook.length > 2 ? " at " + hook[1] : "")).toList();
        }

        Object cancelListener(int index) {
            Object[] hook = cancelHooks.get(index);
            return hook[hook.length - 1];
        }

        // Records each addListener by its parameter types, hands back a handle removeListener takes, and answers post with postAnswer.
        private Object cancellableBus() throws ClassNotFoundException {
            java.util.Map<Object, Object[]> byHandle = new java.util.IdentityHashMap<>();
            return java.lang.reflect.Proxy.newProxyInstance(this, new Class<?>[]{type(CANCELLABLE_BUS)}, (proxy, method, args) -> {
                if (method.getName().equals("addListener")) {
                    String overload = java.util.Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                            .collect(java.util.stream.Collectors.joining(",", "addListener(", ")"));
                    Object[] hook = new Object[args.length + 1];
                    hook[0] = overload;
                    System.arraycopy(args, 0, hook, 1, args.length);
                    cancelHooks.add(hook);
                    Object handle = handle(overload);
                    byHandle.put(handle, hook);
                    return handle;
                }
                if (method.getName().equals("removeListener")) {
                    cancelHooks.remove(byHandle.remove(args[0]));
                    return null;
                }
                if (method.getName().equals("post")) {
                    posts++;
                    return postAnswer;
                }
                if (method.getName().equals("toString")) return "cancellable bus";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return method.getName().equals("equals") && proxy == args[0];
            });
        }

        // The test classpath holds the rebuilt classes too, so this loader's own classes come first.
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && classes.containsKey(name.replace('.', '/'))) loaded = findClass(name);
                if (loaded == null) loaded = super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return loadClass(internalName.replace('/', '.'));
        }

        Object priority(String name) throws Exception {
            return type(ForgeEventBusSynthetics.PRI).getField(name).get(null);
        }

        /** Calls one of the bridge's static helpers by name. */
        void invoke(String helper, Object... arguments) throws Exception {
            java.lang.reflect.Method method = java.util.Arrays.stream(type(ForgeEventBusSynthetics.LEB_IMPL).getMethods())
                    .filter(m -> m.getName().equals(helper)).findFirst().orElseThrow(() -> new AssertionError(helper));
            method.invoke(null, arguments);
        }

        /** Each bus the listener was hooked onto, with the priority it got. */
        List<String> hooksOf(Object listener) {
            return hooks.stream().filter(hook -> hook[2] == listener).map(hook -> hook[0] + " at " + hook[1]).toList();
        }

        /** Each listener hooked onto the event's bus, with the priority it got. */
        List<String> hooksOn(String event) {
            return hooks.stream().filter(hook -> hook[0].equals(event)).map(hook -> hook[0] + " at " + hook[1]).toList();
        }

        List<Object> listenersOn(String event) {
            return hooks.stream().filter(hook -> hook[0].equals(event)).map(hook -> hook[2]).toList();
        }

        /** The objects the bridge handed to EventBus 7's {@code BusGroup.register}. */
        final List<Object> groupRegistrations = new java.util.ArrayList<>();

        /** The listener handles the bridge handed back to EventBus 7's {@code BusGroup.unregister}. */
        final List<Object> groupUnregistrations = new java.util.ArrayList<>();

        /** Calls the bridge's {@code register(Object)} on the given bus. */
        void register(Object bus, Object listener) throws Exception {
            type(ForgeEventBusSynthetics.LEB).getMethod("register", Object.class).invoke(bus, listener);
        }

        /** Calls the bridge's {@code unregister(Object)} on the given bus. */
        void unregister(Object bus, Object listener) throws Exception {
            type(ForgeEventBusSynthetics.LEB).getMethod("unregister", Object.class).invoke(bus, listener);
        }

        /** A listener handle as EventBus 7 returns one, named for what it stands for. */
        private Object handle(String name) throws ClassNotFoundException {
            return java.lang.reflect.Proxy.newProxyInstance(this, new Class<?>[]{type(LISTENER)}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) return "listener on " + name;
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return method.getName().equals("equals") && proxy == args[0];
            });
        }

        /** Calls the bridge's {@code register(Object)} on a bus whose group records what it is given. */
        void register(Object listener) throws Exception {
            type(ForgeEventBusSynthetics.LEB).getMethod("register", Object.class).invoke(bus(), listener);
        }

        /** A bridge bus over a group that records what EventBus 7's own registration is given. */
        Object bus() throws Exception {
            Object group = java.lang.reflect.Proxy.newProxyInstance(this, new Class<?>[]{type(BUS_GROUP)}, (proxy, method, args) -> {
                if (method.getName().equals("register")) {
                    groupRegistrations.add(args[1]);
                    return List.of(handle("group registration of " + args[1].getClass().getName()));
                }
                if (method.getName().equals("unregister")) {
                    groupUnregistrations.addAll((java.util.Collection<?>) args[0]);
                    return null;
                }
                if (method.getName().equals("toString")) return "group";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return method.getName().equals("equals") && proxy == args[0];
            });
            return type(ForgeEventBusSynthetics.LEB_IMPL).getMethod("of", type(BUS_GROUP)).invoke(null, group);
        }

        Object listener(String name) throws Exception {
            var constructor = type(name).getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }

        /** One handler of a listener fixture: its name, access, and EventBus 7 priority. */
        record Listener(String name, int access, int priority) {}

        /** A listener class on {@code OwnBusEvent} whose handlers each count a call on {@code Handlers.calls}. */
        private static byte[] listenerClass(String name, int access, String superName, Listener... listeners) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, access | ACC_SUPER, name, null, superName, null);
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            for (Listener listener : listeners) {
                MethodVisitor mv = cw.visitMethod(listener.access(), listener.name(), "(L" + OWN_BUS_EVENT + ";)V", null, null);
                org.objectweb.asm.AnnotationVisitor subscribe = mv.visitAnnotation("L" + SUBSCRIBE + ";", true);
                subscribe.visit("priority", (byte) listener.priority());
                subscribe.visitEnd();
                mv.visitCode();
                mv.visitFieldInsn(GETSTATIC, HANDLERS, "calls", "I");
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IADD);
                mv.visitFieldInsn(PUTSTATIC, HANDLERS, "calls", "I");
                mv.visitInsn(RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
            cw.visitEnd();
            return cw.toByteArray();
        }

        /**
         * The twins Retromod gives a cancelling handler on {@code CancelHostEvent}: a boolean one at 64 that counts one
         * call and cancels, and an (event, boolean) monitor that counts ten.
         */
        private static byte[] cancelTwins(String name, int access, boolean isStatic) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, access | ACC_SUPER, name, null, "java/lang/Object", null);
            defaultConstructor(cw);
            int flags = ACC_PUBLIC | (isStatic ? ACC_STATIC : 0);
            String event = "L" + CANCEL_EVENT + ";";
            MethodVisitor twin = cw.visitMethod(flags, "retromod$cancellable$onClick", "(" + event + ")Z", null, null);
            org.objectweb.asm.AnnotationVisitor subscribe = twin.visitAnnotation("L" + SUBSCRIBE + ";", true);
            subscribe.visit("priority", (byte) 64);
            subscribe.visitEnd();
            twin.visitCode();
            countCalls(twin, 1);
            twin.visitInsn(ICONST_1);
            twin.visitInsn(IRETURN);
            twin.visitMaxs(0, 0);
            twin.visitEnd();
            if (!isStatic) {
                MethodVisitor monitor = cw.visitMethod(flags, "retromod$cancelled$onClick", "(" + event + "Z)V", null, null);
                org.objectweb.asm.AnnotationVisitor monitorSubscribe = monitor.visitAnnotation("L" + SUBSCRIBE + ";", true);
                monitorSubscribe.visit("priority", (byte) -128);
                monitorSubscribe.visitEnd();
                monitor.visitCode();
                countCalls(monitor, 10);
                monitor.visitInsn(RETURN);
                monitor.visitMaxs(0, 0);
                monitor.visitEnd();
            }
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void countCalls(MethodVisitor mv, int calls) {
            mv.visitFieldInsn(GETSTATIC, HANDLERS, "calls", "I");
            mv.visitIntInsn(BIPUSH, calls);
            mv.visitInsn(IADD);
            mv.visitFieldInsn(PUTSTATIC, HANDLERS, "calls", "I");
        }

        /** A lone listener whose instances are all equal: {@code equals} compares classes and {@code hashCode} is 1. */
        private static byte[] equalListener() {
            ClassNode node = node(listenerClass(EQUAL, ACC_PUBLIC, "java/lang/Object", new Listener("onOwn", ACC_PUBLIC, 0)));
            org.objectweb.asm.tree.MethodNode equals = new org.objectweb.asm.tree.MethodNode(ACC_PUBLIC, "equals",
                    "(Ljava/lang/Object;)Z", null, null);
            org.objectweb.asm.Label other = new org.objectweb.asm.Label();
            equals.visitVarInsn(ALOAD, 1);
            equals.visitJumpInsn(IFNULL, other);
            equals.visitVarInsn(ALOAD, 1);
            equals.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
            equals.visitVarInsn(ALOAD, 0);
            equals.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
            equals.visitJumpInsn(IF_ACMPNE, other);
            equals.visitInsn(ICONST_1);
            equals.visitInsn(IRETURN);
            equals.visitLabel(other);
            equals.visitInsn(ICONST_0);
            equals.visitInsn(IRETURN);
            org.objectweb.asm.tree.MethodNode hash = new org.objectweb.asm.tree.MethodNode(ACC_PUBLIC, "hashCode", "()I", null, null);
            hash.visitInsn(ICONST_1);
            hash.visitInsn(IRETURN);
            node.methods.add(equals);
            node.methods.add(hash);
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String a, String b) {
                    return "java/lang/Object";
                }
            };
            node.accept(cw);
            return cw.toByteArray();
        }

        /** EventBus 7's {@code @SubscribeEvent}: visible at run time, with a byte priority defaulting to NORMAL. */
        private static byte[] subscribeAnnotation() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE | ACC_ANNOTATION, SUBSCRIBE, null, "java/lang/Object",
                    new String[]{"java/lang/annotation/Annotation"});
            org.objectweb.asm.AnnotationVisitor retention = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
            retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
            retention.visitEnd();
            MethodVisitor priority = cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "priority", "()B", null, null);
            org.objectweb.asm.AnnotationVisitor defaultValue = priority.visitAnnotationDefault();
            defaultValue.visit(null, (byte) 0);
            defaultValue.visitEnd();
            priority.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private Object recordingBus(String event) throws ClassNotFoundException {
            return java.lang.reflect.Proxy.newProxyInstance(this, new Class<?>[]{type(EVENT_BUS)}, (proxy, method, args) -> {
                if (method.getName().equals("addListener")) {
                    Object handle = handle(event);
                    hooks.add(args.length == 2 ? new Object[]{event, args[0], args[1], handle}
                            : new Object[]{event, "none", args[0], handle});
                    return handle;
                }
                if (method.getName().equals("removeListener")) {
                    hooks.removeIf(hook -> hook[3] == args[0]);
                    return null;
                }
                if (method.getName().equals("toString")) return event + " bus";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return method.getName().equals("equals") && proxy == args[0];
            });
        }

        private static byte[] standIn(int access, String name, String[] interfaces,
                                      java.util.function.Consumer<ClassWriter> members) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC | access, name, null, "java/lang/Object", interfaces);
            members.accept(cw);
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** An event class with a settable static BUS, as Forge 26.2's records have a final one. */
        private static byte[] eventWithBus(String name, String[] interfaces) {
            return standIn(ACC_FINAL, name, interfaces,
                    cw -> cw.visitField(ACC_PUBLIC | ACC_STATIC, "BUS", "L" + EVENT_BUS + ";", null, null));
        }

        /** {@code LogUtils.getLogger()} over SLF4J, as Minecraft's does. */
        private static byte[] logUtils() {
            String slf4j = com.retromod.util.HostLibraryNames.SLF4J_PACKAGE;
            return standIn(0, "com/mojang/logging/LogUtils", null, cw -> {
                MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getLogger", "()L" + slf4j + "/Logger;", null, null);
                mv.visitCode();
                mv.visitLdcInsn("Retromod");
                mv.visitMethodInsn(INVOKESTATIC, slf4j + "/LoggerFactory", "getLogger",
                        "(Ljava/lang/String;)L" + slf4j + "/Logger;", false);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            });
        }

        /** {@code FMLJavaModLoadingContext.get()}, whose mod group is null here, as no test needs one. */
        private static byte[] modLoadingContext() {
            return standIn(0, FMLJMLC, null, cw -> {
                defaultConstructor(cw);
                MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "()L" + FMLJMLC + ";", null, null);
                get.visitCode();
                get.visitTypeInsn(NEW, FMLJMLC);
                get.visitInsn(DUP);
                get.visitMethodInsn(INVOKESPECIAL, FMLJMLC, "<init>", "()V", false);
                get.visitInsn(ARETURN);
                get.visitMaxs(0, 0);
                get.visitEnd();
                MethodVisitor group = cw.visitMethod(ACC_PUBLIC, "getModBusGroup", "()L" + BUS_GROUP + ";", null, null);
                group.visitCode();
                group.visitInsn(ACONST_NULL);
                group.visitInsn(ARETURN);
                group.visitMaxs(0, 0);
                group.visitEnd();
            });
        }

        /** {@code class Handlers { public static int calls; private static void onOwn(OwnBusEvent e) { calls++; } }}. */
        private static byte[] handlers() {
            return standIn(0, HANDLERS, null, cw -> {
                cw.visitField(ACC_PUBLIC | ACC_STATIC, "calls", "I", null, null);
                MethodVisitor on = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "onOwn", "(L" + OWN_BUS_EVENT + ";)V", null, null);
                on.visitCode();
                on.visitFieldInsn(GETSTATIC, HANDLERS, "calls", "I");
                on.visitInsn(ICONST_1);
                on.visitInsn(IADD);
                on.visitFieldInsn(PUTSTATIC, HANDLERS, "calls", "I");
                on.visitInsn(RETURN);
                on.visitMaxs(0, 0);
                on.visitEnd();
            });
        }

        private static void defaultConstructor(ClassWriter cw) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }

        /** {@code class ServerTickListener implements Consumer<TickEvent.ServerTickEvent>}, which keeps its type argument. */
        private static byte[] typedListener() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, TYPED_LISTENER, "Ljava/lang/Object;Ljava/util/function/Consumer<L" + SERVER_TICK + ";>;",
                    "java/lang/Object", new String[]{"java/util/function/Consumer"});
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor accept = cw.visitMethod(ACC_PUBLIC, "accept", "(Ljava/lang/Object;)V", null, null);
            accept.visitCode();
            accept.visitInsn(RETURN);
            accept.visitMaxs(0, 0);
            accept.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }
}
