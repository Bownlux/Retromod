/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Finds and runs an old item renderer as Forge 1.20.1 did, whichever translated jar's copy of the rebuilt base it extends. */
public final class LegacyItemRendererCalls {

    private static final String GETTER = "getCustomRenderer";
    private static final String RENDER = "renderByItem";
    // The rebuilt base under its own name, which every jar's copy keeps after its embedded package prefix.
    private static final String BASE_SUFFIX = ".legacyapi.minecraft.client.renderer.LegacyItemRenderer";
    /** What an extension object without getCustomRenderer answers, as IClientItemExtensions.DEFAULT does. */
    static final Object NO_GETTER = new Object();

    // Forge 1.20.1's default getCustomRenderer gave vanilla's item renderer, which drew only vanilla's own items.
    private static final LegacyItemRenderer DEFAULT = new LegacyItemRenderer();
    private static final ClassValue<Optional<Method>> GETTERS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return Optional.ofNullable(findGetter(type));
        }
    };
    private static final ClassValue<Optional<ForeignRender>> RENDERS = new ClassValue<>() {
        @Override
        protected Optional<ForeignRender> computeValue(Class<?> type) {
            return Optional.ofNullable(findRender(type));
        }
    };
    // Another jar's renderers, each wrapped once, so a mod that keeps the result gets the same object again. The wrapper
    // holds its renderer, so it is held weakly here: an entry leaves once nothing else keeps the wrapper.
    private static final Map<Object, WeakReference<LegacyItemRenderer>> FOREIGN = new WeakHashMap<>();
    // The loader's IClientItemExtensions.of(ItemStack), which the loader's install hands this copy.
    private static volatile Function<ItemStack, Object> extensions;

    private LegacyItemRendererCalls() {}

    /** Sets how this copy finds a stack's client extensions: Forge's or NeoForge's {@code IClientItemExtensions.of}. */
    public static void useExtensions(Function<ItemStack, Object> lookup) {
        extensions = lookup;
    }

    /** The stack's client extensions, or null before a loader's install named its lookup. */
    static Object extensionsOf(ItemStack stack) {
        Function<ItemStack, Object> lookup = extensions;
        return lookup == null ? null : lookup.apply(stack);
    }

    // Forge's and NeoForge's IClientItemExtensions lost getCustomRenderer, and an old call on either arrives here.
    /** {@code IClientItemExtensions.getCustomRenderer()}, which Forge 26.2 and NeoForge 1.21.4 removed: the mod's own method. */
    public static LegacyItemRenderer getCustomRenderer(Object extensions) {
        Object renderer = rendererOf(Objects.requireNonNull(extensions, "extensions"));
        if (renderer == NO_GETTER) return DEFAULT;
        if (renderer == null || renderer instanceof LegacyItemRenderer) return (LegacyItemRenderer) renderer;
        // A renderer extending another jar's copy cannot be cast to this one, so it is wrapped.
        synchronized (FOREIGN) {
            WeakReference<LegacyItemRenderer> known = FOREIGN.get(renderer);
            LegacyItemRenderer wrapper = known == null ? null : known.get();
            if (wrapper == null) {
                wrapper = new Foreign(renderer);
                FOREIGN.put(renderer, new WeakReference<>(wrapper));
            }
            return wrapper;
        }
    }

    /** {@code IClientItemExtensions.super.getCustomRenderer()} from a mod's own override: the default renderer. */
    public static LegacyItemRenderer superGetCustomRenderer(Object extensions) {
        return DEFAULT;
    }

    /** What {@code extensions.getCustomRenderer()} returns, or {@link #NO_GETTER} when it declares none. */
    static Object rendererOf(Object extensions) {
        Method getter = GETTERS.get(extensions.getClass()).orElse(null);
        if (getter == null) return NO_GETTER;
        try {
            return getter.invoke(extensions);
        } catch (IllegalAccessException failure) {
            LegacyLog.warnOnce("item renderer getter " + extensions.getClass().getName(), "Retromod could not call getCustomRenderer"
                    + " on " + extensions.getClass().getName() + ", so it draws that item without its old renderer: " + failure);
            return NO_GETTER;
        } catch (InvocationTargetException failure) {
            throw rethrow(failure.getCause());
        }
    }

    /** Runs {@code renderer.renderByItem}, handing it this copy's buffers in the type its own copy of the base takes. */
    static void render(Object renderer, ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
            int light, int overlay) throws ReflectiveOperationException {
        // A renderer from this jar takes this copy's buffer source, and the call reaches its override directly.
        if (renderer instanceof LegacyItemRenderer own) {
            own.renderByItem(stack, context, poseStack, buffers, light, overlay);
            return;
        }
        ForeignRender render = RENDERS.get(renderer.getClass())
                .orElseThrow(() -> new NoSuchMethodException(renderer.getClass().getName() + "." + RENDER));
        // The proxy for that copy's buffer source type is made once and pointed at this draw's buffers.
        ForeignBuffers foreign = render.buffers();
        if (!foreign.target.compareAndSet(null, buffers)) {
            foreign = new ForeignBuffers(render.bufferType());
            foreign.target.set(buffers);
        }
        try {
            render.method().invoke(renderer, stack, context, poseStack, foreign.proxy, light, overlay);
        } catch (InvocationTargetException failure) {
            throw rethrow(failure.getCause());
        } finally {
            foreign.target.set(null);
        }
    }

    // The no-argument getCustomRenderer the class runs: its own or a superclass's first, then an interface default, as the
    // JVM picks. Each class is read on its own, so one whose other methods name a removed class hides no other's getter.
    private static Method findGetter(Class<?> type) {
        Deque<Class<?>> interfaces = new ArrayDeque<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Method found = declaredGetter(current);
            if (found != null) return accessible(found);
            Collections.addAll(interfaces, current.getInterfaces());
        }
        Set<Class<?>> seen = new HashSet<>();
        while (!interfaces.isEmpty()) {
            Class<?> current = interfaces.poll();
            if (!seen.add(current)) continue;
            Method found = declaredGetter(current);
            if (found != null) return accessible(found);
            Collections.addAll(interfaces, current.getInterfaces());
        }
        return null;
    }

    private static Method declaredGetter(Class<?> type) {
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(GETTER) && method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                        && !Modifier.isAbstract(method.getModifiers()) && method.getReturnType() != void.class) {
                    return method;
                }
            }
        } catch (LinkageError unreadable) {
            LegacyLog.warnOnce("item renderer getter " + type.getName(), "Retromod could not read the methods of " + type.getName()
                    + " while looking for getCustomRenderer: " + unreadable);
        }
        return null;
    }

    // renderByItem as the renderer's copy of the rebuilt base declares it; virtual dispatch reaches the mod's override.
    // Only the base is read, since a mod's renderer may have other methods that name classes the host removed.
    private static ForeignRender findRender(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (!current.getName().endsWith(BASE_SUFFIX)) continue;
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(RENDER) && method.getParameterCount() == 6) {
                    Class<?> bufferType = method.getParameterTypes()[3];
                    if (!bufferType.isInterface()) return null;
                    return new ForeignRender(accessible(method), bufferType, new ForeignBuffers(bufferType));
                }
            }
        }
        return null;
    }

    private static Method accessible(Method method) {
        // A mod's renderer properties are often an anonymous class, whose public method is still not reachable without this.
        try {
            method.setAccessible(true);
        } catch (RuntimeException closed) {
            // A module that keeps the package closed still allows calls on public members of public classes.
        }
        return method;
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error error) throw error;
        throw new IllegalStateException(cause);
    }

    /** Another copy's renderByItem and the type of buffer source it takes. */
    private record ForeignRender(Method method, Class<?> bufferType, ForeignBuffers buffers) {}

    /** A proxy of another copy's buffer source interface, whose getBuffer asks the buffers of the draw in progress. */
    private static final class ForeignBuffers implements InvocationHandler {
        // The proxy reads every member's types once, so the stand-in's statics need ByteBufferBuilder, which 26.2 and
        // 26.3 both have.
        final Object proxy;
        final AtomicReference<MultiBufferSource> target = new AtomicReference<>();

        ForeignBuffers(Class<?> type) {
            proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this);
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            int count = method.getParameterCount();
            switch (method.getName()) {
                case "getBuffer":
                    if (count != 1) break;
                    MultiBufferSource buffers = target.get();
                    if (buffers != null) return buffers.getBuffer((RenderType) args[0]);
                    // A renderer that kept the buffer source past its draw writes into nothing, as no draw is open.
                    LegacyLog.warnOnce("stale item renderer buffers " + method.getDeclaringClass().getName(), "An old item renderer"
                            + " asked for a buffer after its draw had ended, so Retromod dropped what it wrote");
                    return LegacyItemGeometry.discard();
                case "hashCode":
                    if (count == 0) return System.identityHashCode(self);
                    break;
                case "equals":
                    if (count == 1) return self == args[0];
                    break;
                case "toString":
                    if (count == 0) return "Retromod item renderer buffers";
                    break;
                default:
                    break;
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
            throw new UnsupportedOperationException(method.getDeclaringClass().getName() + "." + method.getName());
        }
    }

    /** Another jar's renderer as this copy's type, for a mod that holds on to what getCustomRenderer returned. */
    private static final class Foreign extends LegacyItemRenderer {
        private final Object target;

        Foreign(Object target) {
            this.target = target;
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
            try {
                render(target, stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
            } catch (ReflectiveOperationException failure) {
                LegacyLog.warnOnce("foreign item renderer " + target.getClass().getName(), "Retromod could not run the item renderer "
                        + target.getClass().getName() + " for another mod: " + failure);
            }
        }

        @Override
        public void onResourceManagerReload(ResourceManager manager) {
            if (target instanceof ResourceManagerReloadListener listener) listener.onResourceManagerReload(manager);
        }

        @Override
        public String toString() {
            return target.toString();
        }
    }
}
