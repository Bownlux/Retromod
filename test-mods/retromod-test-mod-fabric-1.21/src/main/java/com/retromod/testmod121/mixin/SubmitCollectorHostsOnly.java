/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/** Applies this config only on hosts with 1.21.9's submit collector, the shape its mixins are written for. */
public final class SubmitCollectorHostsOnly implements IMixinConfigPlugin {

    // Joined at run time, so no constant names a class for a remapper to rewrite.
    private static final String[] COLLECTOR = {String.join("/", "net", "minecraft", "class_11659"),
            String.join("/", "net", "minecraft", "client", "renderer", "SubmitNodeCollector")};

    private boolean hostHasCollector;

    @Override
    public void onLoad(String mixinPackage) {
        for (String name : COLLECTOR) {
            try {
                MixinService.getService().getBytecodeProvider().getClassNode(name);
                hostHasCollector = true;
            } catch (Exception absent) {
                // Not this name, or a host before 1.21.9.
            }
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return hostHasCollector;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
