/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Points an old mixin's surface rule shadow and injection at 26.3's rule holder, and skips old context mixins it cannot keep. */
final class MixinMaterialRuleRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    static final String SETTINGS = "net/minecraft/world/level/levelgen/NoiseGeneratorSettings";
    static final String RULE = "net/minecraft/world/level/levelgen/material/rule/MaterialRule";
    static final String HOLDER = "net/minecraft/core/Holder";
    static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    static final String OLD_NAME = "surfaceRule";
    static final String NEW_NAME = "materialRule";
    static final String CONTEXT = "net/minecraft/world/level/levelgen/material/MaterialRuleContext";
    // 26.3's context fields, for offline translation where the host class cannot be read.
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "system:Lnet/minecraft/world/level/levelgen/material/MaterialSystem;",
            "randomState:Lnet/minecraft/world/level/levelgen/RandomState;",
            "expectedVolume:Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;",
            "densitySamplers:Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;",
            "biomeGetter:Ljava/util/function/Function;", "context:Lnet/minecraft/world/level/levelgen/WorldGenerationContext;",
            "possibleBiomes:Ljava/util/Set;",
            "preliminarySurfaceVolume:Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;",
            "preliminarySurfaceBuffer:Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;",
            "noiseSamplers2d:Ljava/util/Map;", "noiseSamplers3d:Ljava/util/Map;", "lastUpdateXZ:J", "blockX:I", "blockZ:I",
            "surfaceGradientX:I", "surfaceGradientZ:I", "surfaceDepth:I", "lastSurfaceDepth2Update:J", "surfaceSecondary:D",
            "lastMinSurfaceLevelUpdate:J", "minSurfaceLevel:I", "lastUpdateY:J", "pos:Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            "biome:Lnet/minecraft/core/Holder;", "blockY:I", "waterHeight:I", "stoneDepthBelow:I", "stoneDepthAbove:I");
    private static final String RULE_DESC = "L" + RULE + ";";
    private static final String HOLDER_DESC = "L" + HOLDER + ";";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private MixinMaterialRuleRepair() {}

    /** {@code check} is null when no host jar is indexed, and then no settings mixin is repaired. */
    static boolean apply(ClassNode mixin, MixinLinkCheck check) {
        return repairSettingsMixin(mixin, check) | skipContextMixin(mixin) | MixinRuleHolderAccessors.retarget(mixin);
    }

    /** Keeps an old context mixin skipped, as its missing target left it, when it shadows a field 26.3's context dropped. */
    static boolean skipContextMixin(ClassNode mixin) {
        // A stand-in for the chunk, the noise chunk or the old surface system would hold nothing the mod could use.
        if (!targets(mixin).contains(CONTEXT)) return false;
        Set<String> host = hostContextFields();
        if (host == null) return false;
        for (FieldNode field : mixin.fields) {
            boolean shadow = has(field.visibleAnnotations, SHADOW_DESC) || has(field.invisibleAnnotations, SHADOW_DESC);
            if (shadow && !host.contains(field.name + ":" + field.desc) && MixinCompatibilityTransformer.neutralizeMixin(mixin)) {
                LOGGER.debug(RetromodLog.DETAIL, "Disabled Mixin {} because 26.3's surface rule context has no {} field. "
                        + "Its changes to the game are not applied.", mixin.name, field.name);
                return true;
            }
        }
        return false;
    }

    /** The host context's fields; offline, 26.3's, and null before 26.3, where the old context still exists. */
    private static Set<String> hostContextFields() {
        ClassNode host = ClassResourceInspector.read(CONTEXT);
        if (host == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0 ? CONTEXT_FIELDS : null;
        }
        Set<String> fields = new HashSet<>();
        for (FieldNode field : host.fields) fields.add(field.name + ":" + field.desc);
        return fields;
    }

    static boolean repairSettingsMixin(ClassNode mixin, MixinLinkCheck check) {
        // An interface accessor is not merged into the settings, so its shadow cannot become a holder read (pitfall 20).
        if ((mixin.access & Opcodes.ACC_INTERFACE) != 0 || !targets(mixin).contains(SETTINGS)) return false;
        if (shadowRule(mixin) == null && ruleHandlers(mixin).isEmpty()) return false;
        if (!hostKeepsRuleInHolder() || check == null) return false;
        // A handler may only set the rule or cancel; any other use of its callback would see a holder.
        for (MethodNode handler : ruleHandlers(mixin)) {
            if (callbackEscapes(handler)) {
                LOGGER.debug(RetromodLog.DETAIL, "Left {} unrepaired: its handler {} uses its callback beyond setting the "
                        + "surface rule", mixin.name, handler.name);
                return false;
            }
        }
        // The repaired code runs whenever a world generates, so everything it loads must link (pitfall 21).
        ClassNode repaired = new ClassNode();
        mixin.accept(repaired);
        repair(repaired);
        String missing = check.firstMissing(repaired, null);
        if (missing != null) {
            LOGGER.debug(RetromodLog.DETAIL, "Left {} unrepaired: its code needs {}, which this host lacks", mixin.name, missing);
            return false;
        }
        repair(mixin);
        return true;
    }

    /** Moves the shadow and the handlers of a settings mixin onto the rule holder. */
    private static void repair(ClassNode mixin) {
        FieldNode shadow = shadowRule(mixin);
        List<MethodNode> handlers = ruleHandlers(mixin);
        if (shadow != null) {
            shadow.name = NEW_NAME;
            shadow.desc = HOLDER_DESC;
            shadow.signature = null;
            for (MethodNode method : mixin.methods) useHolder(mixin, method);
        }
        for (MethodNode handler : handlers) {
            retargetSelectors(handler);
            wrapReturnValues(handler);
        }
    }

    /** Whether the handler does more with its callback than set the return value or cancel. */
    static boolean callbackEscapes(MethodNode handler) {
        Type[] arguments = Type.getArgumentTypes(handler.desc);
        int slot = (handler.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        int callback = -1;
        for (Type argument : arguments) {
            if (argument.getSort() == Type.OBJECT && argument.getInternalName().equals(CALLBACK)) {
                callback = slot;
                break;
            }
            slot += argument.getSize();
        }
        if (callback < 0) return true;
        // Every load of the callback must feed one of its two allowed calls, so it is passed nowhere else.
        int loads = 0;
        int allowed = 0;
        for (AbstractInsnNode insn : handler.instructions) {
            if (insn instanceof VarInsnNode local && local.var == callback) {
                if (local.getOpcode() != Opcodes.ALOAD) return true;
                loads++;
            } else if (insn instanceof MethodInsnNode call && call.owner.equals(CALLBACK)) {
                if (!call.name.equals("setReturnValue") && !call.name.equals("cancel")) return true;
                allowed++;
            }
        }
        return loads != allowed;
    }

    /** Whether the host's settings hold the rule in a holder and lost the old accessor; offline, 26.3 and newer do. */
    static boolean hostKeepsRuleInHolder() {
        ClassNode host = ClassResourceInspector.read(SETTINGS);
        if (host == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        boolean holder = false;
        for (MethodNode method : host.methods) {
            if (method.name.equals(OLD_NAME)) return false;
            if (method.name.equals(NEW_NAME) && method.desc.equals("()" + HOLDER_DESC)) holder = true;
        }
        return holder;
    }

    private static FieldNode shadowRule(ClassNode mixin) {
        for (FieldNode field : mixin.fields) {
            if (field.name.equals(OLD_NAME) && field.desc.equals(RULE_DESC)
                    && (has(field.visibleAnnotations, SHADOW_DESC) || has(field.invisibleAnnotations, SHADOW_DESC))) {
                return field;
            }
        }
        return null;
    }

    /** The {@code @Inject} handlers whose every selector names the old accessor. */
    private static List<MethodNode> ruleHandlers(ClassNode mixin) {
        List<MethodNode> handlers = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            AnnotationNode inject = annotation(method, INJECT_DESC);
            List<String> selectors = inject == null ? null : selectors(inject);
            if (selectors != null && !selectors.isEmpty() && selectors.stream().allMatch(MixinMaterialRuleRepair::namesOldAccessor)) {
                handlers.add(method);
            }
        }
        return handlers;
    }

    /** {@code surfaceRule}, with or without its owner and descriptor. */
    static boolean namesOldAccessor(String selector) {
        String member = selector.startsWith("L") && selector.contains(";") ? selector.substring(selector.indexOf(';') + 1) : selector;
        return member.equals(OLD_NAME) || member.equals(OLD_NAME + "()" + RULE_DESC);
    }

    static String retarget(String selector) {
        String owner = selector.startsWith("L") && selector.contains(";") ? selector.substring(0, selector.indexOf(';') + 1) : "";
        String member = selector.substring(owner.length());
        return owner + (member.equals(OLD_NAME) ? NEW_NAME : NEW_NAME + "()" + HOLDER_DESC);
    }

    private static void retargetSelectors(MethodNode handler) {
        AnnotationNode inject = annotation(handler, INJECT_DESC);
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if ("method".equals(inject.values.get(i)) && inject.values.get(i + 1) instanceof List<?> values) {
                List<Object> retargeted = new ArrayList<>();
                for (Object value : values) retargeted.add(retarget((String) value));
                inject.values.set(i + 1, retargeted);
            }
        }
    }

    /** Each read of the shadow takes the rule out of the holder; each write, as Citadel's swap does, puts one in. */
    private static void useHolder(ClassNode mixin, MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode field) || !field.owner.equals(mixin.name) || !field.name.equals(OLD_NAME)
                    || !field.desc.equals(RULE_DESC)) {
                continue;
            }
            field.name = NEW_NAME;
            field.desc = HOLDER_DESC;
            if (field.getOpcode() == Opcodes.GETFIELD) {
                InsnList value = new InsnList();
                value.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true));
                value.add(new TypeInsnNode(Opcodes.CHECKCAST, RULE));
                method.instructions.insert(field, value);
            } else if (field.getOpcode() == Opcodes.PUTFIELD) {
                method.instructions.insertBefore(field, new MethodInsnNode(Opcodes.INVOKESTATIC, HOLDER, "direct",
                        "(Ljava/lang/Object;)" + HOLDER_DESC, true));
            }
        }
    }

    /** The rule a handler returns goes back wrapped in a holder, as the host's accessor now returns. */
    private static void wrapReturnValues(MethodNode handler) {
        for (AbstractInsnNode insn = handler.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (isCallbackCall(insn, "setReturnValue")) {
                handler.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HOLDER, "direct",
                        "(Ljava/lang/Object;)" + HOLDER_DESC, true));
            }
        }
    }

    private static boolean isCallbackCall(AbstractInsnNode insn, String name) {
        return insn instanceof MethodInsnNode call && call.owner.equals(CALLBACK) && call.name.equals(name);
    }

    private static List<String> selectors(AnnotationNode inject) {
        if (inject.values == null) return null;
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if ("method".equals(inject.values.get(i)) && inject.values.get(i + 1) instanceof List<?> values) {
                List<String> result = new ArrayList<>();
                for (Object value : values) result.add(String.valueOf(value));
                return result;
            }
        }
        return null;
    }

    private static List<String> targets(ClassNode mixin) {
        List<String> result = new ArrayList<>();
        for (List<AnnotationNode> annotations : lists(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_DESC.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    Object value = annotation.values.get(i + 1);
                    if (!(value instanceof List<?> values)) continue;
                    for (Object target : values) {
                        result.add(target instanceof Type type ? type.getInternalName() : target.toString().replace('.', '/'));
                    }
                }
            }
        }
        return result;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        for (List<AnnotationNode> annotations : lists(method.visibleAnnotations, method.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (desc.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static boolean has(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) return true;
        }
        return false;
    }

    private static List<List<AnnotationNode>> lists(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        return List.of(visible != null ? visible : List.of(), invisible != null ? invisible : List.of());
    }
}
