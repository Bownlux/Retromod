/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.ClassResourceInspector;
import com.retromod.util.RetromodLog;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps an old mixin's head handler on the biome source's per-position lookup working on 26.3, which removed that
 * lookup and resolves biomes through {@code createResolver}. TerraBlender places its regions this way.
 */
public final class MixinBiomeResolverRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-mixin");

    static final String SOURCE = "net/minecraft/world/level/biome/MultiNoiseBiomeSource";
    static final String SAMPLER = "Lnet/minecraft/world/level/biome/Climate$Sampler;";
    static final String RESOLVER = "net/minecraft/world/level/biome/BiomeResolver";
    static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    static final String OLD_LOOKUP = "getNoiseBiome(III" + SAMPLER + ")Lnet/minecraft/core/Holder;";
    static final String HANDLER_DESC = "(III" + SAMPLER + "L" + CALLBACK + ";)V";
    static final String CREATE = "createResolver(" + SAMPLER + ")L" + RESOLVER + ";";
    static final String CREATE_FOR_CHUNK = "createResolverForChunk(" + SAMPLER + "IIIIII)L" + RESOLVER + ";";

    private static final String ROOT = "com/retromod/legacyapi/minecraft/world/level/biome/";
    static final String HOOK = ROOT + "LegacyNoiseBiomeHook";
    static final String WRAPPER = ROOT + "LegacyNoiseBiomeResolver";
    static final String HOOK_METHOD = "retromod$legacyNoiseBiome";

    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";

    private MixinBiomeResolverRepair() {}

    /** Adds the hook and the wrapper to mods that need them, on hosts that resolve biomes through createResolver. */
    public static void registerClasses(com.retromod.core.RetromodTransformer t) {
        if (hostUsesResolvers()) com.retromod.shim.common.RebuiltApiClasses.registerTree(t, WRAPPER);
    }

    /** Turns the one head handler into the hook a wrapped resolver calls, and wraps both host resolvers. */
    static boolean apply(ClassNode mixin) {
        if ((mixin.access & Opcodes.ACC_INTERFACE) != 0 || !MixinMaterialRuleRepair.targets(mixin).contains(SOURCE)) return false;
        List<MethodNode> handlers = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            if (isOldHeadHandler(method)) handlers.add(method);
        }
        // Two handlers would both need the one hook; that is left as it was.
        if (handlers.size() != 1 || !hostUsesResolvers()) return false;
        MethodNode handler = handlers.get(0);
        if (MixinMaterialRuleRepair.callbackEscapes(handler)) {
            LOGGER.debug(RetromodLog.DETAIL, "Left {} unrepaired: its biome handler uses its callback beyond setting the biome",
                    mixin.name);
            return false;
        }
        // The handler stays as the mod wrote it, as a plain public method the resolver reaches through the hook.
        removeInject(handler);
        handler.name = HOOK_METHOD;
        handler.access = (handler.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        if (!mixin.interfaces.contains(HOOK)) mixin.interfaces.add(HOOK);
        mixin.methods.add(wrapper("retromod$wrapResolver", CREATE, "(" + SAMPLER + "L" + CALLBACK + ";)V", 2));
        mixin.methods.add(wrapper("retromod$wrapChunkResolver", CREATE_FOR_CHUNK,
                "(" + SAMPLER + "IIIIIIL" + CALLBACK + ";)V", 8));
        LOGGER.debug(RetromodLog.DETAIL, "Moved {}'s biome lookup handler onto 26.3's biome resolvers", mixin.name);
        return true;
    }

    /** A non-static {@code @Inject} at HEAD of the removed lookup, with the lookup's own arguments and a callback. */
    static boolean isOldHeadHandler(MethodNode method) {
        AnnotationNode inject = MixinMaterialRuleRepair.annotation(method, INJECT_DESC);
        if (inject == null || (method.access & Opcodes.ACC_STATIC) != 0 || !method.desc.equals(HANDLER_DESC)) return false;
        List<String> selectors = MixinMaterialRuleRepair.selectors(inject);
        if (selectors == null || selectors.isEmpty()) return false;
        for (String selector : selectors) {
            String member = selector.startsWith("L") && selector.contains(";") ? selector.substring(selector.indexOf(';') + 1) : selector;
            // The bare name is safe here: the handler's arguments match only the removed lookup.
            if (!member.equals(OLD_LOOKUP) && !member.equals("getNoiseBiome")) return false;
        }
        return atHead(inject);
    }

    private static boolean atHead(AnnotationNode inject) {
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            if (!"at".equals(inject.values.get(i))) continue;
            Object value = inject.values.get(i + 1);
            List<?> ats = value instanceof List<?> list ? list : List.of(value);
            if (ats.size() != 1 || !(ats.get(0) instanceof AnnotationNode at) || at.values == null) return false;
            for (int j = 0; j + 1 < at.values.size(); j += 2) {
                if ("value".equals(at.values.get(j))) return "HEAD".equals(at.values.get(j + 1));
            }
        }
        return false;
    }

    /** Whether the host's source lost the per-position lookup and resolves through createResolver; offline, 26.3 and newer. */
    static boolean hostUsesResolvers() {
        ClassNode host = ClassResourceInspector.read(SOURCE);
        if (host == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        boolean resolver = false;
        for (MethodNode method : host.methods) {
            if ((method.name + method.desc).equals(OLD_LOOKUP)) return false;
            if ((method.name + method.desc).equals(CREATE)) resolver = true;
        }
        return resolver;
    }

    private static void removeInject(MethodNode handler) {
        if (handler.visibleAnnotations != null) handler.visibleAnnotations.removeIf(a -> INJECT_DESC.equals(a.desc));
        if (handler.invisibleAnnotations != null) handler.invisibleAnnotations.removeIf(a -> INJECT_DESC.equals(a.desc));
    }

    /** A RETURN handler on {@code target} that hands its result to the wrapper, which runs the hook first on each lookup. */
    private static MethodNode wrapper(String name, String target, String desc, int callbackSlot) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
        AnnotationNode at = new AnnotationNode(AT_DESC);
        at.values = new ArrayList<>(List.of("value", "RETURN"));
        AnnotationNode inject = new AnnotationNode(INJECT_DESC);
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(target)), "at", new ArrayList<>(List.of(at)),
                "cancellable", Boolean.TRUE));
        method.visibleAnnotations = new ArrayList<>(List.of(inject));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, WRAPPER));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, HOOK));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK, "getReturnValue", "()Ljava/lang/Object;", false));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, RESOLVER));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, WRAPPER, "<init>",
                "(L" + HOOK + ";" + SAMPLER + "L" + RESOLVER + ";)V", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK, "setReturnValue", "(Ljava/lang/Object;)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 6;
        method.maxLocals = callbackSlot + 1;
        return method;
    }
}
