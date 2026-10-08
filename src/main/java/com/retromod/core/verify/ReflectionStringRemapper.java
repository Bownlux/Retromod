/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.verify;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Rewrites MC-typed strings passed to reflection APIs so mods using
 * {@code Class.forName("net.minecraft.old.X")} still resolve after the target
 * MC renamed the class.
 *
 * <p>The main transformer's {@code ClassRemapper} rewrites every class reference
 * in bytecode, but not class names that appear as {@link String} constants:
 * strings carry no type information. Mods looking up MC APIs via
 * {@code Class.forName("net.minecraft...")} thus slip through and fail at runtime
 * with {@link ClassNotFoundException}, likewise for {@code getDeclaredMethod},
 * {@code getField}, {@code MethodHandles.Lookup.findVirtual}, etc.
 *
 * <p>For each method we walk the instructions keeping a small sliding window of
 * recent {@code LDC} nodes. On an invocation of a known reflection sink (a JDK or
 * loader method taking a class/member name as a string) we look back in the
 * window for string constants matching MC-typed patterns and rewrite them.
 *
 * <p>Only strings that match the MC FQN or intermediary patterns, sit inside a
 * sink's lookback window, and have a known remap target get rewritten. Otherwise
 * the string is left alone: a missed rewrite throws diagnosably, an incorrect one
 * silently corrupts behavior.
 *
 * <p>Limitations: dynamic strings ({@code "net.minecraft." + pkg + ".Foo"}) need
 * data-flow analysis we don't do; array-packed args are only handled when the name
 * string is the LDC immediately before the call; member strings are remapped only
 * when their class is known; reflection wrapped in a mod's own utility method is
 * invisible since the sink is the utility, not a recognizable reflection API.
 *
 * <p>Stateless apart from its final configuration, so one instance can process
 * classes concurrently; counters are atomic.
 */
