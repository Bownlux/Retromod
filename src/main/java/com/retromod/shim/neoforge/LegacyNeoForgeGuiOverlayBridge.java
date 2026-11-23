/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.ModConstructorHooks;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Runs a Forge 1.20.1 mod's HUD overlays and overlay events on NeoForge's GUI layers. */
public final class LegacyNeoForgeGuiOverlayBridge {

    /** Turns on {@link #apply} in the post-remap class passes. */
    public static final String FLAG = "neoforge-legacy-gui-overlay";

    static final String OLD_OVERLAY = "net/minecraftforge/client/gui/overlay/";
    static final String OLD_EVENT = "net/minecraftforge/client/event/";
    static final String ROOT = RebuiltApiClasses.ROOT + "neoforge/client/gui/overlay/";
    static final String LAYERS = ROOT + "LegacyGuiLayers";
    static final String FORGE_GUI = ROOT + "ForgeGui";
    static final String NEO_EVENT = "net/neoforged/neoforge/client/event/";
    static final String REGISTER = NEO_EVENT + "RegisterGuiLayersEvent";
    static final String RENDER = NEO_EVENT + "RenderGuiLayerEvent";
    static final String GUI_LAYER = "net/neoforged/neoforge/client/gui/GuiLayer";

    /** The old overlay classes, each rebuilt under the same simple name. */
    static final List<String> REBUILT = List.of("IGuiOverlay", "NamedGuiOverlay", "VanillaGuiOverlay", "ForgeGui", "GuiOverlayManager");

    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    private static final String OLD_LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyNeoForgeGuiOverlayBridge() {}

    /** Registers the bridge where NeoForge registers GUI layers by Identifier; offline, a NeoForge target from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostHasLayers(host.apply(REGISTER), host.apply(GUI_LAYER))) return;
        RebuiltApiClasses.registerTree(t, LAYERS);
        for (String name : REBUILT) RebuiltApiClasses.registerTree(t, ROOT + name);
        // The rebuilt classes note what they could not draw through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        for (String name : REBUILT) t.registerClassRedirect(OLD_OVERLAY + name, ROOT + name);
        // The old events become NeoForge's own, so their listeners register on NeoForge's buses as they are.
        t.registerClassRedirect(OLD_EVENT + "RegisterGuiOverlaysEvent", REGISTER);
        t.registerClassRedirect(OLD_EVENT + "RenderGuiOverlayEvent", RENDER);
        t.registerClassRedirect(OLD_EVENT + "RenderGuiOverlayEvent$Pre", RENDER + "$Pre");
        t.registerClassRedirect(OLD_EVENT + "RenderGuiOverlayEvent$Post", RENDER + "$Post");
        registerEventMembers(t);

        // Each jar's copy learns the mod id its overlays are named under while the mod constructs.
        ModConstructorHooks.register(t, "neoforge-legacy-gui-overlays", LegacyNeoForgeGuiOverlayBridge::plan);
        t.enablePostProcess(FLAG);
    }

    /** The old registration methods and render event getters, under the old and the NeoForge owner names. */
    static void registerEventMembers(RetromodTransformer t) {
        String overlay = "L" + OLD_OVERLAY + "IGuiOverlay;";
        String rebuiltOverlay = "L" + ROOT + "IGuiOverlay;";
        String registerHead = "(L" + REGISTER + ";";
        for (String owner : List.of(OLD_EVENT + "RegisterGuiOverlaysEvent", REGISTER)) {
            for (String location : List.of(OLD_LOCATION, IDENTIFIER)) {
                for (String name : List.of("registerAbove", "registerBelow")) {
                    t.registerMethodRedirect(owner, name, "(" + location + STRING + overlay + ")V", LAYERS, name,
                            registerHead + IDENTIFIER + STRING + rebuiltOverlay + ")V", true);
                }
            }
            for (String name : List.of("registerAboveAll", "registerBelowAll")) {
                t.registerMethodRedirect(owner, name, "(" + STRING + overlay + ")V", LAYERS, name,
                        registerHead + STRING + rebuiltOverlay + ")V", true);
            }
        }
        String renderHead = "(L" + RENDER + ";)";
        for (String event : List.of("", "$Pre", "$Post")) {
            for (String owner : List.of(OLD_EVENT + "RenderGuiOverlayEvent" + event, RENDER + event)) {
                t.registerMethodRedirect(owner, "getOverlay", "()L" + OLD_OVERLAY + "NamedGuiOverlay;", LAYERS, "getOverlay",
                        renderHead + "L" + ROOT + "NamedGuiOverlay;", true);
                t.registerMethodRedirect(owner, "getPartialTick", "()F", LAYERS, "getPartialTick", renderHead + "F", true);
                t.registerMethodRedirect(owner, "getWindow", "()Lcom/mojang/blaze3d/platform/Window;", LAYERS, "getWindow",
                        renderHead + "Lcom/mojang/blaze3d/platform/Window;", true);
            }
        }
    }

    /**
     * Whether the host's layer registration takes an Identifier and a GuiLayer that draws into a GuiGraphicsExtractor.
     * NeoForge 1.21.11 has the same registration with a GuiGraphics layer, which the rebuilt classes do not draw into.
     * Unreadable, a host from 26.1 has it.
     */
    static boolean hostHasLayers(ClassNode register, ClassNode guiLayer) {
        if (register == null || guiLayer == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        }
        return declares(register, "registerAbove", "(" + IDENTIFIER + IDENTIFIER + "L" + GUI_LAYER + ";)V")
                && declares(guiLayer, "render", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V");
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** The attach call at the start of each {@code @Mod} constructor of a jar that names the old overlay API; null otherwise. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (namesOldOverlayApi(original)) {
                return (modClass, modId) -> List.of(new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, LAYERS,
                        "attach", "(Ljava/lang/String;)V", List.of(modId)));
            }
        }
        return null;
    }

    private static boolean namesOldOverlayApi(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        return text.contains(OLD_OVERLAY) || text.contains(OLD_EVENT + "RegisterGuiOverlaysEvent");
    }

    /** The class with its ForgeGui casts and checks sent to the rebuilt helpers, since Minecraft.gui is no ForgeGui. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        if (!new String(classBytes, StandardCharsets.ISO_8859_1).contains(FORGE_GUI)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        // The rebuilt classes themselves keep their own code.
        if (node.name.startsWith(RebuiltApiClasses.ROOT)) return classBytes;
        boolean changed = false;
        for (MethodNode method : node.methods) changed |= rewriteCasts(method);
        if (!changed) return classBytes;
        // Each helper leaves the stack as the instruction it replaced did.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewriteCasts(MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof TypeInsnNode type) || !type.desc.equals(FORGE_GUI)) continue;
            if (insn.getOpcode() == Opcodes.CHECKCAST) {
                method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE_GUI, "cast",
                        "(Ljava/lang/Object;)L" + FORGE_GUI + ";", false));
                changed = true;
            } else if (insn.getOpcode() == Opcodes.INSTANCEOF) {
                method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, FORGE_GUI, "isInstance",
                        "(Ljava/lang/Object;)Z", false));
                changed = true;
            }
        }
        return changed;
    }
}
