/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.world.gen.treedecorator.TreeDecorator;
import net.minecraft.world.gen.treedecorator.TreeDecoratorType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Load-time regression for the worldgen registrar bridge. The registrar and the constructor are
 * both private, so a mod can only register a custom tree decorator type through invokers, and
 * both of them still carry the {@code Codec} descriptor this mod was built against.
 */
@Mixin(TreeDecoratorType.class)
public abstract class LegacyTreeDecoratorTypeMixin {

    @Invoker("register")
    public static <P extends TreeDecorator> TreeDecoratorType<P> retromod$invokeRegister(
            String id, Codec<P> codec) {
        throw new AssertionError("Mixin did not replace the legacy tree decorator registrar");
    }

    @Invoker("<init>")
    public static <P extends TreeDecorator> TreeDecoratorType<P> retromod$newType(Codec<P> codec) {
        throw new AssertionError("Mixin did not replace the legacy tree decorator constructor");
    }
}
