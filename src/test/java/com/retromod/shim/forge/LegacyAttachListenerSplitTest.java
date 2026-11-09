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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.retromod.shim.forge.CapabilityHostFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old {@code AttachCapabilitiesEvent<T>} listener hears on Forge 26.2 exactly the holder kinds it heard on 1.20.1. */
class LegacyAttachListenerSplitTest {

    private static final String SUB7 = ForgeSubscribeAnnotations.SUBSCRIBE7;
    private static final String GENERIC = "(L" + ATTACH + "<%s>;)V";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    /** One listener of the fixture class: how it is declared, the note it adds, and the holder it casts to. */
    record Handler(String name, int access, String signature, String priority, String note, String castTo) {}

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The type argument picks the kinds: Entity one, ItemStack one, ? and raw all five, Player none")
    void typeArgumentPicksTheKinds() {
        ClassNode out = node(LegacyAttachListenerSplit.apply(attacher(ACC_PUBLIC,
                handler("onEntity", ACC_PUBLIC, "L" + ENTITY + ";"),
                handler("onStack", ACC_PUBLIC, "L" + ITEM_STACK + ";"),
                handler("onAny", ACC_PUBLIC, "*"),
                new Handler("onRaw", ACC_PUBLIC, null, null, "onRaw", null),
                handler("onPlayer", ACC_PUBLIC, "L" + PLAYER + ";"))));

        Map<String, String> listeners = new TreeMap<>();
        for (MethodNode method : out.methods) {
            if (method.name.startsWith("retromod$")) listeners.put(method.name, method.desc);
        }
        Map<String, String> expected = new TreeMap<>();
        expected.put("retromod$onEntity$Entities", "(L" + ATTACH + "$Entities;)V");
        expected.put("retromod$onStack$ItemStacks", "(L" + ATTACH + "$ItemStacks;)V");
        for (String kind : List.of("Entities", "ItemStacks", "BlockEntities", "Levels", "LevelChunks")) {
            expected.put("retromod$onAny$" + kind, "(L" + ATTACH + "$" + kind + ";)V");
            expected.put("retromod$onRaw$" + kind, "(L" + ATTACH + "$" + kind + ";)V");
        }
        assertEquals(expected, listeners);
        for (String original : List.of("onEntity", "onStack", "onAny", "onRaw", "onPlayer")) {
            MethodNode method = method(out, original);
            assertNull(subscribe(method), original + " keeps no subscription, so no listener lands on the abstract event");
            assertEquals("(L" + ATTACH + ";)V", method.desc, original + " keeps its parameter, so its body still links");
        }
    }

    @Test
    @DisplayName("A bounded wildcard, a type variable or a nested generic class matched no holder on Forge 1.20.1")
    void otherTypeArgumentsMatchNothing() {
        String attach = "L" + ATTACH;
        assertEquals(List.of("Entities"), LegacyAttachListenerSplit.kindsFor("(" + attach + "<L" + ENTITY + ";>;)V"));
        assertEquals(List.of("LevelChunks"),
                LegacyAttachListenerSplit.kindsFor("<T:Ljava/lang/Object;>(" + attach + "<L" + LEVEL_CHUNK + ";>;)V"));
        assertEquals(5, LegacyAttachListenerSplit.kindsFor("(" + attach + ";)V").size(), "raw");
        assertEquals(5, LegacyAttachListenerSplit.kindsFor(null).size(), "no signature at all is raw");
        assertEquals(List.of(), LegacyAttachListenerSplit.kindsFor("(" + attach + "<+L" + ENTITY + ";>;)V"));
        assertEquals(List.of(), LegacyAttachListenerSplit.kindsFor("<T:Ljava/lang/Object;>(" + attach + "<TT;>;)V"));
        assertEquals(List.of(), LegacyAttachListenerSplit.kindsFor("(" + attach + "<Lmod/Outer<*>.Inner;>;)V"));
        assertEquals(List.of(), LegacyAttachListenerSplit.kindsFor("(" + attach + "<[L" + ENTITY + ";>;)V"));
    }

