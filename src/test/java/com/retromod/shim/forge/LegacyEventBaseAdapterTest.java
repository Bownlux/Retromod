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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 removed Forge 1.20.1's Event; an old mod's events and Event-typed code move onto Retromod's rebuilt base. */
class LegacyEventBaseAdapterTest {

    private static final String EVENT = LegacyEventBaseAdapter.EVENT;
    private static final String GENERIC_EVENT = LegacyEventBaseAdapter.GENERIC_EVENT;
    private static final String LEGACY_EVENT = LegacyEventBaseAdapter.LEGACY_EVENT;
    private static final String LEGACY_GENERIC_EVENT = LegacyEventBaseAdapter.LEGACY_GENERIC_EVENT;
    private static final String ACCESS = LegacyEventBaseAdapter.ACCESS;
    private static final String L_EVENT = "L" + EVENT + ";";
    private static final String CANCELABLE = "Lnet/minecraftforge/eventbus/api/Cancelable;";
    private static final String HAS_RESULT = "L" + EVENT + "$HasResult;";
    private static final String OLD_RESULT = "L" + EVENT + "$Result;";
    private static final String HOST_RESULT = "Lnet/minecraftforge/common/util/Result;";
    private static final String OLD_BUS = "net/minecraftforge/eventbus/api/IEventBus";
    private static final String INHERITABLE = "net/minecraftforge/eventbus/api/event/InheritableEvent";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        // RetromodForge and --target-loader forge set this before any shim registers.
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
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
    @DisplayName("A custom event that extends Event is rebased onto the rebuilt base, and nothing names the old Event")
    void customEventIsRebased(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        ClassNode out = transform(eventClass("mod/MyEvent", EVENT, null));

        assertEquals(LEGACY_EVENT, out.superName);
        assertTrue(calls(method(out, "<init>")).contains(INVOKESPECIAL + " " + LEGACY_EVENT + ".<init>()V"),
                calls(method(out, "<init>")).toString());
        // The rebuilt base is no parent bus: a mod group refuses any type that is not a mod-bus event.
        assertEquals(List.of(INHERITABLE), out.interfaces, "EventBus 7 passes parent listeners down to its subclasses");
        assertNoOldBase(out);
    }

    @Test
    @DisplayName("A GenericEvent subclass keeps its Class constructor and its type argument")
    void genericEventKeepsItsClassConstructor() {
        ClassNode out = transform(eventClass("mod/RegistryEvent", GENERIC_EVENT,
                "<T:Ljava/lang/Object;>L" + GENERIC_EVENT + "<TT;>;"));

        assertEquals(LEGACY_GENERIC_EVENT, out.superName);
        assertEquals("<T:Ljava/lang/Object;>L" + LEGACY_GENERIC_EVENT + "<TT;>;L" + INHERITABLE + ";", out.signature,
                "the class itself declares InheritableEvent, in its signature too");
        assertTrue(calls(method(out, "<init>")).contains(
                INVOKESPECIAL + " " + LEGACY_GENERIC_EVENT + ".<init>(Ljava/lang/Class;)V"));
        assertNoOldBase(out);
    }

    @Test
    @DisplayName("@Cancelable adds isCancelable returning true unless declared, and @HasResult moves to the rebuilt marker")
    void cancelableAndHasResult() {
        ClassNode cancelable = transform(annotated("mod/CancelableEvent", CANCELABLE, false));
        MethodNode added = method(cancelable, "isCancelable");
        assertEquals("()Z", added.desc);
        assertEquals(ACC_PUBLIC, added.access);
        assertEquals(List.of(ICONST_1, IRETURN), opcodes(added));

        ClassNode declared = transform(annotated("mod/OwnCancelable", CANCELABLE, true));
        assertEquals(1, declared.methods.stream().filter(m -> m.name.equals("isCancelable")).count());
        assertEquals(List.of(ICONST_0, IRETURN), opcodes(method(declared, "isCancelable")), "the mod's own answer stays");

        ClassNode withResult = transform(annotated("mod/ResultEvent", HAS_RESULT, false));
        assertTrue(annotationDescs(withResult).contains("L" + LegacyEventBaseBridge.LEGACY_HAS_RESULT + ";"),
                annotationDescs(withResult).toString());
        assertTrue(withResult.methods.stream().noneMatch(m -> m.name.equals("hasResult")),
                "LegacyEvent.hasResult() reads the marker, so no override is added");
        assertTrue(withResult.innerClasses.stream().noneMatch(inner -> inner.name.startsWith(EVENT + "$")
                || "java/lang/Object".equals(inner.outerName)), "Event.HasResult is no longer a nested class");
    }

