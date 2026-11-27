/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.RenderBufferSynthetics;
import com.retromod.shim.neoforge.LegacyNeoForgeClientApiBridge;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Rebuilds the shader, player render and anger events, the positional fluid reads, the old spawn event constructor and armor hooks on Forge 26.2. */
public final class LegacyForgeClientApiBridge {

    private static final String OLD = "net/minecraftforge/";
    static final String OLD_SHADERS = OLD + "client/event/RegisterShadersEvent";
    static final String OLD_RENDER_PLAYER = OLD + "client/event/RenderPlayerEvent";
    static final String OLD_ANGER = OLD + "event/entity/living/EnderManAngerEvent";
    static final String FLUID_EXT = OLD + "client/extensions/common/IClientFluidTypeExtensions";
    static final String FINALIZE = OLD + "event/entity/living/MobSpawnEvent$FinalizeSpawn";
    static final String RENDER_AVATAR = OLD + "client/event/RenderAvatarEvent";

    static final String ROOT = RebuiltApiClasses.ROOT + "forge/";
    static final String SHADERS = ROOT + "client/event/RegisterShadersEvent";
    static final String RENDER_PLAYER = ROOT + "client/event/RenderPlayerEvent";
    // Generated, as its entity's class is EnderMan on Forge 26.2 and Enderman on 26.3.
    static final String ANGER = "com/retromod/generated/LegacyEnderManAngerEvent";
    static final String LIVING_EVENT = ROOT + "event/LegacyLivingEvent";
    /** Minecraft 26.3 renamed EnderMan to Enderman. */
    static final String ENDERMAN_RENAMED = "26.3";
    static final String FLUID_READS = ROOT + "client/LegacyForgeFluidTypeReads";
    static final String FINALIZE_FACTORY = ROOT + "event/LegacyFinalizeSpawn";
    /** The rebuilt class whose {@code install()} posts the shader and player render events. */
    public static final String INSTALLER = ROOT + "client/event/LegacyForgeClientEvents";
    // On Forge 26.2 an old shader instance is Retromod's inert stand-in, which registerShader's descriptor then carries.
    static final String LEGACY_SHADER = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyShaderInstance";

    private static final String AT = "Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
            + "Lnet/minecraft/core/BlockPos;";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";

