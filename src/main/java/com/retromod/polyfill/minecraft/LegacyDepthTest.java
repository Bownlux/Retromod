/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Stand-in for the removed {@code DepthTestFunction} and the two pipeline-builder calls that
 * took it. 26.1 folded depth testing and depth writes into one {@code DepthStencilState}, so the
 * builder state is kept per builder and re-applied after each old call. The old defaults were
 * a less-or-equal test with writes on. No test and no writes means no depth state at all.
 *
 * <p>No compile-time Minecraft dependency: the state type is found from the builder's own
 * {@code withDepthStencilState} overloads, so the repackaged 26.3 classes work unchanged.
 */
public final class LegacyDepthTest {

    public static final LegacyDepthTest NO_DEPTH_TEST = new LegacyDepthTest("ALWAYS_PASS");
    public static final LegacyDepthTest EQUAL_DEPTH_TEST = new LegacyDepthTest("EQUAL");
    public static final LegacyDepthTest LEQUAL_DEPTH_TEST = new LegacyDepthTest("LESS_THAN_OR_EQUAL");
    public static final LegacyDepthTest LESS_DEPTH_TEST = new LegacyDepthTest("LESS_THAN");
    public static final LegacyDepthTest GREATER_DEPTH_TEST = new LegacyDepthTest("GREATER_THAN");

    private static final Map<Object, State> STATES = new WeakHashMap<>();

    private final String compareOp;

    private LegacyDepthTest(String compareOp) {
        this.compareOp = compareOp;
    }

    private record State(String compareOp, boolean write) {}

    /** {@code RenderPipeline.Builder.withDepthTestFunction(DepthTestFunction)}. */
    public static Object withDepthTestFunction(Object builder, Object test) {
        String compare = test instanceof LegacyDepthTest legacy ? legacy.compareOp : "LESS_THAN_OR_EQUAL";
        State state;
        synchronized (STATES) {
            state = new State(compare, current(builder).write());
            STATES.put(builder, state);
        }
        return apply(builder, state);
    }

    /** {@code RenderPipeline.Builder.withDepthWrite(boolean)}. */
    public static Object withDepthWrite(Object builder, boolean write) {
        State state;
        synchronized (STATES) {
            state = new State(current(builder).compareOp(), write);
            STATES.put(builder, state);
        }
        return apply(builder, state);
    }

    private static State current(Object builder) {
        State state = STATES.get(builder);
        return state != null ? state : new State("LESS_THAN_OR_EQUAL", true);
    }

    private static Object apply(Object builder, State state) {
        try {
            Method withOptional = null;
            Method withState = null;
            for (Method method : builder.getClass().getMethods()) {
                if (!method.getName().equals("withDepthStencilState") || method.getParameterCount() != 1) continue;
                if (method.getParameterTypes()[0] == Optional.class) {
                    withOptional = method;
                } else {
                    withState = method;
                }
            }
            if (state.compareOp().equals("ALWAYS_PASS") && !state.write() && withOptional != null) {
                return withOptional.invoke(builder, Optional.empty());
            }
            if (withState == null) return builder;
            Class<?> stateType = withState.getParameterTypes()[0];
            for (Constructor<?> constructor : stateType.getConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 2 && params[0].isEnum() && params[1] == boolean.class) {
                    Object compare = enumConstant(params[0], state.compareOp());
                    return withState.invoke(builder, constructor.newInstance(compare, state.write()));
                }
            }
            return builder;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Default depth handling is safer than failing the pipeline's static initializer.
            return builder;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
