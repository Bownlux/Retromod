/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Rebuilds Forge 1.20.1's HUD overlay API, which Forge 26.2 replaced with layers, inside each old mod that uses it. */
public final class LegacyGuiOverlayBridge {

    /** Turns on {@link #apply} in the post-remap class passes. */
    public static final String FLAG = "forge-legacy-gui-overlay";

    private static final String OLD_OVERLAY = "net/minecraftforge/client/gui/overlay/";
    private static final String OLD_EVENT = "net/minecraftforge/client/event/";
    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/";
    static final String HUB = ROOT + "LegacyOverlayHub";
    static final String FORGE_GUI = ROOT + "gui/overlay/ForgeGui";
    static final String DEBUG_TEXT = ROOT + "event/LegacyDebugText";
    static final String FORGE_LAYER = OLD_OVERLAY + "ForgeLayer";
    static final String OLD_REGISTER = OLD_EVENT + "RegisterGuiOverlaysEvent";
    static final String OLD_DEBUG_TEXT = OLD_EVENT + "CustomizeGuiOverlayEvent$DebugText";
    static final String LAYER = "net/minecraft/client/gui/LayeredDraw$Layer";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    /** The first Forge version checked to use the DebugText record, bounded by the 1.21.1 corpus; 1.20.2 to 1.20.6 are unchecked. */
    static final String DEBUG_TEXT_RECORD_FROM = "1.21";

    private static final String GRAPHICS = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
    private static final String DELTA = "Lnet/minecraft/client/DeltaTracker;";
    private static final String LAYER_DESC = "(" + GRAPHICS + DELTA + ")V";

    /** Each removed class an old mod names, and the class rebuilt in its place. */
    static final Map<String, String> REBUILT = rebuilt();

    /** The removed events rebuilt on the legacy event base, whose old listeners go to Retromod's dispatch. */
    static final List<String> LEGACY_DISPATCH = List.of(OLD_EVENT + "RenderGuiOverlayEvent",
            OLD_EVENT + "RenderGuiOverlayEvent$Pre", OLD_EVENT + "RenderGuiOverlayEvent$Post", OLD_EVENT + "RenderGuiEvent",
            OLD_EVENT + "RenderGuiEvent$Pre", OLD_EVENT + "RenderGuiEvent$Post");

    /** Removed Forge classes whose mixins are dropped, since the methods they inject into exist in no form on Forge 26.2. */
    static final Set<String> REMOVED_MIXIN_TARGETS = Set.of(OLD_OVERLAY + "ForgeGui", OLD_OVERLAY + "ForgeGui$ForgeDebugScreenOverlay",
            OLD_OVERLAY + "VanillaGuiOverlay", OLD_OVERLAY + "GuiOverlayManager");

