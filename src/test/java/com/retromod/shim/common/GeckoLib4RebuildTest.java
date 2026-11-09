/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.api.common.LegacyGeckoLib4Adapter;
import com.retromod.shim.api.common.LegacyGeckoLib4Bridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GeckoLib 4's textures, buffer check and Molang queries run on Retromod's rebuilt code against a 26.3-shaped host. */
class GeckoLib4RebuildTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An animated texture shows one frame at a time, changing only when the next frame starts")
    void animatedTextureFollowsTheFrameTimes(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        // Three frames of two ticks each, one pixel per frame: 0x01, 0x02, 0x03.
        // The second number counts uploads: one when the texture is made, then one per frame change.
        assertEquals("1:2,1:2,2:3,2:3,3:4,3:4,1:5", run.call("mod/Uses", "frames", "geo:textures/strip.png", 7));
    }

    @Test
    @DisplayName("A glow layer keeps the base color where the mask is opaque, and is empty without a mask")
    void glowLayerFollowsTheMask(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("geo:textures/bat_glowmask.png=80aabbcc|0", run.call("mod/Uses", "glow", "geo:textures/bat.png"));
        assertEquals("geo:textures/plain_glowmask.png=0", run.call("mod/Uses", "glow", "geo:textures/plain.png"));
        assertEquals("emissive:geo:textures/bat_glowmask.png|emissive:geo:textures/bat_glowmask.png",
                run.call("mod/Uses", "renderTypes", "geo:textures/bat.png"));
    }

    @Test
    @DisplayName("The buffer check keeps the BufferBuilder case and drops the removed buffer classes")
    void bufferCheckLinksWithoutTheRemovedClasses(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("fresh|kept|kept|given", run.call("mod/Uses", "buffers"));
    }

    @Test
    @DisplayName("Molang's saddle and charge queries read the mob and the creeper")
    void moLangQueriesUseTheHostMobs(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("saddled:true,false powered:true,false", run.call("mod/Uses", "queries"));
    }

    @Test
    @DisplayName("GeckoLib 5, built for 1.21.5 or newer, and classes outside GeckoLib are left as they are")
    void newerGeckoLibAndOtherModsAreUntouched(@TempDir Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        byte[] texture = old.get("software/bernie/geckolib/cache/texture/AnimatableTexture");
        assertSame(texture, LegacyGeckoLib4Adapter.apply(texture, "1.21.5", true));
        assertSame(texture, LegacyGeckoLib4Adapter.apply(texture, null, true));
        byte[] uses = old.get("mod/Uses");
        assertSame(uses, LegacyGeckoLib4Adapter.apply(uses, "1.21.1", true));
        assertFalse(java.util.Arrays.equals(texture, LegacyGeckoLib4Adapter.apply(texture, "1.21.1", true)));
    }

    @Test
    @DisplayName("The buffer checks lose the removed buffer classes only where the host lost them, from 26.2")
    void bufferChecksFollowTheHost(@TempDir Path dir) throws Exception {
        byte[] renderer = BridgeHarness.compile(dir.resolve("old"), oldWorld()).get("software/bernie/geckolib/renderer/GeoRenderer");
        String doubleBuffer = "com/mojang/blaze3d/vertex/VertexMultiConsumer$Double";
        assertFalse(codeNames(LegacyGeckoLib4Adapter.apply(renderer, "1.21.1", true), doubleBuffer),
                "a 26.2 host has no paired buffer, so no case may name it");
        assertTrue(codeNames(LegacyGeckoLib4Adapter.apply(renderer, "1.21.1", false), doubleBuffer),
                "a 26.1 host still pairs buffers for outlines, so GeckoLib's own check stays");
    }

    @Test
    @DisplayName("GeckoLib's armor model copies the humanoid flags 1.21.2 removed without failing")
    void armorModelFlagsAreDropped(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("copied:false", run.call("mod/Uses", "armor"));
    }

    /** Whether any method's code names {@code type} in a type, field or call instruction. */
    private static boolean codeNames(byte[] classBytes, String type) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (var method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.TypeInsnNode t && t.desc.equals(type)) return true;
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f && f.owner.equals(type)) return true;
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode m && m.owner.equals(type)) return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("The bridge registers only where the host has RenderTypes, and offline from 1.21.11")
    void bridgeNeedsRenderTypes() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.10";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyGeckoLib4Bridge.register(t, name -> null);
        assertFalse(t.getSyntheticClasses().containsKey(LegacyGeckoLib4Bridge.TEXTURES));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyGeckoLib4Bridge.register(t, name -> null);
        assertTrue(t.getSyntheticClasses().containsKey(LegacyGeckoLib4Bridge.TEXTURES));
        t.clearRedirectsForTesting();
        LegacyGeckoLib4Bridge.register(t, name -> name.equals("net/minecraft/client/Minecraft") ? new ClassNode() : null);
        assertFalse(t.getSyntheticClasses().containsKey(LegacyGeckoLib4Bridge.TEXTURES), "a client host without RenderTypes");
    }

    private static BridgeHarness.Run run(Path dir, String source) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Function<String, ClassNode> reader = name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
        Map<String, byte[]> mod = new HashMap<>(BridgeHarness.only(old, "software/"));
        mod.putAll(BridgeHarness.only(old, "mod/"));
        Map<String, byte[]> transformed = BridgeHarness.transform("26.3", t -> {
            LegacyGeckoLib4Bridge.register(t, reader);
            t.setModSourceVersion(source);
        }, mod, host);
        return new BridgeHarness.Run(host, transformed);
    }

    // GeckoLib 4's shapes after the name remap: the 26.3 classes plus the 1.21.1 ones it still names.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>(newWorld());
        s.put("net/minecraft/client/renderer/RenderStateShard.java", """
                package net.minecraft.client.renderer;
                public class RenderStateShard { public static class ShaderStateShard extends RenderStateShard {} }""");
        s.put("com/mojang/blaze3d/vertex/VertexMultiConsumer.java", """
                package com.mojang.blaze3d.vertex;
                public class VertexMultiConsumer { public static class Double implements VertexConsumer {
                    public final VertexConsumer first, second;
                    public Double(VertexConsumer first, VertexConsumer second) { this.first = first; this.second = second; } } }""");
        s.put("net/minecraft/world/entity/Saddleable.java", """
                package net.minecraft.world.entity;
                public interface Saddleable { boolean isSaddled(); }""");
        s.put("net/minecraft/world/entity/PowerableMob.java", """
                package net.minecraft.world.entity;
                public interface PowerableMob { boolean isPowered(); }""");
        s.put("software/bernie/geckolib/cache/texture/AnimatableTexture.java", """
                package software.bernie.geckolib.cache.texture;
                import net.minecraft.resources.Identifier;
                public class AnimatableTexture {
                    public static void setAndUpdate(Identifier id, int tick) { throw new IllegalStateException("old upload"); }
                    public void load(net.minecraft.server.packs.resources.ResourceManager manager) { throw new IllegalStateException("old load"); }
                }""");
        s.put("software/bernie/geckolib/cache/texture/AutoGlowingTexture.java", """
                package software.bernie.geckolib.cache.texture;
                import net.minecraft.resources.Identifier;
                import net.minecraft.client.renderer.rendertype.RenderType;
                public class AutoGlowingTexture {
                    private static final net.minecraft.client.renderer.RenderStateShard.ShaderStateShard SHADER =
                            new net.minecraft.client.renderer.RenderStateShard.ShaderStateShard();
                    public static Identifier getEmissiveResource(Identifier base) { throw new IllegalStateException(); }
                    public static RenderType getRenderType(Identifier base) { throw new IllegalStateException(); }
                    public static RenderType getOutlineRenderType(Identifier base) { throw new IllegalStateException(); }
                }""");
        s.put("software/bernie/geckolib/renderer/GeoRenderer.java", """
                package software.bernie.geckolib.renderer;
                import com.mojang.blaze3d.vertex.*;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.client.renderer.rendertype.RenderType;
                public interface GeoRenderer {
                    default VertexConsumer checkAndRefreshBuffer(boolean isReRender, VertexConsumer buffer, MultiBufferSource source, RenderType type) {
                        if (isReRender) return buffer;
                        if (buffer instanceof BufferBuilder builder && !builder.building) return source.getBuffer(type);
                        if (buffer instanceof VertexMultiConsumer.Double both && bufferNeedsRefresh(both.first)) {
                            return new VertexMultiConsumer.Double(source.getBuffer(type), both.second);
                        }
                        return buffer;
                    }
                    private boolean bufferNeedsRefresh(VertexConsumer buffer) {
                        if (buffer instanceof BufferBuilder builder) return !builder.building;
                        if (buffer instanceof VertexMultiConsumer.Double both) return bufferNeedsRefresh(both.first);
                        return false;
                    }
                }""");
        s.put("software/bernie/geckolib/loading/math/MolangQueries.java", """
                package software.bernie.geckolib.loading.math;
                public final class MolangQueries {
                    public static boolean saddled(Object entity) { return entity instanceof net.minecraft.world.entity.Saddleable s && s.isSaddled(); }
                    public static boolean powered(Object entity) { return entity instanceof net.minecraft.world.entity.PowerableMob p && p.isPowered(); }
                }""");
        s.put("net/minecraft/client/model/HumanoidModel.java", """
                package net.minecraft.client.model;
                public class HumanoidModel { public boolean young; public boolean crouching; }""");
        s.put("software/bernie/geckolib/renderer/GeoArmorRenderer.java", """
                package software.bernie.geckolib.renderer;
                public class GeoArmorRenderer extends net.minecraft.client.model.HumanoidModel {
                    public String copyFrom(net.minecraft.client.model.HumanoidModel base) {
                        this.young = base.young;
                        this.crouching = base.crouching;
                        return "copied:" + this.young;
                    }
                }""");
        s.put("mod/Uses.java", """
                package mod;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.renderer.texture.DynamicTexture;
                import net.minecraft.resources.Identifier;
                import software.bernie.geckolib.cache.texture.*;
                public final class Uses {
                    public static String frames(String id, int ticks) {
                        Identifier texture = Identifier.parse(id);
                        StringBuilder out = new StringBuilder();
                        for (int tick = 0; tick < ticks; tick++) {
                            AnimatableTexture.setAndUpdate(texture, tick);
                            DynamicTexture shown = (DynamicTexture) Minecraft.getInstance().getTextureManager().getTexture(texture);
                            out.append(out.length() == 0 ? "" : ",").append(shown.getPixels().getPixel(0, 0)).append(':').append(shown.uploads);
                        }
                        return out.toString();
                    }
                    public static String glow(String id) {
                        Identifier glow = AutoGlowingTexture.getEmissiveResource(Identifier.parse(id));
                        DynamicTexture layer = (DynamicTexture) Minecraft.getInstance().getTextureManager().getTexture(glow);
                        StringBuilder out = new StringBuilder(glow + "=");
                        for (int x = 0; x < layer.getPixels().getWidth(); x++) {
                            out.append(x == 0 ? "" : "|").append(Integer.toHexString(layer.getPixels().getPixel(x, 0)));
                        }
                        return out.toString();
                    }
                    public static String renderTypes(String id) {
                        return AutoGlowingTexture.getRenderType(Identifier.parse(id)) + "|" + AutoGlowingTexture.getOutlineRenderType(Identifier.parse(id));
                    }
                    public static String buffers() {
                        software.bernie.geckolib.renderer.GeoRenderer renderer = new software.bernie.geckolib.renderer.GeoRenderer() {};
                        net.minecraft.client.renderer.MultiBufferSource source = type -> new Named("fresh");
                        com.mojang.blaze3d.vertex.BufferBuilder done = new com.mojang.blaze3d.vertex.BufferBuilder();
                        com.mojang.blaze3d.vertex.BufferBuilder open = new com.mojang.blaze3d.vertex.BufferBuilder();
                        open.building = true;
                        Named given = new Named("given");
                        return name(renderer.checkAndRefreshBuffer(false, done, source, null), done) + "|"
                                + name(renderer.checkAndRefreshBuffer(false, open, source, null), open) + "|"
                                + name(renderer.checkAndRefreshBuffer(true, done, source, null), done) + "|"
                                + name(renderer.checkAndRefreshBuffer(false, given, source, null), given);
                    }
                    private static String name(com.mojang.blaze3d.vertex.VertexConsumer result, Object input) {
                        return result == input ? (input instanceof Named n ? n.name : "kept") : ((Named) result).name;
                    }
                    public static String armor() {
                        return new software.bernie.geckolib.renderer.GeoArmorRenderer().copyFrom(new software.bernie.geckolib.renderer.GeoArmorRenderer());
                    }
                    public static String queries() {
                        net.minecraft.world.entity.Mob saddled = new net.minecraft.world.entity.Mob(true);
                        net.minecraft.world.entity.monster.Creeper charged = new net.minecraft.world.entity.monster.Creeper(true);
                        return "saddled:" + software.bernie.geckolib.loading.math.MolangQueries.saddled(saddled) + ","
                                + software.bernie.geckolib.loading.math.MolangQueries.saddled("not a mob")
                                + " powered:" + software.bernie.geckolib.loading.math.MolangQueries.powered(charged) + ","
                                + software.bernie.geckolib.loading.math.MolangQueries.powered(saddled);
                    }
                    record Named(String name) implements com.mojang.blaze3d.vertex.VertexConsumer {}
                }""");
        return s;
    }

    // What 26.3 still has, which both worlds share.
    private static Map<String, String> sharedWorld() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String namespace, path;
                    private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    public static Identifier parse(String id) { int colon = id.indexOf(':'); return new Identifier(id.substring(0, colon), id.substring(colon + 1)); }
                    public String getNamespace() { return namespace; }
                    public String getPath() { return path; }
                    public boolean equals(Object o) { return o instanceof Identifier i && i.toString().equals(toString()); }
                    public int hashCode() { return toString().hashCode(); }
                    public String toString() { return namespace + ":" + path; }
                }""");
        s.put("com/mojang/blaze3d/vertex/VertexConsumer.java", """
                package com.mojang.blaze3d.vertex;
                public interface VertexConsumer {}""");
        s.put("com/mojang/blaze3d/vertex/BufferBuilder.java", """
                package com.mojang.blaze3d.vertex;
                public class BufferBuilder implements VertexConsumer { public boolean building; }""");
        s.put("net/minecraft/client/renderer/rendertype/RenderType.java", """
                package net.minecraft.client.renderer.rendertype;
                public class RenderType { final String name; public RenderType(String name) { this.name = name; } public String toString() { return name; } }""");
        s.put("net/minecraft/client/renderer/MultiBufferSource.java", """
                package net.minecraft.client.renderer;
                public interface MultiBufferSource { com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.rendertype.RenderType type); }""");
        s.put("net/minecraft/client/model/HumanoidModel.java", """
                package net.minecraft.client.model;
                public class HumanoidModel {}""");
        s.put("net/minecraft/world/entity/Mob.java", """
                package net.minecraft.world.entity;
                public class Mob { private final boolean flag; public Mob(boolean flag) { this.flag = flag; } public boolean isSaddled() { return flag; } public boolean flag() { return flag; } }""");
        s.put("net/minecraft/world/entity/monster/Creeper.java", """
                package net.minecraft.world.entity.monster;
                public class Creeper extends net.minecraft.world.entity.Mob { public Creeper(boolean powered) { super(powered); } public boolean isPowered() { return flag(); } }""");
        s.put("net/minecraft/world/entity/boss/wither/WitherBoss.java", """
                package net.minecraft.world.entity.boss.wither;
                public class WitherBoss extends net.minecraft.world.entity.Mob { public WitherBoss() { super(false); } public boolean isPowered() { return false; } }""");
        s.put("net/minecraft/client/renderer/texture/AbstractTexture.java", """
                package net.minecraft.client.renderer.texture;
                public abstract class AbstractTexture implements AutoCloseable { public void close() {} }""");
        s.put("com/mojang/blaze3d/platform/NativeImage.java", """
                package com.mojang.blaze3d.platform;
                public final class NativeImage implements AutoCloseable {
                    private final int width, height; private final int[] pixels;
                    public NativeImage(int width, int height, boolean clear) { this.width = width; this.height = height; this.pixels = new int[width * height]; }
                    // Test images are stored as width, height, then one int per pixel.
                    public static NativeImage read(java.io.InputStream in) throws java.io.IOException {
                        java.io.DataInputStream data = new java.io.DataInputStream(in);
                        NativeImage image = new NativeImage(data.readInt(), data.readInt(), false);
                        for (int i = 0; i < image.pixels.length; i++) image.pixels[i] = data.readInt();
                        return image;
                    }
                    public int getWidth() { return width; }
                    public int getHeight() { return height; }
                    public int getPixel(int x, int y) { return pixels[y * width + x]; }
                    public void setPixel(int x, int y, int color) { pixels[y * width + x] = color; }
                    public void close() {}
                }""");
        s.put("net/minecraft/client/renderer/texture/DynamicTexture.java", """
                package net.minecraft.client.renderer.texture;
                public class DynamicTexture extends AbstractTexture {
                    private final com.mojang.blaze3d.platform.NativeImage pixels; public int uploads;
                    public DynamicTexture(java.util.function.Supplier<String> label, com.mojang.blaze3d.platform.NativeImage pixels) { this.pixels = pixels; upload(); }
                    public com.mojang.blaze3d.platform.NativeImage getPixels() { return pixels; }
                    public void upload() { uploads++; }
                }""");
        return s;
    }

    // 26.3's texture, resource and render type classes, as far as the rebuilt code reads them.
    private static Map<String, String> newWorld() {
        Map<String, String> s = new HashMap<>(sharedWorld());
        s.put("com/mojang/blaze3d/systems/RenderSystem.java", """
                package com.mojang.blaze3d.systems;
                public final class RenderSystem { public static boolean isOnRenderThread() { return true; } }""");
        s.put("net/minecraft/client/renderer/rendertype/RenderTypes.java", """
                package net.minecraft.client.renderer.rendertype;
                public final class RenderTypes {
                    public static RenderType entityTranslucentEmissive(net.minecraft.resources.Identifier texture) { return new RenderType("emissive:" + texture); }
                }""");
        s.put("net/minecraft/client/renderer/texture/TextureManager.java", """
                package net.minecraft.client.renderer.texture;
                public class TextureManager {
                    private final java.util.Map<net.minecraft.resources.Identifier, AbstractTexture> byId = new java.util.HashMap<>();
                    public void register(net.minecraft.resources.Identifier id, AbstractTexture texture) { byId.put(id, texture); }
                    public AbstractTexture getTexture(net.minecraft.resources.Identifier id) { return byId.get(id); }
                }""");
        s.put("net/minecraft/server/packs/metadata/MetadataSectionType.java", """
                package net.minecraft.server.packs.metadata;
                public final class MetadataSectionType<T> {}""");
        s.put("net/minecraft/client/resources/metadata/animation/FrameSize.java", """
                package net.minecraft.client.resources.metadata.animation;
                public record FrameSize(int width, int height) {}""");
        s.put("net/minecraft/client/resources/metadata/animation/AnimationFrame.java", """
                package net.minecraft.client.resources.metadata.animation;
                public record AnimationFrame(int index) { public int timeOr(int fallback) { return fallback; } }""");
        s.put("net/minecraft/client/resources/metadata/animation/AnimationMetadataSection.java", """
                package net.minecraft.client.resources.metadata.animation;
                public record AnimationMetadataSection(int defaultFrameTime) {
                    public static final net.minecraft.server.packs.metadata.MetadataSectionType<AnimationMetadataSection> TYPE = new net.minecraft.server.packs.metadata.MetadataSectionType<>();
                    public FrameSize calculateFrameSize(int width, int height) { return new FrameSize(width, width); }
                    public java.util.Optional<java.util.List<AnimationFrame>> frames() { return java.util.Optional.empty(); }
                    public boolean interpolatedFrames() { return false; }
                }""");
        s.put("net/minecraft/server/packs/resources/ResourceMetadata.java", """
                package net.minecraft.server.packs.resources;
                public interface ResourceMetadata { <T> java.util.Optional<T> getSection(net.minecraft.server.packs.metadata.MetadataSectionType<T> type); }""");
        s.put("net/minecraft/server/packs/resources/Resource.java", """
                package net.minecraft.server.packs.resources;
                public class Resource {
                    private final int[] image; private final Object section;
                    public Resource(int[] image, Object section) { this.image = image; this.section = section; }
                    public java.io.InputStream open() throws java.io.IOException {
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
                        for (int value : image) out.writeInt(value);
                        return new java.io.ByteArrayInputStream(bytes.toByteArray());
                    }
                    @SuppressWarnings("unchecked")
                    public ResourceMetadata metadata() { return new ResourceMetadata() { public <T> java.util.Optional<T> getSection(net.minecraft.server.packs.metadata.MetadataSectionType<T> type) { return java.util.Optional.ofNullable((T) section); } }; }
                }""");
        s.put("net/minecraft/server/packs/resources/ResourceProvider.java", """
                package net.minecraft.server.packs.resources;
                public interface ResourceProvider { java.util.Optional<Resource> getResource(net.minecraft.resources.Identifier id); }""");
        s.put("net/minecraft/server/packs/resources/ResourceManager.java", """
                package net.minecraft.server.packs.resources;
                public interface ResourceManager extends ResourceProvider {}""");
        s.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
                import net.minecraft.server.packs.resources.Resource;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    private final net.minecraft.client.renderer.texture.TextureManager textures = new net.minecraft.client.renderer.texture.TextureManager();
                    private final java.util.Map<String, Resource> files = java.util.Map.of(
                            "geo:textures/strip.png", new Resource(new int[] {1, 3, 1, 2, 3}, new AnimationMetadataSection(2)),
                            "geo:textures/bat.png", new Resource(new int[] {2, 1, 0xffaabbcc, 0xff112233}, null),
                            "geo:textures/bat_glowmask.png", new Resource(new int[] {2, 1, 0x80ffffff, 0x00ffffff}, null),
                            "geo:textures/plain.png", new Resource(new int[] {1, 1, 0xff000000}, null));
                    public static Minecraft getInstance() { return INSTANCE; }
                    public net.minecraft.client.renderer.texture.TextureManager getTextureManager() { return textures; }
                    public net.minecraft.server.packs.resources.ResourceManager getResourceManager() {
                        return id -> java.util.Optional.ofNullable(files.get(id.toString()));
                    }
                }""");
        return s;
    }
}
