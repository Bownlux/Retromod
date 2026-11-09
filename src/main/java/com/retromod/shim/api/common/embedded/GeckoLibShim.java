/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

/** Routes legacy GeckoLib v3 calls to whatever GeckoLib version is present at runtime. */
public class GeckoLibShim {
    
    public static void initialize() {
        System.out.println("[Retromod] GeckoLib legacy initialize() called - v4+ auto-initializes");
    }
    
    public static void registerControllersCompat(Object animatable, Object dataOrRegistrar) {
        try {
            String className = dataOrRegistrar.getClass().getName();
            if (className.contains("AnimationData")) {
                for (Method m : LegacyPublicMethods.methods(animatable.getClass())) {
                    if (m.getName().equals("registerControllers")) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1 && params[0].getName().contains("ControllerRegistrar")) {
                            Object registrar = createRegistrarFromData(dataOrRegistrar);
                            m.invoke(animatable, registrar);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Retromod] GeckoLib registerControllers compat failed: " + e.getMessage());
        }
    }
    
    private static Object createRegistrarFromData(Object animationData) {
        try {
            Method getControllers = LegacyPublicMethods.method(animationData.getClass(), "getAnimationControllers");
            return getControllers.invoke(animationData);
        } catch (Exception e) {
            return animationData;
        }
    }
    
    public static Object createController(Object animatable, String name, float transitionTick, Object predicate) {
        try {
            Class<?> controllerClass = Class.forName("software.bernie.geckolib.animation.AnimationController");
            for (Constructor<?> c : controllerClass.getConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 4) {
                    int ticks = Math.round(transitionTick);
                    return c.newInstance(animatable, name, ticks, predicate);
                }
            }
            Class<?> oldControllerClass = Class.forName("software.bernie.geckolib3.core.controller.AnimationController");
            for (Constructor<?> c : oldControllerClass.getConstructors()) {
                if (c.getParameterCount() == 4) {
                    return c.newInstance(animatable, name, transitionTick, predicate);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot create AnimationController", e);
        }
        return null;
    }
    
    public static Object addAnimation(Object builder, String animationName) {
        return addAnimationWithLoop(builder, animationName, true);
    }
    
    public static Object addAnimationWithLoop(Object builder, String animationName, boolean loop) {
        try {
            String builderClassName = builder.getClass().getName();
            if (builderClassName.contains("RawAnimation")) {
                Class<?> loopTypeClass = Class.forName("software.bernie.geckolib.animation.Animation$LoopType");
                Object loopType = loop ? loopTypeClass.getField("LOOP").get(null) : loopTypeClass.getField("PLAY_ONCE").get(null);
                Method thenMethod = LegacyPublicMethods.method(builder.getClass(), "then", String.class, loopTypeClass);
                return thenMethod.invoke(builder, animationName, loopType);
            } else {
                Method addMethod = LegacyPublicMethods.method(builder.getClass(), "addAnimation", String.class, boolean.class);
                return addMethod.invoke(builder, animationName, loop);
            }
        } catch (Exception e) {
            try {
                Method addMethod = LegacyPublicMethods.method(builder.getClass(), "addAnimation", String.class);
                return addMethod.invoke(builder, animationName);
            } catch (Exception e2) {
                throw new RuntimeException("Cannot add animation: " + animationName, e2);
            }
        }
    }
    
    public static Object createRawAnimation() {
        try {
            Class<?> rawAnimClass = Class.forName("software.bernie.geckolib.animation.RawAnimation");
            Method begin = rawAnimClass.getMethod("begin");
            return begin.invoke(null);
        } catch (Exception e) {
            try {
                Class<?> builderClass = Class.forName("software.bernie.geckolib3.core.builder.AnimationBuilder");
                return builderClass.getConstructor().newInstance();
            } catch (Exception e2) {
                throw new RuntimeException("Cannot create animation builder", e2);
            }
        }
    }
}
