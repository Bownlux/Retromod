/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Stand-in for Forge's {@code CapabilityToken}. Mods create it as an anonymous subclass,
 * {@code new CapabilityToken<MyData>(){}}, so the capability's identity is the type argument
 * recorded in that subclass's generic signature.
 */
public abstract class LegacyCapabilityToken<T> {

    protected LegacyCapabilityToken() {}

    public final String getType() {
        Type superType;
        try {
            superType = getClass().getGenericSuperclass();
        } catch (TypeNotPresentException e) {
            // Reading the signature tries to load the data class; its name is all that is needed.
            return e.typeName();
        }
        if (superType instanceof ParameterizedType parameterized) {
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof ParameterizedType generic) argument = generic.getRawType();
            return argument.getTypeName();
        }
        // A raw token still needs a stable, distinct identity.
        return getClass().getName();
    }

    @Override
    public String toString() {
        return "CapabilityToken[" + getType() + "]";
    }
}
