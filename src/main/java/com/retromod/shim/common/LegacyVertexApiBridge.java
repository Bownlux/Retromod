/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.commons.SimpleRemapper;

import java.util.List;
import java.util.Map;

/** Rebuilds the VertexConsumer builder calls Minecraft 1.21 removed (vertex, color, uv, endVertex and the rest) on the 1.21 builder. */
public final class LegacyVertexApiBridge {

    public static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyVertexCalls";
    public static final String INTERMEDIARY_HELPER = RebuiltApiClasses.ROOT + "intermediary/minecraft/client/renderer/LegacyVertexCalls";
    static final String CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer";
    static final String BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    static final String POSE = "com/mojang/blaze3d/vertex/PoseStack$Pose";
    static final String INTERMEDIARY_CONSUMER = "net/minecraft/class_4588";
    static final String INTERMEDIARY_BUILDER = "net/minecraft/class_287";
    static final String MATRIX4 = "Lorg/joml/Matrix4f;";
    static final String MATRIX3 = "Lorg/joml/Matrix3f;";
    static final String WHOLE_VERTEX = "(FFFFFFFFFIIFFF)V";

    /** An old call: its name and descriptor without the return, the helper method, and whether it returns the consumer. */
    record OldCall(String name, String args, String helperName, boolean chained) {
        String desc(String consumer) {
            return "(" + args + ")" + (chained ? "L" + consumer + ";" : "V");
        }
    }

    // The names Forge and NeoForge mods call by once their SRG names are decoded: Minecraft 1.20.1's.
    static final List<OldCall> MOJANG = List.of(
            new OldCall("vertex", "DDD", "vertex", true),
            new OldCall("vertex", MATRIX4 + "FFF", "vertex", true),
            new OldCall("vertex", "FFFFFFFFFIIFFF", "vertex", false),
            new OldCall("color", "IIII", "color", true),
            new OldCall("color", "FFFF", "color", true),
            new OldCall("color", "I", "color", true),
            new OldCall("uv", "FF", "uv", true),
            new OldCall("overlayCoords", "II", "overlayCoords", true),
            new OldCall("overlayCoords", "I", "overlayCoords", true),
            new OldCall("uv2", "II", "uv2", true),
            new OldCall("uv2", "I", "uv2", true),
            new OldCall("normal", "FFF", "normal", true),
            new OldCall("normal", MATRIX3 + "FFF", "normal", true),
            new OldCall("endVertex", "", "endVertex", false),
            new OldCall("defaultColor", "IIII", "defaultColor", false),
            new OldCall("unsetDefaultColor", "", "unsetDefaultColor", false),
            // A Fabric 1.20.1 mod on a 26.x host: the intermediary ids kept, renamed to the 1.21 names, with the old descriptors.
            new OldCall("addVertex", "DDD", "vertex", true),
            new OldCall("addVertex", "FFFFFFFFFIIFFF", "vertex", false));

    // A Fabric 1.21 to 1.21.11 host runs intermediary names. The ids whose call still links there are left alone.
    static final List<OldCall> INTERMEDIARY = List.of(
            new OldCall("method_22912", "DDD", "vertex", true),
            new OldCall("method_23919", "FFFFFFFFFIIFFF", "vertex", false),
            new OldCall("method_22917", "II", "overlayCoords", true),
            new OldCall("method_22916", "I", "uv2", true),
            new OldCall("method_23763", MATRIX3 + "FFF", "normal", true),
            new OldCall("method_1344", "", "endVertex", false),
            new OldCall("method_22901", "IIII", "defaultColor", false),
            new OldCall("method_35666", "", "unsetDefaultColor", false));

    // The 1.21 builder in the intermediary names of 1.21 to 1.21.11, which keep the same ids (checked in their tables).
    static final SimpleRemapper INTERMEDIARY_NAMES = new SimpleRemapper(Map.ofEntries(
            Map.entry(HELPER, INTERMEDIARY_HELPER),
            Map.entry(HELPER + "$OldConsumer", INTERMEDIARY_HELPER + "$OldConsumer"),
            Map.entry(CONSUMER, INTERMEDIARY_CONSUMER),
            Map.entry(CONSUMER + ".addVertex(FFF)L" + CONSUMER + ";", "method_22912"),
            Map.entry(CONSUMER + ".setColor(IIII)L" + CONSUMER + ";", "method_1336"),
            Map.entry(CONSUMER + ".setUv(FF)L" + CONSUMER + ";", "method_22913"),
            Map.entry(CONSUMER + ".setUv1(II)L" + CONSUMER + ";", "method_60796"),
            Map.entry(CONSUMER + ".setUv2(II)L" + CONSUMER + ";", "method_22921"),
            Map.entry(CONSUMER + ".setNormal(FFF)L" + CONSUMER + ";", "method_22914"),
            Map.entry(CONSUMER + ".addVertex(FFFIFFIIFFF)V", "method_23919"),
            // The line width only 1.21.11 has, read and set only where the host's builder declares the setter.
            Map.entry(CONSUMER + ".setLineWidth(F)L" + CONSUMER + ";", "method_75298"),
            Map.entry("net/minecraft/client/Minecraft", "net/minecraft/class_310"),
            Map.entry("com/mojang/blaze3d/platform/Window", "net/minecraft/class_1041"),
            Map.entry("net/minecraft/client/Minecraft.getInstance()Lnet/minecraft/client/Minecraft;", "method_1551"),
            Map.entry("net/minecraft/client/Minecraft.getWindow()Lcom/mojang/blaze3d/platform/Window;", "method_22683"),
            Map.entry("com/mojang/blaze3d/platform/Window.getAppropriateLineWidth()F", "method_75291")));

    private LegacyVertexApiBridge() {}

    /** Mojang names: Forge and NeoForge from 1.21, and Fabric from 26.1. */
    public static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, HELPER);
        redirect(t, MOJANG, CONSUMER, BUILDER, HELPER);
        // 1.20.5's matrix pose forms kept their shape in 1.21 and only took the builder's names.
        String pose = "(L" + POSE + ";FFF)L" + CONSUMER + ";";
        for (String owner : List.of(CONSUMER, BUILDER)) {
            t.registerMethodRedirect(owner, "vertex", pose, owner, "addVertex", pose);
            t.registerMethodRedirect(owner, "normal", pose, owner, "setNormal", pose);
        }
    }

    /** Intermediary names, for a Fabric host from 1.21 to 1.21.11. */
    public static void registerIntermediary(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, HELPER, INTERMEDIARY_NAMES);
        redirect(t, INTERMEDIARY, INTERMEDIARY_CONSUMER, INTERMEDIARY_BUILDER, INTERMEDIARY_HELPER);
    }

    private static void redirect(RetromodTransformer t, List<OldCall> calls, String consumer, String builder, String helper) {
        for (OldCall call : calls) {
            String desc = call.desc(consumer);
            String helperDesc = "(L" + consumer + ";" + desc.substring(1);
            // A mod's own consumer that leaves an old default to the interface calls it on itself, so the redirect follows the class.
            t.registerInheritedMethodRedirect(consumer, call.name(), desc, helper, call.helperName(), helperDesc);
            // BufferBuilder's own calls name the builder but keep the interface's descriptor.
            t.registerMethodRedirect(builder, call.name(), desc, helper, call.helperName(), helperDesc, true);
        }
    }
}
