/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.screen.shared.LegacyHudRenderCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** Serves the old {@code HudRenderCallback} through one element in the host's HUD element registry. */
public final class LegacyHud {

    /** The id of the one HUD element that draws every old callback. */
    static final String ELEMENT_PATH = "legacy_hud_render_callback";

    private static final String POSE_STACK = "com.mojang.blaze3d.vertex.PoseStack";

    private LegacyHud() {}

    /** Builds the old event and, on a client with Fabric API, the HUD element that draws it. */
    public static Event<LegacyHudRenderCallback> hudRenderEvent() {
        Event<LegacyHudRenderCallback> event = EventFactory.createArrayBacked(
                LegacyHudRenderCallback.class, Invoker::new);
        try {
            installHudElement(event);
        } catch (RuntimeException e) {
            // The mod's class setup reads this event, so a host refusal must not fail it.
            HostGui.warnOnce("hud-element", "Retromod could not add the HUD element for old HUD "
                    + "callbacks ({}). They are kept but do not draw.", e.toString());
        }
        return event;
    }

    /** Draws one old callback in the 1.21 shape, whatever shape its class implements. */
    public static void dispatch(Object callback, Object graphics, Object deltaTracker) {
        Shape shape = SHAPES.get(callback.getClass());
        switch (shape.kind) {
            case ERASED_PARTIAL_TICK -> ((LegacyHudRenderCallback) callback)
                    .renderLegacyHudWithPartialTick(graphics, partialTick(deltaTracker));
            case ERASED_TRACKER -> ((LegacyHudRenderCallback) callback).renderLegacyHud(graphics, deltaTracker);
            case TYPED_TRACKER -> invoke(shape.handle, callback, graphics, deltaTracker);
            case TYPED_PARTIAL_TICK -> invoke(shape.handle, callback, graphics, partialTick(deltaTracker));
            default -> reportUnserved(callback.getClass(), shape);
        }
    }

    /** Draws one old callback in the 1.20 shape, as a mod that calls the old invoker does. */
    public static void dispatchPartialTick(Object callback, Object graphics, float tickDelta) {
        Shape shape = SHAPES.get(callback.getClass());
        switch (shape.kind) {
            case ERASED_TRACKER -> ((LegacyHudRenderCallback) callback)
                    .renderLegacyHud(graphics, trackerFor(tickDelta));
            case ERASED_PARTIAL_TICK -> ((LegacyHudRenderCallback) callback)
                    .renderLegacyHudWithPartialTick(graphics, tickDelta);
            case TYPED_PARTIAL_TICK -> invoke(shape.handle, callback, graphics, tickDelta);
            case TYPED_TRACKER -> invoke(shape.handle, callback, graphics, trackerFor(tickDelta));
            default -> reportUnserved(callback.getClass(), shape);
        }
    }

    /** The partial tick an old 1.20 callback was given, read from the frame's delta tracker. */
    static float partialTick(Object deltaTracker) {
        if (deltaTracker == null) return 0f;
        Optional<MethodHandle> read = HostLink.handle(HostGui.PARTIAL_TICK);
        // 1.20 Fabric API passed the frame's partial tick, which ignores a paused game as false does.
        return read.map(handle -> (Float) HostGui.call(handle, deltaTracker, false)).orElse(0f);
    }

    /** A host delta tracker that answers {@code tickDelta}, for a mod that calls the invoker with a float. */
    static Object trackerFor(float tickDelta) {
        return HostGui.type(HostGui.DELTA_TRACKER).map(type -> HostGui.proxy(type, "legacy partial tick",
                (proxy, method, args) -> method.getReturnType() == float.class ? tickDelta
                        : HostGui.defaultOf(proxy, method, args, "legacy partial tick"))).orElse(null);
    }

    // ---- The host element ---------------------------------------------------------------

    private static void installHudElement(Event<LegacyHudRenderCallback> event) {
        Optional<MethodHandle> addLast = HostLink.handle(HostGui.HUD_ADD_LAST);
        Optional<Class<?>> elementType = HostGui.type(HostGui.HUD_ELEMENT);
        if (addLast.isEmpty() || elementType.isEmpty()) {
            // A dedicated server, or a client without Fabric API: the event keeps its listeners.
            HostGui.debugOnce("no-hud-registry", "Old HUD callbacks are kept but not drawn: this side "
                    + "has no Fabric API HudElementRegistry.");
            return;
        }
        // Old Fabric API drew these callbacks after the vanilla HUD, so the element goes last.
        Object element = HostGui.proxy(elementType.get(), "legacy HUD render callbacks", (proxy, method, args) -> {
            if (method.getName().equals("extractRenderState") && args != null && args.length == 2) {
                event.invoker().renderLegacyHud(args[0], args[1]);
                return null;
            }
            return HostGui.defaultOf(proxy, method, args, "legacy HUD render callbacks");
        });
        HostGui.call(addLast.get(), HostGui.id("retromod", ELEMENT_PATH), element);
    }