    @Test
    @DisplayName("Event-typed helpers and fields become Object-typed, and the bus post takes an Object")
    void eventTypedHelpersBecomeObjectTyped() {
        ClassNode out = transform(poster());

        assertEquals("(Ljava/lang/Object;)Z", method(out, "postAny").desc);
        assertTrue(calls(method(out, "postAny")).contains(INVOKEINTERFACE + " " + ForgeEventBusSynthetics.LEB
                + ".post(Ljava/lang/Object;)Z"), calls(method(out, "postAny")).toString());
        FieldNode last = out.fields.get(0);
        assertEquals("Ljava/lang/Object;", last.desc);
        assertEquals("Ljava/util/List<Ljava/lang/Object;>;", out.fields.get(1).signature,
                "a GenericEvent type argument is erased with the type");
        assertNoOldBase(out);
    }

    @Test
    @DisplayName("ldc Event.class, instanceof, checkcast and Event-owner calls go to LegacyEventAccess")
    void eventOwnerCallsGoToTheAccessHelper() {
        ClassNode out = transform(classifier());

        assertEquals(List.of(INVOKESTATIC + " " + ACCESS + ".eventRoot()Ljava/lang/Class;"), calls(method(out, "root")));
        assertEquals(List.of(INVOKESTATIC + " " + ACCESS + ".isEvent(Ljava/lang/Object;)Z"), calls(method(out, "isEvent")));
        MethodNode cancel = method(out, "cancel");
        assertEquals(List.of(INVOKESTATIC + " " + ACCESS + ".setCanceled(Ljava/lang/Object;Z)V"), calls(cancel));
        assertTrue(types(cancel).isEmpty(), "the cast to Event is dropped: " + types(cancel));
        assertEquals("(Ljava/lang/Object;)" + HOST_RESULT, method(out, "result").desc);
        assertEquals(List.of(INVOKESTATIC + " " + ACCESS + ".getResult(Ljava/lang/Object;)" + HOST_RESULT),
                calls(method(out, "result")));
        assertEquals(List.of(INVOKESTATIC + " " + ACCESS + ".getPhase(Ljava/lang/Object;)L" + ForgeEventBusSynthetics.PRI + ";"),
                calls(method(out, "phase")));
        assertEquals(List.of(INVOKESTATIC + " " + ACCESS + ".getGenericType(Ljava/lang/Object;)Ljava/lang/reflect/Type;"),
                calls(method(out, "genericType")));

        // A method reference such as Event::isCanceled points at the helper too.
        InvokeDynamicInsnNode indy = indy(method(out, "canceledTest"));
        Handle impl = (Handle) indy.bsmArgs[1];
        assertEquals(H_INVOKESTATIC, impl.getTag());
        assertEquals(ACCESS + ".isCanceled(Ljava/lang/Object;)Z", impl.getOwner() + "." + impl.getName() + impl.getDesc());
        assertEquals(Type.getMethodType("(Ljava/lang/Object;)Z"), indy.bsmArgs[2]);
        assertNoOldBase(out);
    }

