/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime half of the removed Fabric {@code EntityModelLayerRegistry} bridge.
 *
 * <p>26.1's {@code ModelLayerRegistry} renamed the provider SAM
 * ({@code TexturedModelDataProvider.createModelData} to
 * {@code TexturedLayerDefinitionProvider.createLayerDefinition}); the return type is
 * the same class (Yarn {@code TexturedModelData} = Mojang {@code LayerDefinition},
 * already remapped by the harvest), so the wrapper calls the old SAM and returns its
 * value. {@code registerModelLayer} wraps the mod's old provider in a new one and
 * forwards once; a reflective miss leaves the layer unregistered and logs.</p>
 */
public final class EntityModelLayerRegistryBridge {

    private EntityModelLayerRegistryBridge() {}

    private static final String TAG = "[Retromod] EntityModelLayerRegistryBridge: ";

    private static final String MLR = "net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry";
    private static final String NEW_PROVIDER = MLR + "$TexturedLayerDefinitionProvider";
    private static final String MODEL_LAYER_LOCATION = "net.minecraft.client.model.geom.ModelLayerLocation";

    private static ClassLoader cl() {
        return EntityModelLayerRegistryBridge.class.getClassLoader();
    }

    /** Wraps a v1 provider as a v2 {@code TexturedLayerDefinitionProvider} and registers it. */
    public static void registerModelLayer(Object loc, Object oldProvider) {
        try {
            ClassLoader cl = cl();
            Class<?> mlr = Class.forName(MLR, true, cl);
            Class<?> newProviderType = Class.forName(NEW_PROVIDER, false, cl);
            Class<?> locType = Class.forName(MODEL_LAYER_LOCATION, false, cl);

            Object wrapped = adaptProvider(cl, newProviderType, oldProvider);

            mlr.getMethod("registerModelLayer", locType, newProviderType).invoke(null, loc, wrapped);
        } catch (Throwable t) {
            System.out.println(TAG + "registerModelLayer failed (" + t + "); this model layer is not registered.");
        }
    }

    /**
     * Adapts the provider object itself instead of loading the generated provider by
     * its original name. SyntheticEmbedder gives each transformed mod a private copy
     * of that interface, so a lambda from the mod is not an instance of the original
     * generated interface even though both interfaces have the same SAM.
     */
    static Object adaptProvider(ClassLoader loader, Class<?> newProviderType, Object oldProvider) {
        if (newProviderType.isInstance(oldProvider)) {
            return oldProvider;
        }

        Method newSam = sam(newProviderType);
        Method oldSam = providerSam(oldProvider, newSam.getReturnType());
        return Proxy.newProxyInstance(loader, new Class<?>[]{newProviderType}, (proxy, method, args) -> {
            if (method.equals(newSam)) {
                return invokeReturning(oldSam, oldProvider);
            }
            return objectMethod(proxy, method, args);
        });
    }

    private static Method sam(Class<?> declared) {
        for (Method m : declared.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) && isSam(m)) return m;
        }
        throw new IllegalStateException("no SAM on " + declared.getName());
    }

    private static Method providerSam(Object provider, Class<?> expectedReturnType) {
        if (provider == null) {
            throw new IllegalArgumentException("model layer provider is null");
        }

        Set<Method> candidates = new LinkedHashSet<>();
        for (Class<?> type = provider.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> providerInterface : type.getInterfaces()) {
                for (Method method : providerInterface.getMethods()) {
                    if (Modifier.isAbstract(method.getModifiers())
                            && isSam(method)
                            && method.getParameterCount() == 0
                            && expectedReturnType.isAssignableFrom(method.getReturnType())) {
                        candidates.add(method);
                    }
                }
            }
        }

        Method namedCandidate = null;
        for (Method candidate : candidates) {
            if ("createModelData".equals(candidate.getName())) {
                if (namedCandidate != null) {
                    throw ambiguousProvider(provider, expectedReturnType);
                }
                namedCandidate = candidate;
            }
        }
        if (namedCandidate != null) {
            return namedCandidate;
        }
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        throw ambiguousProvider(provider, expectedReturnType);
    }

    private static IllegalStateException ambiguousProvider(Object provider, Class<?> expectedReturnType) {
        return new IllegalStateException("provider " + provider.getClass().getName()
                + " does not expose one zero-argument model layer method returning "
                + expectedReturnType.getName());
    }

    private static boolean isSam(Method m) {
        String n = m.getName();
        return m.getDeclaringClass().isInterface()
                && !("equals".equals(n) && m.getParameterCount() == 1)
                && !"hashCode".equals(n)
                && !"toString".equals(n);
    }

    private static Object invokeReturning(Method m, Object target) throws Throwable {
        try {
            return m.invoke(target);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":   return proxy == (args == null ? null : args[0]);
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "RetromodModelLayerProvider@" + Integer.toHexString(System.identityHashCode(proxy));
            default:         return null;
        }
    }
}
