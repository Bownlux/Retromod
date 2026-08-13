/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 RevivalSMP. Licensed under MIT.
 */
package com.retromod.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;

/**
 * Detects the runtime environment: client vs dedicated server, headless vs GUI,
 * CPU architecture, OS, and rendering backend.
 */
public class EnvironmentDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Env");

    private static Boolean isClient = null;
    private static Boolean isHeadless = null;
    private static Boolean isDedicatedServer = null;

    private static String cpuArch = null;
    private static String osName = null;
    private static RenderingBackend renderingBackend = null;

    public enum CpuArch {
        X86_64,     // Intel/AMD 64-bit
        X86,        // Intel/AMD 32-bit
        AARCH64,    // ARM 64-bit (Apple Silicon, Raspberry Pi 4+)
        ARM,        // ARM 32-bit
        RISCV64,
        UNKNOWN
    }

    public enum OsFamily {
        WINDOWS,
        MACOS,
        LINUX,
        UNKNOWN
    }

    public enum RenderingBackend {
        OPENGL,
        VULKAN,
        METAL,
        DIRECTX,
        SOFTWARE,
        UNKNOWN
    }

    /** True when running on a client (rendering, GUI). */
    public static boolean isClient() {
        if (isClient == null) {
            isClient = detectClient();
        }
        return isClient;
    }

    /** True when running on a dedicated server (no client, no GUI). */
    public static boolean isDedicatedServer() {
        if (isDedicatedServer == null) {
            isDedicatedServer = detectDedicatedServer();
        }
        return isDedicatedServer;
    }

    /** True when no display is available. */
    public static boolean isHeadless() {
        if (isHeadless == null) {
            isHeadless = detectHeadless();
        }
        return isHeadless;
    }

    /** True when GUI dialogs can be shown. */
    public static boolean canShowGui() {
        return isClient() && !isHeadless();
    }

    public static CpuArch getCpuArch() {
        String arch = getCpuArchString();
        return switch (arch) {
            case "amd64", "x86_64" -> CpuArch.X86_64;
            case "x86", "i386", "i486", "i586", "i686" -> CpuArch.X86;
            case "aarch64", "arm64" -> CpuArch.AARCH64;
            case "arm", "armv7l", "armhf" -> CpuArch.ARM;
            case "riscv64" -> CpuArch.RISCV64;
            default -> CpuArch.UNKNOWN;
        };
    }

    /** Raw {@code os.arch} string from the JVM. */
    public static String getCpuArchString() {
        if (cpuArch == null) {
            cpuArch = System.getProperty("os.arch", "unknown").toLowerCase();
        }
        return cpuArch;
    }

    /** True on either 32-bit or 64-bit ARM. */
    public static boolean isArm() {
        CpuArch arch = getCpuArch();
        return arch == CpuArch.AARCH64 || arch == CpuArch.ARM;
    }

    public static boolean isAppleSilicon() {
        return getCpuArch() == CpuArch.AARCH64 && getOsFamily() == OsFamily.MACOS;
    }

    public static OsFamily getOsFamily() {
        String os = getOsString();
        if (os.contains("win")) return OsFamily.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OsFamily.MACOS;
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) return OsFamily.LINUX;
        return OsFamily.UNKNOWN;
    }

    /** Raw {@code os.name} string. */
    public static String getOsString() {
        if (osName == null) {
            osName = System.getProperty("os.name", "unknown").toLowerCase();
        }
        return osName;
    }

    public static RenderingBackend getRenderingBackend() {
        if (renderingBackend == null) {
            renderingBackend = detectRenderingBackend();
        }
        return renderingBackend;
    }

    public static boolean isVulkan() {
        return getRenderingBackend() == RenderingBackend.VULKAN;
    }

    public static boolean isMetal() {
        return getRenderingBackend() == RenderingBackend.METAL;
    }

    public static boolean isOpenGL() {
        return getRenderingBackend() == RenderingBackend.OPENGL;
    }

    private static RenderingBackend detectRenderingBackend() {
        if (isDedicatedServer()) {
            return RenderingBackend.SOFTWARE;
        }

        // Explicit override, should MC ever set one
        String backendProp = System.getProperty("minecraft.rendering.backend", "");
        if (!backendProp.isEmpty()) {
            return switch (backendProp.toLowerCase()) {
                case "vulkan", "vk" -> RenderingBackend.VULKAN;
                case "metal", "mtl" -> RenderingBackend.METAL;
                case "opengl", "gl" -> RenderingBackend.OPENGL;
                case "directx", "dx", "d3d" -> RenderingBackend.DIRECTX;
                default -> RenderingBackend.UNKNOWN;
            };
        }

        if (classExists("org.lwjgl.vulkan.VK10")
                && (classExists("com.mojang.blaze3d.platform.VulkanStateManager")
                    || classExists("net.minecraft.client.render.VulkanRenderer"))) {
            return RenderingBackend.VULKAN;
        }

        if (getOsFamily() == OsFamily.MACOS) {
            if (classExists("com.mojang.blaze3d.platform.MetalStateManager")) {
                return RenderingBackend.METAL;
            }

            // MoltenVK routes Vulkan over Metal
            String vulkanICD = System.getenv("VK_ICD_FILENAMES");
            if (vulkanICD != null && vulkanICD.contains("MoltenVK")) {
                return RenderingBackend.VULKAN;
            }
        }

        if (getOsFamily() == OsFamily.WINDOWS) {
            if (classExists("com.mojang.blaze3d.platform.DirectXStateManager")) {
                return RenderingBackend.DIRECTX;
            }
        }

        // OpenGL is MC's only backend through 26.1.x. GlStateManager was dropped
        // in the 26.1 render refactor, so RenderSystem is the signal there.
        for (String glSignal : new String[]{
                "com.mojang.blaze3d.platform.GlStateManager",   // 1.8 - 1.21.x
                "com.mojang.blaze3d.systems.RenderSystem",       // 1.15+ incl 26.1
                "com.mojang.blaze3d.opengl.GlStateManager"       // possible 26.1 relocation
        }) {
            if (classExists(glSignal)) {
                return RenderingBackend.OPENGL;
            }
        }

        return RenderingBackend.UNKNOWN;
    }

    private static boolean detectClient() {
        if (classExists("net.minecraft.client.MinecraftClient")   // Yarn (dev)
                || classExists("net.minecraft.client.Minecraft")  // Mojang (26.1+, NeoForge)
                || classExists("net.minecraft.class_310")) {      // intermediary, pre-26.1 Fabric
            return true;
        }
        return !detectHeadless();
    }

    private static boolean detectDedicatedServer() {
        // The MC runtime ships a merged jar, so server classes exist on a client
        // too. Absence of a client class is the only reliable dedicated signal.
        boolean clientPresent = classExists("net.minecraft.client.MinecraftClient")
                || classExists("net.minecraft.client.Minecraft")
                || classExists("net.minecraft.class_310");
        if (clientPresent) {
            return false;
        }
        if (classExists("net.minecraft.server.MinecraftServer")
                || classExists("net.minecraft.server.dedicated.DedicatedServer")) {
            return true;
        }
        return detectHeadless();
    }

    /**
     * Tests class presence without defining or initializing it (#46). Even the three-arg
     * {@link Class#forName(String, boolean, ClassLoader)} defines the target through Knot,
     * which makes Mixin apply untransformed mod mixins during Retromod's pre-launch pass.
     */
    static boolean classExists(String name) {
        return ClassResourceInspector.exists(name.replace('.', '/'));
    }

    /**
     * Non-defining host-class probe (same contract as {@link #classExists(String)}).
     * The mixin compat layer uses it to neutralize a {@code @Mixin} whose target was
     * removed on the host MC.
     */
    public static boolean hostClassExists(String binaryName) {
        return classExists(binaryName);
    }

    private static boolean detectHeadless() {
        if (GraphicsEnvironment.isHeadless()) {
            return true;
        }

        String headless = System.getProperty("java.awt.headless");
        if ("true".equalsIgnoreCase(headless)) {
            return true;
        }

        String display = System.getenv("DISPLAY");
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            if (display == null || display.isEmpty()) {
                return true;
            }
        }

        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.getDefaultScreenDevice();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** Log the detected environment. */
    public static void logEnvironment() {
        LOGGER.info("Environment: {} (headless: {})",
            isDedicatedServer() ? "Dedicated Server" : "Client",
            isHeadless());
        LOGGER.info("  OS: {} ({})", getOsFamily(), getOsString());
        LOGGER.info("  CPU: {} ({})", getCpuArch(), getCpuArchString());
        if (isAppleSilicon()) {
            LOGGER.info("  Apple Silicon detected");
        }
        if (!isDedicatedServer()) {
            LOGGER.info("  Rendering: {}", getRenderingBackend());
        }
    }

    /** Override detection (testing, or when auto-detection fails). */
    public static void setEnvironment(boolean client, boolean headless) {
        isClient = client;
        isHeadless = headless;
        isDedicatedServer = !client;
    }
}