    @Test
    @DisplayName("A super call from an override reaches the rebuilt base, not the helper, so it cannot recurse")
    void superCallStaysASuperCall() {
        ClassNode out = transform(overrider());

        assertEquals(List.of(INVOKESPECIAL + " " + LEGACY_EVENT + ".setCanceled(Z)V"), calls(method(out, "setCanceled")));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("@SubscribeEvent on a mod's own event becomes @LegacySubscribe with its old values; a Forge event's handler keeps EventBus 7's")
    void modEventHandlersMoveToLegacySubscribe(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        ClassNode out = transform(handlers());

        AnnotationNode own = onlyAnnotation(method(out, "onOwn"));
        assertEquals(LegacyEventBaseAdapter.LEGACY_SUBSCRIBE, own.desc);
        assertEquals(List.of("priority", "HIGH", "receiveCanceled", true), own.values,
                "the old constant name and receiveCanceled, which Retromod's dispatch keeps");
        AnnotationNode forge = onlyAnnotation(method(out, "onForge"));
        assertEquals(ForgeSubscribeAnnotations.SUBSCRIBE7, forge.desc);
        assertEquals(List.of("priority", (byte) 64), forge.values, "EventBus 7 takes the Forge event's handler itself");
        assertEquals(LegacyEventBaseAdapter.LEGACY_SUBSCRIBE, onlyAnnotation(method(out, "onBase")).desc,
                "a handler of the old base itself goes to the dispatcher, which reports that it cannot run");
        assertEquals("(Ljava/lang/Object;)V", method(out, "onBase").desc);
    }

    @Test
    @DisplayName("A @Mixin class that extends Event is left alone, and a mixin's post call still takes an Object")
    void mixinClasses() {
        byte[] mirror = mixin("mod/mixin/AttachMixin", EVENT, false);
        assertSame(mirror, LegacyEventBaseAdapter.apply(mirror));

        byte[] poster = mixin("mod/mixin/PostMixin", "java/lang/Object", true);
        ClassNode out = transform(poster);
        assertEquals("java/lang/Object", out.superName);
        assertTrue(calls(method(out, "fire")).contains(INVOKEINTERFACE + " " + ForgeEventBusSynthetics.LEB
                + ".post(Ljava/lang/Object;)Z"), calls(method(out, "fire")).toString());
    }

    @Test
    @DisplayName("Nothing changes at Forge 1.21.1, on NeoForge, or on a host that still has EventBus 6")
    void otherHostsAreUntouched() {
        byte[] event = annotated("mod/CancelableEvent", CANCELABLE, false);
        byte[] handlers = handlers();

        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertArrayEquals(transformWithout(event), transformWithBridge(event), "Forge 1.21.1");
        assertArrayEquals(transformWithout(handlers), transformWithBridge(handlers), "Forge 1.21.1 handlers");
        assertFalse(transformer.getClassRedirects().containsKey(EVENT + "$Result"));

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        assertArrayEquals(transformWithout(event), transformWithBridge(event), "NeoForge");
        assertArrayEquals(transformWithout(handlers), transformWithBridge(handlers), "NeoForge handlers");
        assertFalse(transformer.getSyntheticClasses().containsKey(LEGACY_EVENT));
        McReflect.setForceNeoForge(false);

        assertFalse(withHostClasses(Set.of(ForgeHostShape.ANCHOR, LegacyEventBaseBridge.BUS_GROUP,
                LegacyEventBaseBridge.RESULT, LegacyEventBaseBridge.HAS_RESULT, EVENT),
                LegacyEventBaseBridge::hostLacksEventBase), "a host that still ships the old Event keeps it");
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertTrue(withHostClasses(Set.of(ForgeHostShape.ANCHOR, LegacyEventBaseBridge.BUS_GROUP,
                LegacyEventBaseBridge.RESULT, LegacyEventBaseBridge.HAS_RESULT),
                LegacyEventBaseBridge::hostLacksEventBase), "the host's own classes decide over the target");
    }

    @Test
    @DisplayName("Two methods that would share a descriptor refuse the class, and a colliding bridge is dropped")
    void descriptorCollisions() {
        byte[] clash = clash();
        assertSame(clash, LegacyEventBaseAdapter.apply(clash), "handle(Event) and handle(Object) cannot both stay");

        ClassNode receiver = node(LegacyEventBaseAdapter.apply(receiver()));
        List<MethodNode> accepts = receiver.methods.stream().filter(m -> m.name.equals("accept")).toList();
        assertEquals(1, accepts.size(), "the Consumer<Event> bridge is now the method itself");
        assertEquals("(Ljava/lang/Object;)V", accepts.get(0).desc);
        assertEquals(0, accepts.get(0).access & ACC_BRIDGE);
        assertTrue(calls(accepts.get(0)).contains(INVOKESTATIC + " mod/Receiver.note(Ljava/lang/Object;)V"));
        assertEquals("Ljava/lang/Object;Ljava/util/function/Consumer<Ljava/lang/Object;>;", receiver.signature);
        check(LegacyEventBaseAdapter.apply(receiver()));
    }

    @Test
    @DisplayName("The generated BiomeLoadingEvent stand-in and its modifier move onto the rebuilt base on Forge 26.2")
    void generatedBiomeLoadingEvent() {
        LegacyForge119Bridge.register(transformer);

        ClassNode event = node(transformer.getSyntheticClasses().get(LegacyForge119Bridge.BIOME_LOADING_STAND_IN));
        assertEquals(LEGACY_EVENT, event.superName);
        assertNoOldBase(event);
        ClassNode modifier = node(transformer.getSyntheticClasses().get(LegacyBiomeLoadingBridge.MODIFIER));
        assertTrue(calls(method(modifier, "modify")).contains(INVOKEINTERFACE + " " + OLD_BUS + ".post(Ljava/lang/Object;)Z"));

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        LegacyForge119Bridge.register(transformer);
        assertEquals(EVENT, node(transformer.getSyntheticClasses().get(LegacyForge119Bridge.BIOME_LOADING_STAND_IN)).superName,
                "EventBus 6 still has the old base");
    }

    @Test
    @DisplayName("A second pass over a translated class changes nothing")
    void secondPassIsIdentity() {
        for (byte[] input : List.of(poster(), classifier(), receiver(), eventClass("mod/MyEvent", EVENT, null),
                annotated("mod/CancelableEvent", CANCELABLE, false))) {
            byte[] once = LegacyEventBaseAdapter.apply(input);
            assertSame(once, LegacyEventBaseAdapter.apply(once));
        }
    }

    // ---- helpers ----

    private ClassNode transform(byte[] input) {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        byte[] out = transformer.transformClass(input, new ClassReader(input).getClassName() + ".class");
        check(out);
        return node(out);
    }

    private byte[] transformWithBridge(byte[] input) {
        transformer.clearRedirectsForTesting();
        ForgeEventBusSynthetics.register(transformer);
        LegacyEventBaseBridge.register(transformer);
        return transformer.transformClass(input, "mod/Input.class");
    }

    private byte[] transformWithout(byte[] input) {
        transformer.clearRedirectsForTesting();
        ForgeEventBusSynthetics.register(transformer);
        return transformer.transformClass(input, "mod/Input.class");
    }

    private static void check(byte[] bytes) {
        new ClassReader(bytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    /** Every class a class file names, so a test can prove the old base is gone. */
    private static void assertNoOldBase(ClassNode node) {
        Set<String> names = new HashSet<>();
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        new ClassReader(writer.toByteArray()).accept(new ClassRemapper(new ClassNode(), new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }), 0);
        assertFalse(names.contains(EVENT), node.name + " still names Event");
        assertFalse(names.contains(GENERIC_EVENT), node.name + " still names GenericEvent");
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static List<String> types(MethodNode method) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type) types.add(type.getOpcode() + " " + type.desc);
        }
        return types;
    }

