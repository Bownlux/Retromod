/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.geckolib;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/** GeckoLib 4's animated and glowing entity textures, rebuilt on the texture manager of Minecraft 1.21.11 and newer. */
public final class LegacyGeoTextures {

    private static final String GLOW_SUFFIX = "_glowmask";
    // Every base texture looked at so far: its animation, or NONE when the host's own texture is right as it is.
    private static final Map<Identifier, Animation> ANIMATIONS = new ConcurrentHashMap<>();
    private static final Animation NONE = new Animation(null, null, 0, 0, 0, new int[0], new int[0], false, null);
    // Glow layers already built, by the id of the base texture they belong to.
    private static final Map<Identifier, Identifier> GLOW_LAYERS = new ConcurrentHashMap<>();

    private LegacyGeoTextures() {}

    /** {@code AnimatableTexture.setAndUpdate(id, tick)}: shows the frame an animated texture has at this tick. */
    public static void setAndUpdate(Identifier id, int tick) {
        // Uploads go through the render device, which only the render thread may use.
        if (!RenderSystem.isOnRenderThread()) return;
        Animation animation = animation(id);
        if (animation != NONE) animation.show(tick);
    }

    /** {@code AutoGlowingTexture.getEmissiveResource(base)}: the id of the base texture's glow layer, built on first use. */
    public static Identifier emissiveResource(Identifier base) {
        Identifier glow = withGlowSuffix(base);
        if (RenderSystem.isOnRenderThread() && !GLOW_LAYERS.containsKey(base)) {
            buildGlowLayer(base, glow);
            GLOW_LAYERS.put(base, glow);
        }
        return glow;
    }

    /**
     * {@code AutoGlowingTexture.getRenderType(base)} and {@code getOutlineRenderType(base)}: the base texture's glow
     * layer at full brightness. 26.3 kept only the form that also draws into the glowing outline, so both use it.
     */
    public static RenderType glowingRenderType(Identifier base) {
        return RenderTypes.entityTranslucentEmissive(emissiveResource(base));
    }

    // GeckoLib's own naming: textures/entity/bat.png has its mask at textures/entity/bat_glowmask.png.
    static Identifier withGlowSuffix(Identifier base) {
        String path = base.getPath();
        int dot = path.lastIndexOf('.');
        String renamed = dot < 0 ? path + GLOW_SUFFIX : path.substring(0, dot) + GLOW_SUFFIX + path.substring(dot);
        return Identifier.fromNamespaceAndPath(base.getNamespace(), renamed);
    }

    private static Animation animation(Identifier id) {
        Animation known = ANIMATIONS.get(id);
        // A resource reload puts the host's own texture back, so a replaced animation is read again.
        if (known != null && (known == NONE || known.isRegistered())) return known;
        Animation loaded = load(id);
        ANIMATIONS.put(id, loaded);
        return loaded;
    }

    // Reads the texture and its .mcmeta animation section, and puts a texture one frame in size under the same id.
    private static Animation load(Identifier id) {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<Resource> resource = minecraft.getResourceManager().getResource(id);
        if (resource.isEmpty()) return NONE;
        try {
            Optional<AnimationMetadataSection> section = resource.get().metadata().getSection(AnimationMetadataSection.TYPE);
            if (section.isEmpty()) return NONE;
            NativeImage strip = readImage(resource.get());
            Animation animation = Animation.of(id, strip, section.get(), minecraft.getTextureManager());
            if (animation == NONE) strip.close();
            return animation;
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    private static NativeImage readImage(Resource resource) throws IOException {
        try (InputStream in = resource.open()) {
            return NativeImage.read(in);
        }
    }

    // GeckoLib's glow layer: the base texture's pixels where the mask is opaque, with the mask's alpha.
    private static void buildGlowLayer(Identifier base, Identifier glow) {
        Minecraft minecraft = Minecraft.getInstance();
        NativeImage layer;
        try {
            layer = maskedImage(minecraft, base, glow);
        } catch (IOException | RuntimeException e) {
            layer = null;
        }
        // Without a usable mask nothing glows; an empty layer keeps the missing texture from being drawn instead.
        if (layer == null) layer = new NativeImage(1, 1, true);
        Animation animation = animation(base);
        if (animation != NONE && layer.getWidth() == animation.stripWidth() && layer.getHeight() == animation.stripHeight()) {
            animation.addFollower(glow, layer, minecraft.getTextureManager());
            return;
        }
        minecraft.getTextureManager().register(glow, new DynamicTexture(glow::toString, layer));
    }

    private static NativeImage maskedImage(Minecraft minecraft, Identifier base, Identifier glow) throws IOException {
        Optional<Resource> baseResource = minecraft.getResourceManager().getResource(base);
        Optional<Resource> maskResource = minecraft.getResourceManager().getResource(glow);
        if (baseResource.isEmpty() || maskResource.isEmpty()) return null;
        try (NativeImage source = readImage(baseResource.get()); NativeImage mask = readImage(maskResource.get())) {
            if (source.getWidth() != mask.getWidth() || source.getHeight() != mask.getHeight()) return null;
            NativeImage layer = new NativeImage(source.getWidth(), source.getHeight(), true);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int alpha = mask.getPixel(x, y) >>> 24;
                    if (alpha > 0) layer.setPixel(x, y, alpha << 24 | source.getPixel(x, y) & 0x00FFFFFF);
                }
            }
            return layer;
        }
    }