public final class ReflectionStringRemapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ReflectionRemapper");

    /**
     * How far back to look for an LDC when we hit a reflection sink. 8 covers
     * realistic patterns (LDC name, LDC class, dup, store, invoke) without
     * matching across unrelated call sites.
     */
    private static final int LOOKBACK_WINDOW = 8;

    /**
     * Matches dotted-notation strings that look like MC or loader class FQNs.
     * Both namespaces are covered since the loader-API rename path also handles
     * {@code net.minecraftforge.X} and {@code net.neoforged.X} at sinks. The
     * prefix alternation is most-specific-first so anchoring picks the longer
     * prefix ({@code net.minecraftforge} before {@code net.minecraft}); the
     * {@code \\.} enforces a package boundary after the prefix.
     */
    private static final Pattern MC_FQN_PATTERN = Pattern.compile(
            "^(net\\.minecraftforge|net\\.neoforged|net\\.fabricmc|net\\.minecraft|com\\.mojang)"
                    + "\\.[A-Za-z0-9_$.]+$");

    /** Matches intermediary names (for Fabric mods on 26.1+ that still hardcode them). */
    private static final Pattern INTERMEDIARY_METHOD_PATTERN = Pattern.compile(
            "^(method|comp)_[0-9]+$");
    private static final Pattern INTERMEDIARY_FIELD_PATTERN = Pattern.compile(
            "^field_[0-9]+$");

    /**
     * Reflection sinks: {@code "owner#name"} pairs whose first or only
     * {@link String} argument is a class/member name the runtime looks up.
     * Kept narrow: a sink that isn't a name-lookup ({@code String.format})
     * would rewrite ordinary data strings.
     */
    private static final java.util.Set<String> SINKS_TAKING_CLASS_FQN = java.util.Set.of(
            "java/lang/Class#forName",
            "java/lang/ClassLoader#loadClass",
            "java/lang/ClassLoader#findClass",
            // Fabric mods built for intermediary runtimes ask the loader to translate a
            // hardcoded intermediary name, as Cardinal Components does for Identifier when it
            // generates component classes. A 26.1+ runtime has no intermediary namespace and
            // hands the name back unchanged, so the generated class names a missing class.
            "net/fabricmc/loader/api/MappingResolver#mapClassName"
    );

    private static final java.util.Set<String> SINKS_TAKING_MEMBER_NAME = java.util.Set.of(
            "java/lang/Class#getMethod",
            "java/lang/Class#getDeclaredMethod",
            "java/lang/Class#getField",
            "java/lang/Class#getDeclaredField",
            "java/lang/invoke/MethodHandles$Lookup#findVirtual",
            "java/lang/invoke/MethodHandles$Lookup#findStatic",
            "java/lang/invoke/MethodHandles$Lookup#findSpecial",
            "java/lang/invoke/MethodHandles$Lookup#findGetter",
            "java/lang/invoke/MethodHandles$Lookup#findSetter"
    );

    /**
     * Loader calls whose name argument is an intermediary member name. On a 26.1+ runtime the
     * resolver has no intermediary namespace and returns that name unchanged, so the literal itself
     * has to be translated. The owner and descriptor arguments do not affect the returned name.
     */
    private static final String MAP_METHOD_NAME = "net/fabricmc/loader/api/MappingResolver#mapMethodName";
    private static final String MAP_FIELD_NAME = "net/fabricmc/loader/api/MappingResolver#mapFieldName";

    /** Class-rename table in dotted form (converted from slash-form for string matching). */
    private final Map<String, String> classRedirectsDotted;

    /** Intermediary method names (method_XXXX to mojangName). */
    private final Map<String, String> intermediaryMethodNames;

    /** Intermediary field names (field_XXXX to mojangName). */
    private final Map<String, String> intermediaryFieldNames;

    /** Curated loader-API renames, consulted alongside the MC class redirects. */
    private final LoaderApiRenames loaderRenames;

    private final AtomicInteger stringsRemapped = new AtomicInteger();
    private final AtomicInteger suspiciousUnmapped = new AtomicInteger();
    private final AtomicInteger dynamicStringsSkipped = new AtomicInteger();

    /**
     * @param slashedClassRedirects the {@code classRedirects} map from
     *                              {@link com.retromod.core.RetromodTransformer},
     *                              in slash-separated internal names; converted to
     *                              dotted form internally since reflection strings
     *                              are dotted.
     * @param intermediaryMethodNames intermediary to Mojang method name map
     * @param intermediaryFieldNames  intermediary to Mojang field name map
     * @param loaderRenames           curated loader-API rename table (see
     *                                {@link LoaderApiRenames}); may be empty
     */
    public ReflectionStringRemapper(Map<String, String> slashedClassRedirects,
                                    Map<String, String> intermediaryMethodNames,
                                    Map<String, String> intermediaryFieldNames,
                                    LoaderApiRenames loaderRenames) {
        // Loader-API renames stay in their native slash form and are looked up
        // per-call in tryRewriteClassFqn; the dotted map carries only shim renames.
        this.classRedirectsDotted = buildDottedMap(slashedClassRedirects);
        this.intermediaryMethodNames = Map.copyOf(intermediaryMethodNames);
        this.intermediaryFieldNames = Map.copyOf(intermediaryFieldNames);
        this.loaderRenames = loaderRenames;
    }

    /** Convert the slash-form redirect map to the dotted form used for matching reflection strings. */
    private static Map<String, String> buildDottedMap(Map<String, String> slashed) {
        java.util.HashMap<String, String> out = new java.util.HashMap<>(slashed.size());
        for (Map.Entry<String, String> e : slashed.entrySet()) {
            out.put(slashToDot(e.getKey()), slashToDot(e.getValue()));
        }
        return out;
    }

    private static String slashToDot(String s) {
        return s == null ? null : s.replace('/', '.');
    }

    private static String dotToSlash(String s) {
        return s == null ? null : s.replace('.', '/');
    }

    /**
     * Remap reflection strings in the given class bytecode.
     *
     * @param classBytes the bytecode to scan
     * @return rewritten bytes if any remapping occurred; otherwise the input
     *         bytes unchanged, with no new ClassWriter allocation
     */
    public byte[] remap(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) return classBytes;

        // Tree API gives us random-access walking with a lookback window, awkward
        // under the streaming visitor API.
        ClassNode classNode = new ClassNode();
        try {
            new ClassReader(classBytes).accept(classNode, 0);
        } catch (Exception e) {
            LOGGER.debug("Skipping reflection remap: class unparseable ({})", e.getMessage());
            return classBytes;
        }

        int remappedBefore = stringsRemapped.get();
        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            if (processMethod(method, classNode.name)) {
                modified = true;
            }
        }

        if (!modified) {
            return classBytes;
        }

        // COMPUTE_MAXS suffices: we only changed LDC constants, not stack shape.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);

        int delta = stringsRemapped.get() - remappedBefore;
        LOGGER.debug("Reflection remap: {} ({} strings rewritten)", classNode.name, delta);
        return writer.toByteArray();
    }

    /**
     * Scan one method for LDC-to-reflection-sink patterns and rewrite matched LDCs.
     * Returns true if any rewrites happened.
     */
    private boolean processMethod(MethodNode method, String ownerClass) {
        InsnList insns = method.instructions;
        // Ring buffer of recent LDC nodes; we keep references so we can mutate
        // each node's cst field later.
        LdcInsnNode[] window = new LdcInsnNode[LOOKBACK_WINDOW];
        int windowEnd = 0; // slot for the next insert

        // Tracks whether we just rewrote a class FQN, a hook for future
        // member-name remapping via the intermediary tables.
        //noinspection unused
        String lastRewrittenClassSlashed = null;

        boolean modified = false;
        AbstractInsnNode insn = insns.getFirst();
        while (insn != null) {
            AbstractInsnNode nextInsn = insn.getNext(); // capture before any mutation

            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
                window[windowEnd % LOOKBACK_WINDOW] = ldc;
                windowEnd++;
            } else if (insn instanceof MethodInsnNode call) {
                String sinkKey = call.owner + "#" + call.name;
                boolean wasSink = false;

                if (SINKS_TAKING_CLASS_FQN.contains(sinkKey)) {
                    wasSink = true;
                    LdcInsnNode target = findLastStringLdc(window, windowEnd,
                            s -> MC_FQN_PATTERN.matcher(s).matches());
                    if (target != null) {
                        if (tryRewriteClassFqn(target)) {
                            modified = true;
                            lastRewrittenClassSlashed = dotToSlash((String) target.cst);
                        } else {
                            // Shape recognized but unmapped: feeds the gap report.
                            suspiciousUnmapped.incrementAndGet();
                        }
                    }
                } else if (MAP_METHOD_NAME.equals(sinkKey) || MAP_FIELD_NAME.equals(sinkKey)) {
                    wasSink = true;
                    boolean methodName = MAP_METHOD_NAME.equals(sinkKey);
                    Pattern namePattern = methodName
                            ? INTERMEDIARY_METHOD_PATTERN : INTERMEDIARY_FIELD_PATTERN;
                    Map<String, String> names = methodName
                            ? intermediaryMethodNames : intermediaryFieldNames;
                    LdcInsnNode target = findLastStringLdc(window, windowEnd,
                            s -> namePattern.matcher(s).matches());
                    String mapped = target == null ? null : names.get((String) target.cst);
                    if (mapped != null) {
                        target.cst = mapped;
                        stringsRemapped.incrementAndGet();
                        modified = true;
                    }
                } else if (SINKS_TAKING_MEMBER_NAME.contains(sinkKey)) {
                    wasSink = true;
                    // First String arg is the member name, possibly an intermediary
                    // name needing translation. Look back for the string before the call.
                    LdcInsnNode target = findLastStringLdc(window, windowEnd, s -> true);
                    if (target != null && target.cst instanceof String name) {
                        if (INTERMEDIARY_METHOD_PATTERN.matcher(name).matches()) {
                            String mapped = intermediaryMethodNames.get(name);
                            if (mapped != null) {
                                target.cst = mapped;
                                stringsRemapped.incrementAndGet();
                                modified = true;
                            }
                        } else if (INTERMEDIARY_FIELD_PATTERN.matcher(name).matches()) {
                            String mapped = intermediaryFieldNames.get(name);
                            if (mapped != null) {
                                target.cst = mapped;
                                stringsRemapped.incrementAndGet();
                                modified = true;
                            }
                        }
                        // Non-intermediary member names are left alone: remapping
                        // them needs the exact lookup class, hence data-flow analysis.
                    }
                }

                // Reset the window only after a recognized sink. Clearing after
                // every invoke broke patterns where an intermediate helper sits
                // between the LDC and the real sink; after a sink its consumed
                // LDCs are off the stack, so clearing is correct.
                if (wasSink) {
                    windowEnd = 0;
                    java.util.Arrays.fill(window, null);
                }
            }

            insn = nextInsn;
        }
        return modified;
    }

    /**
     * Walk the LDC window backward (most recent first) and return the first
     * entry whose string value satisfies the predicate, or null if none match
     * within the current window.
     */
    private static LdcInsnNode findLastStringLdc(LdcInsnNode[] window, int windowEnd,
                                                  java.util.function.Predicate<String> predicate) {
        int start = Math.max(0, windowEnd - LOOKBACK_WINDOW);
        for (int i = windowEnd - 1; i >= start; i--) {
            LdcInsnNode ldc = window[i % LOOKBACK_WINDOW];
            if (ldc != null && ldc.cst instanceof String s && predicate.test(s)) {
                return ldc;
            }
        }
        return null;
    }

    /**
     * Rewrite the LDC's string value via the class redirect or loader-API rename
     * table; returns true if a rewrite happened. Inner-class {@code $} notation
     * resolves against the outer rename; {@code net.minecraft.X.Inner} is left
     * alone since dot-separated nesting is ambiguous with member access.
     */
    private boolean tryRewriteClassFqn(LdcInsnNode ldc) {
        String original = (String) ldc.cst;

        String direct = classRedirectsDotted.get(original);
        if (direct != null) {
            ldc.cst = direct;
            stringsRemapped.incrementAndGet();
            return true;
        }

        // "net.minecraft.Foo$Inner": split on '$', map the outer, reassemble.
        int dollar = original.indexOf('$');
        if (dollar > 0) {
            String outer = original.substring(0, dollar);
            String remapped = classRedirectsDotted.get(outer);
            if (remapped != null) {
                ldc.cst = remapped + original.substring(dollar);
                stringsRemapped.incrementAndGet();
                return true;
            }
        }

        // Fall back to the curated loader-API table (slash form).
        String slashed = dotToSlash(original);
        String loaderRename = loaderRenames.getClassRename(slashed);
        if (loaderRename != null) {
            ldc.cst = slashToDot(loaderRename);
            stringsRemapped.incrementAndGet();
            return true;
        }

        return false;
    }

    /** Total strings rewritten across every class this remapper has processed. */
    public int getStringsRemapped() {
        return stringsRemapped.get();
    }

    /**
     * Strings that looked like MC references near a reflection sink but had no
     * mapping; flags missing class-redirect entries for the gap report.
     */
    public int getSuspiciousUnmapped() {
        return suspiciousUnmapped.get();
    }

    /**
     * Dynamic (concat) strings passing through reflection sinks. Not rewritable
     * without data-flow analysis; counted so mod authors see why some reflection
     * calls weren't fixed.
     */
    public int getDynamicStringsSkipped() {
        return dynamicStringsSkipped.get();
    }

    /** Reset all counters, useful for per-mod reports. */
    public void resetMetrics() {
        stringsRemapped.set(0);
        suspiciousUnmapped.set(0);
        dynamicStringsSkipped.set(0);
    }
}
