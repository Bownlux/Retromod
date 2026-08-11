/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityModelLayerRegistryBridgeTest {

    @Test
    @DisplayName("provider adapter invokes the relocated interface implemented by the lambda")
    void adaptsRelocatedProviderLambda() throws Exception {
        LayerDefinition expected = new LayerDefinition();
        RelocatedProvider relocated = () -> expected;

        Method originalSam = OriginalGeneratedProvider.class.getMethod("createModelData");
        assertThrows(IllegalArgumentException.class, () -> originalSam.invoke(relocated),
                "the original generated interface cannot invoke a lambda implementing its relocated copy");

        CurrentProvider adapted = (CurrentProvider) EntityModelLayerRegistryBridge.adaptProvider(
                getClass().getClassLoader(), CurrentProvider.class, relocated);

        assertSame(expected, adapted.createLayerDefinition());
    }

    public interface OriginalGeneratedProvider {
        LayerDefinition createModelData();
    }

    public interface RelocatedProvider {
        LayerDefinition createModelData();
    }

    public interface CurrentProvider {
        LayerDefinition createLayerDefinition();
    }

    public static final class LayerDefinition {}
}
