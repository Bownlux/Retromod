/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old @SubscribeEvent methods that can cancel a Forge 26.2 or 26.3 event get a boolean twin, which EventBus 7 reads. */
class CancellableListenerSiblingsTest {

    private static final String CANCEL = LegacyCancelBridge.CANCEL;
    private static final String SUBSCRIBE7 = ForgeSubscribeAnnotations.SUBSCRIBE7;
    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String OLD_PRIORITY = "Lnet/minecraftforge/eventbus/api/EventPriority;";
    private static final String INTERACT = "net/minecraftforge/event/entity/player/PlayerInteractEvent";
    private static final String RIGHT_CLICK = INTERACT + "$RightClickBlock";
    private static final String RIGHT_CLICK_EMPTY = INTERACT + "$RightClickEmpty";
    private static final List<String> INTERACT_CHILDREN = List.of(INTERACT + "$EntityInteractSpecific",
            INTERACT + "$LeftClickBlock", RIGHT_CLICK, INTERACT + "$RightClickItem");
    private static final String LIVING_TICK = "net/minecraftforge/event/entity/living/LivingEvent$LivingTickEvent";
    private static final String LOGGED_IN = "net/minecraftforge/event/entity/player/PlayerEvent$PlayerLoggedInEvent";
    private static final String KEY_MAPPING = Forge_26_2_to_26_3.KEY_MAPPING_TRIGGERED;
    private static final String SCREEN_INIT = "net/minecraftforge/client/event/ScreenEvent$Init";
    private static final String OVERLAY = "net/minecraftforge/client/event/RenderBlockScreenEffectEvent";
    private static final String ADVANCEMENT = "net/minecraftforge/event/entity/player/AdvancementEvent";
    private static final String MOUSE_BUTTON = "net/minecraftforge/client/event/InputEvent$MouseButton";
    private static final String SCREEN = "net/minecraftforge/client/event/ScreenEvent";
    private static final String BLOCK = "net/minecraftforge/event/level/BlockEvent";
    private static final String NOTE_BLOCK = "net/minecraftforge/event/level/NoteBlockEvent";
    private static final String INPUT = "net/minecraftforge/client/event/InputEvent";
    private static final String PLAYER = "net/minecraftforge/event/entity/player/PlayerEvent";
    private static final String LIVING = "net/minecraftforge/event/entity/living/LivingEvent";
    private static final String TELEPORT = "net/minecraftforge/event/entity/EntityTeleportEvent";
    private static final String HANDLERS = "mod/Handlers";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    // ---- the twins a class gets ----

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Static and instance HIGH handlers that cancel get a public boolean twin at 64, and the original loses its annotation")
    void cancellingHandlersGetBooleanTwins(String host) throws Exception {
        byte[] transformed = transformOn(host, null, handlers(HANDLERS,
                old("onStatic", RIGHT_CLICK, "HIGH").asStatic().then(Body.CANCEL),
                old("onInstance", RIGHT_CLICK, "HIGH").then(Body.CANCEL)));
        ClassNode out = node(transformed);

        for (String handler : List.of("onStatic", "onInstance")) {
            MethodNode twin = method(out, "retromod$cancellable$" + handler);
            assertEquals("(L" + RIGHT_CLICK + ";)Z", twin.desc);
            assertEquals(ACC_PUBLIC, twin.access & (ACC_PUBLIC | ACC_SYNTHETIC), handler + "'s twin is public and not synthetic");
            assertEquals(handler.equals("onStatic"), (twin.access & ACC_STATIC) != 0);
            assertEquals(List.of("priority", (byte) 64), subscription(twin).values, "the old HIGH as EventBus 7's byte");
            assertNull(subscription(method(out, handler)), handler + " no longer registers itself");
            assertEquals(List.of(CANCEL + ".enter", HANDLERS + "." + handler, CANCEL + ".leave", CANCEL + ".exit"),
                    calls(twin), "enter a frame, call the original, answer the cancel, and leave the frame on a throw");
        }
        verify(transformed);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A handler that only reads its event keeps its own normalized annotation and gets no twin")
    void readingHandlerGetsNoTwin(String host) {
        ClassNode out = node(transformOn(host, null, handlers(HANDLERS,
                old("onTick", LIVING_TICK, "LOW").asStatic().then(Body.GETTER))));

        assertTrue(out.methods.stream().noneMatch(m -> m.name.startsWith("retromod$")), "no twin for a getter-only handler");
        assertEquals(List.of("priority", (byte) -64), subscription(method(out, "onTick")).values);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("receiveCanceled on an event that takes no cancel is dropped and adds no monitor")
    void receiveCanceledOnAPlainEventIsDropped(String host) {
        ClassNode out = node(transformOn(host, null, handlers(HANDLERS,
                old("onLogin", LOGGED_IN, null).asStatic().receivingCanceled().then(Body.CANCEL))));

        assertTrue(out.methods.stream().noneMatch(m -> m.name.startsWith("retromod$")));
        assertEquals(List.of(), values(subscription(method(out, "onLogin"))));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("receiveCanceled on RightClickBlock adds an (event, boolean) monitor twin at MONITOR priority")
    void receiveCanceledAddsAMonitor(String host) throws Exception {
        byte[] transformed = transformOn(host, null, handlers(HANDLERS,
                old("onWatch", RIGHT_CLICK, "LOWEST").receivingCanceled().then(Body.READ_CANCEL)));
        ClassNode out = node(transformed);

        assertEquals(List.of("priority", (byte) -127), subscription(method(out, "retromod$cancellable$onWatch")).values,
                "the boolean twin notes that it ran, so the monitor does not run the handler twice");
        MethodNode monitor = method(out, "retromod$cancelled$onWatch");
        assertEquals("(L" + RIGHT_CLICK + ";Z)V", monitor.desc);
        assertEquals(List.of("priority", (byte) -128), subscription(monitor).values);
        assertEquals(List.of(CANCEL + ".enterCancelled", HANDLERS + ".onWatch", CANCEL + ".exit", CANCEL + ".exit"),
                calls(monitor));
        verify(transformed);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A PlayerInteractEvent handler gets a twin per cancellable child and a parent twin that leaves those children alone")
    void parentHandlerIsSplitPerChild(String host) throws Exception {
        byte[] transformed = transformOn(host, null, handlers(HANDLERS,
                old("onInteract", INTERACT, "HIGH").asStatic().then(Body.CANCEL)));
        ClassNode out = node(transformed);

        for (String child : INTERACT_CHILDREN) {
            MethodNode twin = method(out, "retromod$cancellable$onInteract$" + child.substring(child.lastIndexOf('$') + 1));
            assertEquals("(L" + child + ";)Z", twin.desc);
            assertEquals(List.of("priority", (byte) 64), subscription(twin).values);
        }
        MethodNode parent = method(out, "retromod$plain$onInteract");
        assertEquals("(L" + INTERACT + ";)V", parent.desc);
        assertEquals(INTERACT_CHILDREN, instanceofs(parent), "the parent's twin skips the children that have their own");
        assertEquals(List.of(HANDLERS + ".onInteract"), calls(parent));
        assertNull(subscription(method(out, "onInteract")));
        verify(transformed);
    }

    @ParameterizedTest(name = "a mod built for {0}")
    @ValueSource(strings = {"1.21.11", "26.2"})
    @DisplayName("A mod built for EventBus 7 keeps its listeners as they are")
    void eventBus7ModsAreUntouched(String source) {
        byte[] input = transformOn("26.2", "1.20.1", handlers(HANDLERS,
                old("onStatic", RIGHT_CLICK, "HIGH").asStatic().then(Body.CANCEL)));
        // The class pass on its own: the twin depends only on the mod's source version.
        byte[] unchanged = handlers(HANDLERS, old("onStatic", RIGHT_CLICK, null).asStatic().then(Body.CANCEL));
        byte[] remapped = withNewSubscribe(unchanged);
        assertSame(remapped, CancellableListenerSiblings.apply(remapped, source, name -> null));
        assertTrue(node(input).methods.stream().anyMatch(m -> m.name.startsWith("retromod$")), "a 1.20.1 mod does get one");

        ClassNode out = node(transformOn("26.2", source, handlers(HANDLERS,
                old("onStatic", RIGHT_CLICK, "HIGH").asStatic().then(Body.CANCEL))));
        assertTrue(out.methods.stream().noneMatch(m -> m.name.startsWith("retromod$")));
    }

    @Test
    @DisplayName("Forge 26.3 split the key mapping event: a handler reaches each key's event, and isUseItem answers from its class")
    void keyMappingHandlerReachesEachKeyOn263() throws Exception {
        byte[] handler = handlers(HANDLERS, old("onKey", KEY_MAPPING, "HIGH").asStatic().then(Body.USE_ITEM_CANCEL));

        ClassNode on262 = node(transformOn("26.2", null, handler));
        assertEquals("(L" + KEY_MAPPING + ";)Z", method(on262, "retromod$cancellable$onKey").desc,
                "Forge 26.2 still posts the event itself");
        assertTrue(calls(method(on262, "onKey")).contains(KEY_MAPPING + ".isUseItem"), "and it keeps isUseItem");

        byte[] transformed = transformOn("26.3", null, handler);
        ClassNode on263 = node(transformed);
        for (String key : List.of("Attack", "PickBlock", "UseItem")) {
            assertEquals("(L" + KEY_MAPPING + "$" + key + ";)Z", method(on263, "retromod$cancellable$onKey$" + key).desc);
        }
        assertTrue(on263.methods.stream().noneMatch(m -> m.name.equals("retromod$plain$onKey")), "nothing posts the parent");
        assertTrue(calls(method(on263, "onKey")).contains(Forge_26_2_to_26_3.KEY_MAPPING_TRIGGERS + ".isUseItem"));
        verify(transformed);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A handler on ScreenEvent.Init, which has no bus, reaches Init.Pre and Init.Post even when it cannot cancel")
    void buslessParentHandlerReachesItsChild(String host) throws Exception {
        byte[] transformed = transformOn(host, null, handlers(HANDLERS,
                old("onInit", SCREEN_INIT, null).asStatic().then(Body.NOTE)));
        ClassNode out = node(transformed);

        for (String child : List.of("Pre", "Post")) {
            MethodNode forward = method(out, "retromod$onInit$" + child);
            assertEquals("(L" + SCREEN_INIT + "$" + child + ";)V", forward.desc);
            assertEquals(List.of(), values(subscription(forward)));
            assertEquals(List.of(HANDLERS + ".onInit"), calls(forward), "as Forge 1.20.1 posted both to a parent listener");
        }
        assertNull(subscription(method(out, "onInit")));
        verify(transformed);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A cancelling MobSpawnEvent handler gets a cancel twin on FinalizeSpawn and plain forwards on the plain-bus children")
    void buslessParentHandlerSplitsByChildBus(String host) throws Exception {
        String mobSpawn = "net/minecraftforge/event/entity/living/MobSpawnEvent";
        byte[] transformed = transformOn(host, null, handlers(HANDLERS,
                old("onSpawn", mobSpawn, "HIGH").asStatic().then(Body.CANCEL)));
        ClassNode out = node(transformed);

        assertEquals("(L" + mobSpawn + "$FinalizeSpawn;)Z", method(out, "retromod$cancellable$onSpawn$FinalizeSpawn").desc);
        for (String plain : List.of("AllowDespawn", "PositionCheck")) {
            MethodNode forward = method(out, "retromod$onSpawn$" + plain);
            assertEquals("(L" + mobSpawn + "$" + plain + ";)V", forward.desc, "a plain bus takes no cancel");
            assertEquals(List.of("priority", (byte) 64), subscription(forward).values);
        }
        verify(transformed);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Lootr's lone handler on AdvancementEvent, an interface with no bus, becomes one listener per advancement event, which FML subscribes")
    void interfaceParentHandlerReachesEveryChild(String host) throws Exception {
        byte[] lootr = subscriber(handlers(HANDLERS, old("onAdvancement", ADVANCEMENT, null).asStatic()), "lootr");
        byte[] transformed = transformOn(host, null, lootr);
        ClassNode out = node(transformed);

        for (String child : List.of("AdvancementEarnEvent", "AdvancementProgressEvent")) {
            MethodNode forward = method(out, "retromod$onAdvancement$" + child);
            assertEquals("(L" + ADVANCEMENT + "$" + child + ";)V", forward.desc);
            assertEquals(List.of(HANDLERS + ".onAdvancement"), calls(forward));
        }
        assertNull(subscription(method(out, "onAdvancement")), "nothing is left on the parent, which no bus takes");
        verify(transformed);

        // Two static listeners now, so the class stays with FML on the game bus instead of a companion on the mod's group.
        List<String> companions = new ArrayList<>();
        byte[] kept = ForgeEventBusSynthetics.stripLenientAutoSubscriber(transformed, (name, bytes) -> companions.add(name));
        assertSame(transformed, kept);
        assertEquals(List.of(), companions);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A handler on ScreenEvent gets one listener per screen event below it, named apart where two share Pre")
    void nestedParentsGiveDistinctTwinNames(String host) throws Exception {
        byte[] transformed = transformOn(host, null, handlers(HANDLERS, old("onScreen", SCREEN, null)));
        ClassNode out = node(transformed);
        RetromodVersion.TARGET_MC_VERSION = host;
        java.util.function.Function<String, ClassNode> offline = name -> null;
        int below = LegacyCancelBridge.buslessChildren(SCREEN, offline).size()
                + LegacyCancelBridge.buslessPlainChildren(SCREEN, offline).size();

        List<MethodNode> twins = out.methods.stream().filter(m -> m.name.startsWith("retromod$")).toList();
        assertEquals(below, twins.size(), "one forward per screen event, with no cancel in the body");
        assertEquals(twins.size(), twins.stream().map(m -> m.name).distinct().count(), "every twin has its own name");
        assertEquals("(L" + SCREEN + "$Init$Pre;)V", method(out, "retromod$onScreen$Init$Pre").desc);
        assertEquals("(L" + SCREEN + "$Render$Pre;)V", method(out, "retromod$onScreen$Render$Pre").desc);
        assertEquals("(L" + SCREEN + "$Closing;)V", method(out, "retromod$onScreen$Closing").desc, "a unique last name stays short");
        verify(transformed);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Overloaded handlers on nested parents, as Architectury's event(...) methods are, get twins with names of their own")
    void overloadedHandlersKeepTwinsApart(String host) throws Exception {
        byte[] transformed = transformOn(host, null, handlers(HANDLERS,
                old("onEvent", INPUT, "HIGH").asStatic().then(Body.CANCEL),
                old("onEvent", MOUSE_BUTTON, "HIGH").asStatic().receivingCanceled().then(Body.CANCEL),
                old("onEvent", PLAYER, null).asStatic(),
                old("onEvent", LIVING, null).asStatic()));
        ClassNode out = node(transformed);

        List<String> pairs = out.methods.stream().map(m -> m.name + m.desc).toList();
        assertEquals(pairs.size(), pairs.stream().distinct().count(), "every method has its own name and descriptor");
        // The first handler keeps the plain name; the second, on the same child, also names its own event.
        assertEquals("(L" + MOUSE_BUTTON + "$Pre;)Z", method(out, "retromod$cancellable$onEvent$Pre").desc);
        assertEquals("(L" + MOUSE_BUTTON + "$Pre;)Z", method(out, "retromod$cancellable$onEvent$InputEvent$MouseButton$Pre").desc);
        String arrowLoose = "net/minecraftforge/event/entity/player/ArrowLooseEvent";
        assertEquals("(L" + arrowLoose + ";)V", method(out, "retromod$onEvent$ArrowLooseEvent").desc);
        assertEquals("(L" + arrowLoose + ";)V", method(out, "retromod$onEvent$LivingEvent$ArrowLooseEvent").desc);
        verify(transformed);
        assertDoesNotThrow(() -> define(transformed), "the class loads, where duplicate methods would refuse it");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Tick and attach listeners stay with their own bridges, and a parent the table does not name keeps its listener")
    void parentsOwnedElsewhereAreLeftAlone(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        java.util.function.Function<String, ClassNode> offline = name -> null;
        String tick = LegacyTickEventBridge.TICK;
        String attach = LegacyAttachListenerSplit.ATTACH;
        assertTrue(LegacyCancelBridge.section(host).busless().containsKey(tick + "$ServerTickEvent"), "the table still lists the tick halves");
        for (String owned : List.of(tick, tick + "$ServerTickEvent", attach)) {
            assertFalse(LegacyCancelBridge.splitsBusless(owned), owned + " belongs to another bridge");
            assertFalse(LegacyCancelBridge.buslessTable(host).contains(owned.replace('/', '.') + "="), owned + " is not in the bus bridge's table");
        }
        ClassNode out = node(transformOn(host, null, handlers(HANDLERS, old("onOther", "net/minecraftforge/event/RetromodUnnamedEvent", null))));
        assertTrue(out.methods.stream().noneMatch(m -> m.name.startsWith("retromod$")));
    }

    @Test
    @DisplayName("A Forge 26.2 mod's listeners on the key mapping event move to each key's event on 26.3, keeping their shapes")
    void eventBus7ModListenersMoveOffAParentThatLostItsBus() throws Exception {
        byte[] listeners = eventBus7Listeners();

        assertEquals(withoutTwins(listeners), withoutTwins(transformOn("26.2", "26.2", listeners)),
                "Forge 26.2 still posts the key mapping event");
        assertTrue(node(transformOn("26.3", "26.3", listeners)).methods.stream().noneMatch(m -> m.name.startsWith("retromod$")),
                "a mod built for 26.3 listens on the keys itself");

        byte[] transformed = transformOn("26.3", "26.2", listeners);
        ClassNode out = node(transformed);
        for (String key : List.of("Attack", "PickBlock", "UseItem")) {
            String child = "L" + KEY_MAPPING + "$" + key + ";";
            assertEquals("(" + child + ")V", method(out, "retromod$onKey$" + key).desc);
            assertEquals("(" + child + ")Z", method(out, "retromod$onAnswer$" + key).desc, "a boolean listener still cancels");
            MethodNode monitor = method(out, "retromod$onWatch$" + key);
            assertEquals("(" + child + "Z)V", monitor.desc);
            assertEquals(List.of("priority", (byte) -128), subscription(monitor).values);
        }
        for (String moved : List.of("onKey", "onAnswer", "onWatch")) assertNull(subscription(method(out, moved)));
        assertNotNull(subscription(method(out, "onInit")), "ScreenEvent.Init had no bus on 26.2 either, so its listener stays");
        assertEquals(List.of(Forge_26_2_to_26_3.KEY_MAPPING_TRIGGERS + ".isUseItem"), calls(method(out, "onAnswer")));
        verify(transformed);

        // The forward hands the event and the answer through.
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(KEY_MAPPING, simpleEvent(KEY_MAPPING, ACC_ABSTRACT, "java/lang/Object"));
        for (String key : List.of("Attack", "PickBlock", "UseItem")) {
            classes.put(KEY_MAPPING + "$" + key, simpleEvent(KEY_MAPPING + "$" + key, ACC_FINAL, KEY_MAPPING));
        }
        classes.put(SCREEN_INIT, simpleEvent(SCREEN_INIT, ACC_ABSTRACT, "java/lang/Object"));
        classes.put("mod/Keys", transformed);
        ClassLoader mod = loader(classes);
        Object useItem = mod.loadClass(dotted(KEY_MAPPING + "$UseItem")).getConstructor().newInstance();
        assertEquals(true, invoke(mod.loadClass("mod.Keys"), "retromod$onAnswer$UseItem", useItem));
    }

    @Test
    @DisplayName("A handler on Forge 26.3's RenderBlockScreenEffectEvent stays where it is, since the children lost its getters")
    void overlayHandlerIsNotSplitOn263() {
        ClassNode out = node(transformOn("26.3", null, handlers(HANDLERS,
                old("onOverlay", OVERLAY, null).asStatic().then(Body.CANCEL))));

        assertTrue(out.methods.stream().noneMatch(m -> m.name.startsWith("retromod$")));
        assertNotNull(subscription(method(out, "onOverlay")));
    }

    @Test
    @DisplayName("The escape check keeps every handler that can reach a cancel, and only those")
    void escapeCheck() {
        String event = "L" + RIGHT_CLICK + ";";
        assertFalse(mayCancel(event, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, RIGHT_CLICK, "getPos", "()Ljava/lang/Object;", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, CANCEL, "isCanceled", "(Ljava/lang/Object;)Z", false);
            mv.visitInsn(POP);
        }), "a getter and a read of the cancel");
        assertTrue(mayCancel(event, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKESTATIC, CANCEL, "setCanceled", "(Ljava/lang/Object;Z)V", false);
        }), "the rewritten cancel call");
        assertTrue(mayCancel(event, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitTypeInsn(CHECKCAST, INTERACT);
            mv.visitVarInsn(ASTORE, 1);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "mod/Helper", "handle", "(L" + INTERACT + ";)V", false);
        }), "a cast kept in another local and handed to a helper, as Zeta forwards its events");
        assertTrue(mayCancel(event, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(PUTSTATIC, "mod/Holder", "last", event);
        }), "a stored event");
        assertTrue(mayCancel(event, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInvokeDynamicInsn("run", "(" + event + ")Ljava/lang/Runnable;", metafactory(),
                    Type.getMethodType("()V"), new Handle(H_INVOKESTATIC, "mod/Helper", "later", "(" + event + ")V", false),
                    Type.getMethodType("()V"));
            mv.visitInsn(POP);
        }), "an event a lambda captures");
        assertTrue(mayCancel(event, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKEVIRTUAL, RIGHT_CLICK, "setUseBlock", "(Ljava/lang/Object;)V", false);
        }), "a call with arguments on the event");
    }

