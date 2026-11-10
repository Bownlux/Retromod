/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** An old handler on a Forge event with no bus of its own reaches the events below it on the game bus, on Forge's real EventBus 7. */
class BuslessParentHookTest {

    private static final String ADVANCEMENT = "net/minecraftforge/event/entity/player/AdvancementEvent";
    private static final String EARN = ADVANCEMENT + "$AdvancementEarnEvent";
    private static final String PROGRESS = ADVANCEMENT + "$AdvancementProgressEvent";
    private static final String MOUSE = "net/minecraftforge/client/event/InputEvent$MouseButton";
    private static final String MOUSE_PRE = MOUSE + "$Pre";
    private static final String MOUSE_POST = MOUSE + "$Post";
    // A Forge parent with no bus that the table does not name, so no child can take its handler.
    private static final String UNNAMED = "net/minecraftforge/event/RetromodUnnamedParentEvent";
    private static final String SUBSCRIBE7 = ForgeSubscribeAnnotations.SUBSCRIBE7;
    private static final String MOD_BUS_EVENT = "net.minecraftforge.fml.event.IModBusEvent";

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2 with eventbus 7.0.1", "26.3 with eventbus 7.0.6"})
    @DisplayName("A subscriber's companion hooks Lootr's AdvancementEvent handler onto both advancement events on the game bus, never the mod's group")
    void companionHooksTheChildrenOnTheGameBus(String host) throws Exception {
        Game game = game(host, handlers("mod/HandleAdvancement", new Handler("advancement", ADVANCEMENT, (byte) 0, true, false)));
        Object modGroup = game.modGroup("lootr");

        game.hookStatic("mod.HandleAdvancement", "onAdvancement", ADVANCEMENT, (byte) 0);
        game.startup(modGroup);

        assertFalse(game.post(EARN));
        assertFalse(game.post(PROGRESS));
        assertEquals(List.of("advancement", "advancement"), game.notes("mod/HandleAdvancement"),
                "Forge 1.20.1 delivered both advancement events to a listener on AdvancementEvent");
        assertOnlyModBusEvents(game, modGroup);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2 with eventbus 7.0.1", "26.3 with eventbus 7.0.6"})
    @DisplayName("register(Class) on a mod's bus hooks a static handler on AdvancementEvent onto both children once, and unregister takes both off")
    void registerOnTheModBusHooksTheChildren(String host) throws Exception {
        Game game = game(host, handlers("mod/ClientEvents", new Handler("advancement", ADVANCEMENT, (byte) 0, true, false)));
        Object modGroup = game.modGroup("somemod");
        Object modBus = EventBus7Host.modBus(game.mod, modGroup);
        Class<?> handlers = game.mod.loadClass("mod.ClientEvents");

        // LegacyAutoSubscriber registers a bus=MOD subscriber class on the mod's bus, which once refused the game event.
        EventBus7Host.register(modBus, handlers);
        EventBus7Host.register(modBus, handlers);
        game.startup(modGroup);
        game.post(EARN);
        game.post(PROGRESS);
        assertEquals(List.of("advancement", "advancement"), game.notes("mod/ClientEvents"), "each child once, however often the class registers");
        assertOnlyModBusEvents(game, modGroup);

        EventBus7Host.unregister(modBus, handlers);
        game.post(EARN);
        assertEquals(2, game.notes("mod/ClientEvents").size(), "unregister removed the listener from every child");
        EventBus7Host.register(modBus, handlers);
        game.post(PROGRESS);
        assertEquals(3, game.notes("mod/ClientEvents").size(), "and a later register puts it back");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2 with eventbus 7.0.1", "26.3 with eventbus 7.0.6"})
    @DisplayName("At HIGH a handler on InputEvent.MouseButton cancels Pre, which stops the LOW one, and both run on Post in priority order")
    void priorityAndCancelPerChild(String host) throws Exception {
        // LOW registers first, so only the priority puts HIGH ahead of it.
        Game game = game(host, handlers("mod/MouseEvents",
                new Handler("low", MOUSE, (byte) -64, true, false),
                new Handler("high", MOUSE, (byte) 64, true, true)));
        EventBus7Host.register(EventBus7Host.gameBus(game.mod), game.mod.loadClass("mod.MouseEvents"));
        game.startup(game.gameGroup());

        assertTrue(game.post(MOUSE_PRE), "Pre posts on a cancellable bus, so the old cancel reaches EventBus 7");
        assertEquals(List.of("high"), game.notes("mod/MouseEvents"), "the cancel at HIGH stops the LOW handler");
        game.notes("mod/MouseEvents").clear();
        assertFalse(game.post(MOUSE_POST), "Post posts on a plain bus, which takes no cancel");
        assertEquals(List.of("high", "low"), game.notes("mod/MouseEvents"), "HIGH runs before LOW");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2 with eventbus 7.0.1", "26.3 with eventbus 7.0.6"})
    @DisplayName("A handler on a Forge parent the table does not name goes onto no bus, neither the mod's group nor a game bus nothing posts")
    void unnamedParentGoesNowhere(String host) throws Exception {
        Game game = game(host, handlers("mod/Unnamed", new Handler("unnamed", UNNAMED, (byte) 0, true, false)));
        Object modGroup = game.modGroup("somemod");

        game.hookStatic("mod.Unnamed", "onUnnamed", UNNAMED, (byte) 0);

        assertOnlyModBusEvents(game, modGroup);
        Class<?> unnamed = game.host.type(UNNAMED);
        assertFalse(game.host.busTypes(game.gameGroup()).contains(unnamed), "no bus is made that nothing would post");
    }

    // ---- the game ----

    /** One Forge host on the real EventBus 7 with the bridge built for its version, and the handler class as a jar left it. */
    private Game game(String host, byte[] handlerClass) throws Exception {
        String version = host.substring(0, 4);
        Path eventBus = EventBus7Host.eventBusJar(version.equals("26.2") ? "7.0.1" : "7.0.6");
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + version
                + " (set retromod.test.eventbus701 or retromod.test.eventbus706 to a jar)");
        RetromodVersion.TARGET_MC_VERSION = version;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        if (version.equals("26.3")) new Forge_26_2_to_26_3().registerRedirects(transformer);

        EventBus7Host eventHost = new EventBus7Host(eventBus);
        eventHost.define(ADVANCEMENT, parent(ADVANCEMENT));
        eventHost.define(EARN, child(EARN, ADVANCEMENT, false));
        eventHost.define(PROGRESS, child(PROGRESS, ADVANCEMENT, false));
        eventHost.define(MOUSE, parent(MOUSE));
        eventHost.define(MOUSE_PRE, child(MOUSE_PRE, MOUSE, true));
        eventHost.define(MOUSE_POST, child(MOUSE_POST, MOUSE, false));
        eventHost.define(UNNAMED, parent(UNNAMED));
        // A class whose handler still names the parent when it runs, as a jar translated without the split leaves it.
        eventHost.define(new ClassReader(handlerClass).getClassName(), handlerClass);
        eventHost.completeLoading();
        ClassLoader mod = eventHost.mod(eventHost, transformer, "", Map.of());
        return new Game(eventHost, mod);
    }

    private static void assertOnlyModBusEvents(Game game, Object modGroup) throws Exception {
        Class<?> modBusEvent = game.host.type(MOD_BUS_EVENT.replace('.', '/'));
        for (Object type : game.host.busTypes(modGroup)) {
            assertTrue(modBusEvent.isAssignableFrom((Class<?>) type), "the mod's group took the game event " + type);
        }
    }

    /** Reflection over one test game: the host and one mod's embedded copy of the bus bridge. */
    private record Game(EventBus7Host host, ClassLoader mod) {

        Object gameGroup() throws Exception {
            return host.gameGroup();
        }

        /** A mod's group, made the one its bridge copy uses, as FML hands it while the mod constructs. */
        Object modGroup(String modId) throws Exception {
            Object group = host.modGroup(modId);
            Field field = EventBus7Host.implOf(mod).getDeclaredField("modGroup");
            field.setAccessible(true);
            field.set(null, group);
            return group;
        }

        void startup(Object group) throws Exception {
            host.startup(group);
            host.startup(host.gameGroup());
        }

        /** What a subscriber's companion calls on the construct event. */
        void hookStatic(String owner, String handler, String event, byte priority) throws Exception {
            EventBus7Host.implOf(mod).getMethod("hookStatic", String.class, String.class, String.class, byte.class)
                    .invoke(null, owner, handler, event.replace('/', '.'), priority);
        }

        boolean post(String event) throws Exception {
            return host.postOnOwnBus(host.type(event).getConstructor().newInstance());
        }

        @SuppressWarnings("unchecked")
        List<String> notes(String handlers) throws Exception {
            return (List<String>) mod.loadClass(handlers.replace('/', '.')).getField("NOTES").get(null);
        }
    }

    // ---- fixtures ----

    /** One handler: the tag it notes, its event, EventBus 7's priority byte, static, and whether it cancels its event. */
    record Handler(String tag, String event, byte priority, boolean isStatic, boolean cancels) {}

    /**
     * A public class of EventBus 7 {@code @SubscribeEvent} handlers, as a translated jar holds them, noting their tags in
     * {@code public static List NOTES}; a cancelling one calls LegacyCancel as the cancel bridge rewrote it.
     */
    static byte[] handlers(String name, Handler... handlers) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "NOTES", "Ljava/util/List;", null, null).visitEnd();
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
        for (Handler handler : handlers) {
            String method = "on" + Character.toUpperCase(handler.tag().charAt(0)) + handler.tag().substring(1);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | (handler.isStatic() ? ACC_STATIC : 0), method,
                    "(L" + handler.event() + ";)V", null, null);
            AnnotationVisitor subscribe = mv.visitAnnotation(SUBSCRIBE7, true);
            subscribe.visit("priority", handler.priority());
            subscribe.visitEnd();
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, name, "NOTES", "Ljava/util/List;");
            mv.visitLdcInsn(handler.tag());
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(POP);
            if (handler.cancels()) {
                mv.visitVarInsn(ALOAD, handler.isStatic() ? 0 : 1);
                mv.visitInsn(ICONST_1);
                mv.visitMethodInsn(INVOKESTATIC, LegacyCancelBridge.CANCEL, "setCanceled", "(Ljava/lang/Object;Z)V", false);
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge 26.2 parent with no bus: an interface that is no event itself, as AdvancementEvent is. */
    private static byte[] parent(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A final event below the parent with a bus of its own, cancellable when the host's is. */
    private static byte[] child(String name, String parent, boolean cancellable) {
        String mutable = "net/minecraftforge/eventbus/api/event/MutableEvent";
        String busType = cancellable ? LegacyCancelBridge.CANCELLABLE_BUS : EventBus7Host.EVENT_BUS;
        List<String> interfaces = new ArrayList<>(List.of(parent));
        if (cancellable) interfaces.add("net/minecraftforge/eventbus/api/event/characteristic/Cancellable");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER | ACC_FINAL, name, null, mutable, interfaces.toArray(new String[0]));
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", "L" + busType + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(GETSTATIC, EventBus7Host.BUS_GROUP, "DEFAULT", "L" + EventBus7Host.BUS_GROUP + ";");
        clinit.visitLdcInsn(Type.getObjectType(name));
        clinit.visitMethodInsn(INVOKESTATIC, busType, "create",
                "(L" + EventBus7Host.BUS_GROUP + ";Ljava/lang/Class;)L" + busType + ";", true);
        clinit.visitFieldInsn(PUTSTATIC, name, "BUS", "L" + busType + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, mutable, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
