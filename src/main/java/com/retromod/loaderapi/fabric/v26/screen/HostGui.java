/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The host Minecraft and Fabric API members that serve old HUD, screen and tooltip calls, by 26.x name. */
final class HostGui {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");

    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String DELTA_TRACKER = "net/minecraft/client/DeltaTracker";
    static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphicsExtractor";
    static final String HUD_ELEMENT = "net/fabricmc/fabric/api/client/rendering/v1/hud/HudElement";
    static final String HUD_REGISTRY = "net/fabricmc/fabric/api/client/rendering/v1/hud/HudElementRegistry";
    static final String VANILLA_HUD = "net/fabricmc/fabric/api/client/rendering/v1/hud/VanillaHudElements";
    static final String TOOLTIP_CALLBACK = "net/fabricmc/fabric/api/client/item/v1/ItemTooltipCallback";
    static final String TOOLTIP_CONTEXT = "net/minecraft/world/item/Item$TooltipContext";
    static final String DEBUG_ENTRIES = "net/minecraft/client/gui/components/debug/DebugScreenEntries";
    static final String DEBUG_ENTRY = "net/minecraft/client/gui/components/debug/DebugScreenEntry";
    static final String DEBUG_DISPLAYER = "net/minecraft/client/gui/components/debug/DebugScreenDisplayer";
    static final String CLIENT_LIFECYCLE = "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents";
    static final String CLIENT_STARTED_TYPE = CLIENT_LIFECYCLE + "$ClientStarted";
    static final String EVENT = "net/fabricmc/fabric/api/event/Event";

    private static final String ID_DESC = "L" + IDENTIFIER + ";";
    private static final String ELEMENT_DESC = "L" + HUD_ELEMENT + ";";

    // Every host member these helpers call; HostGuiMembersTest checks each on the 26.x hosts.
    static final Member IDENTIFIER_OF = Member.staticMethod(IDENTIFIER, "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)" + ID_DESC);
    static final Member PARTIAL_TICK = Member.virtual(DELTA_TRACKER, "getGameTimeDeltaPartialTick", "(Z)F");
    static final Member HUD_ADD_LAST = Member.staticMethod(HUD_REGISTRY, "addLast",
            "(" + ID_DESC + ELEMENT_DESC + ")V");
    static final Member HUD_ATTACH_BEFORE = Member.staticMethod(HUD_REGISTRY, "attachElementBefore",
            "(" + ID_DESC + ID_DESC + ELEMENT_DESC + ")V");
    static final Member HUD_ATTACH_AFTER = Member.staticMethod(HUD_REGISTRY, "attachElementAfter",
            "(" + ID_DESC + ID_DESC + ELEMENT_DESC + ")V");
    static final Member HUD_REMOVE = Member.staticMethod(HUD_REGISTRY, "removeElement", "(" + ID_DESC + ")V");
    static final Member HUD_REPLACE = Member.staticMethod(HUD_REGISTRY, "replaceElement",
            "(" + ID_DESC + "Ljava/util/function/Function;)V");
    static final Member HUD_EXTRACT = Member.virtual(HUD_ELEMENT, "extractRenderState",
            "(L" + GRAPHICS + ";L" + DELTA_TRACKER + ";)V");
    static final Member TOOLTIP_FOUR_ARGS = Member.virtual(TOOLTIP_CALLBACK, "getTooltip",
            "(Lnet/minecraft/world/item/ItemStack;L" + TOOLTIP_CONTEXT
                    + ";Lnet/minecraft/world/item/TooltipFlag;Ljava/util/List;)V");
    static final Member TOOLTIP_EMPTY = new Member(TOOLTIP_CONTEXT, "EMPTY", "L" + TOOLTIP_CONTEXT + ";", true);
    static final Member TOOLTIP_OF_LEVEL = Member.staticMethod(TOOLTIP_CONTEXT, "of",
            "(Lnet/minecraft/world/level/Level;)L" + TOOLTIP_CONTEXT + ";");
    static final Member MINECRAFT_INSTANCE = Member.staticMethod(MINECRAFT, "getInstance", "()L" + MINECRAFT + ";");
    static final Member CLIENT_LEVEL = new Member(MINECRAFT, "level",
            "Lnet/minecraft/client/multiplayer/ClientLevel;", false);
    static final Member DEBUG_REGISTER = Member.staticMethod(DEBUG_ENTRIES, "register",
            "(" + ID_DESC + "L" + DEBUG_ENTRY + ";)" + ID_DESC);
    static final Member DEBUG_ADD_GROUP = Member.virtual(DEBUG_DISPLAYER, "addToGroup",
            "(" + ID_DESC + "Ljava/util/Collection;)V");
    static final Member CLIENT_STARTED = new Member(CLIENT_LIFECYCLE, "CLIENT_STARTED", "L" + EVENT + ";", true);
    static final Member REGISTER_LISTENER = Member.virtual(EVENT, "register", "(Ljava/lang/Object;)V");