    // ---- what the twins do when they run ----

    @Test
    @DisplayName("A twin answers whether its handler cancelled, un-cancels too, and leaves no frame behind when the handler throws")
    void twinAnswersItsHandlersCancel() throws Exception {
        ClassLoader mod = loader(Map.of(RIGHT_CLICK, simpleEvent(RIGHT_CLICK),
                HANDLERS, transformOn("26.2", null, handlers(HANDLERS,
                        old("onCancel", RIGHT_CLICK, null).asStatic().then(Body.CANCEL),
                        old("onUncancel", RIGHT_CLICK, null).asStatic().then(Body.CANCEL_UNCANCEL),
                        old("onThrow", RIGHT_CLICK, null).asStatic().then(Body.CANCEL_THROW),
                        old("onInstance", RIGHT_CLICK, null).receivingCanceled().then(Body.CANCEL)))));
        // Loading the class has the JVM verify every twin's frames, the instance twin and its monitor's too.
        Class<?> handlers = mod.loadClass("mod.Handlers");
        Object event = mod.loadClass(dotted(RIGHT_CLICK)).getConstructor().newInstance();

        Object receiver = handlers.getConstructor().newInstance();
        assertEquals(true, handlers.getMethod("retromod$cancellable$onInstance", event.getClass()).invoke(receiver, event));
        handlers.getMethod("retromod$cancelled$onInstance", event.getClass(), boolean.class).invoke(receiver, event, true);
        assertEquals(List.of("onInstance"), notes(handlers), "the monitor leaves an instance handler that already ran");
        assertEquals(true, invoke(handlers, "retromod$cancellable$onCancel", event));
        assertEquals(false, invoke(handlers, "retromod$cancellable$onUncancel", event));
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> handlers.getMethod("retromod$cancellable$onThrow", event.getClass()).invoke(null, event));
        assertEquals("handler failed", thrown.getCause().getMessage());
        // The twins call the test classpath's LegacyCancel, whose frames this thread now holds.
        java.lang.reflect.Field field = Class.forName(dotted(CANCEL)).getDeclaredField("FRAMES");
        field.setAccessible(true);
        Object frames = ((ThreadLocal<?>) field.get(null)).get();
        assertTrue(((java.util.Collection<?>) frames).isEmpty(), "every frame left with its listener");
    }

    @Test
    @DisplayName("A receiveCanceled handler runs once a post: at its own turn, or from its monitor after a cancel skipped it")
    void monitorRunsTheHandlerOnce() throws Exception {
        ClassLoader mod = loader(Map.of(RIGHT_CLICK, simpleEvent(RIGHT_CLICK),
                HANDLERS, transformOn("26.2", null, handlers(HANDLERS,
                        old("onWatch", RIGHT_CLICK, "LOWEST").asStatic().receivingCanceled().then(Body.READ_CANCEL)))));
        Class<?> handlers = mod.loadClass("mod.Handlers");
        Class<?> type = mod.loadClass(dotted(RIGHT_CLICK));
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) handlers.getField("NOTES").get(null);

        // Not cancelled: the boolean twin runs it, and the monitor leaves it.
        Object first = type.getConstructor().newInstance();
        assertEquals(false, invoke(handlers, "retromod$cancellable$onWatch", first));
        invoke(handlers, "retromod$cancelled$onWatch", first, false);
        // Cancelled after it ran: still once.
        Object second = type.getConstructor().newInstance();
        invoke(handlers, "retromod$cancellable$onWatch", second);
        invoke(handlers, "retromod$cancelled$onWatch", second, true);
        // Cancelled before its turn: the monitor runs it, and it reads the cancel.
        Object third = type.getConstructor().newInstance();
        invoke(handlers, "retromod$cancelled$onWatch", third, true);

        assertEquals(List.of("onWatch false", "onWatch false", "onWatch true"), notes);
    }

    @Test
    @DisplayName("The parent's twin runs its handler for a plain child and skips a cancellable child, which has its own twin")
    void parentTwinSkipsCancellableChildren() throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(INTERACT, simpleEvent(INTERACT, ACC_ABSTRACT, "java/lang/Object"));
        for (String child : INTERACT_CHILDREN) classes.put(child, simpleEvent(child, 0, INTERACT));
        classes.put(RIGHT_CLICK_EMPTY, simpleEvent(RIGHT_CLICK_EMPTY, 0, INTERACT));
        classes.put(HANDLERS, transformOn("26.2", null, handlers(HANDLERS, old("onInteract", INTERACT, null).asStatic().then(Body.CANCEL),
                old("onInstanceInteract", INTERACT, null).then(Body.CANCEL))));
        ClassLoader mod = loader(classes);
        Class<?> handlers = mod.loadClass("mod.Handlers");
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) handlers.getField("NOTES").get(null);

        Method parent = handlers.getMethod("retromod$plain$onInteract", mod.loadClass(dotted(INTERACT)));
        parent.invoke(null, mod.loadClass(dotted(RIGHT_CLICK)).getConstructor().newInstance());
        parent.invoke(null, mod.loadClass(dotted(RIGHT_CLICK_EMPTY)).getConstructor().newInstance());
        assertEquals(List.of("onInteract"), notes, "only RightClickEmpty, which has no twin of its own");
        Method instanceParent = handlers.getMethod("retromod$plain$onInstanceInteract", mod.loadClass(dotted(INTERACT)));
        instanceParent.invoke(handlers.getConstructor().newInstance(), mod.loadClass(dotted(RIGHT_CLICK)).getConstructor().newInstance());
        assertEquals(List.of("onInteract"), notes, "an instance handler's parent twin skips the child the same way");
    }

    @Test
    @DisplayName("isAttack, isUseItem and isPickBlock answer from the class of Forge 26.3's key event")
    void keyMappingTriggersAnswerFromTheClass() throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        for (String key : List.of("Attack", "UseItem", "PickBlock")) {
            classes.put(KEY_MAPPING + "$" + key, simpleEvent(KEY_MAPPING + "$" + key));
        }
        ClassLoader host = loader(classes);
        Class<?> triggers = Class.forName(dotted(Forge_26_2_to_26_3.KEY_MAPPING_TRIGGERS));
        Object useItem = host.loadClass(dotted(KEY_MAPPING + "$UseItem")).getConstructor().newInstance();

        assertEquals(true, triggers.getMethod("isUseItem", Object.class).invoke(null, useItem));
        assertEquals(false, triggers.getMethod("isAttack", Object.class).invoke(null, useItem));
        assertEquals(false, triggers.getMethod("isPickBlock", Object.class).invoke(null, useItem));
    }

    // ---- every transform path, and the real EventBus 7 ----

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths add the same twins, outer and nested, calling the embedded LegacyCancel")
    void everyPathAddsTheTwins(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("cancels.jar");
        Files.write(input, modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("cancels.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        String twins = "[onLow(L" + RIGHT_CLICK + ";)V at -64, retromod$cancellable$onHigh(L" + RIGHT_CLICK
                + ";)Z at 64 calling the embedded LegacyCancel]";
        Map<String, String> expected = new TreeMap<>(Map.of(HANDLERS, twins, "nested:lib/LibHandlers", twins));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths move handlers on parents with no bus onto the same children, outer and nested")
    void everyPathMovesBuslessParentHandlers(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("busless.jar");
        Files.write(input, modJar(name -> handlers(name, old("onAdvancement", ADVANCEMENT, null).asStatic(),
                old("onMouse", MOUSE_BUTTON, "HIGH").asStatic().then(Body.CANCEL))));

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put("Forge runtime", summary(forgeRuntime(input, dir, host)));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("busless.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        outputs.put("CLI batch", summary(onlyJar(batchOut)));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        outputs.put("AOT", summary(aot));

        String twins = "[retromod$cancellable$onMouse$Pre(L" + MOUSE_BUTTON + "$Pre;)Z at 64 calling the embedded LegacyCancel, "
                + "retromod$onAdvancement$AdvancementEarnEvent(L" + ADVANCEMENT + "$AdvancementEarnEvent;)V at 0, "
                + "retromod$onAdvancement$AdvancementProgressEvent(L" + ADVANCEMENT + "$AdvancementProgressEvent;)V at 0, "
                + "retromod$onMouse$Post(L" + MOUSE_BUTTON + "$Post;)V at 64]";
        Map<String, String> expected = new TreeMap<>(Map.of(HANDLERS, twins, "nested:lib/LibHandlers", twins));
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey());
        }
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge runtime, CLI batch and AOT paths give overloaded handlers the same twins with names of their own")
    void everyPathKeepsOverloadedTwinsApart(String host, @TempDir Path dir) throws Exception {
        Path input = dir.resolve("overloads.jar");
        Files.write(input, modJar(name -> handlers(name, old("onEvent", INPUT, "HIGH").asStatic().then(Body.CANCEL),
                old("onEvent", MOUSE_BUTTON, "HIGH").asStatic().then(Body.CANCEL))));

        Map<String, Path> jars = new LinkedHashMap<>();
        jars.put("Forge runtime", forgeRuntime(input, dir, host));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("overloads.jar"));
        CliHarness.batch(batchIn, batchOut, host);
        jars.put("CLI batch", onlyJar(batchOut));
        Path aot = dir.resolve("aot.jar");
        CliHarness.aot(input, aot, host);
        jars.put("AOT", aot);

        Map<String, String> expected = summary(jars.get("Forge runtime"));
        assertTrue(expected.get(HANDLERS).contains("retromod$cancellable$onEvent$InputEvent$MouseButton$Pre(L" + MOUSE_BUTTON
                + "$Pre;)Z at 64 calling the embedded LegacyCancel"), expected.get(HANDLERS));
        for (Map.Entry<String, Path> jar : jars.entrySet()) {
            assertEquals(expected, summary(jar.getValue()), jar.getKey());
            try (JarFile file = new JarFile(jar.getValue().toFile())) {
                byte[] handlers = file.getInputStream(file.getJarEntry(HANDLERS + ".class")).readAllBytes();
                assertDoesNotThrow(() -> define(handlers), jar.getKey() + " wrote a class that loads");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("On Forge's real EventBus 7, a cancelling HIGH handler stops LOW, and a receiveCanceled LOWEST handler runs once")
    void realEventBusStopsAfterACancel(String host) throws Exception {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host + "; looked at "
                + eventBusCandidates(host) + " (set retromod.test.eventbus701 or retromod.test.eventbus706 to a jar)");
        Map<String, byte[]> classes = jarClasses(eventBus);
        classes.put(INTERACT, hostEvent(INTERACT, ACC_ABSTRACT, "java/lang/Object", false));
        for (String child : INTERACT_CHILDREN) classes.put(child, hostEvent(child, ACC_FINAL, INTERACT, true));
        classes.put(RIGHT_CLICK_EMPTY, hostEvent(RIGHT_CLICK_EMPTY, ACC_FINAL, INTERACT, false));
        classes.put("mod/Clicks", transformOn(host.substring(0, 4), null, handlers("mod/Clicks",
                old("onHigh", RIGHT_CLICK, "HIGH").asStatic().then(Body.NOTE_CANCEL_WHEN_ASKED),
                old("onLow", RIGHT_CLICK, "LOW").asStatic().then(Body.NOTE),
                old("onWatch", RIGHT_CLICK, "LOWEST").asStatic().receivingCanceled().then(Body.READ_CANCEL))));
        classes.put("mod/Interactions", transformOn(host.substring(0, 4), null, handlers("mod/Interactions",
                old("onInteract", INTERACT, "HIGH").asStatic().then(Body.NOTE_CANCEL_WHEN_ASKED))));
        ClassLoader game = loader(classes);
        Class<?> busGroup = game.loadClass("net.minecraftforge.eventbus.api.bus.BusGroup");
        Object group = busGroup.getField("DEFAULT").get(null);
        busGroup.getMethod("startup").invoke(group);
        register(game, group, "mod.Clicks");
        register(game, group, "mod.Interactions");

        Class<?> clicks = game.loadClass("mod.Clicks");
        Class<?> interactions = game.loadClass("mod.Interactions");
        clicks.getField("CANCEL").setBoolean(null, true);
        assertEquals(true, post(game, RIGHT_CLICK), "the cancel reaches EventBus 7");
        assertEquals(List.of("onHigh", "onWatch true"), notes(clicks), "LOW is skipped and the watcher sees the cancel once");

        clicks.getField("CANCEL").setBoolean(null, false);
        notes(clicks).clear();
        assertEquals(false, post(game, RIGHT_CLICK));
        assertEquals(List.of("onHigh", "onLow", "onWatch false"), notes(clicks));

        // The PlayerInteractEvent handler cancels a RightClickBlock through its child twin, and still hears RightClickEmpty.
        clicks.getField("CANCEL").setBoolean(null, false);
        interactions.getField("CANCEL").setBoolean(null, true);
        notes(clicks).clear();
        notes(interactions).clear();
        assertEquals(true, post(game, RIGHT_CLICK));
        assertEquals(List.of("onInteract"), notes(interactions));
        assertEquals(List.of("onHigh", "onWatch true"), notes(clicks),
                "the parent handler's cancel at HIGH stops the LOW handler of the child, after the HIGH one registered first");
        post(game, RIGHT_CLICK_EMPTY);
        assertEquals(List.of("onInteract", "onInteract"), notes(interactions));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("On Forge's real EventBus 7, BlockEvent handlers reach every block event once, in priority order, and cancel where it posts cancellable")
    void realEventBusDeliversEveryEventBelowABuslessParent(String host) throws Exception {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host + "; looked at "
                + eventBusCandidates(host) + " (set retromod.test.eventbus701 or retromod.test.eventbus706 to a jar)");
        String version = host.substring(0, 4);
        Map<String, byte[]> classes = jarClasses(eventBus);
        classes.putAll(blockEventsOf(version));
        classes.put("mod/Blocks", transformOn(version, null, handlers("mod/Blocks",
                old("onWatch", BLOCK, "LOWEST").asStatic().receivingCanceled().then(Body.READ_CANCEL),
                old("onLow", BLOCK, "LOW").asStatic().then(Body.NOTE),
                old("onHigh", BLOCK, "HIGH").asStatic().then(Body.NOTE_CANCEL_WHEN_ASKED))));
        ClassNode out = node(classes.get("mod/Blocks"));
        assertEquals(List.of(NOTE_BLOCK + "$Change", NOTE_BLOCK + "$Play"), instanceofs(method(out, "retromod$plain$onHigh$NoteBlockEvent")),
                "the plain NoteBlockEvent bus leaves its cancellable children to their own twins");
        ClassLoader game = loader(classes);
        Class<?> busGroup = game.loadClass("net.minecraftforge.eventbus.api.bus.BusGroup");
        Object group = busGroup.getField("DEFAULT").get(null);
        busGroup.getMethod("startup").invoke(group);
        register(game, group, "mod.Blocks");
        Class<?> blocks = game.loadClass("mod.Blocks");

        // A cancellable child of the plain NoteBlockEvent: the HIGH cancel stops LOW, and the watcher sees it once.
        blocks.getField("CANCEL").setBoolean(null, true);
        assertEquals(true, post(game, NOTE_BLOCK + "$Play"));
        assertEquals(List.of("onHigh", "onWatch true"), notes(blocks));
        blocks.getField("CANCEL").setBoolean(null, false);
        notes(blocks).clear();
        assertEquals(false, post(game, NOTE_BLOCK + "$Play"));
        assertEquals(List.of("onHigh", "onLow", "onWatch false"), notes(blocks), "each handler once, in priority order");

        // A cancellable event right below BlockEvent, and a plain one.
        blocks.getField("CANCEL").setBoolean(null, true);
        notes(blocks).clear();
        assertEquals(true, post(game, BLOCK + "$EntityPlaceEvent"));
        assertEquals(List.of("onHigh", "onWatch true"), notes(blocks));
        blocks.getField("CANCEL").setBoolean(null, false);
        notes(blocks).clear();
        assertEquals(false, post(game, "net/minecraftforge/event/level/PistonEvent$Post"));
        assertEquals(List.of("onHigh", "onLow", "onWatch false"), notes(blocks));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("On Forge's real EventBus 7, a receiveCanceled EntityTeleportEvent handler sees a cancelled teleport once, whenever its bus was made")
    void realEventBusMonitorsReachHeirsMadeLater(String host) throws Exception {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host + "; looked at "
                + eventBusCandidates(host) + " (set retromod.test.eventbus701 or retromod.test.eventbus706 to a jar)");
        String version = host.substring(0, 4);
        RetromodVersion.TARGET_MC_VERSION = version;
        Map<String, byte[]> classes = jarClasses(eventBus);
        classes.put(TELEPORT, hostEvent(TELEPORT, 0, "java/lang/Object", true));
        for (String heir : LegacyCancelBridge.heirs(TELEPORT, name -> null)) classes.put(heir, hostEvent(heir, ACC_FINAL, TELEPORT, true));
        classes.put("mod/Watch", transformOn(version, null, handlers("mod/Watch",
                old("onWatch", TELEPORT, "LOWEST").asStatic().receivingCanceled().then(Body.READ_CANCEL))));
        // EventBus 7 refuses a class with a single listener, so each stopper has a second one after it.
        classes.put("mod/Pearls", transformOn(version, null, handlers("mod/Pearls",
                old("onPearl", TELEPORT + "$EnderPearl", "HIGH").asStatic().then(Body.NOTE_CANCEL_WHEN_ASKED),
                old("onPearlLate", TELEPORT + "$EnderPearl", "LOW").asStatic())));
        classes.put("mod/Chorus", transformOn(version, null, handlers("mod/Chorus",
                old("onChorus", TELEPORT + "$ChorusFruit", "HIGH").asStatic().then(Body.NOTE_CANCEL_WHEN_ASKED),
                old("onChorusLate", TELEPORT + "$ChorusFruit", "LOW").asStatic())));
        ClassLoader game = loader(classes);
        Class<?> busGroup = game.loadClass("net.minecraftforge.eventbus.api.bus.BusGroup");
        Object group = busGroup.getField("DEFAULT").get(null);
        busGroup.getMethod("startup").invoke(group);
        // The pearl's bus exists before the watcher registers and gets the parent's monitor too; the chorus fruit's
        // bus is made after, which gives it the watcher's boolean twin but not the parent's monitor.
        register(game, group, "mod.Pearls");
        register(game, group, "mod.Watch");
        register(game, group, "mod.Chorus");
        Class<?> watch = game.loadClass("mod.Watch");

        for (String heir : List.of("Pearls", "Chorus")) {
            Class<?> stopper = game.loadClass("mod." + heir);
            stopper.getField("CANCEL").setBoolean(null, true);
            notes(watch).clear();
            assertEquals(true, post(game, TELEPORT + (heir.equals("Pearls") ? "$EnderPearl" : "$ChorusFruit")));
            assertEquals(List.of("onWatch true"), notes(watch), heir + ": the watcher sees the cancelled teleport once");
            stopper.getField("CANCEL").setBoolean(null, false);
            notes(watch).clear();
            assertEquals(false, post(game, TELEPORT + (heir.equals("Pearls") ? "$EnderPearl" : "$ChorusFruit")));
            assertEquals(List.of("onWatch false"), notes(watch), heir + ": and runs once on a teleport nobody cancelled");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("On Forge's real EventBus 7, HIGHEST receiveCanceled handlers that ran before a cancel run once, whenever the child's bus was made")
    void realEventBusRunsAWatcherOnceWhenItRanBeforeTheCancel(String host) throws Exception {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host + "; looked at "
                + eventBusCandidates(host) + " (set retromod.test.eventbus701 or retromod.test.eventbus706 to a jar)");
        String version = host.substring(0, 4);
        RetromodVersion.TARGET_MC_VERSION = version;
        Map<String, byte[]> classes = jarClasses(eventBus);
        classes.put(TELEPORT, hostEvent(TELEPORT, 0, "java/lang/Object", true));
        for (String heir : LegacyCancelBridge.heirs(TELEPORT, name -> null)) classes.put(heir, hostEvent(heir, ACC_FINAL, TELEPORT, true));
        classes.put("mod/Read", transformOn(version, null, handlers("mod/Read",
                old("onRead", TELEPORT, "HIGHEST").asStatic().receivingCanceled().then(Body.READ_CANCEL))));
        classes.put("mod/SelfStop", transformOn(version, null, handlers("mod/SelfStop",
                old("onSelf", TELEPORT, "HIGHEST").asStatic().receivingCanceled().then(Body.NOTE_CANCEL_WHEN_ASKED))));
        // EventBus 7 refuses a class with a single listener, so each stopper has a second one after it.
        for (String heir : List.of("EnderPearl", "ChorusFruit")) {
            classes.put("mod/Stop" + heir, transformOn(version, null, handlers("mod/Stop" + heir,
                    old("onStop", TELEPORT + "$" + heir, "LOW").asStatic().then(Body.NOTE_CANCEL_WHEN_ASKED),
                    old("onLate", TELEPORT + "$" + heir, "LOWEST").asStatic())));
        }
        ClassLoader game = loader(classes);
        Class<?> busGroup = game.loadClass("net.minecraftforge.eventbus.api.bus.BusGroup");
        Object group = busGroup.getField("DEFAULT").get(null);
        busGroup.getMethod("startup").invoke(group);
        // The pearl's bus exists before the watchers register, so it holds two monitors of each; the chorus fruit's is made after.
        register(game, group, "mod.StopEnderPearl");
        register(game, group, "mod.Read");
        register(game, group, "mod.SelfStop");
        register(game, group, "mod.StopChorusFruit");
        Class<?> read = game.loadClass("mod.Read");
        Class<?> selfStop = game.loadClass("mod.SelfStop");

        for (String heir : List.of("EnderPearl", "ChorusFruit")) {
            Class<?> stopper = game.loadClass("mod.Stop" + heir);
            // A later LOW listener cancels after both watchers ran.
            stopper.getField("CANCEL").setBoolean(null, true);
            notes(read).clear();
            notes(selfStop).clear();
            assertEquals(true, post(game, TELEPORT + "$" + heir));
            assertEquals(List.of("onRead false"), notes(read), heir + ": the reader ran once, before the cancel");
            assertEquals(List.of("onSelf"), notes(selfStop), heir + ": the other watcher ran once too");
            // The watcher cancels itself.
            stopper.getField("CANCEL").setBoolean(null, false);
            selfStop.getField("CANCEL").setBoolean(null, true);
            notes(read).clear();
            notes(selfStop).clear();
            assertEquals(true, post(game, TELEPORT + "$" + heir));
            assertEquals(1, notes(read).size(), heir + ": the reader runs once, before or after the other cancelled: " + notes(read));
            assertEquals(List.of("onSelf"), notes(selfStop), heir + ": a watcher that cancelled runs once");
            selfStop.getField("CANCEL").setBoolean(null, false);
        }
    }

    // ---- transform helpers ----

    /** One class through the Forge shims on {@code host}, for a mod built for {@code source} (null when unknown). */
    private byte[] transformOn(String host, String source, byte[] classBytes) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        if (host.equals("26.3")) new Forge_26_2_to_26_3().registerRedirects(transformer);
        transformer.setModSourceVersion(source);
        String name = new ClassReader(classBytes).getClassName();
        return transformer.transformClass(classBytes, name + ".class");
    }

    /** What RetromodForge registers on the host, then one runtime transform. */
    private Path forgeRuntime(Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path out = new ForgeModTransformer(host).transformMod(input, Files.createDirectory(dir.resolve("runtime")));
        assertNotNull(out, "the Forge runtime wrote a jar");
        return out;
    }

    private static boolean mayCancel(String event, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, HANDLERS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "on", "(" + event + ")V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return CancellableListenerSiblings.mayCancel(HANDLERS, method(node(cw.toByteArray()), "on"));
    }

    private static Handle metafactory() {
        return new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    /** The class with its old annotations already named EventBus 7's, as the remap leaves them. */
    private static byte[] withNewSubscribe(byte[] classBytes) {
        ClassNode node = node(classBytes);
        for (MethodNode method : node.methods) {
            if (method.visibleAnnotations == null) continue;
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (annotation.desc.equals(OLD_SUBSCRIBE)) annotation.desc = SUBSCRIBE7;
            }
        }
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** A Forge 26.2 mod's EventBus 7 listeners: on the key mapping event a void, a boolean and a monitor one, and one on ScreenEvent.Init. */
    private static byte[] eventBus7Listeners() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Keys", null, "java/lang/Object", null);
        String key = "L" + KEY_MAPPING + ";";
        Object[][] listeners = {{"onKey", "(" + key + ")V", (byte) 64}, {"onAnswer", "(" + key + ")Z", (byte) 0},
                {"onWatch", "(" + key + "Z)V", (byte) -128}, {"onInit", "(L" + SCREEN_INIT + ";)V", (byte) 0}};
        for (Object[] listener : listeners) {
            String desc = (String) listener[1];
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, (String) listener[0], desc, null, null);
            AnnotationVisitor subscribe = mv.visitAnnotation(SUBSCRIBE7, true);
            subscribe.visit("priority", listener[2]);
            subscribe.visitEnd();
            mv.visitCode();
            if (desc.endsWith("Z")) {
                // return event.isUseItem();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKEVIRTUAL, KEY_MAPPING, "isUseItem", "()Z", false);
                mv.visitInsn(IRETURN);
            } else {
                mv.visitInsn(RETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The class's methods other than Retromod's twins, by name and descriptor, so two transforms compare by shape. */
    private static String withoutTwins(byte[] classBytes) {
        return node(classBytes).methods.stream().map(m -> m.name + m.desc).filter(m -> !m.startsWith("retromod$")).sorted().toList()
                + " twins " + node(classBytes).methods.stream().filter(m -> m.name.startsWith("retromod$")).count();
    }

    private static AnnotationNode subscription(MethodNode method) {
        if (method.visibleAnnotations == null) return null;
        return method.visibleAnnotations.stream().filter(a -> a.desc.equals(SUBSCRIBE7)).findFirst().orElse(null);
    }

    // An annotation written with no values reads back with none at all.
    private static List<Object> values(AnnotationNode annotation) {
        return annotation.values == null ? List.of() : annotation.values;
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static List<String> instanceofs(MethodNode method) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == INSTANCEOF) types.add(type.desc);
        }
        return types;
    }

    // The twins' explicit frames must hold up with no help, so the class goes through the checker and the analyzer.
    private static void verify(byte[] classBytes) throws Exception {
        new ClassReader(classBytes).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = node(classBytes);
        for (MethodNode method : node.methods) {
            if (method.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    /** Defines the class alone, as the JVM checks its methods when it loads it. */
    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(null) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name + ": "
                        + node.methods.stream().map(m -> m.name).toList()));
    }

    // ---- run-time helpers ----

    private static ClassLoader loader(Map<String, byte[]> classes) {
        return new LegacyEventRuntimeTest.Loader(CancellableListenerSiblingsTest.class.getClassLoader(), classes);
    }

    private static Object invoke(Class<?> owner, String name, Object... arguments) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(null, arguments);
            }
        }
        throw new AssertionError(owner.getName() + " has no " + name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(Class<?> handlers) throws Exception {
        return (List<String>) handlers.getField("NOTES").get(null);
    }

    /** {@code BusGroup.DEFAULT.register(Handlers.lookup(), Handlers.class)}, as FML's lenient registration does for a class. */
    private static void register(ClassLoader game, Object group, String handlers) throws Exception {
        Class<?> type = game.loadClass(handlers);
        Object lookup = type.getMethod("lookup").invoke(null);
        game.loadClass("net.minecraftforge.eventbus.api.bus.BusGroup")
                .getMethod("register", MethodHandles.Lookup.class, Class.class).invoke(group, lookup, type);
    }

    /** {@code Event.BUS.post(new Event())}. */
    private static boolean post(ClassLoader game, String event) throws Exception {
        Class<?> type = game.loadClass(dotted(event));
        Object bus = type.getField("BUS").get(null);
        Method post = game.loadClass("net.minecraftforge.eventbus.api.bus.EventBus")
                .getMethod("post", game.loadClass("net.minecraftforge.eventbus.internal.Event"));
        return (boolean) post.invoke(bus, type.getConstructor().newInstance());
    }

    /** Where the EventBus 7 jar of Forge {@code host} may be: a system property, Prism's libraries, then a local copy. */
    private static List<Path> eventBusCandidates(String host) {
        String version = host.startsWith("26.2") ? "7.0.1" : "7.0.6";
        String home = System.getProperty("user.home");
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("retromod.test.eventbus" + version.replace(".", ""));
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        candidates.add(Path.of(home, "Library/Application Support/PrismLauncher/libraries/net/minecraftforge/eventbus",
                version, "eventbus-" + version + ".jar"));
        if (version.equals("7.0.6")) candidates.add(Path.of(home, ".claude/jobs/3da2a019/tmp/forge263/eventbus-7.0.6.jar"));
        return candidates;
    }

    private static Path eventBusJar(String host) {
        return eventBusCandidates(host).stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static Map<String, byte[]> jarClasses(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    classes.put(name.substring(0, name.length() - ".class".length()), zip.getInputStream(entry).readAllBytes());
                }
            }
        }
        return classes;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    // ---- fixtures ----

    /** What an old handler does with its event, as javac wrote it for Forge 1.20.1. */
    enum Body {
        NOTE, CANCEL, CANCEL_UNCANCEL, CANCEL_THROW, GETTER, READ_CANCEL, NOTE_CANCEL_WHEN_ASKED, USE_ITEM_CANCEL
    }

    /** One old handler: its name, event, old priority or null, receiveCanceled, static, and body. */
    record Old(String name, String event, String priority, boolean receiveCanceled, boolean isStatic, Body body) {
        Old asStatic() {
            return new Old(name, event, priority, receiveCanceled, true, body);
        }

        Old receivingCanceled() {
            return new Old(name, event, priority, true, isStatic, body);
        }

        Old then(Body next) {
            return new Old(name, event, priority, receiveCanceled, isStatic, next);
        }
    }

    private static Old old(String name, String event, String priority) {
        return new Old(name, event, priority, false, false, Body.NOTE);
    }

    /**
     * A public Forge 1.20.1 handler class with {@code public static List NOTES}, {@code public static boolean CANCEL}
     * and a {@code lookup()} for EventBus 7, whose handlers note their names and act on their events.
     */
    static byte[] handlers(String name, Old... handlers) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "NOTES", "Ljava/util/List;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "CANCEL", "Z", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/ArrayList");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, name, "NOTES", "Ljava/util/List;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor lookup = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
        lookup.visitCode();
        lookup.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        lookup.visitInsn(ARETURN);
        lookup.visitMaxs(0, 0);
        lookup.visitEnd();
        for (Old handler : handlers) handler(cw, name, handler);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void handler(ClassWriter cw, String owner, Old handler) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | (handler.isStatic() ? ACC_STATIC : 0), handler.name(),
                "(L" + handler.event() + ";)V", null, null);
        AnnotationVisitor subscribe = mv.visitAnnotation(OLD_SUBSCRIBE, true);
        if (handler.priority() != null) subscribe.visitEnum("priority", OLD_PRIORITY, handler.priority());
        if (handler.receiveCanceled()) subscribe.visit("receiveCanceled", true);
        subscribe.visitEnd();
        mv.visitCode();
        int event = handler.isStatic() ? 0 : 1;
        String type = handler.event();
        Label done = new Label();
        switch (handler.body()) {
            case GETTER -> {
                mv.visitVarInsn(ALOAD, event);
                mv.visitMethodInsn(INVOKEVIRTUAL, type, "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;", false);
                mv.visitInsn(POP);
            }
            case READ_CANCEL -> {
                // NOTES.add(name + " " + event.isCanceled())
                mv.visitFieldInsn(GETSTATIC, owner, "NOTES", "Ljava/util/List;");
                mv.visitLdcInsn(handler.name() + " ");
                mv.visitVarInsn(ALOAD, event);
                mv.visitMethodInsn(INVOKEVIRTUAL, type, "isCanceled", "()Z", false);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Z)Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
                mv.visitInsn(POP);
            }
            default -> note(mv, owner, handler.name());
        }
        switch (handler.body()) {
            case CANCEL, CANCEL_UNCANCEL, CANCEL_THROW -> setCanceled(mv, event, type, true);
            case NOTE_CANCEL_WHEN_ASKED -> {
                mv.visitFieldInsn(GETSTATIC, owner, "CANCEL", "Z");
                mv.visitJumpInsn(IFEQ, done);
                setCanceled(mv, event, type, true);
            }
            case USE_ITEM_CANCEL -> {
                mv.visitVarInsn(ALOAD, event);
                mv.visitMethodInsn(INVOKEVIRTUAL, type, "isUseItem", "()Z", false);
                mv.visitJumpInsn(IFEQ, done);
                setCanceled(mv, event, type, true);
            }
            default -> { }
        }
        if (handler.body() == Body.CANCEL_UNCANCEL) setCanceled(mv, event, type, false);
        if (handler.body() == Body.CANCEL_THROW) {
            mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("handler failed");
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(ATHROW);
        }
        mv.visitLabel(done);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void note(MethodVisitor mv, String owner, String note) {
        mv.visitFieldInsn(GETSTATIC, owner, "NOTES", "Ljava/util/List;");
        mv.visitLdcInsn(note);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
    }

    private static void setCanceled(MethodVisitor mv, int event, String type, boolean cancel) {
        mv.visitVarInsn(ALOAD, event);
        mv.visitInsn(cancel ? ICONST_1 : ICONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, type, "setCanceled", "(Z)V", false);
    }

    /** A public event class with a no-argument constructor and nothing else, enough for a twin to run on. */
    private static byte[] simpleEvent(String name) {
        return simpleEvent(name, ACC_FINAL, "java/lang/Object");
    }

    private static byte[] simpleEvent(String name, int access, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | access, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A Forge 26.2 shaped event on the real EventBus 7: the abstract PlayerInteractEvent is inheritable with a plain
     * bus, as the host's is, and each final child has its own bus, cancellable when the host's is.
     */
    private static byte[] hostEvent(String name, int access, String superName, boolean cancellable) {
        String busType = cancellable ? "net/minecraftforge/eventbus/api/bus/CancellableEventBus"
                : "net/minecraftforge/eventbus/api/bus/EventBus";
        List<String> interfaces = new ArrayList<>();
        if (superName.equals("java/lang/Object")) interfaces.add("net/minecraftforge/eventbus/api/event/InheritableEvent");
        if (cancellable) interfaces.add("net/minecraftforge/eventbus/api/event/characteristic/Cancellable");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | access, name, null, superName, interfaces.toArray(new String[0]));
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", "L" + busType + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(Type.getObjectType(name));
        clinit.visitMethodInsn(INVOKESTATIC, busType, "create", "(Ljava/lang/Class;)L" + busType + ";", true);
        clinit.visitFieldInsn(PUTSTATIC, name, "BUS", "L" + busType + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor init = cw.visitMethod((access & ACC_ABSTRACT) != 0 ? ACC_PROTECTED : ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Every event the table names below BlockEvent on {@code version}, shaped as the table says: BlockEvent an interface
     * with no bus, each event below it with a bus of its own, and the plain NoteBlockEvent with cancellable children.
     */
    private static Map<String, byte[]> blockEventsOf(String version) {
        RetromodVersion.TARGET_MC_VERSION = version;
        java.util.function.Function<String, ClassNode> offline = name -> null;
        Map<String, byte[]> classes = new HashMap<>();
        ClassWriter parent = new ClassWriter(0);
        parent.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, BLOCK, null, "java/lang/Object", null);
        parent.visitEnd();
        classes.put(BLOCK, parent.toByteArray());
        for (String child : LegacyCancelBridge.buslessChildren(BLOCK, offline)) {
            classes.put(child, hostEventBelow(child, ACC_FINAL, "java/lang/Object", BLOCK, true));
        }
        for (String child : LegacyCancelBridge.buslessPlainChildren(BLOCK, offline)) {
            Set<String> split = LegacyCancelBridge.cancellableChildren(child, offline);
            classes.put(child, hostEventBelow(child, split.isEmpty() ? ACC_FINAL : ACC_ABSTRACT, "java/lang/Object", BLOCK, false));
            for (String grandchild : split) classes.put(grandchild, hostEvent(grandchild, ACC_FINAL, child, true));
        }
        return classes;
    }

    /** {@link #hostEvent} that also implements a parent interface with no bus, as Forge 26.2's events below BlockEvent do. */
    private static byte[] hostEventBelow(String name, int access, String superName, String parent, boolean cancellable) {
        ClassNode node = node(hostEvent(name, access, superName, cancellable));
        node.interfaces.add(0, parent);
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** The class with Forge 1.20.1's {@code @Mod.EventBusSubscriber(modid = modId)}, which leaves the bus at FORGE. */
    private static byte[] subscriber(byte[] classBytes, String modId) {
        ClassNode node = node(classBytes);
        AnnotationNode subscriber = new AnnotationNode("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;");
        subscriber.values = new ArrayList<>(List.of("modid", modId));
        node.visibleAnnotations = new ArrayList<>(List.of(subscriber));
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    // ---- path parity ----

    /** A Forge 1.20.1 mod whose HIGH handler cancels and whose LOW one only notes, with a nested library that does the same. */
    private static byte[] modJar() throws Exception {
        return modJar(CancellableListenerSiblingsTest::parityHandlers);
    }

    /** A Forge 1.20.1 mod and a nested library, each with the handler class {@code handlers} builds under its name. */
    private static byte[] modJar(java.util.function.Function<String, byte[]> handlers) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("META-INF/mods.toml", toml("cancellib"));
        lib.put("lib/LibHandlers.class", handlers.apply("lib/LibHandlers"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", toml("cancelmod"));
        mod.put(HANDLERS + ".class", handlers.apply(HANDLERS));
        mod.put("META-INF/jarjar/lib.jar", jar(lib));
        return jar(mod);
    }

    private static byte[] parityHandlers(String name) {
        return handlers(name, old("onHigh", RIGHT_CLICK, "HIGH").asStatic().then(Body.CANCEL),
                old("onLow", RIGHT_CLICK, "LOW").asStatic());
    }

    // Each listener the class registers, with its priority, and whether a twin calls the jar's own LegacyCancel copy.
    private static Map<String, String> summary(Path jar) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            found.put(HANDLERS, listeners(file, HANDLERS));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                Files.write(nested, file.getInputStream(file.getJarEntry("META-INF/jarjar/lib.jar")).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibHandlers", listeners(lib, "lib/LibHandlers"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static String listeners(JarFile jar, String owner) throws Exception {
        JarEntry entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, owner + " is missing from " + jar.getName());
        ClassNode node = node(jar.getInputStream(entry).readAllBytes());
        List<String> listeners = new ArrayList<>();
        for (MethodNode method : node.methods) {
            AnnotationNode subscription = subscription(method);
            if (subscription == null) continue;
            String listener = method.name + method.desc + " at " + ForgeSubscribeAnnotations.priorityOf(subscription);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.endsWith(CANCEL) && call.name.equals("enter")) {
                    boolean embedded = call.owner.startsWith(SyntheticEmbedder.PREFIX) && jar.getJarEntry(call.owner + ".class") != null;
                    listener += embedded ? " calling the embedded LegacyCancel" : " calling a LegacyCancel the jar lacks";
                }
            }
            listeners.add(listener);
        }
        listeners.sort(null);
        return listeners.toString();
    }

    private static Path onlyJar(Path dir) throws Exception {
        try (var jars = Files.list(dir)) {
            List<Path> all = jars.filter(path -> path.toString().endsWith(".jar")).toList();
            assertEquals(1, all.size(), "the batch wrote one jar: " + all);
            return all.get(0);
        }
    }

    private static byte[] toml(String modId) {
        return ("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"1.0\"\n[[dependencies." + modId + "]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.20.2)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