    /** Calls every registered callback in the 1.21 shape. */
    private static final class Invoker implements LegacyHudRenderCallback {
        private final LegacyHudRenderCallback[] callbacks;

        Invoker(LegacyHudRenderCallback[] callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public void renderLegacyHud(Object graphics, Object deltaTracker) {
            for (LegacyHudRenderCallback callback : callbacks) callback.renderLegacyHud(graphics, deltaTracker);
        }
    }

    // ---- Shapes ---------------------------------------------------------------------------

    private enum Kind { ERASED_TRACKER, ERASED_PARTIAL_TICK, TYPED_TRACKER, TYPED_PARTIAL_TICK, POSE_STACK, NONE }

    /** How one callback class implements the old callback; found once per class. */
    private record Shape(Kind kind, MethodHandle handle) {}

    private static final ClassValue<Shape> SHAPES = new ClassValue<>() {
        @Override
        protected Shape computeValue(Class<?> type) {
            try {
                return shapeOf(type);
            } catch (LinkageError e) {
                // Reading a class's methods loads their parameter types, which a server lacks.
                HostGui.infoOnce("hud-shape:" + type.getName(), "The old HUD callback {} names a "
                        + "type this side lacks ({}), so it does not draw here.", type.getName(), e.toString());
                return new Shape(Kind.NONE, null);
            }
        }
    };

    private static Shape shapeOf(Class<?> type) {
        // A retyped lambda or a bridge overrides an erased method; that is the cheapest call.
        if (overrides(type, "renderLegacyHud", Object.class, Object.class)) {
            return new Shape(Kind.ERASED_TRACKER, null);
        }
        if (overrides(type, "renderLegacyHudWithPartialTick", Object.class, float.class)) {
            return new Shape(Kind.ERASED_PARTIAL_TICK, null);
        }
        // A mod class keeps the typed method it was written with; its parameter types tell the shape.
        Shape found = new Shape(Kind.NONE, null);
        for (Method method : type.getMethods()) {
            // A sub-interface's bridge forwards to the erased methods, so calling it would loop back here.
            if (!method.getName().equals("onHudRender") || method.getParameterCount() != 2
                    || Modifier.isAbstract(method.getModifiers()) || method.isBridge()) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params[0].getName().equals(POSE_STACK)) {
                found = new Shape(Kind.POSE_STACK, null);
            } else if (params[1] == float.class) {
                return typed(method, Kind.TYPED_PARTIAL_TICK, float.class);
            } else {
                return typed(method, Kind.TYPED_TRACKER, Object.class);
            }
        }
        return found;
    }

    private static boolean overrides(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params).getDeclaringClass() != LegacyHudRenderCallback.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Shape typed(Method method, Kind kind, Class<?> second) {
        try {
            // A lambda's class or a mod's package-private class is not public; its method is.
            method.setAccessible(true);
            MethodHandle handle = MethodHandles.lookup().unreflect(method)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, second));
            return new Shape(kind, handle);
        } catch (IllegalAccessException | RuntimeException e) {
            HostGui.infoOnce("hud-access:" + method.getDeclaringClass().getName(), "The old HUD "
                    + "callback {} cannot be called from Retromod ({}), so it does not draw.",
                    method.getDeclaringClass().getName(), e.toString());
            return new Shape(Kind.NONE, null);
        }
    }

    private static void invoke(MethodHandle handle, Object callback, Object graphics, Object second) {
        try {
            handle.invokeExact(callback, graphics, second);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void invoke(MethodHandle handle, Object callback, Object graphics, float second) {
        try {
            handle.invokeExact(callback, graphics, second);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void reportUnserved(Class<?> type, Shape shape) {
        String why = shape.kind == Kind.POSE_STACK
                ? "draws with the pre-1.20 PoseStack, which no 26.x HUD element accepts"
                : "has no onHudRender method Retromod recognizes";
        HostGui.infoOnce("hud-unserved:" + type.getName(), "The old HUD callback {} {}, so it does not draw.",
                type.getName(), why);
    }
}
