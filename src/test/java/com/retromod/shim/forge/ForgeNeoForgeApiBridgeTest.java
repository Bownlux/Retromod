/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.embedded.LegacyEventCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The Forge 1.20.1 API surface NeoForge kept under other names (#306, #308). */
class ForgeNeoForgeApiBridgeTest {

    private RetromodTransformer transformer;

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
    void movesOnlyOntoClassesTheHostHas() {
        Set<String> host = Set.of("net/neoforged/neoforge/client/extensions/common/IClientItemExtensions");
        int moved = ForgeNeoForgeApiBridge.registerMoves(transformer, host::contains);
        assertEquals(1, moved);
        assertEquals("net/neoforged/neoforge/client/extensions/common/IClientItemExtensions",
                transformer.getClassRedirects().get("net/minecraftforge/client/extensions/common/IClientItemExtensions"));
        assertNull(transformer.getClassRedirects().get("net/minecraftforge/fluids/FluidType"),
                "a NeoForge class the host lacks must not become a redirect target");
    }

    @Test
    void callsOnForgesLazyInterfaceBecomeCallsOnNeoForgesLazyClass() {
        String forgeLazy = "net/minecraftforge/common/util/Lazy";
        String neoLazy = "net/neoforged/neoforge/common/util/Lazy";
        ForgeNeoForgeApiBridge.registerMoves(transformer, neoLazy::equals);
        ForgeNeoForgeApiBridge.registerClassOwners(transformer, neoLazy::equals);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/LazyUser", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use",
                "(Ljava/util/function/Supplier;)Ljava/lang/Object;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, forgeLazy, "of",
                "(Ljava/util/function/Supplier;)L" + forgeLazy + ";", true);
        m.visitMethodInsn(INVOKEINTERFACE, forgeLazy, "get", "()Ljava/lang/Object;", true);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/LazyUser.class"))
                .accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.stream().filter(mn -> mn.name.equals("use"))
                .findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(2, calls.size());
        for (MethodInsnNode call : calls) {
            assertEquals(neoLazy, call.owner);
            assertFalse(call.itf, "NeoForge's Lazy is a class: " + call.name + " needs a Methodref");
        }
        assertEquals(INVOKEVIRTUAL, calls.get(1).getOpcode(), "get() on a class is a virtual call");
    }

    @Test
    void everyForgeNameIsMovedOnce() {
        Set<String> seen = new HashSet<>();
        for (String[] move : ForgeNeoForgeApiBridge.MOVES) {
            assertTrue(seen.add(move[0]), "listed twice: " + move[0]);
        }
    }

    @Test
    void eventBusSubscriberBecomesTheTopLevelAnnotation() {
        ForgeNeoForgeApiBridge.registerEventBusSubscriber(transformer);
        assertEquals("net/neoforged/fml/common/EventBusSubscriber",
                transformer.getClassRedirects().get("net/minecraftforge/fml/common/Mod$EventBusSubscriber"),
                "without this NeoForge never registers the class's listeners");
    }

    @Test
    void cancelCallsOnUncancellableEventsGoToTheHelper() {
        String damage = "net/neoforged/neoforge/event/entity/living/LivingDamageEvent";
        byte[] out = LegacyEventCancelAdapter.apply(cancelCaller(damage));
        MethodInsnNode call = firstCall(out);
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyEventCancelAdapter.HELPER, call.owner);
        assertEquals("(Ljava/lang/Object;Z)V", call.desc);
    }

    @Test
    void cancelCallsOnModClassesAreLeftAlone() {
        byte[] in = cancelCaller("test/mod/MyThing");
        assertSame(in, LegacyEventCancelAdapter.apply(in));
    }

    @Test
    void postReportsAnUncancellableEventAsNotCancelled() {
        RecordingBus bus = new RecordingBus();
        Object event = new Object();
        assertFalse(LegacyEventCalls.post(bus, event));
        assertEquals(List.of(event), bus.posted, "the event must still be delivered");
        assertFalse(LegacyEventCalls.isCancelable(event));
        LegacyEventCalls.setCanceled(event, true); // must not throw for an uncancellable event
    }

    @Test
    void livingTickListenerSkipsEntitiesThatAreNotLiving() {
        String tick = "net/neoforged/neoforge/event/tick/EntityTickEvent$Pre";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Ticks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "onTick", "(L" + tick + ";)V", null, null);
        m.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, tick, "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;", false);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(LegacyLivingTickAdapter.apply(cw.toByteArray())).accept(node, 0);
        List<String> calls = new ArrayList<>();
        boolean guarded = false;
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.name + call.desc);
            if (insn.getOpcode() == INSTANCEOF) guarded = true;
        }
        assertTrue(guarded, "Forge only delivered this event for living entities");
        assertTrue(calls.stream().allMatch(c -> c.equals("getEntity()Lnet/minecraft/world/entity/Entity;")),
                "NeoForge's getter returns Entity: " + calls);
    }

    @Test
    void architecturyBusRegistrationMovesToTheNeoForgeHooks() {
        Set<String> host = Set.of("dev/architectury/platform/hooks/EventBusesHooks");
        ForgeNeoForgeApiBridge.registerArchitecturyEventBuses(transformer, host::contains);
        assertEquals("dev/architectury/platform/hooks/EventBusesHooks",
                transformer.getClassRedirects().get("dev/architectury/platform/forge/EventBuses"));
    }

    @Test
    void registeringAListenerWithoutHandlersIsANoOpAsOnForge() {
        RejectingBus bus = new RejectingBus("class Main has no @SubscribeEvent methods, but register was called anyway.");
        LegacyEventCalls.register(bus, new Object());
        assertEquals(1, bus.attempts, "NeoForge is still asked first");
    }

    @Test
    void otherRegistrationErrorsStillSurface() {
        RejectingBus bus = new RejectingBus("something else went wrong");
        assertThrows(IllegalArgumentException.class, () -> LegacyEventCalls.register(bus, new Object()));
    }

    @Test
    void handlersLiveBeforeARejectedOneAreDroppedBeforeTheFallback() {
        PartlyRegisteringBus bus = new PartlyRegisteringBus();
        Object listener = new Object();
        LegacyEventCalls.register(bus, listener);
        assertEquals(List.of(listener), bus.unregistered,
                "NeoForge left earlier handlers registered; without unregister each would fire twice");
    }

    /** Throws the way NeoForge does after it has already registered some of the target's handlers. */
    public static final class PartlyRegisteringBus {
        final List<Object> unregistered = new ArrayList<>();

        public void register(Object target) {
            throw new IllegalArgumentException("Expected @SubscribeEvent method x to NOT be static");
        }

        public void unregister(Object target) {
            unregistered.add(target);
        }

        public void addListener(Object priority, boolean receiveCanceled, Class<?> type, Consumer<Object> listener) {
        }
    }

    @Test
    void lambdaTickListenersAreGuardedToo() {
        String tick = "net/neoforged/neoforge/event/tick/EntityTickEvent$Pre";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/LambdaTicks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$new$0",
                "(L" + tick + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, tick, "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;", false);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(LegacyLivingTickAdapter.apply(cw.toByteArray())).accept(node, 0);
        boolean guarded = false;
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn.getOpcode() == INSTANCEOF) guarded = true;
        }
        assertTrue(guarded, "an addListener lambda gets every entity on NeoForge, so it needs the living guard");
    }

    @Test
    void itemsTheModAlreadyRegisteredAreNotRegisteredTwice() throws Exception {
        FakeRegisterEvent event = new FakeRegisterEvent();
        LegacyItem item = new LegacyItem();
        event.alreadyRegistered.add(item);
        Method register = ClientExtensionsBridge.findArrayRegister(FakeRegisterEvent.class, "registerItem");
        assertFalse(ClientExtensionsBridge.routeOne(event, register, item));
        assertTrue(event.registered.isEmpty(), "NeoForge rejects a second registration for the same item");
    }

    public static final class RejectingBus {
        private final String message;
        int attempts;

        RejectingBus(String message) {
            this.message = message;
        }

        public void register(Object target) {
            attempts++;
            throw new IllegalArgumentException(message);
        }
    }

    @Test
    void clientExtensionHooksAreRoutedToTheRegisterEvent() throws Exception {
        FakeRegisterEvent event = new FakeRegisterEvent();
        Method register = ClientExtensionsBridge.findArrayRegister(FakeRegisterEvent.class, "registerItem");
        assertNotNull(register);
        assertTrue(ClientExtensionsBridge.routeOne(event, register, new LegacyItem()));
        assertEquals(List.of("extensions for item"), event.registered);
        assertFalse(ClientExtensionsBridge.routeOne(event, register, "plain item"),
                "an object without the Forge hook has nothing to route");
    }

    public static final class RecordingBus {
        final List<Object> posted = new ArrayList<>();

        public Object post(Object event) {
            posted.add(event);
            return event;
        }
    }

    public static final class FakeRegisterEvent {
        final List<Object> registered = new ArrayList<>();
        final List<Object> alreadyRegistered = new ArrayList<>();

        public boolean isItemRegistered(Object item) {
            return alreadyRegistered.contains(item);
        }

        public void registerItem(Object extensions, Object... items) {
            registered.add(extensions + " for " + items[0]);
        }
    }

    public static final class LegacyItem {
        public void initializeClient(Consumer<Object> consumer) {
            consumer.accept("extensions");
        }

        @Override
        public String toString() {
            return "item";
        }
    }

    private static byte[] cancelCaller(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Listener", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "onDamage", "(L" + owner + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, "setCanceled", "(Z)V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode firstCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call");
    }
}
