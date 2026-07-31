/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core.bridge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Synthesizes bridge methods in mod classes that OVERRIDE a parent method whose
 * name was renamed in the target Minecraft version.
 *
 * <h3>The bug this fixes</h3>
 * <p>Suppose a mod class {@code class MyEntity extends net/minecraft/entity/Entity}
 * declares {@code public World getWorld()}, an override of
 * {@code Entity.getWorld()} on the old MC. Between that MC version and the target,
 * Minecraft renamed {@code Entity.getWorld()} to {@code Entity.getLevel()}. The
 * method-redirect pipeline rewrites every <b>invocation</b> of {@code getWorld}
 * into {@code getLevel}, but the mod's DECLARED method keeps its old name
 * {@code getWorld}.</p>
 *
 * <p>Now at runtime:</p>
 * <ol>
 *   <li>Code calls {@code someEntity.getLevel()} (the new name).</li>
 *   <li>JVM virtual dispatch looks for {@code getLevel} on {@code MyEntity}. Not
 *       found, the mod still has {@code getWorld}.</li>
 *   <li>Dispatch walks up to {@code Entity.getLevel()}, the MC parent
 *       implementation. The mod's override is bypassed entirely.</li>
 * </ol>
 *
 * <p>The mod's {@code getWorld} body becomes dead code. Subtle, silent bug.</p>
 *
 * <h3>The fix: synthesize {@code getLevel()} in {@code MyEntity}</h3>
 * <p>We add a bridge method named {@code getLevel} with the same descriptor
 * that delegates to {@code getWorld}. Virtual dispatch now finds
 * {@code MyEntity.getLevel()}, which calls {@code MyEntity.getWorld()}, so the
 * mod's original override runs as intended.</p>
 *
 * <pre>
 * // Added by this synthesizer:
 * public World getLevel() {
 *     return this.getWorld();
 * }
 * </pre>
 *
 * <p>The bridge is tagged {@code ACC_SYNTHETIC | ACC_BRIDGE} so debuggers and
 * reflection APIs that filter bridge methods (like {@code Method.isBridge()})
 * can distinguish it from mod-authored code.</p>
 *
 * <h3>When this triggers</h3>
 * <p>For each mod class, for each declared method, we look up
 * {@code (superclass, methodName, methodDescriptor)} in the method-redirect
 * table. If a redirect renames that method without changing the descriptor, we
 * synthesize a bridge. Also checks interface methods the class implements.</p>
 *
 * <h3>v1 scope</h3>
 * <ul>
 *   <li>Only descriptor-preserving renames. Cases where the descriptor itself
 *       changes require argument conversion (risk of VerifyError is higher), and
 *       {@link com.retromod.core.BridgeAdapterGenerator} already handles the
 *       best-known case (MC 26.1 input-event overhaul).</li>
 *   <li>Only public and protected methods. Private methods can't be overridden
 *       so the orphan scenario doesn't apply.</li>
 *   <li>Only methods declared on classes in {@code modOwnClasses}. We never
 *       modify MC or loader code.</li>
 *   <li>If a method with the bridge's name already exists on the class, we skip
 *       (avoid VerifyError from duplicate methods).</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Stateless apart from config; metric counters are atomic. Safe to share
 * across threads processing different classes concurrently.</p>
 */
