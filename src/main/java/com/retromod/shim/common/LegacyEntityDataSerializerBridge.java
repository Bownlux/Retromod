/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.ForgeHostShape;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.util.McReflect;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Puts back the compound tag entity data serializer 1.21.9 removed, for Forge-family mods built for 1.21.8 or older. */
public final class LegacyEntityDataSerializerBridge {

    static final String SERIALIZERS = "net/minecraft/network/syncher/EntityDataSerializers";
    static final String SERIALIZER_DESC = "Lnet/minecraft/network/syncher/EntityDataSerializer;";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/network/syncher/CompoundTagSerializer";
    static final String LEGACY_LOG = "com/retromod/shim/common/embedded/LegacyLog";
    // EventBus 7's bus group marks Forge 26.2 and newer, whose registration event the serializer listens to.
    static final String FORGE_BUS_GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";
    // The field as a jar names it before translation: its Mojang name, or Forge 1.20.1's SRG id.
    private static final List<String> FIELD_NAMES = List.of("COMPOUND_TAG", "f_135042_");
    private static final ModConstructorHooks.Call WATCH =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, REBUILT, "watch", "()V", List.of());

    private LegacyEntityDataSerializerBridge() {}

    /** NeoForge from 1.21.9 and Forge from 26.2, whose own serializer registries the rebuilt serializer joins. */
    public static void register(RetromodTransformer transformer) {
        if (!hostLacksCompoundTag(ClassResourceInspector.read(SERIALIZERS))) return;
        // Forge 1.21.9 to 1.21.11 run EventBus 6, which this registration does not reach, and Fabric has no mod
        // constructor to start it from, so both keep the old field read.
        if (!McReflect.isNeoForge() && !ForgeHostShape.matches(List.of(FORGE_BUS_GROUP), List.of())) return;
        registerRedirects(transformer);
    }

    /** Whether the host's serializers lack the field; {@code serializers} is null offline, where the version decides. */
    static boolean hostLacksCompoundTag(ClassNode serializers) {
        if (serializers == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.9") >= 0;
        for (FieldNode field : serializers.fields) {
            if (field.name.equals("COMPOUND_TAG")) return false;
        }
        return true;
    }

    static void registerRedirects(RetromodTransformer transformer) {
        RebuiltApiClasses.registerTree(transformer, REBUILT);
        SyntheticEmbedder.registerClassResource(transformer, LEGACY_LOG, LegacyLog.class);
        transformer.registerExactFieldRedirect(SERIALIZERS, "COMPOUND_TAG", SERIALIZER_DESC,
                REBUILT, "get", "()" + SERIALIZER_DESC);
        // Each mod constructor of a jar that names the field starts the registration, on the client and the
        // server alike, so the serializer is in the loader's registry before any entity is sent.
        ModConstructorHooks.register(transformer, "legacy-compound-tag-serializer", LegacyEntityDataSerializerBridge::plan);
    }

    /** The registration start in each {@code @Mod} constructor of a jar that names the old field, or null. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (namesOldField(original)) return (modClass, modId) -> List.of(WATCH);
        }
        return null;
    }

    private static boolean namesOldField(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!text.contains(SERIALIZERS)) return false;
        for (String name : FIELD_NAMES) {
            if (text.contains(name)) return true;
        }
        return false;
    }
}