    private LegacyForgeClientApiBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // First, so the player render event below knows the old renderer type has a name on this host.
        if (ForgeHostShape.matches(List.of(LegacyAvatarRendererBridge.AVATAR_RENDERER), List.of(LegacyAvatarRendererBridge.PLAYER_RENDERER),
                ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            LegacyAvatarRendererBridge.register(t);
        }
        List<String> posted = new ArrayList<>();
        if (ForgeHostShape.matches(List.of(), List.of(OLD_SHADERS), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            registerShaders(t);
            posted.add(OLD_SHADERS);
        }
        if (ForgeHostShape.matches(List.of(RENDER_AVATAR), List.of(OLD_RENDER_PLAYER), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            registerRenderPlayer(t);
            posted.add(OLD_RENDER_PLAYER);
        }
        if (ForgeHostShape.matches(List.of(), List.of(OLD_ANGER), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            RebuiltApiClasses.registerTree(t, LIVING_EVENT);
            boolean renamed = host.apply("net/minecraft/world/entity/monster/Enderman") != null
                    || (host.apply(ForgeHostShape.ANCHOR) == null && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, ENDERMAN_RENAMED) >= 0);
            t.registerSyntheticClass(ANGER, angerEvent("net/minecraft/world/entity/monster/" + (renamed ? "Enderman" : "EnderMan")));
            t.registerClassRedirect(OLD_ANGER, ANGER);
            // Old listeners keep the old cancel rules in Retromod's dispatch; Forge 26.2 and newer have no hook to post it from.
            LegacyEventBaseAdapter.dispatchAsLegacy(t, List.of(OLD_ANGER));
        }
        if (ForgeHostMembers.lacksMethod(FLUID_EXT, "getStillTexture", "(" + AT + ")" + ID, ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            registerFluidReads(t);
        }
        String oldFinalize = oldFinalize();
        if (ForgeHostMembers.lacksMethod(FINALIZE, "<init>", oldFinalize, ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            RebuiltApiClasses.registerTree(t, FINALIZE_FACTORY);
            t.registerConstructorRedirect(FINALIZE, oldFinalize, FINALIZE_FACTORY, "create",
                    oldFinalize.substring(0, oldFinalize.length() - 1) + "L" + FINALIZE + ";");
        }
        if (!posted.isEmpty()) {
            LegacyClientRenderBootstrapInjector.addPart(t, new LegacyClientRenderBootstrapInjector.Part("legacy-client-events", posted, INSTALLER));
        }
        registerArmor(t, host);
    }

    // The old armor and disguise hooks.
    private static void registerArmor(RetromodTransformer t, Function<String, ClassNode> host) {
        String first = ForgeHostShape.FIRST_CHECKED_VERSION;
        boolean armorGone = ForgeHostMembers.lacksMethod(OLD + LegacyArmorHooksBridge.EXT, "getHumanoidArmorModel",
                LegacyArmorHooksBridge.OLD_MODEL_ARGS + "Lnet/minecraft/client/model/HumanoidModel;", first, host);
        String stack = OLD + "common/extensions/IForgeItemStack";
        boolean disguiseGone = ForgeHostMembers.lacksMethod(stack, "isEnderMask",
                "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/monster/EnderMan;)Z", first, host)
                && (host.apply(ForgeHostShape.ANCHOR) == null || !ForgeHostMembers.lacksMethod(stack, "isMonsterDisguise",
                        "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/monster/Monster;)Z", first, host));
        LegacyArmorHooksBridge.registerForge(t, armorGone, disguiseGone);
    }

    private static void registerShaders(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, SHADERS);
        t.registerClassRedirect(OLD_SHADERS, SHADERS);
        String helper = "(L" + SHADERS + ";Ljava/lang/Object;Ljava/util/function/Consumer;)V";
        List<String> types = new ArrayList<>(LegacyNeoForgeClientApiBridge.shaderTypes());
        types.add(LEGACY_SHADER);
        for (String owner : List.of(OLD_SHADERS, SHADERS)) {
            for (String type : types) {
                t.registerMethodRedirect(owner, "registerShader", "(L" + type + ";Ljava/util/function/Consumer;)V",
                        SHADERS, "registerShader", helper, true);
            }
        }
    }

    private static void registerRenderPlayer(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, RENDER_PLAYER);
        List<String> events = new ArrayList<>();
        for (String nested : List.of("", "$Pre", "$Post")) {
            t.registerClassRedirect(OLD_RENDER_PLAYER + nested, RENDER_PLAYER + nested);
            events.add(OLD_RENDER_PLAYER + nested);
        }
        LegacyEventBaseAdapter.dispatchAsLegacy(t, events);
        // Buffer source draws reach nothing on 26.2, so they get a stand-in; the renderer is the avatar event's own.
        for (String nested : List.of("", "$Pre", "$Post")) {
            for (String owner : List.of(OLD_RENDER_PLAYER + nested, RENDER_PLAYER + nested)) {
                for (String buffers : List.of("net/minecraft/client/renderer/MultiBufferSource", RenderBufferSynthetics.MBS)) {
                    t.registerMethodRedirect(owner, "getMultiBufferSource", "()L" + buffers + ";", RenderBufferSynthetics.BS,
                            "sharedFor", "(Ljava/lang/Object;)L" + RenderBufferSynthetics.BS + ";", true);
                }
            }
        }
    }

    private static void registerFluidReads(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, FLUID_READS);
        String self = "(L" + FLUID_EXT + ";";
        for (String read : List.of("getStillTexture", "getFlowingTexture", "getOverlayTexture")) {
            t.registerMethodRedirect(FLUID_EXT, read, "(" + AT + ")" + ID, FLUID_READS, read, self + AT + ")" + ID, true);
        }
        t.registerMethodRedirect(FLUID_EXT, "getTintColor", "(" + AT + ")I", FLUID_READS, "getTintColor", self + AT + ")I", true);
        t.registerMethodRedirect(FLUID_EXT, "renderOverlay", "(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                FLUID_READS, "renderOverlay", self + "Ljava/lang/Object;Ljava/lang/Object;)V", true);
    }

    /** Forge 1.20.1's anger event on the legacy living event base, cancelable, with the enderman class of this host. */
    static byte[] angerEvent(String enderman) {
        String player = "net/minecraft/world/entity/player/Player";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, ANGER, null,
                LIVING_EVENT, new String[]{"net/minecraftforge/eventbus/api/event/InheritableEvent"});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "player", "L" + player + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + enderman + ";L" + player + ";)V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LIVING_EVENT, "<init>", "(Lnet/minecraft/world/entity/LivingEntity;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, ANGER, "player", "L" + player + ";");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getPlayer", "()L" + player + ";", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ANGER, "player", "L" + player + ";");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getEntity", "()L" + enderman + ";", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LIVING_EVENT, "getEntity", "()Lnet/minecraft/world/entity/LivingEntity;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, enderman);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "isCancelable", "()Z", null, null);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Forge 1.20.1's constructor, whose spawn tag Forge 26.2 takes as a value input. */
    static String oldFinalize() {
        return LegacyNeoForgeClientApiBridge.oldFinalize();
    }
}