    @Test
    @DisplayName("Static stays static, private goes through INVOKESPECIAL, an interface through INVOKEINTERFACE, and the class verifies")
    void dispatchRulesAndVerification() throws Exception {
        byte[] cls = attacher(ACC_PUBLIC,
                handler("onStatic", ACC_PUBLIC | ACC_STATIC, "L" + ENTITY + ";"),
                handler("onPrivate", ACC_PRIVATE, "L" + ENTITY + ";"),
                handler("onPublic", ACC_PUBLIC, "L" + ENTITY + ";"));
        ClassNode out = node(LegacyAttachListenerSplit.apply(cls));
        assertCall(out, "retromod$onStatic$Entities", INVOKESTATIC, true);
        assertCall(out, "retromod$onPrivate$Entities", INVOKESPECIAL, false);
        assertCall(out, "retromod$onPublic$Entities", INVOKEVIRTUAL, false);
        assertEquals(ACC_PRIVATE, method(out, "retromod$onPrivate$Entities").access);
        EventBus7Host host = new EventBus7Host();
        defineAttachEvents(host);
        verify(LegacyAttachListenerSplit.apply(cls), host);

        ClassNode itf = node(LegacyAttachListenerSplit.apply(attacher(ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE,
                handler("onDefault", ACC_PUBLIC, "L" + ENTITY + ";"))));
        MethodInsnNode call = onlyCall(method(itf, "retromod$onDefault$Entities"));
        assertEquals(INVOKEINTERFACE, call.getOpcode());
        assertTrue(call.itf);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Through the transform, an old HIGH priority becomes EventBus 7's byte and receiveCanceled goes")
    void transformConvertsTheOldAnnotation(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        byte[] in = attacher(ACC_PUBLIC, new Handler("onEntity", ACC_PUBLIC, "L" + ENTITY + ";", "HIGH", "entity", null));
        ClassNode out = node(transformer.transformClass(in, "mod/Attacher.class"));

        AnnotationNode annotation = subscribe(method(out, "retromod$onEntity$Entities"));
        assertNotNull(annotation, "the listener carries EventBus 7's annotation");
        assertEquals(SUB7, annotation.desc);
        assertEquals(List.of("priority", (byte) 64), annotation.values, "Priority.HIGH, with receiveCanceled dropped");
        assertNull(subscribe(method(out, "onEntity")));
    }

    @Test
    @DisplayName("No split on NeoForge or on a Forge host whose attach event is still generic")
    void gatedByHost() {
        byte[] in = attacher(ACC_PUBLIC, handler("onEntity", ACC_PUBLIC, "L" + ENTITY + ";"));

        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertTrue(names(node(transformer.transformClass(in, "mod/Attacher.class"))).stream()
                .noneMatch(name -> name.startsWith("retromod$")), "NeoForge keeps its own capability bridge");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        LegacyCapabilityEventBridge.register(transformer, name -> name.equals(ForgeHostShape.ANCHOR) ? new ClassNode() : null);
        assertTrue(names(node(transformer.transformClass(in, "mod/Attacher.class"))).stream()
                .noneMatch(name -> name.startsWith("retromod$")), "a host without the split kinds keeps the old event");
    }

    @Test
    @DisplayName("An item stack's attach event never reaches an Entity listener, which casts getObject() to Entity")
    void eachKindReachesOnlyItsListeners() throws Exception {
        EventBus7Host host = new EventBus7Host();
        defineAttachEvents(host);
        ClassLoader mod = mod(host, transformer, "", Map.of("mod/Attacher", attacher(ACC_PUBLIC,
                new Handler("onEntity", ACC_PUBLIC, "L" + ENTITY + ";", "HIGH", "entity", ENTITY),
                new Handler("onStack", ACC_PUBLIC, "L" + ITEM_STACK + ";", "HIGHEST", "stack", ITEM_STACK),
                new Handler("onAny", ACC_PUBLIC | ACC_STATIC, "*", null, "any", null),
                new Handler("onRaw", ACC_PRIVATE, null, "LOW", "raw", null),
                new Handler("onPlayer", ACC_PUBLIC, "L" + PLAYER + ";", null, "player", PLAYER))));
        Object attacher = mod.loadClass("mod.Attacher").getConstructor().newInstance();
        subscribeAsEventBus7(attacher);

        post(busOf(host, "Entities"), attachEvent(host, "Entities"));
        assertEquals(List.of("entity", "any", "raw"), notes(mod));
        notes(mod).clear();
        post(busOf(host, "ItemStacks"), attachEvent(host, "ItemStacks"));
        assertEquals(List.of("stack", "any", "raw"), notes(mod));
        notes(mod).clear();
        post(busOf(host, "Levels"), attachEvent(host, "Levels"));
        assertEquals(List.of("any", "raw"), notes(mod), "a Player listener heard nothing on Forge 1.20.1 either");
    }

    // ---- fixture ----

    private static Handler handler(String name, int access, String typeArgument) {
        return new Handler(name, access, typeArgument, null, name, null);
    }

    /** {@code mod/Attacher} with {@code public static final List NOTES} and one old subscribed listener per handler. */
    static byte[] attacher(int classAccess, Handler... handlers) {
        boolean isInterface = (classAccess & ACC_INTERFACE) != 0;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, classAccess | (isInterface ? 0 : ACC_SUPER), "mod/Attacher", null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "NOTES", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/ArrayList");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, "mod/Attacher", "NOTES", "Ljava/util/List;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        if (!isInterface) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        for (Handler handler : handlers) {
            String signature = handler.signature() == null ? null : String.format(GENERIC, handler.signature());
            MethodVisitor mv = cw.visitMethod(handler.access(), handler.name(), "(L" + ATTACH + ";)V", signature, null);
            CapabilityHostFixtures.oldSubscribe(mv, handler.priority(), handler.priority() != null);
            mv.visitCode();
            int event = (handler.access() & ACC_STATIC) != 0 ? 0 : 1;
            // A listener that expects one holder casts the object, as comforts' entity listener does.
            if (handler.castTo() != null) {
                mv.visitVarInsn(ALOAD, event);
                mv.visitMethodInsn(INVOKEVIRTUAL, ATTACH, "getObject", "()Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, handler.castTo());
                mv.visitInsn(POP);
            }
            mv.visitFieldInsn(GETSTATIC, "mod/Attacher", "NOTES", "Ljava/util/List;");
            mv.visitLdcInsn(handler.note());
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static List<String> notes(ClassLoader mod) throws Exception {
        return (List<String>) mod.loadClass("mod.Attacher").getField("NOTES").get(null);
    }

    private static void post(Object bus, Object event) throws Exception {
        for (Method method : bus.getClass().getMethods()) {
            if (method.getName().equals("post") && method.getParameterCount() == 1) {
                method.invoke(bus, event);
                return;
            }
        }
        fail("the stand-in bus has no post");
    }

    private static void assertCall(ClassNode out, String listener, int opcode, boolean isStatic) {
        MethodNode method = method(out, listener);
        assertEquals(isStatic, (method.access & ACC_STATIC) != 0, listener + " static");
        assertEquals(opcode, onlyCall(method).getOpcode(), listener);
    }

    private static MethodInsnNode onlyCall(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(1, calls.size(), method.name + " calls the original once");
        return calls.get(0);
    }

    private static AnnotationNode subscribe(MethodNode method) {
        if (method.visibleAnnotations == null) return null;
        return method.visibleAnnotations.stream()
                .filter(a -> a.desc.equals(SUB7) || a.desc.equals(OLD_SUBSCRIBE)).findFirst().orElse(null);
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " is missing; methods: " + names(node)));
    }

    private static List<String> names(ClassNode node) {
        return node.methods.stream().map(m -> m.name).toList();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void verify(byte[] bytes, ClassLoader host) {
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), host, false, new PrintWriter(problems));
        assertEquals("", problems.toString(), "the split class verifies");
    }
}
