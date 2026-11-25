/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An old mod's section SERIALIZER constants read the host's TYPE, and the removed EMPTY animation is one shared empty section. */
class LegacyClientMetadataSectionsTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("SERIALIZER reads TYPE and EMPTY is a shared section with no frames")
    void serializersAndEmptyAnimation(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", LegacyMetadataSectionBridge::registerClientSections,
                BridgeHarness.only(old, "mod/"), host);
        BridgeHarness.Run run = new BridgeHarness.Run(host, mod);

        assertEquals("animation type", run.call("mod/Sections", "animationSerializer"));
        assertEquals("texture type", run.call("mod/Sections", "textureSerializer"));
        assertEquals(Boolean.TRUE, run.call("mod/Sections", "emptyIsShared"));
        assertEquals(1, run.call("mod/Sections", "emptyFrameTime"));
        assertEquals(Boolean.TRUE, run.call("mod/Sections", "emptyHasNoFrames"));
    }

    // 1.20.1: each section class held its own serializer and the animation an EMPTY constant.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>();
        String pkg = "net/minecraft/client/resources/metadata/";
        s.put(pkg + "animation/AnimationMetadataSectionSerializer.java",
                "package net.minecraft.client.resources.metadata.animation; public class AnimationMetadataSectionSerializer {}");
        s.put(pkg + "animation/AnimationMetadataSection.java", """
                package net.minecraft.client.resources.metadata.animation;
                public class AnimationMetadataSection {
                    public static final AnimationMetadataSectionSerializer SERIALIZER = new AnimationMetadataSectionSerializer();
                    public static final AnimationMetadataSection EMPTY = new AnimationMetadataSection();
                    public int defaultFrameTime() { return 0; }
                    public java.util.Optional<java.util.List<Object>> frames() { return null; }
                }""");
        s.put(pkg + "texture/TextureMetadataSectionSerializer.java",
                "package net.minecraft.client.resources.metadata.texture; public class TextureMetadataSectionSerializer {}");
        s.put(pkg + "texture/TextureMetadataSection.java", """
                package net.minecraft.client.resources.metadata.texture;
                public class TextureMetadataSection {
                    public static final TextureMetadataSectionSerializer SERIALIZER = new TextureMetadataSectionSerializer();
                }""");
        s.put("mod/Sections.java", """
                package mod;
                import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
                import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
                public final class Sections {
                    public static String animationSerializer() { return String.valueOf((Object) AnimationMetadataSection.SERIALIZER); }
                    public static String textureSerializer() { return String.valueOf((Object) TextureMetadataSection.SERIALIZER); }
                    public static boolean emptyIsShared() {
                        AnimationMetadataSection first = AnimationMetadataSection.EMPTY;
                        return first != null && first == AnimationMetadataSection.EMPTY;
                    }
                    public static int emptyFrameTime() { return AnimationMetadataSection.EMPTY.defaultFrameTime(); }
                    public static boolean emptyHasNoFrames() { return AnimationMetadataSection.EMPTY.frames().isEmpty(); }
                }""");
        return s;
    }

    // 1.21.4 and newer: the section is a record and its TYPE replaced the serializer.
    private static Map<String, String> newWorld() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/server/packs/metadata/MetadataSectionType.java", """
                package net.minecraft.server.packs.metadata;
                public record MetadataSectionType<T>(String name) { public String toString() { return name + " type"; } }""");
        s.put("net/minecraft/client/resources/metadata/animation/AnimationMetadataSection.java", """
                package net.minecraft.client.resources.metadata.animation;
                import java.util.*;
                public record AnimationMetadataSection(Optional<List<Object>> frames, Optional<Integer> frameWidth, Optional<Integer> frameHeight,
                        int defaultFrameTime, boolean interpolatedFrames) {
                    public static final net.minecraft.server.packs.metadata.MetadataSectionType<AnimationMetadataSection> TYPE =
                            new net.minecraft.server.packs.metadata.MetadataSectionType<>("animation");
                }""");
        s.put("net/minecraft/client/resources/metadata/texture/TextureMetadataSection.java", """
                package net.minecraft.client.resources.metadata.texture;
                public record TextureMetadataSection(boolean blur, boolean clamp) {
                    public static final net.minecraft.server.packs.metadata.MetadataSectionType<TextureMetadataSection> TYPE =
                            new net.minecraft.server.packs.metadata.MetadataSectionType<>("texture");
                }""");
        return s;
    }
}
