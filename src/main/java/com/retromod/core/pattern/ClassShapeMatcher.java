/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.pattern;

import com.retromod.core.parallel.RetromodExecutors;
import com.retromod.core.pattern.patterns.ApiUsageFingerprintPattern;
import com.retromod.core.pattern.patterns.BlockEntityLikePattern;
import com.retromod.core.pattern.patterns.DeferredRegisterHolderPattern;
import com.retromod.core.pattern.patterns.ForgeEventListenerPattern;
import com.retromod.core.pattern.patterns.MixinTargetPattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs all registered {@link ClassPattern} implementations against mod
 * classes and collects matches into a flat list.
 *
 * <h3>Why one matcher for all patterns?</h3>
 * <p>Patterns are cheap (one ASM tree-walk per class). Running them together
 * means we pay the {@code ClassReader.accept()} cost once per class rather
 * than once per pattern, a clear win when there are many patterns.</p>
 *
 * <h3>Pattern ordering</h3>
 * <p>Patterns don't depend on each other: a class can match multiple, and
 * we report every match. The iteration order in {@link #defaultLibrary()}
 * is just for human readability in the gap report (most-confident patterns
 * first).</p>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are immutable after construction. The pattern list is a
 * defensive copy, so external mutation is not observable. Safe to share
 * across threads processing different classes concurrently.</p>
 */
public final class ClassShapeMatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-PatternMatcher");

    private final List<ClassPattern> patterns;

    public ClassShapeMatcher(List<ClassPattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /**
     * The default pattern library: every pattern Retromod ships out of the
     * box. Each is added in a deliberate order, most-confident first, so
     * the gap report's ordering is stable and obvious.
     */
    public static ClassShapeMatcher defaultLibrary() {
        return new ClassShapeMatcher(Arrays.asList(
                // Ordered most-confident-first so the gap report's top rows
                // are the clearest / hardest signals:
                new ForgeEventListenerPattern(),       // annotation match, 1.0
                new DeferredRegisterHolderPattern(),   // distinctive field type, 0.95
                new MixinTargetPattern(),              // @Mixin annotation, 1.0
                new BlockEntityLikePattern(),          // structural heuristics, 0.75-0.9
                new ApiUsageFingerprintPattern()       // deep method-body scan, 0.6
        ));
    }

    /**
     * Run every pattern against the given class. Returns every match found;
     * empty if no pattern matched.
     *
     * @param classBytes bytecode of the mod class (typically post-transform)
     * @param ctx        read-only matcher context
     * @return list of {@link PatternMatch}, possibly empty, never null
     */
    public List<PatternMatch> matchAll(byte[] classBytes, MatchContext ctx) {
        if (classBytes == null || classBytes.length == 0) return Collections.emptyList();

        ClassNode cls = new ClassNode();
        try {
            // NOTE: we intentionally do not pass SKIP_CODE, because the
            // ApiUsageFingerprintPattern (and any future pattern that
            // inspects method bodies) needs the instruction stream.
            // SKIP_DEBUG + SKIP_FRAMES are still set because patterns don't
            // care about line numbers or stack maps, just structural shape.
            new ClassReader(classBytes).accept(cls, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            LOGGER.debug("Skipping pattern match: class unparseable ({})", e.getMessage());
            return Collections.emptyList();
        }

        List<PatternMatch> matches = new ArrayList<>();
        for (ClassPattern pattern : patterns) {
            try {
                PatternMatch match = pattern.match(cls, ctx);
                if (match != null) matches.add(match);
            } catch (Exception e) {
                // A buggy pattern should never break the verifier loop. Log
                // and move on. Patterns are designed to be read-only, so a
                // failure here can't corrupt state.
                LOGGER.warn("Pattern {} threw for class {}: {}",
                        pattern.name(), cls.name, e.getMessage());
            }
        }
        return matches;
    }

    /**
     * Convenience method to scan a batch of classes and aggregate every match. Used
     * by the CLI's gap report to build a per-mod rollup.
     *
     * <h3>Parallelization</h3>
     * <p>When {@link RetromodExecutors#isParallel()} is true, each class is
     * scanned on a worker thread concurrently. Pattern matchers are read-only
     * and stateless, so sharing them across threads is safe. Results
     * accumulate into a thread-safe queue and are copied to a list at the end.</p>
     *
     * <p>Output order is non-deterministic in parallel mode, so mod authors
     * who diff reports across runs should sort by {@code PatternMatch.className()}
     * before comparing. The {@link com.retromod.core.verify.VerificationReport}
     * does this automatically in {@code writeTo}.</p>
     *
     * @param classBytesByName map from internal-name to bytecode
     * @param ctx              shared matcher context
     * @return flat list of every match across every class
     */
    public List<PatternMatch> matchAllClasses(java.util.Map<String, byte[]> classBytesByName,
                                               MatchContext ctx) {
        if (classBytesByName == null || classBytesByName.isEmpty()) {
            return Collections.emptyList();
        }

        // Serial fast path: avoid pool overhead for small batches or when
        // parallelism was explicitly disabled.
        if (!RetromodExecutors.isParallel() || classBytesByName.size() < 8) {
            List<PatternMatch> all = new ArrayList<>();
            for (byte[] bytes : classBytesByName.values()) {
                all.addAll(matchAll(bytes, ctx));
            }
            return all;
        }

        // Parallel path: one task per class, collecting into a lock-free queue.
        ConcurrentLinkedQueue<PatternMatch> collector = new ConcurrentLinkedQueue<>();
        RetromodExecutors.parallelForEach(classBytesByName.values(), bytes -> {
            List<PatternMatch> perClass = matchAll(bytes, ctx);
            if (!perClass.isEmpty()) collector.addAll(perClass);
        });
        return new ArrayList<>(collector);
    }
}
