/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Analyzes a mod JAR to estimate how likely it is to work after
 * Retromod's bytecode transformation.
 *
 * Mods that deeply depend on Minecraft internals, use heavy reflection,
 * hook into rendering pipelines, or use NMS/Forge capabilities extensively
 * are "unlikely to work" because Retromod cannot remap all of their
 * internal dependencies.
 *
 * The complexity score is a heuristic. It does not guarantee failure,
 * but gives users a heads-up before wasting time on a mod that will crash.
 *
 * Scoring:
 *   - Each risk factor adds points to the complexity score
 *   - Score >= THRESHOLD_UNLIKELY means the mod is "unlikely to work"
 *   - Users can override this via "force_translate_complex" in config
 */
public class ModComplexityAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Complexity");

    // Score threshold: at or above this, we warn the user
    private static final int THRESHOLD_UNLIKELY = 60;

    /**
     * Result of a complexity analysis.
     */
    public record ComplexityReport(
        int score,
        boolean isUnlikelyToWork,
        String reason,
        List<String> riskFactors
    ) {}

    /**
     * Analyze a mod JAR for transformation complexity.
     */
    public ComplexityReport analyze(Path modJar) {
        int score = 0;
        List<String> riskFactors = new ArrayList<>();

        try (JarFile jar = new JarFile(modJar.toFile())) {
            int classCount = 0;
            int mixinCount = 0;
            int reflectionUses = 0;
            int nmsAccesses = 0;
            int asmUses = 0;
            int nativeMethodCalls = 0;
            int capabilityUses = 0;
            int networkingUses = 0;
            int renderingUses = 0;
            boolean usesCoremod = false;
            boolean usesAccessTransformer = false;

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                // Check for coremods (Forge)
                if (entry.getName().equals("META-INF/coremods.json") ||
                    entry.getName().endsWith("_at.cfg") ||
                    entry.getName().equals("META-INF/accesstransformer.cfg")) {
                    usesCoremod = true;
                }

                if (entry.getName().endsWith("_at.cfg") ||
                    entry.getName().equals("META-INF/accesstransformer.cfg")) {
                    usesAccessTransformer = true;
                }

                if (!entry.getName().endsWith(".class")) continue;

                classCount++;

                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] classBytes = is.readAllBytes();
                    ClassReader reader = new ClassReader(classBytes);

                    // Check for mixin classes
                    String[] interfaces = new String[0];
                    try {
                        // Simple check: look for Mixin annotation
                        reader.accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                return null;
                            }
                        }, ClassReader.SKIP_CODE);
                    } catch (Exception ignored) {}

                    // Analyze method bodies
                    final int[] localReflection = {0};
                    final int[] localNms = {0};
                    final int[] localAsm = {0};
                    final int[] localNative = {0};
                    final int[] localCap = {0};
                    final int[] localNet = {0};
                    final int[] localRender = {0};
                    final int[] localMixin = {0};

                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (descriptor.contains("Mixin") || descriptor.contains("mixin")) {
                                localMixin[0]++;
                            }
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc,
                                                         String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String mName,
                                                            String mDesc, boolean isInterface) {
                                    // Reflection
                                    if (owner.equals("java/lang/Class") &&
                                        (mName.equals("forName") || mName.equals("getDeclaredMethod") ||
                                         mName.equals("getDeclaredField") || mName.equals("getMethod"))) {
                                        localReflection[0]++;
                                    }
                                    if (owner.equals("java/lang/reflect/Method") && mName.equals("invoke")) {
                                        localReflection[0]++;
                                    }

                                    // NMS / obfuscated MC internals
                                    if (owner.startsWith("net/minecraft/server/") &&
                                        !owner.startsWith("net/minecraft/server/level/") &&
                                        !owner.startsWith("net/minecraft/server/packs/")) {
                                        localNms[0]++;
                                    }

                                    // ASM usage (mod generating bytecode at runtime)
                                    if (owner.startsWith("org/objectweb/asm/")) {
                                        localAsm[0]++;
                                    }

                                    // Native/LWJGL calls
                                    if (owner.startsWith("org/lwjgl/") || owner.startsWith("sun/misc/Unsafe")) {
                                        localNative[0]++;
                                    }

                                    // Capabilities (Forge)
                                    if (owner.contains("Capability") || owner.contains("capability")) {
                                        localCap[0]++;
                                    }

                                    // Networking
                                    if (owner.contains("Packet") || owner.contains("Channel") ||
                                        owner.contains("NetworkHandler") || owner.contains("NetworkManager")) {
                                        localNet[0]++;
                                    }

                                    // Rendering pipeline
                                    if (owner.contains("Renderer") || owner.contains("RenderType") ||
                                        owner.contains("Shader") || owner.contains("Tessellator") ||
                                        owner.contains("BufferBuilder") || owner.contains("VertexConsumer")) {
                                        localRender[0]++;
                                    }
                                }
                            };
                        }
                    }, 0);

                    reflectionUses += localReflection[0];
                    nmsAccesses += localNms[0];
                    asmUses += localAsm[0];
                    nativeMethodCalls += localNative[0];
                    capabilityUses += localCap[0];
                    networkingUses += localNet[0];
                    renderingUses += localRender[0];
                    mixinCount += localMixin[0];

                } catch (Exception e) {
                    // Skip unreadable classes
                }
            }


            // Large mods are riskier
            if (classCount > 500) {
                score += 15;
                riskFactors.add("Very large mod (" + classCount + " classes)");
            } else if (classCount > 200) {
                score += 8;
                riskFactors.add("Large mod (" + classCount + " classes)");
            }

            // Heavy reflection use
            if (reflectionUses > 20) {
                score += 20;
                riskFactors.add("Heavy reflection use (" + reflectionUses + " calls)");
            } else if (reflectionUses > 5) {
                score += 10;
                riskFactors.add("Moderate reflection use (" + reflectionUses + " calls)");
            }

            // NMS / obfuscated internals
            if (nmsAccesses > 10) {
                score += 25;
                riskFactors.add("Accesses Minecraft server internals (" + nmsAccesses + " calls)");
            } else if (nmsAccesses > 0) {
                score += 10;
                riskFactors.add("Some internal MC server access (" + nmsAccesses + " calls)");
            }

            // ASM usage (mod does its own bytecode manipulation)
            if (asmUses > 0) {
                score += 20;
                riskFactors.add("Uses ASM bytecode manipulation (" + asmUses + " calls)");
            }

            // Native/LWJGL calls
            if (nativeMethodCalls > 5) {
                score += 15;
                riskFactors.add("Uses native/LWJGL calls (" + nativeMethodCalls + " calls)");
            }

            // Coremods
            if (usesCoremod) {
                score += 25;
                riskFactors.add("Uses coremods (deep Forge hook)");
            }

            // Access transformers
            if (usesAccessTransformer) {
                score += 10;
                riskFactors.add("Uses access transformers");
            }

            // Forge capabilities
            if (capabilityUses > 10) {
                score += 15;
                riskFactors.add("Heavy Forge capability use (" + capabilityUses + " calls)");
            }

            // Networking
            if (networkingUses > 15) {
                score += 15;
                riskFactors.add("Heavy networking use (" + networkingUses + " calls)");
            } else if (networkingUses > 5) {
                score += 8;
                riskFactors.add("Moderate networking use (" + networkingUses + " calls)");
            }

            // Rendering pipeline
            if (renderingUses > 20) {
                score += 15;
                riskFactors.add("Heavy rendering pipeline use (" + renderingUses + " calls)");
            }

            // Many mixins
            if (mixinCount > 20) {
                score += 15;
                riskFactors.add("Many Mixin classes (" + mixinCount + ")");
            } else if (mixinCount > 5) {
                score += 5;
                riskFactors.add("Uses Mixins (" + mixinCount + " classes)");
            }

            boolean unlikely = score >= THRESHOLD_UNLIKELY;
            String reason = unlikely
                ? buildReason(riskFactors)
                : "Mod appears transformable";

            LOGGER.debug("Complexity analysis for {}: score={}, unlikely={}, factors={}",
                modJar.getFileName(), score, unlikely, riskFactors);

            return new ComplexityReport(score, unlikely, reason, riskFactors);

        } catch (Exception e) {
            LOGGER.warn("Could not analyze complexity of {}: {}", modJar.getFileName(), e.getMessage());
            // If we can't analyze, assume it's fine
            return new ComplexityReport(0, false, "Could not analyze", List.of());
        }
    }

    private String buildReason(List<String> factors) {
        if (factors.isEmpty()) return "Unknown";
        // Pick the top 2 most impactful reasons
        return String.join("; ", factors.subList(0, Math.min(2, factors.size())));
    }
}