    private static List<Integer> opcodes(MethodNode method) {
        List<Integer> opcodes = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) opcodes.add(insn.getOpcode());
        }
        return opcodes;
    }

    private static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return indy;
        }
        throw new AssertionError(method.name + " has no invokedynamic");
    }

    private static AnnotationNode onlyAnnotation(MethodNode method) {
        assertEquals(1, method.visibleAnnotations.size(), method.name + " has one annotation");
        return method.visibleAnnotations.get(0);
    }

    private static List<String> annotationDescs(ClassNode node) {
        List<String> descs = new ArrayList<>();
        if (node.visibleAnnotations != null) for (AnnotationNode a : node.visibleAnnotations) descs.add(a.desc);
        return descs;
    }

    /** Runs {@code check} while the context loader serves the named classes, as a running Forge host would. */
    private static boolean withHostClasses(Set<String> names, BooleanSupplier check) {
        ClassLoader host = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String resource) {
                String name = resource.substring(0, resource.length() - ".class".length());
                if (!names.contains(name)) return null;
                ClassWriter cw = new ClassWriter(0);
                cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, name, null, "java/lang/Object", null);
                cw.visitEnd();
                return new ByteArrayInputStream(cw.toByteArray());
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        thread.setContextClassLoader(host);
        try {
            return check.getAsBoolean();
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    // ---- old mod classes, as javac wrote them for Forge 1.20.1 ----

    /** {@code class name extends superName} with a no-argument constructor, or a Class one for GenericEvent. */
    static byte[] eventClass(String name, String superName, String signature) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, signature, superName, null);
        boolean generic = superName.equals(GENERIC_EVENT);
        String desc = generic ? "(Ljava/lang/Class;)V" : "()V";
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        if (generic) init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", desc, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An event marked with {@code annotation}, optionally declaring its own isCancelable returning false. */
    static byte[] annotated(String name, String annotation, boolean ownIsCancelable) {
        ClassNode node = node(eventClass(name, EVENT, null));
        node.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(annotation)));
        if (annotation.equals(HAS_RESULT)) {
            node.visitInnerClass(EVENT + "$HasResult", EVENT, "HasResult", ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT
                    | ACC_INTERFACE | ACC_ANNOTATION);
        }
        if (ownIsCancelable) {
            MethodVisitor own = node.visitMethod(ACC_PUBLIC, "isCancelable", "()Z", null, null);
            own.visitCode();
            own.visitInsn(ICONST_0);
            own.visitInsn(IRETURN);
            own.visitMaxs(1, 1);
            own.visitEnd();
        }
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** Old handlers on a mod's own event, on a Forge event, and on the old base itself. */
    private static byte[] handlers() {
        return LegacyEventFixtures.listener("mod/Handlers",
                LegacyEventFixtures.Handler.of("own", "mod/MyEvent", "HIGH").receivingCanceled(),
                LegacyEventFixtures.Handler.of("forge", "net/minecraftforge/event/server/ServerStartedEvent", "HIGH")
                        .receivingCanceled(),
                LegacyEventFixtures.Handler.of("base", EVENT, null));
    }

    /** A class that stores and posts an old {@code Event} and keeps a list of old generic events. */
    // static Event last; static List<GenericEvent<?>> pending;
    // static boolean postAny(Event e) { last = e; return MinecraftForge.EVENT_BUS.post(e); }
    private static byte[] poster() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Poster", null, "java/lang/Object", null);
        cw.visitField(ACC_STATIC, "last", L_EVENT, null, null).visitEnd();
        FieldVisitor pending = cw.visitField(ACC_STATIC, "pending", "Ljava/util/List;",
                "Ljava/util/List<L" + GENERIC_EVENT + "<*>;>;", null);
        pending.visitEnd();
        MethodVisitor post = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "postAny", "(" + L_EVENT + ")Z", null, null);
        post.visitCode();
        post.visitVarInsn(ALOAD, 0);
        post.visitFieldInsn(PUTSTATIC, "mod/Poster", "last", L_EVENT);
        post.visitFieldInsn(GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + OLD_BUS + ";");
        post.visitVarInsn(ALOAD, 0);
        post.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "post", "(" + L_EVENT + ")Z", true);
        post.visitInsn(IRETURN);
        post.visitMaxs(0, 0);
        post.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Zeta's root class, architectury's instanceof, tacz's cast-and-cancel, and reads through an Event-typed value. */
    private static byte[] classifier() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Classifier", null, "java/lang/Object", null);

        MethodVisitor root = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "root", "()Ljava/lang/Class;", null, null);
        root.visitCode();
        root.visitLdcInsn(Type.getObjectType(EVENT));
        root.visitInsn(ARETURN);
        root.visitMaxs(0, 0);
        root.visitEnd();

        MethodVisitor isEvent = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "isEvent", "(Ljava/lang/Object;)Z", null, null);
        isEvent.visitCode();
        isEvent.visitVarInsn(ALOAD, 0);
        isEvent.visitTypeInsn(INSTANCEOF, EVENT);
        isEvent.visitInsn(IRETURN);
        isEvent.visitMaxs(0, 0);
        isEvent.visitEnd();

        MethodVisitor cancel = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "cancel", "(Ljava/lang/Object;)V", null, null);
        cancel.visitCode();
        cancel.visitVarInsn(ALOAD, 0);
        cancel.visitTypeInsn(CHECKCAST, EVENT);
        cancel.visitInsn(ICONST_1);
        cancel.visitMethodInsn(INVOKEVIRTUAL, EVENT, "setCanceled", "(Z)V", false);
        cancel.visitInsn(RETURN);
        cancel.visitMaxs(0, 0);
        cancel.visitEnd();

        forwardRead(cw, "result", EVENT, "getResult", OLD_RESULT);
        forwardRead(cw, "phase", EVENT, "getPhase", "Lnet/minecraftforge/eventbus/api/EventPriority;");
        forwardRead(cw, "genericType", GENERIC_EVENT, "getGenericType", "Ljava/lang/reflect/Type;");

        // Predicate<Event> test = Event::isCanceled;
        MethodVisitor test = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "canceledTest", "()Ljava/util/function/Predicate;",
                null, null);
        test.visitCode();
        test.visitInvokeDynamicInsn("test", "()Ljava/util/function/Predicate;",
                new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                Type.getMethodType("(Ljava/lang/Object;)Z"),
                new Handle(H_INVOKEVIRTUAL, EVENT, "isCanceled", "()Z", false),
                Type.getMethodType("(" + L_EVENT + ")Z"));
        test.visitInsn(ARETURN);
        test.visitMaxs(0, 0);
        test.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static R name(Owner e) { return e.getter(); }}. */
    private static void forwardRead(ClassWriter cw, String name, String owner, String getter, String returnType) {
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + owner + ";)" + returnType, null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitMethodInsn(INVOKEVIRTUAL, owner, getter, "()" + returnType, false);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
    }

    /** An event overriding setCanceled that calls {@code super.setCanceled}, as Quark and embeddium do. */
    private static byte[] overrider() {
        ClassNode node = node(eventClass("mod/Overrider", EVENT, null));
        MethodVisitor set = node.visitMethod(ACC_PUBLIC, "setCanceled", "(Z)V", null, null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 0);
        set.visitVarInsn(ILOAD, 1);
        set.visitMethodInsn(INVOKESPECIAL, EVENT, "setCanceled", "(Z)V", false);
        set.visitInsn(RETURN);
        set.visitMaxs(2, 2);
        set.visitEnd();
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** A mixin class: one mirroring an event's hierarchy, or one that posts an event built in its handler. */
    private static byte[] mixin(String name, String superName, boolean posts) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, name, null, superName, null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/world/level/Level"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        if (posts) {
            MethodVisitor fire = cw.visitMethod(ACC_PRIVATE, "fire", "(L" + EVENT + ";)V", null, null);
            fire.visitCode();
            fire.visitFieldInsn(GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + OLD_BUS + ";");
            fire.visitVarInsn(ALOAD, 1);
            fire.visitMethodInsn(INVOKEINTERFACE, OLD_BUS, "post", "(" + L_EVENT + ")Z", true);
            fire.visitInsn(POP);
            fire.visitInsn(RETURN);
            fire.visitMaxs(0, 0);
            fire.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code void handle(Event)} beside {@code void handle(Object)}: no rewrite can keep both. */
    private static byte[] clash() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Clash", null, "java/lang/Object", null);
        for (String param : List.of(L_EVENT, "Ljava/lang/Object;")) {
            MethodVisitor handle = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "handle", "(" + param + ")V", null, null);
            handle.visitCode();
            handle.visitInsn(RETURN);
            handle.visitMaxs(0, 0);
            handle.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Registrate's {@code OneTimeEventReceiver}: a {@code Consumer<Event>} with javac's {@code accept(Object)} bridge. */
    private static byte[] receiver() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Receiver",
                "Ljava/lang/Object;Ljava/util/function/Consumer<" + L_EVENT + ">;", "java/lang/Object",
                new String[]{"java/util/function/Consumer"});
        MethodVisitor accept = cw.visitMethod(ACC_PUBLIC, "accept", "(" + L_EVENT + ")V", null, null);
        accept.visitCode();
        accept.visitVarInsn(ALOAD, 1);
        accept.visitMethodInsn(INVOKESTATIC, "mod/Receiver", "note", "(" + L_EVENT + ")V", false);
        accept.visitInsn(RETURN);
        accept.visitMaxs(0, 0);
        accept.visitEnd();
        MethodVisitor bridge = cw.visitMethod(ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, "accept", "(Ljava/lang/Object;)V",
                null, null);
        bridge.visitCode();
        bridge.visitVarInsn(ALOAD, 0);
        bridge.visitVarInsn(ALOAD, 1);
        bridge.visitTypeInsn(CHECKCAST, EVENT);
        bridge.visitMethodInsn(INVOKEVIRTUAL, "mod/Receiver", "accept", "(" + L_EVENT + ")V", false);
        bridge.visitInsn(RETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        MethodVisitor note = cw.visitMethod(ACC_STATIC, "note", "(" + L_EVENT + ")V", null, null);
        note.visitCode();
        note.visitInsn(RETURN);
        note.visitMaxs(0, 0);
        note.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