public final class BridgeMethodSynthesizer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-BridgeSynth");

    /**
     * Function the synthesizer queries to find method renames. The function
     * receives a key built from {@code owner + "#" + name + " " + descriptor}
     * (matching {@link #renameKey}) and returns the new method name (or null).
     *
     * <p>We accept a Function rather than a concrete map so the synthesizer
     * doesn't need to depend on {@code RetromodTransformer}'s internal data
     * structures. The transformer can wrap its {@code methodRedirects} map
     * with a lambda.</p>
     */
    private final Function<String, String> methodRenameLookup;

    /** Counter for bridges successfully synthesized. */
    private final AtomicInteger bridgesSynthesized = new AtomicInteger();

    /** Counter for classes we touched (at least one bridge added). */
    private final AtomicInteger classesModified = new AtomicInteger();

    /** Counter for bridges skipped due to name collision (both versions already exist). */
    private final AtomicInteger bridgesSkippedCollision = new AtomicInteger();

    /**
     * @param methodRenameLookup function mapping {@code "owner#name desc"} keys
     *                           to new method names. Typically wraps the
     *                           transformer's method-redirect table.
     */
    public BridgeMethodSynthesizer(Function<String, String> methodRenameLookup) {
        if (methodRenameLookup == null) {
            throw new IllegalArgumentException("methodRenameLookup must not be null");
        }
        this.methodRenameLookup = methodRenameLookup;
    }

    /**
     * Canonical key format used to look up method renames. Matches the format
     * expected by the lookup function.
     */
    public static String renameKey(String owner, String name, String descriptor) {
        return owner + "#" + name + " " + descriptor;
    }

    // Entry point

    /**
     * Scan the class for override-orphan candidates and synthesize bridges for
     * each one found. Returns the rewritten bytecode if any bridge was added;
     * otherwise returns the input bytes unchanged (cheap short-circuit).
     *
     * @param classBytes     the transformed class bytecode (typically post-iterative-loop)
     * @param modOwnClasses  classes defined by the mod itself. If the class
     *                       being processed isn't in this set, we return the
     *                       input unchanged. We never modify MC or loader
     *                       classes.
     * @return possibly-rewritten bytecode
     */
    public byte[] synthesize(byte[] classBytes, Set<String> modOwnClasses) {
        if (classBytes == null || classBytes.length == 0) return classBytes;
        if (modOwnClasses == null) modOwnClasses = Collections.emptySet();

        // First pass: scan the class to determine (a) whether it's mod-defined,
        // and (b) which declared methods need bridges.
        ScanResult scan;
        try {
            scan = scan(classBytes, modOwnClasses);
        } catch (Exception e) {
            LOGGER.debug("Scan failed for bridge synthesis: {}", e.getMessage());
            return classBytes;
        }

        if (!scan.isModClass || scan.candidates.isEmpty()) {
            return classBytes;
        }

        // Filter out candidates whose bridge name collides with an existing
        // declared method. Emitting a second method with the same name+desc
        // is a hard VerifyError.
        List<BridgeCandidate> toEmit = new ArrayList<>(scan.candidates.size());
        for (BridgeCandidate candidate : scan.candidates) {
            String collisionKey = candidate.bridgeName() + "#" + candidate.methodDescriptor();
            if (scan.declaredMethods.contains(collisionKey)) {
                bridgesSkippedCollision.incrementAndGet();
                LOGGER.debug("Bridge {} skipped on {} - target name+descriptor already exists",
                        candidate.bridgeName(), scan.className);
                continue;
            }
            toEmit.add(candidate);
        }

        if (toEmit.isEmpty()) return classBytes;

        // Second pass: emit bridges via a ClassVisitor that copies every input
        // instruction verbatim, then appends the bridges in visitEnd().
        byte[] out;
        try {
            out = emit(classBytes, scan.className, toEmit);
        } catch (Exception e) {
            // Emission failed. Log and return the INPUT unchanged. A partially-
            // emitted class could be worse than no bridge at all.
            LOGGER.warn("Bridge emission failed for {}: {}", scan.className, e.getMessage());
            return classBytes;
        }

        bridgesSynthesized.addAndGet(toEmit.size());
        classesModified.incrementAndGet();
        LOGGER.debug("Synthesized {} bridge method(s) on {}", toEmit.size(), scan.className);
        return out;
    }

    // Find the bridges this class needs.

    /**
     * Immutable scan result. The emitter walks the same bytes a second time
     * rather than mutating state from the scan; two-pass design keeps the
     * visitor code simple.
     */
    private static final class ScanResult {
        final String className;
        final boolean isModClass;
        final List<BridgeCandidate> candidates;
        final Set<String> declaredMethods; // "name#descriptor" set for collision avoidance

        ScanResult(String className, boolean isModClass,
                   List<BridgeCandidate> candidates, Set<String> declaredMethods) {
            this.className = className;
            this.isModClass = isModClass;
            this.candidates = candidates;
            this.declaredMethods = declaredMethods;
        }
    }

    private ScanResult scan(byte[] classBytes, Set<String> modOwnClasses) {
        ClassReader reader = new ClassReader(classBytes);
        String[] ownNameHolder = new String[1];
        String[] superHolder = new String[1];
        List<String> interfacesHolder = new ArrayList<>();
        List<BridgeCandidate> candidates = new ArrayList<>();
        Set<String> declaredMethods = new HashSet<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                ownNameHolder[0] = name;
                superHolder[0] = superName;
                if (interfaces != null) Collections.addAll(interfacesHolder, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                              String signature, String[] exceptions) {
                declaredMethods.add(methodName + "#" + descriptor);

                // Only public/protected methods can be overrides that need bridging.
                // Private/package-private/static don't participate in virtual dispatch
                // (well, package-private does, but an orphan override in package-private
                // scope is rare enough to skip in v1).
                int visibility = access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
                if (visibility == 0) return null;
                // Static methods are resolved statically, not via virtual dispatch -
                // no orphan-override scenario.
                if ((access & Opcodes.ACC_STATIC) != 0) return null;
                // Abstract methods have no body to delegate to.
                if ((access & Opcodes.ACC_ABSTRACT) != 0) return null;

                // Look up rename keys in the supers. We check the direct super first
                // (most common override target), then each declared interface.
                String rename = lookupRename(superHolder[0], methodName, descriptor);
                if (rename == null) {
                    for (String iface : interfacesHolder) {
                        rename = lookupRename(iface, methodName, descriptor);
                        if (rename != null) break;
                    }
                }
                if (rename == null || rename.equals(methodName)) return null;

                candidates.add(new BridgeCandidate(access, methodName, descriptor, rename));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String className = ownNameHolder[0];
        boolean isModClass = className != null && modOwnClasses.contains(className);
        return new ScanResult(className, isModClass, candidates, declaredMethods);
    }

    /**
     * Query the rename lookup with a fully-qualified key. Returns null if no
     * rename applies.
     */
    private String lookupRename(String owner, String name, String descriptor) {
        if (owner == null) return null;
        return methodRenameLookup.apply(renameKey(owner, name, descriptor));
    }

    // Write the bridges into the class.

    private byte[] emit(byte[] classBytes, String ownerClass, List<BridgeCandidate> candidates) {
        ClassReader reader = new ClassReader(classBytes);
        // COMPUTE_FRAMES regenerates stack map frames, required because we
        // append new methods whose bodies need frame computation.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                // Every original method has been forwarded to the writer at this
                // point. Append our synthesized bridges BEFORE calling super.visitEnd()
                // so they land in the class.
                for (BridgeCandidate candidate : candidates) {
                    emitBridge(ownerClass, candidate, this);
                }
                super.visitEnd();
            }
        }, ClassReader.EXPAND_FRAMES);

        return writer.toByteArray();
    }

    /**
     * Emit one bridge method. The body is a straight delegate:
     * <pre>
     *   ALOAD 0                // load 'this'
     *   ALOAD/ILOAD/etc. 1..N  // load args in order per descriptor
     *   INVOKEVIRTUAL owner.originalName originalDesc
     *   X-RETURN               // return type from descriptor
     * </pre>
     */
    private void emitBridge(String ownerClass, BridgeCandidate candidate, ClassVisitor cv) {
        int access = candidate.access() | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;

        MethodVisitor mv = cv.visitMethod(access, candidate.bridgeName(),
                candidate.methodDescriptor(), null, null);
        mv.visitCode();

        // Load 'this' and all arguments onto the stack.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        Type methodType = Type.getMethodType(candidate.methodDescriptor());
        int local = 1;
        for (Type arg : methodType.getArgumentTypes()) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), local);
            local += arg.getSize();
        }

        // Invoke the original method (same owner, same descriptor, original name).
        // INVOKEVIRTUAL even for interface methods: the bridge lives on a class,
        // not an interface, so virtual dispatch is correct.
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ownerClass,
                candidate.methodName(), candidate.methodDescriptor(), false);

        // Return using the opcode matching the declared return type.
        mv.visitInsn(methodType.getReturnType().getOpcode(Opcodes.IRETURN));

        // COMPUTE_FRAMES/COMPUTE_MAXS handles the actual numbers; we pass (0,0).
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // Metrics

    public int getBridgesSynthesized() { return bridgesSynthesized.get(); }
    public int getClassesModified() { return classesModified.get(); }
    public int getBridgesSkippedCollision() { return bridgesSkippedCollision.get(); }

    public void resetMetrics() {
        bridgesSynthesized.set(0);
        classesModified.set(0);
        bridgesSkippedCollision.set(0);
    }

    // Transformer integration

    /**
     * Build a rename-lookup function from a method-redirect table using the
     * format the transformer already uses. The function signature accepts any
     * map keyed on an opaque type (the transformer's {@code MethodKey}) with
     * a value having a {@code .name()} accessor. We don't depend on those
     * types directly to keep this module free of transformer-specific imports.
     *
     * @param keyToString  function that turns the map's key type into a
     *                     {@code "owner#name desc"} string (matches {@link #renameKey})
     * @param valueToName  function that extracts the new name from the map's
     *                     value type
     */
    public static <K, V> Function<String, String> buildLookupFrom(
            Map<K, V> redirectMap,
            Function<K, String> keyToString,
            Function<V, String> valueToName) {

        // Precompute a String-keyed view of the redirect map. The transformer's
        // redirect maps are populated once at startup and immutable thereafter,
        // so a snapshot is safe. We also filter for identity-preserving
        // renames (same owner + descriptor, different name), the only case
        // we can safely bridge for v1.
        Map<String, String> snapshot = new HashMap<>(redirectMap.size());
        for (Map.Entry<K, V> entry : redirectMap.entrySet()) {
            String key = keyToString.apply(entry.getKey());
            String newName = valueToName.apply(entry.getValue());
            if (key != null && newName != null) {
                snapshot.put(key, newName);
            }
        }
        return snapshot::get;
    }
}
