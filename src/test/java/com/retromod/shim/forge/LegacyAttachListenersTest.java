/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.forge.CapabilityHostFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** {@code addGenericListener(Entity.class, ...)} and {@code getGenericType()} on Forge 26.2's per-holder attach events. */
class LegacyAttachListenersTest {

    private static final String MOD = "mod/GenericAttacher";
    private static final String L_BUS = "L" + OLD_BUS + ";";
    private static final String L_PRIORITY = OLD_PRIORITY;
    private static final String CONSUMER = "Ljava/util/function/Consumer;";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("All four addGenericListener forms reach the bridge, and each listener lands on its holder's BUS at its priority")
    void everyFormLandsOnTheHoldersBus(String host) throws Exception {
        TestMod mod = load(host);
        assertTrue(mod.transformed().methods.stream().allMatch(m -> calls(m).stream().noneMatch(c -> c.owner.equals(OLD_BUS))),
                "no call names EventBus 6's IEventBus any more");
        for (String form : List.of("plain", "withPriority", "withCancel", "typed")) {
            mod.call(form, mod.gameBus());
        }

        assertEquals(List.of((byte) 64, (byte) 0), EventBus7Host.priorities(busOf(mod.host(), "Entities")),
                "HIGH and the default NORMAL, as EventBus 7 bytes");
        assertEquals(List.of((byte) -64), EventBus7Host.priorities(busOf(mod.host(), "ItemStacks")));
        assertEquals(List.of((byte) 127), EventBus7Host.priorities(busOf(mod.host(), "BlockEntities")));
        assertEquals(List.of(), EventBus7Host.priorities(busOf(mod.host(), "Levels")));

        post(busOf(mod.host(), "Entities"), attachEvent(mod.host(), "Entities"));
        post(busOf(mod.host(), "ItemStacks"), attachEvent(mod.host(), "ItemStacks"));
        assertEquals(List.of("entity-high", "entity", "stack"), mod.notes());
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A listener on a mod bus, or for a holder Forge never attached to, adds nothing")
    void modBusAndOtherHoldersAddNothing(String host) throws Exception {
        TestMod mod = load(host);
        mod.call("plain", EventBus7Host.modBus(mod.loader(), mod.host().modGroup("attachmod")));
        mod.call("player", mod.gameBus());

        for (String kind : KINDS.keySet()) {
            assertEquals(List.of(), EventBus7Host.priorities(busOf(mod.host(), kind)), kind);
        }
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("unregister(listener) removes a generic attach listener again")
    void unregisterRemovesIt(String host) throws Exception {
        TestMod mod = load(host);
        Class<?> leb = mod.loader().loadClass(ForgeEventBusSynthetics.LEB.replace('/', '.'));
        Class<?> priority = mod.loader().loadClass(ForgeEventBusSynthetics.PRI.replace('/', '.'));
        List<String> notes = mod.notes();
        Consumer<Object> listener = event -> notes.add("level");
        leb.getMethod("addGenericListener", Class.class, priority, boolean.class, Class.class, Consumer.class)
                .invoke(mod.gameBus(), mod.host().type(LEVEL), priority.getField("NORMAL").get(null), false,
                        mod.host().type(ATTACH), listener);
        assertEquals(List.of((byte) 0), EventBus7Host.priorities(busOf(mod.host(), "Levels")));

        EventBus7Host.unregister(mod.gameBus(), listener);
        assertEquals(List.of(), EventBus7Host.priorities(busOf(mod.host(), "Levels")));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("getGenericType() answers the holder class, Entity for an entity's attach event")
    void genericTypeNamesTheHolder(String host) throws Exception {
        TestMod mod = load(host);
        MethodNode read = mod.transformed().methods.stream().filter(m -> m.name.equals("genericType")).findFirst().orElseThrow();
        assertTrue(calls(read).stream().anyMatch(c -> c.getOpcode() == INVOKESTATIC
                && c.owner.equals(LegacyCapabilityEventBridge.ATTACH_LISTENERS) && c.name.equals("getGenericType")),
                "the removed member is read through the helper");

        Class<?> attach = mod.host().type(ATTACH);
        Object type = mod.loader().loadClass("mod.GenericAttacher").getMethod("genericType", attach)
                .invoke(null, attachEvent(mod.host(), "Entities"));
        assertSame(mod.host().type(ENTITY), type);
        assertSame(mod.host().type(LEVEL_CHUNK), mod.loader().loadClass("mod.GenericAttacher")
                .getMethod("genericType", attach).invoke(null, attachEvent(mod.host(), "LevelChunks")));
    }

    // ---- the translated mod ----

    private record TestMod(EventBus7Host host, ClassLoader loader, ClassNode transformed) {
        Object gameBus() throws Exception {
            return EventBus7Host.gameBus(loader);
        }

        void call(String form, Object bus) throws Exception {
            Class<?> mod = loader.loadClass("mod.GenericAttacher");
            try {
                mod.getMethod(form, EventBus7Host.busInterface(bus)).invoke(null, bus);
            } catch (InvocationTargetException failure) {
                throw new AssertionError(form + " threw", failure.getCause());
            }
        }

        @SuppressWarnings("unchecked")
        List<String> notes() throws Exception {
            return (List<String>) loader.loadClass("mod.GenericAttacher").getField("NOTES").get(null);
        }
    }

    private TestMod load(String target) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = target;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        EventBus7Host host = new EventBus7Host();
        defineAttachEvents(host);
        byte[] original = genericAttacher();
        ClassNode transformed = new ClassNode();
        new ClassReader(transformer.transformClass(original, MOD + ".class")).accept(transformed, 0);
        return new TestMod(host, mod(host, transformer, "", Map.of(MOD, original)), transformed);
    }

    private static void post(Object bus, Object event) throws Exception {
        for (java.lang.reflect.Method method : bus.getClass().getMethods()) {
            if (method.getName().equals("post") && method.getParameterCount() == 1) {
                method.invoke(bus, event);
                return;
            }
        }
        fail("the stand-in bus has no post");
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    // ---- fixture ----

    /** A Forge 1.20.1 class that adds generic attach listeners in each of IEventBus's four forms, and reads getGenericType. */
    private static byte[] genericAttacher() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "NOTES", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/ArrayList");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, MOD, "NOTES", "Ljava/util/List;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        // bus.addGenericListener(Entity.class, GenericAttacher::onEntity)
        adder(cw, "plain", ENTITY, null, null, false, "onEntity", "(Ljava/lang/Class;" + CONSUMER + ")V");
        adder(cw, "withPriority", ENTITY, "HIGH", null, false, "onEntityHigh",
                "(Ljava/lang/Class;" + L_PRIORITY + CONSUMER + ")V");
        adder(cw, "withCancel", ITEM_STACK, "LOW", true, false, "onStack",
                "(Ljava/lang/Class;" + L_PRIORITY + "Z" + CONSUMER + ")V");
        adder(cw, "typed", BLOCK_ENTITY, "HIGHEST", false, true, "onBlockEntity",
                "(Ljava/lang/Class;" + L_PRIORITY + "ZLjava/lang/Class;" + CONSUMER + ")V");
        adder(cw, "player", PLAYER, null, null, false, "onPlayer", "(Ljava/lang/Class;" + CONSUMER + ")V");
        for (String[] listener : new String[][]{{"onEntity", "entity"}, {"onEntityHigh", "entity-high"},
                {"onStack", "stack"}, {"onBlockEntity", "block-entity"}, {"onPlayer", "player"}}) {
            note(cw, listener[0], listener[1]);
        }

        // static Type genericType(AttachCapabilitiesEvent event) { return event.getGenericType(); }
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "genericType",
                "(L" + ATTACH + ";)Ljava/lang/reflect/Type;", null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitMethodInsn(INVOKEVIRTUAL, ATTACH, "getGenericType", "()Ljava/lang/reflect/Type;", false);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // static void name(IEventBus bus) { bus.addGenericListener(Holder.class, [priority,] [receiveCanceled,] [type,] lambda); }
    private static void adder(ClassWriter cw, String name, String holder, String priority, Boolean receiveCanceled,
            boolean typed, String listener, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + L_BUS + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(holder));
        if (priority != null) {
            mv.visitFieldInsn(GETSTATIC, OLD_PRIORITY.substring(1, OLD_PRIORITY.length() - 1), priority, L_PRIORITY);
        }
        if (receiveCanceled != null) mv.visitInsn(receiveCanceled ? ICONST_1 : ICONST_0);
        if (typed) mv.visitLdcInsn(Type.getObjectType(ATTACH));
        mv.visitInvokeDynamicInsn("accept", "()" + CONSUMER,
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType("(Ljava/lang/Object;)V"),
                new Handle(H_INVOKESTATIC, MOD, listener, "(L" + ATTACH + ";)V", false),
                Type.getMethodType("(L" + ATTACH + ";)V"));
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "addGenericListener", desc, true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // private static void name(AttachCapabilitiesEvent event) { NOTES.add(tag); }
    private static void note(ClassWriter cw, String name, String tag) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, name, "(L" + ATTACH + ";)V",
                "(L" + ATTACH + "<*>;)V", null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, MOD, "NOTES", "Ljava/util/List;");
        mv.visitLdcInsn(tag);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