    private static final ModConstructorHooks.Call ATTACH =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, HUB, "attach", "()V", List.of());

    private LegacyGuiOverlayBridge() {}

    private static Map<String, String> rebuilt() {
        Map<String, String> names = new LinkedHashMap<>();
        for (String overlay : List.of("IGuiOverlay", "NamedGuiOverlay", "VanillaGuiOverlay", "ForgeGui", "GuiOverlayManager")) {
            names.put(OLD_OVERLAY + overlay, ROOT + "gui/overlay/" + overlay);
        }
        for (String event : List.of("RegisterGuiOverlaysEvent", "RenderGuiOverlayEvent", "RenderGuiOverlayEvent$Pre",
                "RenderGuiOverlayEvent$Post", "RenderGuiEvent", "RenderGuiEvent$Pre", "RenderGuiEvent$Post")) {
            names.put(OLD_EVENT + event, ROOT + "event/" + event);
        }
        return names;
    }

    /** Registers the rebuilt overlay API where the Forge host draws its HUD with layers; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Forge 26.2 replaced the overlay registry with ForgeLayer stacks; a host that still has it needs nothing.
        if (!ForgeHostShape.matches(List.of(FORGE_LAYER), List.of(OLD_REGISTER), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        for (String root : List.of(HUB, DEBUG_TEXT)) RebuiltApiClasses.registerTree(t, root);
        for (String rebuilt : REBUILT.values()) RebuiltApiClasses.registerTree(t, rebuilt);
        // The rebuilt classes note what they could not draw through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        List<String> dispatched = new ArrayList<>();
        REBUILT.forEach((old, rebuilt) -> {
            t.registerClassRedirect(old, rebuilt);
            // A later host that has the name again keeps it for mods written against it.
            if (host.apply(old) != null) t.limitClassRedirectToModsBefore(old, ForgeHostShape.FIRST_CHECKED_VERSION);
            // An old mod's handlers still go to the rebuilt events, which its redirect keeps naming.
            if (LEGACY_DISPATCH.contains(old)) dispatched.add(old);
        });
        // The render events keep Forge 1.20.1's cancel, receiveCanceled and priority rules in Retromod's dispatch.
        LegacyEventBaseAdapter.dispatchAsLegacy(t, dispatched);

        // Forge 1.21 mods use Forge 26.2's DebugText record, so only older ones get the 1.20.1 shape with two lists.
        t.registerClassRedirect(OLD_DEBUG_TEXT, DEBUG_TEXT);
        t.limitClassRedirectToModsBefore(OLD_DEBUG_TEXT, DEBUG_TEXT_RECORD_FROM);

        // Forge 1.21 mods add vanilla LayeredDraw layers to ForgeLayeredDraw, whose layers are ForgeLayers now.
        if (host.apply(ForgeHostShape.ANCHOR) == null || host.apply(LAYER) == null) {
            t.registerClassRedirect(LAYER, FORGE_LAYER);
            t.registerInterfaceMethodRename(LAYER, "render", "extract");
        }

        // Each jar's copy lists itself for the HUD coordinator while its mod constructs.
        ModConstructorHooks.register(t, "legacy-gui-overlays", LegacyGuiOverlayBridge::plan);
        t.enablePostProcess(FLAG);
    }

    /** The hub call at the start of each {@code @Mod} constructor of a jar that names the old overlay API; null otherwise. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (namesOldOverlayApi(original)) return (modClass, modId) -> List.of(ATTACH);
        }
        return null;
    }

    private static boolean namesOldOverlayApi(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (String old : REBUILT.keySet()) {
            if (text.contains(old)) return true;
        }
        return false;
    }

    /** The removed overlay class a mixin targets, when the class redirect for this mod rebuilds it; otherwise null. */
    public static String removedMixinTarget(List<String> targets, UnaryOperator<String> redirectForCurrentMod) {
        for (String target : targets) {
            // A nested class, such as ForgeGui's debug overlay or one of VanillaGuiOverlay's constant bodies, has no
            // stand-in; it goes with its outer class.
            int nested = target.indexOf('$');
            String outer = nested < 0 ? target : target.substring(0, nested);
            if (!REMOVED_MIXIN_TARGETS.contains(target) && !REMOVED_MIXIN_TARGETS.contains(outer)) continue;
            if (redirectForCurrentMod.apply(outer).startsWith(ROOT)) return target;
        }
        return null;
    }

    /** The class with its ForgeGui casts and checks sent to the rebuilt helpers, and an extract on each old LayeredDraw layer. */
    public static byte[] apply(byte[] classBytes, String modSourceVersion) {
        if (classBytes == null) return null;
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        boolean casts = text.contains(FORGE_GUI);
        // A mod built for Forge 26.2 writes its own ForgeLayers, and an interface of its own over one is left alone.
        boolean layers = text.contains(FORGE_LAYER) && (modSourceVersion == null
                || RetromodVersion.compareMcVersions(modSourceVersion, ForgeHostShape.FIRST_CHECKED_VERSION) < 0);
        if (!casts && !layers) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        // The rebuilt classes themselves keep their own code.
        if (node.name.startsWith(RebuiltApiClasses.ROOT)) return classBytes;
        boolean changed = false;
        if (casts) {
            for (MethodNode method : node.methods) changed |= rewriteCasts(method);
        }
        if (layers) changed |= addExtract(node);
        if (!changed) return classBytes;
        // Each helper leaves the stack as the instruction it replaced did, and the new method sets its own sizes.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    // The host's Minecraft.gui is no ForgeGui, so a plain cast or instanceof would fail where Forge 1.20.1's passed.
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

    // An old layer implements render, which the class redirect made a ForgeLayer whose one method is extract.
    private static boolean addExtract(ClassNode node) {
        if (node.interfaces == null || !node.interfaces.contains(FORGE_LAYER)) return false;
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        // A default method needs a Java 8 class file.
        if (isInterface && (node.version & 0xFFFF) < Opcodes.V1_8) return false;
        MethodNode render = null;
        for (MethodNode method : node.methods) {
            if (!method.desc.equals(LAYER_DESC) || (method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (method.name.equals("extract")) return false;
            if (method.name.equals("render")) render = method;
        }
        // A concrete class may inherit its render from a superclass that is no layer; the virtual call still finds it.
        boolean concrete = (node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0;
        // An interface or abstract class over the old layer inherited its abstract render, which ForgeLayer lacks.
        if (render == null && !concrete) {
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "render", LAYER_DESC, null, null));
        }
        MethodNode extract = new MethodNode(Opcodes.ACC_PUBLIC, "extract", LAYER_DESC, null, null);
        extract.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        extract.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        extract.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        extract.instructions.add(new MethodInsnNode(isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                node.name, "render", LAYER_DESC, isInterface));
        extract.instructions.add(new InsnNode(Opcodes.RETURN));
        extract.maxStack = 3;
        extract.maxLocals = 3;
        node.methods.add(extract);
        return true;
    }
}
