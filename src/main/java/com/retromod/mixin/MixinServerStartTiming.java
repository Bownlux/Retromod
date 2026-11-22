/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.util.RetromodLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Moves an old Fabric mod's server-constructor hook that sets up per-dimension worldgen to the start of level loading.
 * On 26.3 a new server has not yet bound the dimension type tags when its constructor returns, so TerraBlender's
 * region setup there found no dimension it applies to and placed no modded biome.
 */
final class MixinServerStartTiming {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String LOAD_LEVEL = "loadLevel()V";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CALLBACK = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";

    // Setup calls known to read level stems and their tags; a hook that calls one of them needs the loaded level.
    static final Set<String> LEVEL_SETUP_CALLS = Set.of("terrablender/util/LevelUtils.initializeOnServerStart");

    private MixinServerStartTiming() {}

    static boolean apply(ClassNode mixin) {
        if ((mixin.access & Opcodes.ACC_INTERFACE) != 0 || !MixinMaterialRuleRepair.targets(mixin).contains(SERVER)) return false;
        if (!MixinBiomeResolverRepair.hostUsesResolvers()) return false;
        boolean changed = false;
        for (MethodNode handler : mixin.methods) {
            AnnotationNode inject = MixinMaterialRuleRepair.annotation(handler, INJECT_DESC);
            if (inject == null || !handler.desc.equals(CALLBACK) || !constructorReturn(inject) || !callsLevelSetup(handler)) continue;
            retarget(inject);
            changed = true;
            LOGGER.debug(RetromodLog.DETAIL, "Moved {}.{} from the server constructor to level loading", mixin.name, handler.name);
        }
        return changed;
    }

    /** An injection at the return of the server constructor. */
    private static boolean constructorReturn(AnnotationNode inject) {
        List<String> selectors = MixinMaterialRuleRepair.selectors(inject);
        if (selectors == null || selectors.isEmpty() || !selectors.stream().allMatch(s -> s.equals("<init>") || s.startsWith("<init>("))) {
            return false;
        }
        return "RETURN".equals(atValue(inject));
    }

    private static boolean callsLevelSetup(MethodNode handler) {
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof MethodInsnNode call && LEVEL_SETUP_CALLS.contains(call.owner + "." + call.name)) return true;
        }
        return false;
    }

    /** Points the injection at the head of loadLevel, which a dedicated and an integrated server both run before any world exists. */
    private static void retarget(AnnotationNode inject) {
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if ("method".equals(inject.values.get(i))) inject.values.set(i + 1, new ArrayList<>(List.of(LOAD_LEVEL)));
            if ("at".equals(inject.values.get(i))) {
                AnnotationNode head = new AnnotationNode(AT_DESC);
                head.values = new ArrayList<>(List.of("value", "HEAD"));
                inject.values.set(i + 1, new ArrayList<>(List.of(head)));
            }
        }
    }

    private static String atValue(AnnotationNode inject) {
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if (!"at".equals(inject.values.get(i))) continue;
            Object value = inject.values.get(i + 1);
            Object at = value instanceof List<?> list && list.size() == 1 ? list.get(0) : value;
            if (!(at instanceof AnnotationNode node) || node.values == null) return null;
            for (int j = 0; j + 1 < node.values.size(); j += 2) {
                if ("value".equals(node.values.get(j))) return String.valueOf(node.values.get(j + 1));
            }
        }
        return null;
    }
}