    /** Members whose absence is expected on some hosts; the helpers check before calling them. */
    static final Set<Member> OPTIONAL_MEMBERS = Set.of(DEBUG_REGISTER);

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private HostGui() {}

    /** A host {@code Identifier}, or null on a side that has none. */
    static Object id(String namespace, String path) {
        return HostLink.handle(IDENTIFIER_OF).map(of -> call(of, namespace, path)).orElse(null);
    }

    /** The host type {@code internalName}, or empty on a side without it. */
    static Optional<Class<?>> type(String internalName) {
        return HostLink.type(internalName);
    }

    /** A proxy for the host interface {@code type}; Object methods answer by identity. */
    static Object proxy(Class<?> type, String label, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object answer = objectMethod(proxy, method, args, label);
            return answer != NOT_OBJECT_METHOD ? answer : handler.invoke(proxy, method, args);
        });
    }

    private static final Object NOT_OBJECT_METHOD = new Object();

    private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
        boolean noArgs = method.getParameterCount() == 0;
        if (method.getName().equals("equals") && method.getParameterCount() == 1) return proxy == args[0];
        if (method.getName().equals("hashCode") && noArgs) return System.identityHashCode(proxy);
        if (method.getName().equals("toString") && noArgs) return label;
        return NOT_OBJECT_METHOD;
    }

    /** Runs the host interface's own default method, for members the proxy does not answer itself. */
    static Object defaultOf(Object proxy, Method method, Object[] args, String label) throws Throwable {
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        throw new UnsupportedOperationException(label + " has no answer for " + method.getName()
                + " on this host");
    }

    /**
     * Runs {@code action} when the client has started, through Fabric API's lifecycle event.
     *
     * @return false on a side without the event, where {@code action} never runs
     */
    static boolean onClientStarted(String label, Runnable action) {
        Optional<MethodHandle> event = HostLink.handle(CLIENT_STARTED);
        Optional<Class<?>> listenerType = type(CLIENT_STARTED_TYPE);
        if (event.isEmpty() || listenerType.isEmpty()) return false;
        Object listener = proxy(listenerType.get(), label, (proxy, method, args) -> {
            if (method.getName().equals("onClientStarted")) {
                action.run();
                return null;
            }
            return defaultOf(proxy, method, args, label);
        });
        call(HostLink.require(REGISTER_LISTENER), call(event.get()), listener);
        return true;
    }

    /** Calls a host member, rethrowing what it threw as an unchecked exception. */
    static Object call(MethodHandle handle, Object... args) {
        try {
            // Fixed arity, so an array passed to a varargs member is not wrapped in another array.
            return handle.asFixedArity().invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Logs {@code message} at info level once per {@code key}. */
    static void infoOnce(String key, String message, Object... args) {
        if (REPORTED.add(key)) LOGGER.info(message, args);
    }

    /** Logs {@code message} at warn level once per {@code key}. */
    static void warnOnce(String key, String message, Object... args) {
        if (REPORTED.add(key)) LOGGER.warn(message, args);
    }

    /** Logs {@code message} at debug level once per {@code key}. */
    static void debugOnce(String key, String message, Object... args) {
        if (REPORTED.add(key)) LOGGER.debug(message, args);
    }
}