    /** One animated texture: the whole strip of frames, the frame-sized texture drawn, and the glow layers that follow it. */
    static final class Animation {
        private final Identifier id;
        private final NativeImage strip;
        private final int frameWidth;
        private final int frameHeight;
        private final int columns;
        private final int[] frames;
        private final int[] times;
        private final boolean interpolate;
        private final DynamicTexture texture;
        private final List<Follower> followers = new ArrayList<>();
        private final int cycle;
        private int shownTick = -1;
        private int shownFrame = -1;

        private Animation(Identifier id, NativeImage strip, int frameWidth, int frameHeight, int columns, int[] frames,
                int[] times, boolean interpolate, DynamicTexture texture) {
            this.id = id;
            this.strip = strip;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.columns = columns;
            this.frames = frames;
            this.times = times;
            this.interpolate = interpolate;
            this.texture = texture;
            int total = 0;
            for (int time : times) total += time;
            this.cycle = total;
        }

        static Animation of(Identifier id, NativeImage strip, AnimationMetadataSection section, TextureManager textures) {
            FrameSize size = section.calculateFrameSize(strip.getWidth(), strip.getHeight());
            int columns = strip.getWidth() / size.width();
            int count = columns * (strip.getHeight() / size.height());
            // The frame list from the .mcmeta file, or every frame of the strip in order, each lasting the default time.
            List<int[]> listed = new ArrayList<>();
            if (section.frames().isPresent()) {
                for (AnimationFrame frame : section.frames().get()) {
                    if (frame.index() >= 0 && frame.index() < count) {
                        listed.add(new int[] {frame.index(), Math.max(1, frame.timeOr(section.defaultFrameTime()))});
                    }
                }
            } else {
                for (int i = 0; i < count; i++) listed.add(new int[] {i, Math.max(1, section.defaultFrameTime())});
            }
            if (listed.size() < 2) return NONE;
            int[] frames = new int[listed.size()];
            int[] times = new int[listed.size()];
            for (int i = 0; i < frames.length; i++) {
                frames[i] = listed.get(i)[0];
                times[i] = listed.get(i)[1];
            }
            NativeImage first = new NativeImage(size.width(), size.height(), false);
            DynamicTexture texture = new DynamicTexture(id::toString, first);
            textures.register(id, texture);
            return new Animation(id, strip, size.width(), size.height(), columns, frames, times,
                    section.interpolatedFrames(), texture);
        }

        int stripWidth() {
            return strip.getWidth();
        }

        int stripHeight() {
            return strip.getHeight();
        }

        boolean isRegistered() {
            return Minecraft.getInstance().getTextureManager().getTexture(id) == texture;
        }

        void addFollower(Identifier glow, NativeImage glowStrip, TextureManager textures) {
            NativeImage first = new NativeImage(frameWidth, frameHeight, false);
            DynamicTexture glowTexture = new DynamicTexture(glow::toString, first);
            textures.register(glow, glowTexture);
            followers.add(new Follower(glowStrip, glowTexture));
            // The next update draws the current frame into the new layer too.
            shownTick = -1;
            shownFrame = -1;
        }

        void show(int tick) {
            int time = Math.floorMod(tick, cycle);
            if (time == shownTick) return;
            shownTick = time;
            // Find the listed frame this tick falls in, and how far into it.
            int slot = 0;
            int start = 0;
            while (time >= start + times[slot]) {
                start += times[slot];
                slot++;
            }
            int next = (slot + 1) % frames.length;
            float progress = interpolate ? (time - start) / (float) times[slot] : 0.0F;
            // Without interpolation a frame is drawn once and kept until the next one starts.
            if (!interpolate && frames[slot] == shownFrame) return;
            shownFrame = frames[slot];
            draw(strip, texture, frames[slot], frames[next], progress);
            for (Follower follower : followers) draw(follower.strip(), follower.texture(), frames[slot], frames[next], progress);
        }

        private void draw(NativeImage source, DynamicTexture target, int frame, int nextFrame, float progress) {
            NativeImage pixels = target.getPixels();
            int fromX = frame % columns * frameWidth;
            int fromY = frame / columns * frameHeight;
            int toX = nextFrame % columns * frameWidth;
            int toY = nextFrame / columns * frameHeight;
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    int color = source.getPixel(fromX + x, fromY + y);
                    if (progress > 0.0F) color = blend(color, source.getPixel(toX + x, toY + y), progress);
                    pixels.setPixel(x, y, color);
                }
            }
            target.upload();
        }

        // Each channel moves the same share of the way to the next frame's color.
        private static int blend(int from, int to, float progress) {
            int result = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                int a = from >>> shift & 0xFF;
                int b = to >>> shift & 0xFF;
                result |= Math.round(a + (b - a) * progress) << shift;
            }
            return result;
        }
    }

    private record Follower(NativeImage strip, DynamicTexture texture) {}
}
