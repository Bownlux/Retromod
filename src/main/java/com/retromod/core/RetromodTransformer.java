/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Core bytecode transformer: intercepts class loading and rewrites references
 * to keep mods compatible across Minecraft versions.
 */
package com.retromod.core;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassFileTransformer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import com.retromod.util.ZipSecurity;

/**
 * Core ASM bytecode transformer that rewrites class/method/field references at load time.
 *
 * <p>When old mods reference Minecraft classes, methods, or fields that were renamed, moved,
 * or removed in newer versions, this rewrites the bytecode to point at the modern targets:
 * method renames/removals, class relocations, signature changes, constructor-to-factory,
 * field accessor wrapping, and intermediary ({@code method_XXXX}) to Mojang remapping.
 *
 * <p>Visitor chain: {@code ClassReader -> ClassRemapper -> RetromodClassVisitor -> ClassWriter}.
 * ClassRemapper runs first (bulk class renames + intermediary to Mojang) so RetromodClassVisitor
 * sees Mojang names by the time it does owner+name+descriptor method-redirect matching.
 *
 * <p>Redirect maps are {@link ConcurrentHashMap}: shims register from ServiceLoader threads
 * while the transformer is already processing classes.
 *
 * <p>Must not reference {@code Retromod} directly (it implements ModInitializer); the transformer
 * also runs in the standalone CLI where Fabric classes aren't on the classpath.
 */
public class RetromodTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Transformer");
    private static final RetromodTransformer INSTANCE = new RetromodTransformer();

    // Redirect maps below are populated by version shims and polyfill providers and read
    // during transformation. ConcurrentHashMap because shims register while classes load.

    // oldOwner.oldName(oldDesc) -> newOwner.newName(newDesc). Checked manually in
    // visitMethodInsn since matching needs owner+name+descriptor, not just name.
    private final Map<MethodKey, MethodTarget> methodRedirects = new ConcurrentHashMap<>(256);

    // Calls to a method deleted on the host: discard the args (and receiver), push a default
    // return, so the mod loads instead of hitting NoSuchMethodError. Motivating case: the
    // RenderSystem state setters (enableBlend/blendFunc/depthMask) removed in the 26.x
    // GpuDevice/RenderPipeline refactor have no redirect target. Exact owner+name+desc match,
    // so a live overload is untouched; owners tracked separately for an O(1) skip.
    private final Set<MethodKey> neutralizedMethods = ConcurrentHashMap.newKeySet();
    private final Set<String> neutralizedMethodOwners = ConcurrentHashMap.newKeySet();

    // Like methodRedirects, but the new method dropped one or more TRAILING parameters the call
    // still passes. On match the extra trailing values are popped before the (renamed) call, so a
    // method that was renamed AND lost a tail arg still links. Chunk force-loading needs this:
    // ServerChunkCache.addRegionTicket(TicketType,ChunkPos,int,T) -> addTicketWithRadius(TicketType,
    // ChunkPos,int) on 26.1 (the value arg was dropped). Keyed/owner-tracked like methodRedirects.
    private final Map<MethodKey, MethodTarget> argDropRedirects = new ConcurrentHashMap<>();
    private final Set<String> argDropOwners = ConcurrentHashMap.newKeySet();

    // Converting redirects: retarget a call AND adapt the last arg and/or the return value with a
    // single primitive conversion or a POP. Covers corpus-mined 26.x descriptor adaptations where
    // the target method still exists but a primitive widened or the return type changed:
    // Mth.cos(F)F -> cos(D)F (F2D the arg), Window.getGuiScale()D -> ()I (I2D the result),
    // SoundManager.play(SoundInstance)V -> ()PlayResult (POP the now-returned result). Same opcode.
    private final Map<MethodKey, ConvertingTarget> convertingRedirects = new ConcurrentHashMap<>();
    private final Set<String> convertingOwners = ConcurrentHashMap.newKeySet();

    // Singleton-static redirects: a NO-ARG static helper that became an INSTANCE method on a
    // singleton, e.g. Screen.hasControlDown() -> Minecraft.getInstance().hasControlDown(). The call
    // is re-expressed as [INVOKESTATIC getter][INVOKEVIRTUAL name]; no-arg only (else the pushed
    // receiver would land under the args).
    private final Map<MethodKey, SingletonTarget> singletonRedirects = new ConcurrentHashMap<>();
    private final Set<String> singletonOwners = ConcurrentHashMap.newKeySet();

    // Indy-typed call rewrites: a call immediately preceded by a Consumer LambdaMetafactory indy
    // is retargeted with the lambda's reified event type appended as a Class constant
    // (EventBus 7 addListener bridging; see registerIndyTypedCallRewrite).
    /**
     * Set when the CURRENT thread's pass emitted a rewrite that adds, removes, or reorders
     * instructions (ctor->factory, super-ctor replace, arg-drop pops, neutralize, appended
     * CHECKCASTs, indy-typed LDC). COMPUTE_FRAMES recomputes frames and doesn't care. The
     * COMPUTE_MAXS fallback preserves the ORIGINAL StackMapTable, which such rewrites
     * invalidate; it consults this flag and refuses to ship a class that would die
     * "StackMapTable format error" at load (hit live on Collective's DuskConfig).
     */
    private final ThreadLocal<Boolean> frameInvalidatingRewrite =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    void markFrameInvalidatingRewrite() {
        frameInvalidatingRewrite.set(Boolean.TRUE);
    }

    private final Map<MethodKey, MethodTarget> indyTypedRewrites = new ConcurrentHashMap<>();
    private final Set<String> indyTypedOwners = ConcurrentHashMap.newKeySet();

    // oldClassName -> newClassName (JVM internal names). Fed into ClassRemapper, which
    // rewrites class refs everywhere (descriptors, signatures, annotations).
    private final Map<String, String> classRedirects = new ConcurrentHashMap<>(64);

    // oldOwner.oldName -> newOwner.newName. When newDesc starts with "(" this is a
    // field-to-method redirect (the field was removed and replaced with a method).
    private final Map<FieldKey, FieldTarget> fieldRedirects = new ConcurrentHashMap<>(64);

    // method_XXXX/field_XXXX -> Mojang names. 26.1 removed obfuscation, so Fabric mods
    // using intermediary names must be remapped. Applied via the ClassRemapper overrides.
    private final Map<String, String> intermediaryMethodNames = new ConcurrentHashMap<>(40000);
    private final Map<String, String> intermediaryFieldNames = new ConcurrentHashMap<>(40000);

    // Descriptor-qualified fallback for AMBIGUOUS intermediary short-names, keyed by
    // "name|descriptor". A short-name is ambiguous when the offline harvest couldn't make it
    // globally unique: the flat maps above are then last-writer-wins, a silent wrong rename
    // (NoSuchMethodError/VerifyError at runtime). Names in the ambiguous set resolve via
    // name+descriptor here; unambiguous names stay on the fast name-only path unchanged.
    // Adapted from Sinytra Connector (MIT): IntermediateMapping.extendedMappings + getMappingKey.
    private final Map<String, String> intermediaryMethodNamesByDesc = new ConcurrentHashMap<>();
    private final Map<String, String> intermediaryFieldNamesByDesc = new ConcurrentHashMap<>();
    private final Set<String> ambiguousIntermediaryMethodNames = ConcurrentHashMap.newKeySet();
    private final Set<String> ambiguousIntermediaryFieldNames = ConcurrentHashMap.newKeySet();

    // m_NNNNNN_ / f_NNNNN_ -> Mojang names. Forge mods reobf'd by ForgeGradle ship SRG
    // names (Blocks.f_50069_); Forge 64.x dropped its SRG->Mojang runtime remap on 26.1+,
    // so without this they hit NoSuchFieldError/NoSuchMethodError. SRG names are globally
    // unique, so a flat name -> Mojang map suffices (same shape as the intermediary maps).
    private final Map<String, String> srgMethodNames = new ConcurrentHashMap<>(8000);
    private final Map<String, String> srgFieldNames = new ConcurrentHashMap<>(8000);

    // Mojang->Mojang method renames on a kept class, keyed "owner#name" -> newName (e.g.
    // ResourceKey.location -> identifier on 26.x). Owner-scoped, not a global dictionary,
    // since the names are ordinary words. Applied via the ClassRemapper's mapMethodName so
    // it also rewrites method references (ResourceKey::location), which methodRedirects
    // can't reach. Only for 26.1+ targets, where the old name is gone.
    private final Map<String, String> mojangMethodRenames = new ConcurrentHashMap<>(16);

    // Class-to-interface migrations: when a class becomes an interface in newer MC,
    // mods extending it get their superclass changed to a bridge plus the interface added.
    private final Map<String, SuperclassRedirect> superclassRedirects = new ConcurrentHashMap<>(16);

    // Supplies classes from the jar currently being transformed. Those classes are not on the
    // transform classpath yet, but ASM still needs their hierarchy to compute valid frames.
    private volatile Function<String, byte[]> jarClassBytes;

    // A nested jar must temporarily replace the outer jar's hierarchy on the current transform
    // thread. A thread-local stack keeps recursive JiJ transforms isolated without disturbing a
    // parallel class pass that is using the outer provider on another worker.
    private final ThreadLocal<ArrayDeque<Function<String, byte[]>>> scopedJarClassBytes =
            ThreadLocal.withInitial(ArrayDeque::new);

    @FunctionalInterface
    public interface JarClassBytesScope extends AutoCloseable {
        @Override
        void close();
    }

    public void setJarClassBytesProvider(Function<String, byte[]> provider) {
        this.jarClassBytes = provider;
    }

    public void clearJarClassBytesProvider() {
        this.jarClassBytes = null;
    }

    /** Temporarily use a class hierarchy for the current thread, restoring it on close. */
    public JarClassBytesScope pushJarClassBytesProvider(Function<String, byte[]> provider) {
        Objects.requireNonNull(provider, "provider");
        ArrayDeque<Function<String, byte[]>> providers = scopedJarClassBytes.get();
        providers.push(provider);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            ArrayDeque<Function<String, byte[]>> current = scopedJarClassBytes.get();
            if (!current.isEmpty()) current.pop();
            if (current.isEmpty()) scopedJarClassBytes.remove();
        };
    }

    private Function<String, byte[]> currentJarClassBytesProvider() {
        ArrayDeque<Function<String, byte[]>> providers = scopedJarClassBytes.get();
        return providers.isEmpty() ? jarClassBytes : providers.peek();
    }

    /** Reads the class entries of an in-memory nested jar for frame hierarchy resolution. */
    public static Map<String, byte[]> readJarClassBytes(byte[] jarData) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        long total = 0;
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(jarData))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                byte[] bytes = ZipSecurity.safeReadAllBytes(jar);
                total += bytes.length;
                if (total > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                    throw new IOException("nested jar classes exceed max decompressed size");
                }
                String name = entry.getName().substring(0, entry.getName().length() - 6);
                classes.put(name, bytes);
            }
        }
        return classes;
    }

    /**
     * Best common superclass for a frame merge when the classpath cannot answer.
     *
     * <p>Shared with writers outside this class so they get the same answer it does. The order
     * matters: the mod's own jar is consulted first, because a merge of two of the mod's types has
     * a real answer sitting in that jar, and widening it to {@code Object} produces a class the
     * verifier rejects as soon as the value is used (#180). Only when nothing can be read does this
     * fall back to the naming-based guess that keeps exception merges working (#94).
     */
    public static String resolveCommonSuperClass(String type1, String type2) {
        Function<String, byte[]> provider = INSTANCE == null
                ? null : INSTANCE.currentJarClassBytesProvider();
        if (provider != null) {
            String shared = commonSuperViaBytes(type1, type2, provider);
            if (shared != null) {
                return shared;
            }
        }
        return commonSuperFallback(type1, type2);
    }

    // new Foo(args) -> Foo.factory(args), for constructors replaced by static factories
    // (e.g. new ResourceLocation(s) -> Identifier.parse(s) in 26.1).
    private final Map<ConstructorKey, FactoryTarget> constructorRedirects = new ConcurrentHashMap<>(16);

    // GETFIELD -> getter(), PUTFIELD -> setter(), for fields that became private.
    private final Map<FieldKey, FieldAccessorTarget> fieldAccessorRedirects = new ConcurrentHashMap<>(16);
    /** GETSTATIC of a removed static field -> a (collection-field + optional enum-arg + accessor-call + cast) sequence. */
    private final Map<FieldKey, StaticFieldAccessor> staticFieldAccessors = new ConcurrentHashMap<>(64);
    /** Instance field moved behind a public sibling field's accessors: GETFIELD owner.name ->
     *  GETFIELD owner.hopField + INVOKEVIRTUAL getter (26.2 Minecraft.screen -> mc.gui.screen()). */
    private final Map<FieldKey, FieldHopAccessor> fieldHopAccessors = new ConcurrentHashMap<>(8);
    /** Instance field whose state moved somewhere the receiver can't reach: GETFIELD/PUTFIELD become
     *  INVOKESTATIC bridge calls that consume the receiver as an ignored arg (26.2 Options.hideGui). */
    private final Map<FieldKey, FieldStaticBridge> fieldStaticBridges = new ConcurrentHashMap<>(8);

    // Super constructor descriptor changes: a parent ctor gained required params in newer
    // MC; pushes extra args before INVOKESPECIAL (e.g. Button gained CreateNarration).
    private final Map<ConstructorKey, SuperCtorRedirect> superCtorRedirects = new ConcurrentHashMap<>(8);

    // Owners of removed classes whose static-field reads (e.g. 1.12.2 Material.IRON) become a
    // pushed default instead of faulting on the missing class. See registerStaticFieldNuller.
    private final Set<String> staticFieldNullers = ConcurrentHashMap.newKeySet();

    // Only classes in these packages get transformed (mod code, not MC itself).
    private final Set<String> transformablePackages = ConcurrentHashMap.newKeySet();

    // Shim classes embedded into mod JARs during transformation.
    private final Set<String> embeddedShimClasses = ConcurrentHashMap.newKeySet();

    // ASM-generated classes for polyfills needing MC-typed members that can't be compiled
    // from Java source (MC isn't on the compile classpath).
    private final Map<String, byte[]> syntheticClasses = new ConcurrentHashMap<>();
    // Synthetic classes generated as INTERFACES; redirect emission consults this for the itf flag.
    private final Set<String> interfaceSyntheticTargets = ConcurrentHashMap.newKeySet();
    // Compiled embedded shims that are classes. Calls redirected from an old interface must not
    // retain INVOKEINTERFACE or an InterfaceMethodref when their replacement owner is a class.
    private final Set<String> classShimTargets = ConcurrentHashMap.newKeySet();

    // Indexes the target MC JAR and scores candidate methods/fields when no hardcoded
    // redirect is found. Never overrides a hardcoded redirect. Lazily initialized.
    private volatile FuzzyMethodResolver fuzzyResolver;

    // Deterministic naming-convention rules (e.g. render* -> extract* in 26.1) for refs not
    // in the redirect tables. Checked before the fuzzy resolver: faster and more reliable.
    private volatile PatternHeuristics patternHeuristics;

    // Cached ASM Remapper, rebuilt for every class otherwise. Invalidated when
    // classRedirects or the intermediary mappings change.
    private volatile Remapper cachedRemapper;
    private final AtomicInteger classRedirectsVersion = new AtomicInteger(0);

    /** Guards the one-time phantom-target sweep (see dropPhantomComRetromodTargets). */
    private final AtomicBoolean targetsValidated = new AtomicBoolean(false);

    // Owners that have method redirects. visitMethodInsn skips the methodRedirects lookup
    // when the call's owner isn't here.
    private final Set<String> methodRedirectOwners = ConcurrentHashMap.newKeySet();

    // Owner class names that have constructor redirects. visitTypeInsn does an O(1)
    // membership test against this instead of streaming constructorRedirects.keySet().
    private final Set<String> constructorRedirectOwners = ConcurrentHashMap.newKeySet();

    // A single pass visits each instruction once: if it rewrites X.foo() -> Y.bar() it has
    // already moved past that instruction and won't see Y.bar()'s own redirect to Z.baz().
    // So transformClass loops until the bytes stabilize. The cap bounds cyclic chains
    // (A -> B -> A). Configurable via -Dretromod.transform.maxIterations=N.
    private static final int MAX_TRANSFORM_ITERATIONS =
        Integer.parseInt(System.getProperty("retromod.transform.maxIterations", "5"));

    /** Total transform passes across all classes. A class needing 3 passes adds 3. */
    private final AtomicInteger totalPassesPerformed = new AtomicInteger();

    /** Classes that stabilized only after 2+ passes (i.e., chained redirects fired). */
    private final AtomicInteger classesNeedingMultiplePasses = new AtomicInteger();

    /** Classes that hit MAX_TRANSFORM_ITERATIONS, a possible redirect cycle. */
    private final AtomicInteger classesHittingIterationCap = new AtomicInteger();

    // After the iterative loop stabilizes, two optional post-steps run:
    //   1. ReflectionStringRemapper rewrites MC-typed string constants passed to reflection
    //      APIs (Class.forName, getDeclaredMethod) that the bytecode-level redirects can't see.
    //   2. ReferenceVerifier scans transformed bytecode for MC references absent from the
    //      target MC JAR, accumulating a per-mod VerificationReport (the "gap report").
    // Both share the McSymbolIndex (wrapping FuzzyMethodResolver) as their MC-version truth.

    private static final boolean REFLECTION_REMAP_ENABLED =
        Boolean.parseBoolean(System.getProperty("retromod.remapReflection", "true"));

    /** ON by default as of the parallel-compute release. Disable on low-memory machines
     *  (less than ~4GB RAM with a large mod collection) via
     *  {@code -Dretromod.verifyTransforms=false} for the best performance. */
    private static final boolean REFERENCE_VERIFY_ENABLED =
        Boolean.parseBoolean(System.getProperty("retromod.verifyTransforms", "true"));

    /** ON by default. Bridge synthesis is narrow-scope and low-risk; the worst case
     *  is a skipped bridge (not a broken class). Disable via
     *  {@code -Dretromod.synthesizeBridges=false} if debugging suggests it's the culprit. */
    private static final boolean BRIDGE_SYNTH_ENABLED =
        Boolean.parseBoolean(System.getProperty("retromod.synthesizeBridges", "true"));

    /** ON by default. The deep ApiUsageFingerprintPattern scans method bodies, which is
     *  the heaviest part of the pattern library. On a 2-core / 4GB-RAM machine with a
     *  very large mod collection (100+ mods) this can add several seconds per mod.
     *  Disable via {@code -Dretromod.matchPatterns=false} if startup time matters more
     *  than diagnostic coverage. */
    private static final boolean PATTERN_MATCH_ENABLED =
        Boolean.parseBoolean(System.getProperty("retromod.matchPatterns", "true"));

    /** Lazily-initialized reflection string remapper. Built once; refresh via {@link #invalidateReflectionRemapper()}. */
    private volatile com.retromod.core.verify.ReflectionStringRemapper reflectionRemapper;

    /** Lazily-initialized reference verifier. Built once the symbol index is available. */
    private volatile com.retromod.core.verify.ReferenceVerifier referenceVerifier;

    /** Counter for classes that went through reflection remapping, for diagnostics. */
    private final AtomicInteger reflectionRemapPassesPerformed = new AtomicInteger();

    /** Lazily-initialized bridge method synthesizer. */
    private volatile com.retromod.core.bridge.BridgeMethodSynthesizer bridgeSynthesizer;

    /** Lazily-initialized pattern matcher; uses the default library. */
    private volatile com.retromod.core.pattern.ClassShapeMatcher classShapeMatcher;

    /**
     * Target MC version, used only in diagnostic output (gap report headers).
     * Populated by {@link #setTargetMcVersion} from the main Retromod initializer.
     * "unknown" until set, never null, so downstream callers don't NPE.
     */
    private volatile String targetMcVersion = "unknown";

    private RetromodTransformer() {
        // Register default shim package as transformable
        transformablePackages.add("com/retromod/shim/");
        // Initialize pattern heuristics: deterministic rules checked before fuzzy matching
        this.patternHeuristics = new PatternHeuristics();
    }
    
    public static RetromodTransformer getInstance() {
        return INSTANCE;
    }

    /**
     * Index the target MC JAR for fuzzy-matching unresolved references. Fallback only:
     * hardcoded shim redirects always win. Call once at startup after shims load.
     *
     * @param mcJarPath path to the Minecraft client JAR, or null to auto-detect
     */
    public void initFuzzyResolver(java.nio.file.Path mcJarPath) {
        // Idempotent: indexing the MC JAR is expensive, and several startup
        // paths now want the index ready early (e.g. host-version-aware class
        // moves). Skip if we've already indexed.
        if (this.fuzzyResolver != null && this.fuzzyResolver.isIndexed()) {
            return;
        }
        try {
            // Auto-detect if no path provided
            if (mcJarPath == null) {
                mcJarPath = FuzzyMethodResolver.findMcJarFromClasspath();
            }
            if (mcJarPath == null) {
                LOGGER.info("MC JAR not found on classpath, fuzzy resolver disabled");
                return;
            }

            FuzzyMethodResolver resolver = new FuzzyMethodResolver();
            resolver.indexJar(mcJarPath);
            if (resolver.isIndexed()) {
                this.fuzzyResolver = resolver;
                LOGGER.info("Fuzzy resolver initialized: {} classes, {} methods, {} fields",
                        resolver.getIndexedClassCount(),
                        resolver.getIndexedMethodCount(),
                        resolver.getIndexedFieldCount());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not initialize fuzzy resolver: {}", e.getMessage());
        }
    }

    /**
     * Get the fuzzy resolver instance, or null if not initialized.
     */
    public FuzzyMethodResolver getFuzzyResolver() {
        return fuzzyResolver;
    }

    /**
     * Pattern heuristics engine, or null if not initialized. Checked before the fuzzy
     * resolver: faster and deterministic.
     */
    public PatternHeuristics getPatternHeuristics() {
        return patternHeuristics;
    }

    /**
     * Register a method redirect: oldOwner.oldName(oldDesc) -> newOwner.newName(newDesc).
     */
    public void registerMethodRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (oldOwner.equals(newOwner) && oldName.equals(newName) && oldDesc.equals(newDesc)) {
            LOGGER.debug("Ignored identity method-redirect registration for: {}.{}{}",
                    oldOwner, oldName, oldDesc);
            return;
        }
        MethodKey key = new MethodKey(oldOwner, oldName, oldDesc);
        MethodTarget target = new MethodTarget(newOwner, newName, newDesc);
        methodRedirects.put(key, target);
        methodRedirectOwners.add(oldOwner); // track owners for the fast-path skip
        registerRemappedMethodAlias(oldOwner, oldName, oldDesc, target);
        
        LOGGER.debug("Registered method redirect: {}.{}{} -> {}.{}{}",
                oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
    }
    
    /**
     * Register a method redirect that converts instance method to static method.
     * The receiver object becomes the first argument in the static method's descriptor.
     *
     * @param devirtualize if true, changes INVOKEVIRTUAL/INVOKEINTERFACE → INVOKESTATIC
     */
    public void registerMethodRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc,
            boolean devirtualize) {

        if (!devirtualize && oldOwner.equals(newOwner) && oldName.equals(newName)
                && oldDesc.equals(newDesc)) {
            LOGGER.debug("Ignored identity method-redirect registration for: {}.{}{}",
                    oldOwner, oldName, oldDesc);
            return;
        }

        MethodKey key = new MethodKey(oldOwner, oldName, oldDesc);
        MethodTarget target = new MethodTarget(newOwner, newName, newDesc, devirtualize);
        methodRedirects.put(key, target);
        methodRedirectOwners.add(oldOwner);
        registerRemappedMethodAlias(oldOwner, oldName, oldDesc, target);
    }

    /**
     * ClassRemapper runs before the instruction visitor, so a method paired with a class redirect
     * arrives under the replacement owner and descriptor. Keep an alias for that final call shape.
     */
    private void registerRemappedMethodAlias(String oldOwner, String oldName, String oldDesc,
            MethodTarget target) {
        String remappedOwner = classRedirects.getOrDefault(oldOwner, oldOwner);
        String remappedDesc = resolveDescriptor(oldDesc);
        if (remappedOwner.equals(oldOwner) && remappedDesc.equals(oldDesc)) {
            return;
        }
        methodRedirects.put(new MethodKey(remappedOwner, oldName, remappedDesc), target);
        methodRedirectOwners.add(remappedOwner);
    }

    /** Rebuild aliases when a later class redirect changes an already registered descriptor. */
    private void refreshRemappedMethodAliases(String redirectedClass) {
        List<Map.Entry<MethodKey, MethodTarget>> registrations =
                new ArrayList<>(methodRedirects.entrySet());
        for (Map.Entry<MethodKey, MethodTarget> registration : registrations) {
            MethodKey key = registration.getKey();
            if (!key.owner().equals(redirectedClass)
                    && !key.desc().contains("L" + redirectedClass + ";")) {
                continue;
            }
            registerRemappedMethodAlias(
                    key.owner(), key.name(), key.desc(), registration.getValue());
        }
    }

    /**
     * Register a method redirect whose new method DROPPED one or more trailing parameters the call
     * still passes. On match the extra trailing values are popped before the renamed call. The kept
     * leading args must match the new method's params; only contiguous trailing args may be dropped.
     * Use where a method was renamed AND lost a tail arg (e.g. 26.1
     * {@code ServerChunkCache.addRegionTicket(TicketType,ChunkPos,int,T)} ->
     * {@code addTicketWithRadius(TicketType,ChunkPos,int)}).
     */
    public void registerArgDropMethodRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        argDropRedirects.put(new MethodKey(oldOwner, oldName, oldDesc),
                new MethodTarget(newOwner, newName, newDesc));
        argDropOwners.add(oldOwner);
    }

    /**
     * Register a converting redirect: retarget {@code oldOwner.oldName(oldDesc)} to
     * {@code newOwner.newName(newDesc)} (same invoke opcode) and, if the descriptors differ only by
     * a primitive widening/narrowing on the LAST argument or the return, splice in one conversion
     * insn. {@code argConvOpcode} (e.g. {@link Opcodes#F2D}) is emitted BEFORE the call to convert
     * the top-of-stack last argument; {@code retAdaptOpcode} (a conversion like {@link Opcodes#I2D},
     * or {@link Opcodes#POP}/{@link Opcodes#POP2} to discard a result the old void call didn't have)
     * is emitted AFTER. Pass 0 for either to skip it.
     *
     * <p>Corpus-mined 26.x cases (top-50 Fabric+NeoForge 1.21.1 audit): {@code Mth.cos(F)F}/{@code
     * sin(F)F} widened to {@code (D)F} (arg F2D), {@code Window.getGuiScale()D} narrowed to {@code
     * ()I} (return I2D), {@code SoundManager.play(SoundInstance)V} now returns a {@code PlayResult}
     * (return POP). The last-arg rule is safe because a widened arg is the final value pushed before
     * the call; multi-arg conversions on non-top slots are not expressible here (register those a
     * different way).
     */
    public void registerConvertingRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc,
            int argConvOpcode, int retAdaptOpcode) {
        convertingRedirects.put(new MethodKey(oldOwner, oldName, oldDesc),
                new ConvertingTarget(newOwner, newName, newDesc, argConvOpcode, retAdaptOpcode));
        convertingOwners.add(oldOwner);
    }

    /**
     * Register a RETURN-UNWRAP redirect: a vanilla method that still exists but whose return type
     * became a WRAPPER. The call is retargeted to the same owner/name with {@code newDesc} (the
     * wrapper-returning descriptor), then {@code unwrapOwner.unwrapName(unwrapDesc)} is appended as an
     * {@code INVOKEVIRTUAL} to pull the original type back out, so the caller sees the type it
     * expected. Motivating 26.x case: {@code Util.backgroundExecutor()}/{@code ioPool()}/{@code
     * nonCriticalIoPool()} used to return {@code ExecutorService} and now return {@code
     * TracingExecutor} (a record wrapping it), unwrapped via {@code TracingExecutor.service()}. A
     * mod hits {@code NoSuchMethodError} on the {@code ExecutorService}-returning form at init. This
     * is a converting redirect whose return adaptation is a method CALL rather than a single insn.
     */
    public void registerReturnUnwrapRedirect(
            String owner, String name, String oldDesc, String newDesc,
            String unwrapOwner, String unwrapName, String unwrapDesc) {
        registerReturnUnwrapRedirect(owner, name, oldDesc, newDesc,
                unwrapOwner, unwrapName, unwrapDesc, false);
    }

    /**
     * {@link #registerReturnUnwrapRedirect(String, String, String, String, String, String, String)}
     * with control over the unwrap's invoke kind: pass {@code unwrapItf=true} when the wrapper type
     * is an INTERFACE (e.g. 26.x {@code ClientAsset$Texture.texturePath()}), so the appended call is
     * {@code INVOKEINTERFACE} (an INVOKEVIRTUAL on an interface is an IncompatibleClassChangeError).
     * The retargeted call may also RENAME the method ({@code newName}), e.g. {@code
     * PlayerSkin.texture()} -> {@code body()} + {@code texturePath()} unwrap.
     */
    public void registerReturnUnwrapRedirect(
            String owner, String name, String oldDesc, String newName, String newDesc,
            String unwrapOwner, String unwrapName, String unwrapDesc, boolean unwrapItf) {
        convertingRedirects.put(new MethodKey(owner, name, oldDesc),
                new ConvertingTarget(owner, newName, newDesc, 0, 0,
                        unwrapOwner, unwrapName, unwrapDesc, unwrapItf));
        convertingOwners.add(owner);
    }

    /** Same-name variant of the interface-aware unwrap registration. */
    public void registerReturnUnwrapRedirect(
            String owner, String name, String oldDesc, String newDesc,
            String unwrapOwner, String unwrapName, String unwrapDesc, boolean unwrapItf) {
        registerReturnUnwrapRedirect(owner, name, oldDesc, name, newDesc,
                unwrapOwner, unwrapName, unwrapDesc, unwrapItf);
    }

    /**
     * Register a singleton-static redirect: a NO-ARG {@code static} helper that moved to an INSTANCE
     * method on a singleton. {@code oldOwner.oldName(oldDesc)} (an {@code invokestatic} taking no
     * args) is re-emitted as {@code [invokestatic singletonOwner.getter:getterDesc]} then
     * {@code [invokevirtual singletonOwner.newName:newDesc]}, so the pushed singleton instance is
     * the receiver. e.g. {@code Screen.hasControlDown()Z} ->
     * {@code Minecraft.getInstance().hasControlDown()Z}. No-arg only.
     */
    public void registerSingletonStaticRedirect(
            String oldOwner, String oldName, String oldDesc,
            String singletonOwner, String getter, String getterDesc,
            String newName, String newDesc) {
        singletonRedirects.put(new MethodKey(oldOwner, oldName, oldDesc),
                new SingletonTarget(singletonOwner, getter, getterDesc, newName, newDesc));
        singletonOwners.add(oldOwner);
    }

    /**
     * Register an invokedynamic-typed call rewrite: when a call to {@code owner.name(descriptor)}
     * is IMMEDIATELY preceded by a {@code LambdaMetafactory} invokedynamic producing a
     * {@code java.util.function.Consumer}, the call is rewritten to {@code INVOKESTATIC
     * newOwner.newName(newDesc)} with the lambda's reified event type appended as a trailing
     * {@code Class} constant (from the indy's instantiatedMethodType, which javac always carries).
     *
     * <p>This recovers the listener event type that EventBus 6 read from the lambda's constant
     * pool and EventBus 7 cannot: {@code bus.addListener(this::onSetup)} compiles to
     * [captures..., INDY, INVOKEINTERFACE], so at transform time the type is right there. Calls
     * not immediately preceded by a Consumer indy (a stored variable, a field) fall through to
     * the normal pipeline (the bridge's default method does runtime generics recovery instead).
     */
    public void registerIndyTypedCallRewrite(
            String owner, String name, String desc,
            String newOwner, String newName, String newDesc) {
        indyTypedRewrites.put(new MethodKey(owner, name, desc),
                new MethodTarget(newOwner, newName, newDesc));
        indyTypedOwners.add(owner);
    }

    /**
     * Register a vanilla Mojang->Mojang method rename (same owner + descriptor, name
     * only), e.g. {@code ResourceKey.location -> identifier} on 26.x. Unlike
     * {@link #registerMethodRedirect}, this goes through the ClassRemapper's
     * {@code mapMethodName}, so it rewrites method REFERENCES (e.g.
     * {@code ResourceKey::location} captured in a codec/lambda) as well as direct calls -
     * a plain method redirect only sees direct {@code INVOKE*} sites. Owner-scoped, so it
     * won't touch an unrelated class's same-named method. Register only for targets where
     * the old name is gone (26.1+), since it renames unconditionally on that owner.
     */
    public void registerMethodRename(String owner, String name, String newName) {
        mojangMethodRenames.put(owner + "#" + name, newName);
        LOGGER.debug("Registered method rename: {}.{} -> {}", owner, name, newName);
    }

    /**
     * Register a removed method to be neutralized at every call site: the call is dropped
     * and replaced with stack-balanced pops (args + receiver) plus a default return value,
     * so a mod calling a method deleted on the host loads instead of hitting
     * {@code NoSuchMethodError}. Use only when there is no equivalent to redirect to; prefer
     * {@link #registerMethodRedirect} where a target exists.
     *
     * <p>The motivating case is the imperative {@code RenderSystem} state setters
     * ({@code enableBlend()}, {@code blendFunc(II)}, {@code depthMask(Z)}) removed in the
     * 26.x GpuDevice/RenderPipeline refactor: state moved onto immutable pipeline objects, so
     * the call has no modern form. The neutralized call is inert; the mod runs but that bit of
     * manual GL state is lost. Match is exact on {@code owner+name+desc}, so a still-present
     * overload sharing the owner is untouched. Gate registration by host version in the caller.
     *
     * <p>Void-return only. A non-void neutralize pushes a synthetic default whose type differs
     * from the original return; that works on the {@code COMPUTE_FRAMES} path (frames are
     * recomputed) but mismatches the preserved stack-map frames on the {@code COMPUTE_MAXS}
     * fallback ({@code VerifyError}), so a non-void registration is rejected with a warning.
     */
    public void registerRemovedMethodNeutralize(String owner, String name, String desc) {
        if (!desc.endsWith(")V")) {
            LOGGER.warn("Ignoring non-void removed-method neutralize for {}.{}{} - only void "
                    + "returns are supported (a non-void neutralize can VerifyError on the "
                    + "COMPUTE_MAXS fallback path). Use a redirect/polyfill instead.",
                    owner, name, desc);
            return;
        }
        neutralizedMethods.add(new MethodKey(owner, name, desc));
        neutralizedMethodOwners.add(owner);
        LOGGER.debug("Registered removed-method neutralize: {}.{}{}", owner, name, desc);
    }

    /**
     * Register a class redirect (for relocated/renamed classes).
     *
     * <p>Identity redirects ({@code A -> A}) are ignored: older shims used them as no-op
     * "part of our compat surface" markers, but {@code Map.put} let them overwrite legitimate
     * redirects from other shims (e.g. a {@code MatrixStack -> MatrixStack} marker clobbered
     * {@code MatrixStack -> PoseStack}, leaving pre-1.17 mods with a {@code NoClassDefFoundError}
     * at first render). The guard here no-ops them rather than deleting 40+ registration sites.
     */
    public void registerClassRedirect(String oldClass, String newClass) {
        if (oldClass.equals(newClass)) {
            LOGGER.debug("Ignored identity class-redirect registration for: {}", oldClass);
            return;
        }
        classRedirects.put(oldClass, newClass);
        if (newClass.startsWith("com/retromod/")) {
            recordCompiledOwnerKind(newClass);
        }
        refreshRemappedMethodAliases(oldClass);
        classRedirectsVersion.incrementAndGet(); // Invalidate cached remapper
        cachedRemapper = null;
        LOGGER.debug("Registered class redirect: {} -> {}", oldClass, newClass);
    }

    /**
     * Register a constructor-to-factory redirect.
     * Converts `new className(constructorDesc)` → `factoryClass.factoryMethod(factoryDesc)`.
     * The factory method must be static and return the class type.
     */
    public void registerConstructorRedirect(String className, String constructorDesc,
            String factoryClass, String factoryMethod, String factoryDesc) {
        registerConstructorRedirect(className, constructorDesc,
                factoryClass, factoryMethod, factoryDesc, false);
    }

    /**
     * Variant for factories declared on an INTERFACE (e.g. 1.19+'s {@code Text.translatable}:
     * {@code class_2561} is an interface). Static interface methods must link through an
     * InterfaceMethodref constant; INVOKESTATIC with a plain Methodref dies at link time with
     * {@code IncompatibleClassChangeError: ... must be InterfaceMethodref constant}.
     */
    public void registerConstructorRedirect(String className, String constructorDesc,
            String factoryClass, String factoryMethod, String factoryDesc,
            boolean factoryIsInterface) {
        constructorRedirects.put(
            new ConstructorKey(className, constructorDesc),
            new FactoryTarget(factoryClass, factoryMethod, factoryDesc, factoryIsInterface));
        constructorRedirectOwners.add(className);
        LOGGER.debug("Registered constructor redirect: new {}({}) -> {}.{}{}",
            className, constructorDesc, factoryClass, factoryMethod, factoryDesc);
    }

    /**
     * Register a super constructor descriptor change.
     * When a subclass calls super(oldDesc), the call is redirected to super(newDesc)
     * with an extra static field value pushed onto the stack.
     *
     * Example: Button(int,int,int,int,Component,OnPress) removed in 26.1
     *          → Button(int,int,int,int,Component,OnPress,CreateNarration)
     *          Extra arg: GETSTATIC Button.DEFAULT_NARRATION
     */
    public void registerSuperConstructorRedirect(String className, String oldDesc, String newDesc,
            String extraFieldOwner, String extraFieldName, String extraFieldDesc) {
        superCtorRedirects.put(
            new ConstructorKey(className, oldDesc),
            new SuperCtorRedirect(newDesc, extraFieldOwner, extraFieldName, extraFieldDesc));
        LOGGER.debug("Registered super ctor redirect: {}.{} -> {} + GETSTATIC {}.{}",
            className, oldDesc, newDesc, extraFieldOwner, extraFieldName);
    }

    /**
     * Register a constructor descriptor change that inserts default values for missing params.
     * Used when a constructor gains new required parameters in newer MC.
     * Example: TranslatableContents(String) → TranslatableContents(String, String, Object[])
     *   inserts ACONST_NULL (for String fallback) and empty Object[] (for args).
     *
     * The transformer detects which params are new by comparing old and new descriptors,
     * and pushes appropriate default values (null for objects, 0 for ints, etc.)
     */
    public void registerSuperConstructorRedirect(String className, String oldDesc, String newDesc) {
        // Use a special sentinel for the field owner to indicate "insert defaults" mode
        superCtorRedirects.put(
            new ConstructorKey(className, oldDesc),
            new SuperCtorRedirect(newDesc, "__INSERT_DEFAULTS__", "", ""));
        LOGGER.debug("Registered super ctor descriptor change: {}.{} -> {} (insert defaults)",
            className, oldDesc, newDesc);
    }

    /**
     * Register a super-constructor descriptor change where the new params differ in TYPE from the
     * old (not an append). At the {@code super(...)} call the old argument values are popped and a
     * fresh default is pushed for each new param. 1.12.2 {@code Block(Material)} -> {@code Block(Properties)}:
     * pop the (removed-type) Material, push {@code BlockBehaviour.Properties.of()}.
     */
    public void registerSuperConstructorReplace(String className, String oldDesc, String newDesc) {
        superCtorRedirects.put(
            new ConstructorKey(className, oldDesc),
            new SuperCtorRedirect(newDesc, "__REPLACE_DEFAULTS__", "", ""));
        LOGGER.debug("Registered super ctor replace: {}.{} -> {} (pop old, push defaults)",
            className, oldDesc, newDesc);
    }

    /**
     * Register intermediary method and field name mappings for bytecode remapping.
     * These are used by the Remapper to translate method_XXXX and field_XXXX names
     * in method calls, field accesses, @Shadow annotations, etc.
     */
    public void registerIntermediaryNameMappings(
            Map<String, String> methodNames, Map<String, String> fieldNames) {
        intermediaryMethodNames.putAll(methodNames);
        intermediaryFieldNames.putAll(fieldNames);
        cachedRemapper = null; // Invalidate cached remapper
        LOGGER.info("Registered {} intermediary method names and {} field names for bytecode remapping",
            methodNames.size(), fieldNames.size());
    }

    /**
     * Register a single descriptor-qualified intermediary member mapping, enabling a
     * descriptor-aware fallback for AMBIGUOUS short-names.
     *
     * <p>The flat {@link #registerIntermediaryNameMappings} maps a short intermediary name
     * ({@code method_XXXX} / {@code field_XXXX}) straight to one Mojang name. When the offline
     * harvest cannot make a short-name globally unique (the same short-name resolves to two
     * different Mojang names depending on descriptor), that flat map is last-writer-wins, which
     * silently produces a wrong rename that surfaces as {@code NoSuchMethodError}/{@code VerifyError}.
     *
     * <p>This overload records the target under the short-name (populating the same fast-path map)
     * AND under a {@code name+descriptor} key. If two distinct targets are ever registered for the
     * same short-name, the short-name is flagged ambiguous, and the remapper resolves it via
     * {@code name+descriptor} at lookup instead of trusting the flat map. Unambiguous names keep the
     * exact same fast name-only behavior as before.
     *
     * <p>Adapted from Sinytra Connector (MIT): {@code IntermediateMapping.extendedMappings} +
     * {@code getMappingKey}.
     *
     * @param name       intermediary short-name (e.g. {@code method_5773} or {@code field_6017})
     * @param descriptor JVM descriptor of the member at this site
     * @param mojang     the Mojang name this (name, descriptor) resolves to
     * @param isField    {@code true} for a field mapping, {@code false} for a method mapping
     */
    public void registerIntermediaryMemberMapping(
            String name, String descriptor, String mojang, boolean isField) {
        if (name == null || descriptor == null || mojang == null) {
            throw new IllegalArgumentException("name, descriptor and mojang must not be null");
        }
        Map<String, String> flat = isField ? intermediaryFieldNames : intermediaryMethodNames;
        Map<String, String> byDesc = isField ? intermediaryFieldNamesByDesc : intermediaryMethodNamesByDesc;
        Set<String> ambiguous = isField ? ambiguousIntermediaryFieldNames : ambiguousIntermediaryMethodNames;

        // Collision detection: if this short-name already resolved to a DIFFERENT Mojang name,
        // it is not globally unique, so name-only is unsafe. Flag it; the by-desc map carries
        // every variant (including the one already in the flat map, re-keyed below).
        String prior = flat.putIfAbsent(name, mojang);
        if (prior != null && !prior.equals(mojang)) {
            ambiguous.add(name);
        }
        byDesc.put(memberDescKey(name, descriptor), mojang);
        cachedRemapper = null; // Invalidate cached remapper
    }

    /** Key for the descriptor-qualified fallback maps. */
    private static String memberDescKey(String name, String descriptor) {
        return name + "|" + descriptor;
    }

    /**
     * Register Forge SRG → Mojang member name mappings.
     *
     * <p>Same shape as {@link #registerIntermediaryNameMappings} but for
     * SRG names ({@code m_NNNNNN_} / {@code f_NNNNN_}). Used by
     * {@code SrgToMojangMapper} which loads the bundled mapping data file.
     *
     * <p>Both maps are merged into the global SRG dictionary; later calls
     * override earlier entries for the same name.
     */
    public void registerSrgNameMappings(
            Map<String, String> methodNames, Map<String, String> fieldNames) {
        srgMethodNames.putAll(methodNames);
        srgFieldNames.putAll(fieldNames);
        cachedRemapper = null; // Invalidate cached remapper
        LOGGER.info("Registered {} SRG method names and {} SRG field names for bytecode remapping",
            methodNames.size(), fieldNames.size());
    }
    
    /**
     * Register a field redirect (field to field).
     */
    public void registerFieldRedirect(
            String oldOwner, String oldName,
            String newOwner, String newName) {
        
        FieldKey key = new FieldKey(oldOwner, oldName);
        FieldTarget target = new FieldTarget(newOwner, newName, null, null);
        fieldRedirects.put(key, target);
    }
    
    /**
     * Register a field redirect with descriptors.
     * If newDesc starts with "(", this is a field-to-method redirect
     * (GETSTATIC/GETFIELD becomes INVOKESTATIC/INVOKEVIRTUAL).
     */
    public void registerFieldRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        
        FieldKey key = new FieldKey(oldOwner, oldName);
        FieldTarget target = new FieldTarget(newOwner, newName, oldDesc, newDesc);
        fieldRedirects.put(key, target);
        
        LOGGER.debug("Registered field redirect: {}.{} {} -> {}.{} {}",
                oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
    }
    
    /**
     * Register a field accessor redirect: GETFIELD becomes INVOKEVIRTUAL getter,
     * PUTFIELD becomes INVOKEVIRTUAL setter. For fields that became private in newer MC.
     */
    public void registerFieldAccessorRedirect(
            String fieldOwner, String fieldName,
            String getterName, String getterDesc,
            String setterName, String setterDesc) {
        FieldKey key = new FieldKey(fieldOwner, fieldName);
        FieldAccessorTarget target = new FieldAccessorTarget(
            fieldOwner, getterName, getterDesc,
            fieldOwner, setterName, setterDesc
        );
        fieldAccessorRedirects.put(key, target);
        LOGGER.debug("Registered field accessor redirect: {}.{} -> get={}, set={}",
                fieldOwner, fieldName, getterName, setterName);
    }

    /**
     * Register a removed static field as an accessor call: a {@code GETSTATIC owner.name}
     * is rewritten to {@code GETSTATIC collectionOwner.collectionField}, an optional
     * {@code GETSTATIC argOwner.argField} (the accessor's enum/key argument), an
     * {@code INVOKEVIRTUAL methodOwner.methodName(methodDesc)}, and an optional
     * {@code CHECKCAST castType}. Used for 26.2's `ColorCollection` consolidation, where
     * the per-color block fields (e.g. {@code Blocks.WHITE_CANDLE}) were deleted in favour
     * of {@code Blocks.DYED_CANDLE.pick(DyeColor.WHITE)}, a field access that has to
     * become a method call, which a plain field-to-field redirect can't express.
     *
     * @param argField pass null for a no-argument accessor (then argOwner/argDesc ignored)
     * @param castType pass null to skip the trailing CHECKCAST
     */
    public void registerStaticFieldAccessor(
            String owner, String name,
            String collectionOwner, String collectionField, String collectionDesc,
            String argOwner, String argField, String argDesc,
            String methodOwner, String methodName, String methodDesc,
            String castType) {
        staticFieldAccessors.put(new FieldKey(owner, name), new StaticFieldAccessor(
                collectionOwner, collectionField, collectionDesc,
                argOwner, argField, argDesc,
                methodOwner, methodName, methodDesc, castType));
        LOGGER.debug("Registered static-field accessor: {}.{} -> {}.{}.{}()",
                owner, name, collectionOwner, collectionField, methodName);
    }

    /** Count of static-field accessor redirects (for tests). */
    public int getStaticFieldAccessorCount() {
        return staticFieldAccessors.size();
    }

    /** Number of direct field-to-getter/setter bridges, exposed for diagnostics and tests. */
    public int getFieldAccessorRedirectCount() {
        return fieldAccessorRedirects.size();
    }

    /**
     * Register a "hop" accessor for an instance field that moved onto an object reachable through a
     * PUBLIC sibling field of the same owner. {@code GETFIELD owner.name} becomes
     * {@code GETFIELD owner.hopField} + {@code INVOKEVIRTUAL hopClass.getter}; {@code PUTFIELD}
     * becomes {@code SWAP} + {@code GETFIELD owner.hopField} + {@code SWAP} +
     * {@code INVOKEVIRTUAL hopClass.setter}. Pure bytecode (no reflection), so it's safe on hot
     * render/input paths. 26.2's {@code Minecraft.screen} -> {@code mc.gui.screen()} /
     * {@code mc.gui.setScreen(s)} is the motivating case.
     *
     * <p>The PUTFIELD rewrite uses {@code SWAP}, so the field type must be a category-1 value
     * (any reference or 32-bit primitive; long/double are rejected at registration).
     *
     * @param setterName pass null for a read-only hop (PUTFIELD then passes through unchanged)
     */
    public void registerFieldHopAccessor(
            String owner, String name,
            String hopField, String hopDesc,
            String getterName, String getterDesc,
            String setterName, String setterDesc) {
        if (setterDesc != null && (setterDesc.startsWith("(J") || setterDesc.startsWith("(D"))) {
            throw new IllegalArgumentException(
                    "registerFieldHopAccessor: long/double field types can't be SWAPped: " + setterDesc);
        }
        String hopClass = hopDesc.substring(1, hopDesc.length() - 1); // Lpkg/Cls; -> pkg/Cls
        fieldHopAccessors.put(new FieldKey(owner, name), new FieldHopAccessor(
                hopField, hopDesc, hopClass, getterName, getterDesc, setterName, setterDesc));
        LOGGER.debug("Registered field hop accessor: {}.{} -> .{}.{}()/{}()",
                owner, name, hopField, getterName, setterName);
    }

    /**
     * Register a static bridge for an instance field whose state moved somewhere the receiver can no
     * longer reach (a different object entirely). {@code GETFIELD owner.name} becomes
     * {@code INVOKESTATIC bridgeOwner.getterName getterDesc} and {@code PUTFIELD} becomes
     * {@code INVOKESTATIC bridgeOwner.setterName setterDesc}; both descs must consume the receiver
     * as their first (typically {@code Ljava/lang/Object;}, ignored) argument, which is what keeps
     * the stack balanced. Unlike the field-to-method form of {@link #registerFieldRedirect}, this is
     * opcode-aware, so reads and writes get different call shapes. 26.2's {@code Options.hideGui} ->
     * {@code RetroClientEnv.isHideGui/setHideGui} (state moved to {@code mc.gui.hud}) is the
     * motivating case.
     */
    public void registerFieldStaticBridge(
            String owner, String name,
            String bridgeOwner,
            String getterName, String getterDesc,
            String setterName, String setterDesc) {
        fieldStaticBridges.put(new FieldKey(owner, name), new FieldStaticBridge(
                bridgeOwner, getterName, getterDesc, setterName, setterDesc));
        LOGGER.debug("Registered field static bridge: {}.{} -> {}.{}/{}",
                owner, name, bridgeOwner, getterName, setterName);
    }

    /**
     * Owners of removed classes whose static-field reads should become a pushed default (null / 0)
     * rather than fault on the missing class. Used by the 1.12.2 Block bridge: {@code super(Material.IRON)}
     * reads {@code Material.IRON} before the super call, but Material was removed in 26.1, so the
     * GETSTATIC would NoClassDefFoundError. We push the field type's default; the super-ctor replace
     * bridge then pops it and substitutes a default {@code Properties}.
     */
    public void registerStaticFieldNuller(String owner) {
        staticFieldNullers.add(owner);
    }

    /**
     * Mark a package as containing legacy mod code that should be transformed.
     */
    public void addTransformablePackage(String packagePrefix) {
        // Ensure it ends with /
        if (!packagePrefix.endsWith("/")) {
            packagePrefix = packagePrefix + "/";
        }
        transformablePackages.add(packagePrefix);
    }
    
    /**
     * Register an embedded shim class that provides removed API implementations.
     */
    public void registerEmbeddedShim(String className) {
        embeddedShimClasses.add(className);
        recordCompiledOwnerKind(className.replace('.', '/'));
    }

    private void recordCompiledOwnerKind(String internalName) {
        ClassLoader loader = RetromodTransformer.class.getClassLoader();
        try {
            Class<?> shimType = Class.forName(internalName.replace('/', '.'), false, loader);
            if (shimType.isInterface()) {
                interfaceSyntheticTargets.add(internalName);
            } else {
                classShimTargets.add(internalName);
            }
        } catch (ClassNotFoundException | LinkageError e) {
            LOGGER.debug("Could not inspect redirect target kind for {}", internalName, e);
        }
    }

    /**
     * Register a synthetic class generated via ASM bytecode.
     * These classes are injected into mod JARs during transformation and
     * can have fields/methods with MC-typed signatures that can't be
     * compiled from Java source (since MC isn't on the compile classpath).
     *
     * @param internalName the class internal name (e.g., "com/retromod/polyfill/...")
     * @param classBytes the generated class file bytes
     */
    public void registerSyntheticClass(String internalName, byte[] classBytes) {
        syntheticClasses.put(internalName, classBytes);
        // Track interface synthetics: a redirect whose TARGET is an interface must emit an
        // InterfaceMethodref regardless of the original call's flag (review finding: pre-1.19.3
        // mods' itf=false Registry.register calls redirected to the WorldgenTypeBridge interface
        // died IncompatibleClassChangeError at first registration).
        try {
            if ((new org.objectweb.asm.ClassReader(classBytes).getAccess()
                    & Opcodes.ACC_INTERFACE) != 0) {
                interfaceSyntheticTargets.add(internalName);
            } else {
                classShimTargets.add(internalName);
            }
        } catch (Exception ignored) {
        }
        // Also register as embedded shim so the embedder picks it up
        embeddedShimClasses.add(internalName.replace('/', '.'));
        LOGGER.debug("Registered synthetic class: {}", internalName);
    }

    /**
     * Get all registered synthetic classes.
     */
    public Map<String, byte[]> getSyntheticClasses() {
        return Collections.unmodifiableMap(syntheticClasses);
    }

    /**
     * Register a superclass redirect for class-to-interface migrations.
     * When a mod extends oldSuperclass, it will be rewritten to extend newSuperclass
     * and implement the specified interfaces.
     *
     * Example: If Explosion (class_1927) changed from a class to an interface,
     * mods extending it need their superclass changed to a bridge class that
     * implements the new interface.
     */
    public void registerSuperclassRedirect(String oldSuperclass, String newSuperclass, String... addInterfaces) {
        superclassRedirects.put(oldSuperclass, new SuperclassRedirect(newSuperclass, addInterfaces, false));
        LOGGER.debug("Registered superclass redirect: {} -> {} (+ {} interfaces)",
                oldSuperclass, newSuperclass, addInterfaces.length);
    }

    /**
     * Rebase a class's superclass from {@code oldSuperclass} to {@code newSuperclass} (both
     * classes), rewriting the {@code extends} clause and any {@code super(...)} calls to the old
     * base, but nothing else.
     *
     * <p>Use when the new base provides the old base's constructors so subclasses keep linking
     * (e.g. the pre-1.17 model bridge's {@code LegacyModelBase_*}). Unlike
     * {@link #registerClassRedirect}, which rewrites every reference to the old type (including a
     * mixin {@code @Inject} handler that captures it as a parameter, the #70 Arcanus crash), this
     * only touches the inheritance edge. Other references stay as the old type, which is correct
     * because the new base is a subtype of it.
     */
    public void registerSuperclassRebase(String oldSuperclass, String newSuperclass) {
        superclassRedirects.put(oldSuperclass, new SuperclassRedirect(newSuperclass, new String[0], true));
        LOGGER.debug("Registered superclass REBASE (extends + super ctor): {} -> {}",
                oldSuperclass, newSuperclass);
    }

    /**
     * Rebase variant for a base class that BECAME AN INTERFACE: {@code extends oldSuperclass}
     * becomes {@code extends newSuperclass implements addInterfaces}, and {@code super(...)}
     * calls to the old base are rewritten to the new one (which must provide matching
     * constructors - typically {@code java/lang/Object} for the old no-arg super()).
     * E.g. 26.2 turned StructureProcessor into an interface; a 1.21.x processor subclass
     * otherwise dies at definition (IncompatibleClassChangeError) or, with only the extends
     * edge rewritten, at {@code <init>} verification ("Bad &lt;init&gt; method call").
     */
    public void registerSuperclassRebase(String oldSuperclass, String newSuperclass,
            String... addInterfaces) {
        superclassRedirects.put(oldSuperclass, new SuperclassRedirect(newSuperclass, addInterfaces, true));
        LOGGER.debug("Registered superclass REBASE (extends + super ctor + {} interfaces): {} -> {}",
                addInterfaces.length, oldSuperclass, newSuperclass);
    }

    /**
     * Test-only: wipe all registered redirect state so a test can assert on the absence of
     * registrations (e.g. that a host-gated shim registers nothing on a pre-26.1 host). The
     * singleton accumulates state across the JVM otherwise. Also clears the intermediary and
     * SRG member-name maps that drive the remap gate, since they leak between tests too.
     * Pattern heuristics are left untouched. Never call this from production code.
     */
    public void clearRedirectsForTesting() {
        methodRedirects.clear();
        interfaceSyntheticTargets.clear();
        classShimTargets.clear();
        argDropRedirects.clear();
        argDropOwners.clear();
        convertingRedirects.clear();
        convertingOwners.clear();
        singletonRedirects.clear();
        singletonOwners.clear();
        staticFieldNullers.clear();
        mojangMethodRenames.clear();
        // These member-name maps drive the remap gate (hasIntermediaryNames); leaving them
        // populated lets a prior test's applyTo() / registerSrgNameMappings() leak into the next.
        intermediaryMethodNames.clear();
        intermediaryFieldNames.clear();
        intermediaryMethodNamesByDesc.clear();
        intermediaryFieldNamesByDesc.clear();
        ambiguousIntermediaryMethodNames.clear();
        ambiguousIntermediaryFieldNames.clear();
        srgMethodNames.clear();
        srgFieldNames.clear();
        classRedirects.clear();
        fieldRedirects.clear();
        superclassRedirects.clear();
        constructorRedirects.clear();
        constructorRedirectOwners.clear();
        fieldAccessorRedirects.clear();
        staticFieldAccessors.clear();
        fieldHopAccessors.clear();
        fieldStaticBridges.clear();
        superCtorRedirects.clear();
        syntheticClasses.clear();
        embeddedShimClasses.clear();
        methodRedirectOwners.clear();
        neutralizedMethods.clear();
        neutralizedMethodOwners.clear();
        indyTypedRewrites.clear();
        indyTypedOwners.clear();
        classRedirectsVersion.incrementAndGet();
        cachedRemapper = null;
        // A fresh redirect set must be re-validated for phantom targets on the next transform
        // (the sweep is otherwise once per transformer lifetime).
        targetsValidated.set(false);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
            Class<?> classBeingRedefined, ProtectionDomain protectionDomain, 
            byte[] classfileBuffer) {
        
        if (className == null || classfileBuffer == null) {
            return null;
        }
        
        // Only transform classes in registered packages
        if (!shouldTransform(className)) {
            return null;
        }
        
        // Use the hybrid engine which prefers AOT, falls back to JIT
        // This also handles performance monitoring automatically
        try {
            HybridTransformationEngine hybrid = HybridTransformationEngine.getInstance();
            String modId = guessModFromClass(className);
            return hybrid.transform(className, classfileBuffer, modId);
        } catch (Exception e) {
            LOGGER.error("Failed to transform class: {}", className, e);
            return null;
        }
    }
    
    /**
     * Guess which mod a class belongs to based on package name.
     */
    private String guessModFromClass(String className) {
        // Common mod package patterns
        // e.g., com/example/mymod/... -> mymod
        String[] parts = className.split("/");
        if (parts.length >= 3) {
            return parts[2]; // Usually the mod name is the 3rd part
        }
        return "unknown";
    }
    
    private boolean shouldTransform(String className) {
        for (String prefix : transformablePackages) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fail-safe against phantom redirect targets: a redirect that points at a
     * {@code com/retromod/*} class which will never resolve at runtime.
     *
     * <p>Historically several shims registered redirects to embedded helper classes that were
     * never actually written (e.g. {@code com/retromod/shim/neoforge/embedded/EnchantmentShim},
     * #119). Such a redirect transforms silently and only detonates the FIRST time the rewritten
     * call executes, with a {@code NoClassDefFoundError} that takes down whatever ran it (an
     * anvil, a render tick, mod construction). That is the worst possible failure shape: far from
     * the cause, and it looks like the mod's fault. This sweep removes any redirect whose target
     * owner is under {@code com/retromod/} yet is neither a registered synthetic (about to be
     * embedded) nor loadable as one of Retromod's own shipped classes, turning a latent hard
     * crash into a one-line warning and a no-op (the original call is left intact, which for a
     * redundant redirect is exactly right and for a genuinely-removed method is no worse than the
     * broken redirect was). Runs once, off the hot path, at the first {@link #transformClass}.
     */
    private void dropPhantomComRetromodTargets() {
        ClassLoader loader = RetromodTransformer.class.getClassLoader();
        Map<String, Boolean> resolvable = new java.util.HashMap<>();
        java.util.function.Predicate<String> isPhantom = owner -> {
            if (owner == null || !owner.startsWith("com/retromod/")) {
                return false; // only our own targets can be phantom; MC/loader names aren't ours
            }
            return !resolvable.computeIfAbsent(owner, o -> {
                if (syntheticClasses.containsKey(o)) {
                    return true; // generated at runtime and embedded per-mod
                }
                try {
                    Class.forName(o.replace('/', '.'), false, loader); // never initialize (#14)
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
        };
        java.util.Set<String> dropped = new java.util.TreeSet<>();

        methodRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().owner())) { dropped.add(e.getValue().owner()); return true; }
            return false;
        });
        argDropRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().owner())) { dropped.add(e.getValue().owner()); return true; }
            return false;
        });
        classRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue())) { dropped.add(e.getValue()); return true; }
            return false;
        });
        fieldRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().owner())) { dropped.add(e.getValue().owner()); return true; }
            return false;
        });
        fieldAccessorRedirects.entrySet().removeIf(e -> {
            FieldAccessorTarget t = e.getValue();
            if (isPhantom.test(t.getterOwner()) || isPhantom.test(t.setterOwner())) {
                dropped.add(t.getterOwner()); return true;
            }
            return false;
        });
        constructorRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().factoryClass())) { dropped.add(e.getValue().factoryClass()); return true; }
            return false;
        });
        superclassRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().newSuperclass())) { dropped.add(e.getValue().newSuperclass()); return true; }
            return false;
        });
        superCtorRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().extraFieldOwner())) { dropped.add(e.getValue().extraFieldOwner()); return true; }
            return false;
        });
        staticFieldAccessors.entrySet().removeIf(e -> {
            StaticFieldAccessor t = e.getValue();
            if (isPhantom.test(t.collectionOwner()) || isPhantom.test(t.methodOwner())
                    || isPhantom.test(t.argOwner()) || isPhantom.test(t.castType())) {
                dropped.add(t.methodOwner()); return true;
            }
            return false;
        });
        fieldHopAccessors.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().hopClass())) { dropped.add(e.getValue().hopClass()); return true; }
            return false;
        });
        fieldStaticBridges.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().owner())) { dropped.add(e.getValue().owner()); return true; }
            return false;
        });

        if (!dropped.isEmpty()) {
            // Rebuild the fast-path owner sets from the surviving redirects; the dropped
            // entries may have been the only user of an owner.
            methodRedirectOwners.clear();
            methodRedirects.keySet().forEach(k -> methodRedirectOwners.add(k.owner()));
            argDropOwners.clear();
            argDropRedirects.keySet().forEach(k -> argDropOwners.add(k.owner()));
            constructorRedirectOwners.clear();
            constructorRedirects.keySet().forEach(k -> constructorRedirectOwners.add(k.className()));
            LOGGER.warn("Dropped redirects to {} phantom com/retromod target(s) that would have "
                    + "crashed at first use: {}", dropped.size(), dropped);
        }
    }

    /**
     * Read-only counterpart of {@link #dropPhantomComRetromodTargets}: returns every currently
     * registered redirect target under {@code com/retromod/} that is neither a registered
     * synthetic nor loadable, without removing anything. The runtime guard makes these fail-safe;
     * this exists so a CI test can assert the set is empty and prevent NEW phantom targets from
     * being introduced (the shim tree carried a long tail of them, #119). Sorted for stable output.
     */
    public java.util.SortedSet<String> collectPhantomComRetromodTargets() {
        ClassLoader loader = RetromodTransformer.class.getClassLoader();
        Map<String, Boolean> resolvable = new java.util.HashMap<>();
        java.util.function.Predicate<String> isPhantom = owner -> {
            if (owner == null || !owner.startsWith("com/retromod/")) return false;
            return !resolvable.computeIfAbsent(owner, o -> {
                if (syntheticClasses.containsKey(o)) return true;
                try {
                    Class.forName(o.replace('/', '.'), false, loader);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
        };
        java.util.SortedSet<String> phantoms = new java.util.TreeSet<>();
        methodRedirects.values().forEach(v -> { if (isPhantom.test(v.owner())) phantoms.add(v.owner()); });
        argDropRedirects.values().forEach(v -> { if (isPhantom.test(v.owner())) phantoms.add(v.owner()); });
        classRedirects.values().forEach(v -> { if (isPhantom.test(v)) phantoms.add(v); });
        fieldRedirects.values().forEach(v -> { if (isPhantom.test(v.owner())) phantoms.add(v.owner()); });
        fieldAccessorRedirects.values().forEach(v -> {
            if (isPhantom.test(v.getterOwner())) phantoms.add(v.getterOwner());
            if (isPhantom.test(v.setterOwner())) phantoms.add(v.setterOwner());
        });
        constructorRedirects.values().forEach(v -> { if (isPhantom.test(v.factoryClass())) phantoms.add(v.factoryClass()); });
        superclassRedirects.values().forEach(v -> { if (isPhantom.test(v.newSuperclass())) phantoms.add(v.newSuperclass()); });
        superCtorRedirects.values().forEach(v -> { if (isPhantom.test(v.extraFieldOwner())) phantoms.add(v.extraFieldOwner()); });
        staticFieldAccessors.values().forEach(v -> {
            if (isPhantom.test(v.collectionOwner())) phantoms.add(v.collectionOwner());
            if (isPhantom.test(v.methodOwner())) phantoms.add(v.methodOwner());
            if (isPhantom.test(v.argOwner())) phantoms.add(v.argOwner());
            if (isPhantom.test(v.castType())) phantoms.add(v.castType());
        });
        fieldHopAccessors.values().forEach(v -> {
            if (isPhantom.test(v.hopClass())) phantoms.add(v.hopClass());
        });
        fieldStaticBridges.values().forEach(v -> {
            if (isPhantom.test(v.owner())) phantoms.add(v.owner());
        });
        return phantoms;
    }


    /**
     * Transform a class's bytecode, rewriting method/field/class references. Core JIT
     * transformation logic, also used by AOT.
     *
     * <p>Visitor chain: {@code ClassReader -> ClassRemapper -> RetromodClassVisitor ->
     * ClassWriter}. ClassRemapper runs first so RetromodClassVisitor sees final Mojang names
     * when it does method-redirect lookups (matching only Mojang names, not intermediary too).
     *
     * <p>Calls {@link #singleTransformPass} repeatedly until the bytecode stabilizes (two
     * consecutive passes byte-identical) or {@link #MAX_TRANSFORM_ITERATIONS} is hit. The loop
     * handles chained redirects: if pass 1 rewrites {@code X.foo() -> Y.bar()} and
     * {@code Y.bar()} itself redirects to {@code Z.baz()}, pass 2 catches the second hop, which
     * a single-pass visitor misses (it has moved past the instruction by the time it rewrites).
     */
    public byte[] transformClass(byte[] originalBytes, String className) {
        // One-time safety sweep: drop any redirect whose target is a com/retromod/* class
        // that will never resolve at runtime (see dropPhantomComRetromodTargets).
        if (targetsValidated.compareAndSet(false, true)) {
            dropPhantomComRetromodTargets();
        }

        // No redirects registered: no pass would do anything, skip the loop.
        // constructorRedirects + fieldAccessorRedirects are included here too, or a
        // ctor-only / field-accessor-only redirect set would skip the transform entirely.
        if (methodRedirects.isEmpty() && classRedirects.isEmpty() &&
                fieldRedirects.isEmpty() && superclassRedirects.isEmpty() &&
                neutralizedMethods.isEmpty() && staticFieldAccessors.isEmpty() &&
                mojangMethodRenames.isEmpty() && argDropRedirects.isEmpty() &&
                constructorRedirects.isEmpty() && fieldAccessorRedirects.isEmpty() &&
                fieldHopAccessors.isEmpty() && fieldStaticBridges.isEmpty() &&
                intermediaryMethodNames.isEmpty() && intermediaryFieldNames.isEmpty() &&
                srgMethodNames.isEmpty() && srgFieldNames.isEmpty()) {
            return originalBytes;
        }

        // Debug: log class redirect count for first few classes (once per class, not per pass)
        if (className != null && (className.contains("Mixin") || className.contains("mixin"))) {
            LOGGER.debug("transformClass({}) with {} class redirects, {} method redirects",
                className, classRedirects.size(), methodRedirects.size());
        }

        // Iterative loop: re-run the visitor chain against the previous pass's output until
        // two consecutive passes produce byte-identical output, or MAX_TRANSFORM_ITERATIONS
        // (possible redirect cycle) is reached. Byte-equality is the stability signal rather
        // than a threaded "dirty" flag: ASM is deterministic, so same input = same output.
        byte[] current = originalBytes;
        for (int pass = 1; pass <= MAX_TRANSFORM_ITERATIONS; pass++) {
            // Constructor rewrites are resolved against the fully remapped owner during the
            // first pass. Re-running the constructor pre-pass is both unnecessary and harmful:
            // an unmatched overload has to be temporarily spilled below NEW, so every later
            // pass used to add another redundant store/load pair (#70 acceptance profile).
            byte[] next = singleTransformPass(current, className, pass == 1);
            totalPassesPerformed.incrementAndGet();

            if (pass == 1) {
                // A pass RE-SERIALIZES the class even when no redirect matched (fresh constant
                // pool + COMPUTE_FRAMES). For a class whose type hierarchy isn't loadable at
                // transform time (a JiJ'd library: YungsApi's bundled javassist), the recomputed
                // frames merge unknown sister types to java/lang/Object and the output fails
                // VERIFICATION at runtime (VerifyError in javassist ConstPool.readOne, found on
                // the 26.2 dedicated server once the directory-entry fix let Reflections load it).
                // Compare pass 1 against an identity re-serialization: byte-equal means nothing
                // semantically changed, so ship the ORIGINAL bytes - bit-perfect frames included.
                // Cost-neutral: this replaces the pass-2 stability check for untouched classes.
                byte[] baseline = identityReserialize(originalBytes, className);
                if (baseline != null && Arrays.equals(next, baseline)) {
                    return postProcess(originalBytes, className);
                }
            }

            if (Arrays.equals(current, next)) {
                // Stable: nothing further was rewritten. activePasses = passes that changed
                // bytes; >= 2 means a later pass changed output on top of an earlier one
                // (chained redirects).
                int activePasses = pass - 1;
                if (activePasses >= 2) {
                    classesNeedingMultiplePasses.incrementAndGet();
                    LOGGER.debug("Class {} needed {} transform passes (chained redirects)",
                            className, activePasses);
                }
                return postProcess(current, className);
            }
            current = next;
        }

        // Hit the cap, most likely a cycle in redirect chains (A -> B -> A). Use the last
        // pass's output; it's at least as transformed as any earlier pass.
        classesHittingIterationCap.incrementAndGet();
        LOGGER.warn("Transform loop hit cap ({} passes) for class {}. " +
                "Possible redirect cycle - check shim/polyfill registrations. " +
                "Using last pass output.",
                MAX_TRANSFORM_ITERATIONS, className);
        return postProcess(current, className);
    }

    /**
     * Run post-loop transforms (reflection remapping) on the stabilized bytecode.
     *
     * <p>Separate from the iterative loop because these passes operate on string constants
     * that {@code ClassRemapper} doesn't see, are idempotent, and so run once per class
     * regardless of how many iterative passes it needed.
     */
    private byte[] postProcess(byte[] stableBytes, String className) {
        byte[] repaired = normalizeEmbeddedShimCalls(stableBytes);
        repaired = com.retromod.shim.fabric.LegacyFabricItemIdRepair
                .apply(repaired, className);
        repaired = com.retromod.shim.fabric.LegacyPatchouliGsonRepair
                .apply(repaired, className);
        repaired = com.retromod.shim.fabric.LegacyPatchouliBufferRepair
                .apply(repaired, className);
        if (!REFLECTION_REMAP_ENABLED) return repaired;
        try {
            byte[] remapped = getReflectionRemapper().remap(repaired);
            if (remapped != repaired) {
                // The remapper returns the same reference if nothing changed, so inequality
                // means a rewrite happened. Count it for the diagnostic summary.
                reflectionRemapPassesPerformed.incrementAndGet();
            }
            return remapped;
        } catch (Exception e) {
            // Reflection remapping is advisory; a failure shouldn't break the transform.
            LOGGER.debug("Reflection remap skipped for {}: {}", className, e.getMessage());
            return repaired;
        }
    }

    /**
     * Correct invocation kind after class remapping has selected the final embedded owner.
     * ASM preserves the old owner's interface flag during a plain class redirect. That produces
     * an invalid InterfaceMethodref when an old API interface is replaced by a compiled class.
     */
    private byte[] normalizeEmbeddedShimCalls(byte[] bytes) {
        if (classShimTargets.isEmpty() && interfaceSyntheticTargets.isEmpty()) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        AtomicBoolean changed = new AtomicBoolean(false);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(
                        access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        int correctedOpcode = opcode;
                        boolean correctedInterface = isInterface;
                        if (classShimTargets.contains(owner)) {
                            if (opcode == Opcodes.INVOKEINTERFACE) {
                                correctedOpcode = Opcodes.INVOKEVIRTUAL;
                            }
                            correctedInterface = false;
                        } else if (interfaceSyntheticTargets.contains(owner)) {
                            if (opcode == Opcodes.INVOKEVIRTUAL) {
                                correctedOpcode = Opcodes.INVOKEINTERFACE;
                            }
                            correctedInterface = true;
                        }
                        if (correctedOpcode != opcode || correctedInterface != isInterface) {
                            changed.set(true);
                        }
                        super.visitMethodInsn(correctedOpcode, owner, methodName,
                                methodDescriptor, correctedInterface);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return changed.get() ? writer.toByteArray() : bytes;
    }

    /**
     * Serialize {@code originalBytes} through the SAME writer/visitor shape as
     * {@link #singleTransformPass} but with an identity mapping: same fresh-constant-pool and
     * COMPUTE_FRAMES behavior, no semantic changes. Byte-equality between this and the real
     * pass-1 output proves no redirect touched the class, so the caller can ship the original
     * bytes and never expose recomputed (possibly Object-fallback-corrupted) frames.
     * Returns null when the baseline can't be built - the caller then keeps today's behavior.
     */
    private byte[] identityReserialize(byte[] originalBytes, String className) {
        try {
            ClassReader reader = new ClassReader(originalBytes);
            Function<String, byte[]> classBytesProvider = currentJarClassBytesProvider();
            // mirror singleTransformPass's remapper-presence condition (line ~930)
            boolean hasClassRemaps = cachedRemapper != null
                    || !classRedirects.isEmpty()
                    || !intermediaryMethodNames.isEmpty() || !intermediaryFieldNames.isEmpty()
                    || !mojangMethodRenames.isEmpty();
            ClassWriter writer = hasClassRemaps
                    ? new SafeClassWriter(ClassWriter.COMPUTE_FRAMES, classBytesProvider)
                    : new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES, classBytesProvider);
            // NO RetromodClassVisitor here: it consults the live redirect maps, which would make
            // the baseline equal pass 1 for changed classes too (false "unchanged"). When nothing
            // matches, RetromodClassVisitor is pure delegation, so leaving it out of the identity
            // chain keeps the serialization byte-comparable for untouched classes; any visitor
            // asymmetry only produces a false "changed", which falls back to today's behavior.
            ClassVisitor visitor = writer;
            if (hasClassRemaps) {
                visitor = new ClassRemapper(visitor, new Remapper() { /* identity */ });
            }
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            byte[] result = writer.toByteArray();
            if (hasClassRemaps) {
                result = deduplicateMethods(result, className);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Run one bytecode transformation pass: a complete visitor-chain traversal
     * ({@code Reader -> ClassRemapper -> RetromodClassVisitor -> ClassWriter}). The
     * {@link #transformClass} outer loop invokes this until output stabilizes.
     *
     * <p>If COMPUTE_FRAMES fails (common when a modded class references off-classpath types),
     * falls back to COMPUTE_MAXS preserving existing stack-map frames. If that also fails,
     * returns the input bytes unchanged so the mod still ships with this class untransformed.
     *
     * @param originalBytes the current bytecode (original input or a previous pass's output)
     * @param className     JVM internal name of the class, used only for logging
     * @return transformed bytes, or {@code originalBytes} if the visitor chain fails completely
     */
    private byte[] singleTransformPass(byte[] originalBytes, String className,
            boolean applyConstructorRedirects) {
        ClassReader reader = new ClassReader(originalBytes);

        // Use the cached remapper if class redirects haven't changed; the cache is invalidated
        // whenever classRedirects or intermediary mappings change. Parallel transformation
        // (Fabric / Forge / CLI) calls this concurrently, so construct-and-publish is
        // double-checked under the lock to publish exactly one Remapper instance.
        Remapper classRemapper = cachedRemapper;
        if (classRemapper == null) {
            boolean hasIntermediaryNames = !intermediaryMethodNames.isEmpty() || !intermediaryFieldNames.isEmpty();
            // SRG maps must also force the remapper into the chain: an SRG-only registration
            // (no class redirects) previously built NO remapper at all, silently skipping the
            // member remap (latent; production paths always had class redirects alongside).
            boolean hasSrgNames = !srgMethodNames.isEmpty() || !srgFieldNames.isEmpty();
            if (!classRedirects.isEmpty() || hasIntermediaryNames || hasSrgNames
                    || !mojangMethodRenames.isEmpty()) {
                synchronized (this) {
                    classRemapper = cachedRemapper; // re-check under lock
                    if (classRemapper == null) {
                        classRemapper = new Remapper() {
                            @Override
                            public String map(String internalName) {
                                return classRedirects.getOrDefault(internalName, internalName);
                            }

                            @Override
                            public String mapMethodName(String owner, String name, String descriptor) {
                                // Remap intermediary method names (method_XXXX / comp_XXXX → Mojang name)
                                if (!intermediaryMethodNames.isEmpty()
                                        && (name.startsWith("method_") || name.startsWith("comp_"))) {
                                    // Ambiguous short-name: resolve by descriptor instead of the
                                    // last-writer-wins flat entry. Fall through to the flat map only
                                    // if this descriptor variant wasn't harvested.
                                    if (ambiguousIntermediaryMethodNames.contains(name)) {
                                        String byDesc = intermediaryMethodNamesByDesc.get(
                                                memberDescKey(name, descriptor));
                                        if (byDesc != null) return byDesc;
                                    }
                                    String mojang = intermediaryMethodNames.get(name);
                                    if (mojang != null) return mojang;
                                }
                                // Remap Forge SRG method names (m_NNNNNN_ -> Mojang). Forge 64.x
                                // dropped the SRG remap layer, so SRG-baked Forge mods would hit
                                // NoSuchMethodError without this. The 1.12.2-era SRG namespace
                                // uses func_NNNNN_x instead of m_NNNNNN_; same dictionary, other
                                // key pattern (#103/#108/#117).
                                if (!srgMethodNames.isEmpty()
                                        && name.length() > 3
                                        && ((name.startsWith("m_") && name.endsWith("_"))
                                            || name.startsWith("func_"))) {
                                    String mojang = srgMethodNames.get(name);
                                    if (mojang != null) return mojang;
                                }
                                // Vanilla Mojang->Mojang rename (owner-scoped), e.g.
                                // ResourceKey.location -> identifier on 26.x. Routed through the
                                // ClassRemapper so it also rewrites method references
                                // (ResourceKey::location), which methodRedirects can't reach.
                                if (!mojangMethodRenames.isEmpty()) {
                                    String renamed = mojangMethodRenames.get(owner + "#" + name);
                                    if (renamed != null) return renamed;
                                }
                                return name;
                            }

                            @Override
                            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                                // The invokedynamic NAME slot is the SAM method of the functional
                                // interface the lambda implements; LambdaMetafactory resolves it
                                // against the RUNTIME interface. A lambda over an intermediary-named
                                // MC interface kept its method_N SAM name while the interface itself
                                // was remapped, so the metafactory threw LambdaConversionException
                                // at first use (#157: nekomasfixed's TargetingConditions$Selector
                                // lambda kept method_18303 instead of test). The indy descriptor is
                                // the capture signature, not the SAM descriptor, so desc-based
                                // disambiguation doesn't apply; functional interfaces have a single
                                // abstract method, so the flat name lookup is the right resolution.
                                if (!intermediaryMethodNames.isEmpty()
                                        && (name.startsWith("method_") || name.startsWith("comp_"))) {
                                    String mojang = intermediaryMethodNames.get(name);
                                    if (mojang != null) return mojang;
                                }
                                if (!srgMethodNames.isEmpty() && name.length() > 3
                                        && ((name.startsWith("m_") && name.endsWith("_"))
                                            || name.startsWith("func_"))) {
                                    String mojang = srgMethodNames.get(name);
                                    if (mojang != null) return mojang;
                                }
                                return name;
                            }

                            public String mapFieldName(String owner, String name, String descriptor) {
                                // Remap intermediary field names (field_XXXX → Mojang name)
                                if (!intermediaryFieldNames.isEmpty() && name.startsWith("field_")) {
                                    // Ambiguous short-name: resolve by descriptor first (see the
                                    // method case above), else fall through to the flat map.
                                    if (ambiguousIntermediaryFieldNames.contains(name)) {
                                        String byDesc = intermediaryFieldNamesByDesc.get(
                                                memberDescKey(name, descriptor));
                                        if (byDesc != null) return byDesc;
                                    }
                                    String mojang = intermediaryFieldNames.get(name);
                                    if (mojang != null) return mojang;
                                }
                                // Remap Forge SRG field names (f_NNNNN_ -> Mojang), same reasoning
                                // as the method case above (f_50069_ is Blocks.STONE). 1.12.2-era
                                // SRG fields are field_NNNNN_x; the Fabric-intermediary branch
                                // above shares the field_ prefix but its lookup already missed
                                // (the two dictionaries have disjoint keys), so falling through
                                // to the SRG map here is safe.
                                if (!srgFieldNames.isEmpty()
                                        && name.length() > 3
                                        && ((name.startsWith("f_") && name.endsWith("_"))
                                            || name.startsWith("field_"))) {
                                    String mojang = srgFieldNames.get(name);
                                    if (mojang != null) return mojang;
                                }
                                return name;
                            }
                        };
                        cachedRemapper = classRemapper;
                    }
                }
            }
        }

        // When using ClassRemapper, don't pass the ClassReader to ClassWriter: ClassWriter(reader,
        // flags) copies the reader's constant pool, so the remapper's name changes wouldn't reach
        // the output. A standalone ClassWriter forces ASM to build a fresh constant pool.
        boolean hasClassRemaps = (classRemapper != null);
        boolean hasApplicableConstructorRedirect = applyConstructorRedirects
                && hasMatchingConstructorRedirect(reader, classRemapper);

        // Pre-26.1 Fabric keeps the intermediary hierarchy. Those Minecraft classes are not on
        // the offline CLI classpath, so recomputing an Entity/ClientPlayer join widens it to
        // Object and can make an otherwise unchanged call fail verification (#179, ENGRAM's
        // offscreen camera restore). Preserve javac's precise frames whenever this pass only
        // performs frame-neutral substitutions. Structural rewrites still use the normal
        // COMPUTE_FRAMES path below.
        if (!RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)
                && hasStackMapFrames(reader)) {
            byte[] preserved = transformPreservingFrames(
                    reader, className, classRemapper, hasClassRemaps,
                    hasApplicableConstructorRedirect);
            if (preserved != null) {
                return preserved;
            }
        }

        Function<String, byte[]> classBytesProvider = currentJarClassBytesProvider();
        ClassWriter writer = hasClassRemaps
            ? new SafeClassWriter(ClassWriter.COMPUTE_FRAMES, classBytesProvider)
            : new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES, classBytesProvider);

        ClassVisitor visitor = writer;

        // RetromodClassVisitor must be inner (closer to writer) so it sees Mojang names after
        // ClassRemapper translates intermediary names.
        visitor = new RetromodClassVisitor(Opcodes.ASM9, visitor);

        // Only add remapping visitor if we have class redirects
        if (classRemapper != null) {
            visitor = new ClassRemapper(visitor, classRemapper);
        }

        // Constructor->factory rewriting runs OUTERMOST (upstream of the remapper) so it
        // sees pre-remap owner names; see CtorRedirectPrePass for why that matters.
        if (hasApplicableConstructorRedirect) {
            visitor = new CtorRedirectPrePass(Opcodes.ASM9, visitor, classRemapper);
        }

        try {
            // Use EXPAND_FRAMES to properly feed frame data into COMPUTE_FRAMES
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            byte[] result = writer.toByteArray();
            // Deduplicate methods that may have identical name+descriptor after class remapping.
            // This happens when two intermediary class names (e.g., class_316 and class_4064)
            // both map to the same Mojang name, causing return types in descriptors to collapse.
            if (hasClassRemaps) {
                result = deduplicateMethods(result, className);
            }
            return result;
        } catch (Exception e) {
            // Fallback: try with COMPUTE_MAXS and preserve existing frames. Log the primary
            // failure: it used to be swallowed silently, which hid a frame-corruption bug for
            // weeks (the fallback preserves ORIGINAL StackMapTable entries, so any structural
            // rewrite that moves or removes instructions ships stale frames).
            LOGGER.warn("Frame recomputation failed for {}; retrying with the original frames",
                    className, e);
            try {
                ClassWriter fallbackWriter = hasClassRemaps
                    ? new SafeClassWriter(ClassWriter.COMPUTE_MAXS)
                    : new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                ClassVisitor fallbackVisitor = fallbackWriter;
                RetromodClassVisitor bodyVisitor =
                        new RetromodClassVisitor(Opcodes.ASM9, fallbackVisitor);
                fallbackVisitor = bodyVisitor;
                if (classRemapper != null) {
                    fallbackVisitor = new ClassRemapper(fallbackVisitor, classRemapper);
                }
                if (hasApplicableConstructorRedirect) {
                    fallbackVisitor = new CtorRedirectPrePass(Opcodes.ASM9, fallbackVisitor,
                            classRemapper);
                }
                // Don't skip frames; preserve existing StackMapTable entries
                frameInvalidatingRewrite.set(Boolean.FALSE);
                reader.accept(fallbackVisitor, 0);
                // A structural rewrite (ctor->factory, super-ctor replace, arg-drop, ...)
                // invalidates the preserved frames: the shipped class would die at CLASS LOAD
                // with "StackMapTable format error: bad offset for Uninitialized" and take the
                // whole mod down (hit live: Collective's DuskConfig on the 1.20.1 acceptance
                // pass). Shipping the ORIGINAL bytes instead degrades to the pre-transform
                // behavior for this one class (a NoSuchMethodError at the untransformed call,
                // soft-failable) rather than corrupting it.
                if (bodyVisitor.structurallyChangedFrames()) {
                    LOGGER.warn("Fallback pass for {} made frame-invalidating rewrites; "
                            + "shipping the original class instead of corrupt frames", className);
                    return originalBytes;
                }
                byte[] result = fallbackWriter.toByteArray();
                if (hasClassRemaps) {
                    result = deduplicateMethods(result, className);
                }
                return result;
            } catch (Exception e2) {
                LOGGER.warn("Transform failed for {}, returning original: {}", className, e2.getMessage());
                return originalBytes;
            }
        }
    }

    private static boolean hasStackMapFrames(ClassReader reader) {
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local,
                            int numStack, Object[] stack) {
                        found[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return found[0];
    }

    /** Whether this class contains a constructor invocation covered by the registered factories. */
    private boolean hasMatchingConstructorRedirect(ClassReader reader, Remapper remapper) {
        if (constructorRedirects.isEmpty()) return false;
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDesc, boolean isInterface) {
                        if (found[0] || opcode != Opcodes.INVOKESPECIAL
                                || !"<init>".equals(methodName)) return;
                        String resolvedOwner = remapper == null ? owner : remapper.mapType(owner);
                        String resolvedDesc = remapper == null
                                ? methodDesc : remapper.mapMethodDesc(methodDesc);
                        found[0] = constructorRedirects.containsKey(
                                new ConstructorKey(resolvedOwner, resolvedDesc))
                                || (!resolvedDesc.equals(methodDesc) && constructorRedirects.containsKey(
                                        new ConstructorKey(resolvedOwner, methodDesc)))
                                || (!resolvedOwner.equals(owner) && constructorRedirects.containsKey(
                                        new ConstructorKey(owner, methodDesc)));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private byte[] transformPreservingFrames(ClassReader reader, String className,
            Remapper classRemapper, boolean hasClassRemaps,
            boolean applyConstructorRedirects) {
        try {
            ClassWriter writer = hasClassRemaps
                    ? new SafeClassWriter(ClassWriter.COMPUTE_MAXS)
                    : new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = writer;
            RetromodClassVisitor bodyVisitor = new RetromodClassVisitor(Opcodes.ASM9, visitor);
            visitor = bodyVisitor;
            if (classRemapper != null) {
                visitor = new ClassRemapper(visitor, classRemapper);
            }
            if (applyConstructorRedirects && !constructorRedirects.isEmpty()) {
                visitor = new CtorRedirectPrePass(Opcodes.ASM9, visitor, classRemapper);
            }

            frameInvalidatingRewrite.set(Boolean.FALSE);
            reader.accept(visitor, 0);
            if (bodyVisitor.structurallyChangedFrames()) {
                return null;
            }
            byte[] result = writer.toByteArray();
            return hasClassRemaps ? deduplicateMethods(result, className) : result;
        } catch (Exception e) {
            return null;
        } finally {
            frameInvalidatingRewrite.set(Boolean.FALSE);
        }
    }

    /**
     * Total number of transformation passes performed across all classes since
     * this transformer was instantiated. A class that stabilizes after one pass
     * contributes one; a class needing three passes contributes three.
     */
    public int getTotalPassesPerformed() {
        return totalPassesPerformed.get();
    }

    /**
     * Number of classes that required 2+ passes to stabilize. Each such class
     * indicates a chained redirect (e.g., shim A called B, which has its own
     * redirect to C). A high count suggests the shim chain has deep hops.
     */
    public int getClassesNeedingMultiplePasses() {
        return classesNeedingMultiplePasses.get();
    }

    /**
     * Number of classes that hit {@link #MAX_TRANSFORM_ITERATIONS} without
     * stabilizing. A non-zero value here indicates a cycle in registered
     * redirects (e.g., A → B → A) and should be investigated; the class's
     * output will be the last pass before the cap, which may be inconsistent.
     */
    public int getClassesHittingIterationCap() {
        return classesHittingIterationCap.get();
    }

    /**
     * Reset iteration metrics. Useful for tests that want to measure pass counts
     * for a specific transformation without inheriting counts from prior runs.
     */
    public void resetIterationMetrics() {
        totalPassesPerformed.set(0);
        classesNeedingMultiplePasses.set(0);
        classesHittingIterationCap.set(0);
        reflectionRemapPassesPerformed.set(0);
    }

    /**
     * Lazily construct the reflection string remapper, reusing the transformer's redirect
     * tables. Built once and not rebuilt for later redirects, since all shims load before the
     * first class is transformed; call {@link #invalidateReflectionRemapper()} if that changes.
     */
    private com.retromod.core.verify.ReflectionStringRemapper getReflectionRemapper() {
        com.retromod.core.verify.ReflectionStringRemapper local = reflectionRemapper;
        if (local != null) return local;
        synchronized (this) {
            if (reflectionRemapper != null) return reflectionRemapper;
            reflectionRemapper = new com.retromod.core.verify.ReflectionStringRemapper(
                    classRedirects,
                    intermediaryMethodNames,
                    intermediaryFieldNames,
                    com.retromod.core.verify.LoaderApiRenames.getInstance());
            return reflectionRemapper;
        }
    }

    /**
     * Force reconstruction of the reflection remapper on next use. Call this
     * after bulk-registering new redirects if you need them reflected in
     * reflection-string rewrites.
     */
    public void invalidateReflectionRemapper() {
        reflectionRemapper = null;
    }

    /**
     * How many classes had at least one reflection string rewritten by the
     * remapper. For diagnostics/metrics reporting.
     */
    public int getReflectionRemapPassesPerformed() {
        return reflectionRemapPassesPerformed.get();
    }

    /**
     * Scan a transformed class for unresolved MC references and append findings
     * to the given report. Callers (CLI {@code batchCommand}, mod-loader
     * transformers) invoke this after processing every class in a mod to
     * produce a per-mod gap report.
     *
     * <p>No-op if reference verification is disabled via
     * {@code -Dretromod.verifyTransforms=false} (the default).</p>
     *
     * @param classBytes    transformed class bytecode (post-iterative-loop)
     * @param className     JVM internal name (for diagnostic logging)
     * @param modOwnClasses classes defined by the mod itself; references to
     *                      these are skipped so we don't report mod-internal
     *                      references as "missing from MC"
     * @param report        report to accumulate findings into
     */
    public void verifyClass(byte[] classBytes, String className,
                             java.util.Set<String> modOwnClasses,
                             com.retromod.core.verify.VerificationReport report) {
        if (!REFERENCE_VERIFY_ENABLED) return;
        if (classBytes == null || report == null) return;
        com.retromod.core.verify.ReferenceVerifier v = getReferenceVerifier();
        if (v == null) return;
        v.verify(classBytes, modOwnClasses, report);
    }

    /** @return {@code true} iff reference verification is enabled via system property */
    public static boolean isVerificationEnabled() {
        return REFERENCE_VERIFY_ENABLED;
    }

    /**
     * Lazily construct the reference verifier once the fuzzy resolver has been
     * initialized with a target MC JAR. Returns null (and logs once) if the
     * resolver isn't indexed yet; verification can't run without it.
     */
    private com.retromod.core.verify.ReferenceVerifier getReferenceVerifier() {
        com.retromod.core.verify.ReferenceVerifier local = referenceVerifier;
        if (local != null) return local;
        synchronized (this) {
            if (referenceVerifier != null) return referenceVerifier;
            if (fuzzyResolver == null || !fuzzyResolver.isIndexed()) {
                // No MC index → can't verify. Return null; caller skips the verify.
                return null;
            }
            com.retromod.core.verify.McSymbolIndex index =
                new com.retromod.core.verify.FuzzyBackedSymbolIndex(
                    fuzzyResolver, targetMcVersion);
            referenceVerifier = new com.retromod.core.verify.ReferenceVerifier(
                index,
                com.retromod.core.verify.LoaderApiRenames.getInstance());
            return referenceVerifier;
        }
    }

    /**
     * Record the target MC version for verification-report headers. Called by the Retromod
     * initializer after it detects the running MC version. Thread-safe and idempotent.
     *
     * <p>Acquires the class monitor so the verifier invalidation happens under the same lock
     * {@link #getReferenceVerifier} uses for its double-checked read; otherwise another thread
     * could observe a {@code referenceVerifier} built from the old version.
     */
    public void setTargetMcVersion(String version) {
        if (version == null || version.isEmpty()) return;
        synchronized (this) {
            this.targetMcVersion = version;
            // Invalidate the cached verifier so the next gap-report header reflects the new
            // version. The field is volatile, so this null-write is visible to DCL readers.
            this.referenceVerifier = null;
        }
    }

    /**
     * Synthesize bridge methods in a mod class for overrides orphaned by MC
     * method renames. See
     * {@link com.retromod.core.bridge.BridgeMethodSynthesizer} for the full
     * explanation of when and why.
     *
     * <p>No-op if bridge synthesis is disabled via
     * {@code -Dretromod.synthesizeBridges=false} (the default) or the class
     * isn't in {@code modOwnClasses}.</p>
     *
     * @param classBytes    transformed class bytecode (post-iterative-loop)
     * @param modOwnClasses JVM internal names of classes defined by the mod
     * @return possibly-rewritten bytecode with bridge methods appended; same
     *         reference as input if nothing changed
     */
    public byte[] synthesizeBridges(byte[] classBytes, java.util.Set<String> modOwnClasses) {
        if (!BRIDGE_SYNTH_ENABLED) return classBytes;
        if (classBytes == null) return classBytes;
        return getBridgeSynthesizer().synthesize(classBytes, modOwnClasses);
    }

    /** @return {@code true} iff bridge synthesis is enabled via system property */
    public static boolean isBridgeSynthesisEnabled() {
        return BRIDGE_SYNTH_ENABLED;
    }

    /** Accessor for bridge-synth counters, mostly for tests and the gap report. */
    public com.retromod.core.bridge.BridgeMethodSynthesizer getBridgeSynthesizer() {
        com.retromod.core.bridge.BridgeMethodSynthesizer local = bridgeSynthesizer;
        if (local != null) return local;
        synchronized (this) {
            if (bridgeSynthesizer != null) return bridgeSynthesizer;
            // Build a rename-lookup from the method-redirect table. Only renames that preserve
            // owner + descriptor are safe to bridge (v1 scope; see BridgeMethodSynthesizer).
            // We return the new name here; the synthesizer's emit() descriptor-collision guard
            // catches descriptor mismatches before emission.
            var lookup = com.retromod.core.bridge.BridgeMethodSynthesizer.buildLookupFrom(
                    methodRedirects,
                    key -> com.retromod.core.bridge.BridgeMethodSynthesizer.renameKey(
                            key.owner(), key.name(), key.desc()),
                    target -> target.name());
            bridgeSynthesizer = new com.retromod.core.bridge.BridgeMethodSynthesizer(lookup);
            return bridgeSynthesizer;
        }
    }

    /**
     * Run all registered class-shape patterns against the given mod class.
     * Returns every match, which callers (primarily the CLI gap report)
     * accumulate into a {@link com.retromod.core.verify.VerificationReport}.
     *
     * <p>No-op if pattern matching is disabled via
     * {@code -Dretromod.matchPatterns=false} (the default).</p>
     */
    public java.util.List<com.retromod.core.pattern.PatternMatch> matchPatterns(
            byte[] classBytes, com.retromod.core.pattern.MatchContext ctx) {
        if (!PATTERN_MATCH_ENABLED) return java.util.Collections.emptyList();
        if (classBytes == null || ctx == null) return java.util.Collections.emptyList();
        return getClassShapeMatcher().matchAll(classBytes, ctx);
    }

    /** @return {@code true} iff pattern matching is enabled via system property */
    public static boolean isPatternMatchingEnabled() {
        return PATTERN_MATCH_ENABLED;
    }

    /** Lazily construct the default pattern library matcher. */
    private com.retromod.core.pattern.ClassShapeMatcher getClassShapeMatcher() {
        com.retromod.core.pattern.ClassShapeMatcher local = classShapeMatcher;
        if (local != null) return local;
        synchronized (this) {
            if (classShapeMatcher != null) return classShapeMatcher;
            classShapeMatcher = com.retromod.core.pattern.ClassShapeMatcher.defaultLibrary();
            return classShapeMatcher;
        }
    }

    /**
     * Remove duplicate methods from a class that were created by class remapping.
     *
     * When two different intermediary class names map to the same Mojang name,
     * methods whose descriptors differ only in those class names end up with
     * identical name+descriptor after remapping. The JVM rejects classes with
     * duplicate methods, so we must keep only one copy.
     *
     * Preference: keep the non-synthetic, non-bridge method when possible.
     */
    private byte[] deduplicateMethods(byte[] classBytes, String className) {
        ClassReader cr = new ClassReader(classBytes);

        // First pass: detect duplicates
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                String key = name + descriptor;
                if (!seen.add(key)) {
                    duplicates.add(key);
                }
                return null; // no need to visit method body
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (duplicates.isEmpty()) {
            return classBytes; // nothing to deduplicate
        }

        LOGGER.info("Deduplicating {} duplicate method(s) in {}: {}",
                duplicates.size(), className, duplicates);

        // Second pass: rebuild class, skipping duplicate methods.
        // For each duplicate signature, prefer the non-synthetic/non-bridge variant.
        // Track which duplicates we have already emitted.
        Set<String> emitted = new HashSet<>();
        // We need two sub-passes for duplicates: first collect access flags, then filter.
        // Collect: for each duplicate key, record the "best" access flags seen.
        Map<String, Integer> bestAccess = new HashMap<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                String key = name + descriptor;
                if (duplicates.contains(key)) {
                    Integer prev = bestAccess.get(key);
                    if (prev == null) {
                        bestAccess.put(key, access);
                    } else {
                        // Prefer non-synthetic, non-bridge
                        boolean prevIsSynthetic = (prev & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
                        boolean currIsSynthetic = (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
                        if (prevIsSynthetic && !currIsSynthetic) {
                            bestAccess.put(key, access);
                        }
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // Now rebuild, keeping only the first method whose access matches "best",
        // or the first occurrence if all have the same flags.
        ClassWriter dedupWriter = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES, currentJarClassBytesProvider());
        cr.accept(new ClassVisitor(Opcodes.ASM9, dedupWriter) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                String key = name + descriptor;
                if (duplicates.contains(key)) {
                    if (emitted.contains(key)) {
                        // Already emitted the preferred copy; skip this one
                        return null;
                    }
                    Integer best = bestAccess.get(key);
                    boolean thisIsBest = (best != null && best == access);
                    boolean thisIsSynthetic = (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
                    if (!thisIsBest && !emitted.contains(key)) {
                        // This is not the preferred copy, but we haven't emitted one yet.
                        // If there's a better one coming, skip; otherwise emit.
                        // We can't look ahead, so skip synthetic/bridge if the best isn't synthetic.
                        boolean bestIsSynthetic = best != null && (best & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
                        if (thisIsSynthetic && !bestIsSynthetic) {
                            return null; // skip, the better one will come
                        }
                    }
                    emitted.add(key);
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, ClassReader.EXPAND_FRAMES);

        return dedupWriter.toByteArray();
    }

    /**
     * ClassWriter that safely handles getCommonSuperClass in modded environments.
     * ASM's default implementation uses Class.forName() which fails when classes
     * are loaded by custom classloaders (as in Minecraft mod loaders).
     */
    private static class SafeClassWriter extends ClassWriter {

        private final Function<String, byte[]> jarClassBytes;

        public SafeClassWriter(int flags) {
            this(flags, null);
        }

        public SafeClassWriter(ClassReader classReader, int flags) {
            this(classReader, flags, null);
        }

        public SafeClassWriter(int flags, Function<String, byte[]> jarClassBytes) {
            super(flags);
            this.jarClassBytes = jarClassBytes;
        }

        public SafeClassWriter(ClassReader classReader, int flags,
                               Function<String, byte[]> jarClassBytes) {
            super(classReader, flags);
            this.jarClassBytes = jarClassBytes;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Exception | LinkageError e) {
                // Mod classes are often unavailable to Class.forName during transformation.
                // Read their hierarchy from the jar before using the broader fallback.
                String viaBytes = commonSuperViaBytes(type1, type2, jarClassBytes);
                if (viaBytes != null) {
                    return viaBytes;
                }
                return commonSuperFallback(type1, type2);
            }
        }
    }

    /**
     * Finds a common superclass from class bytes when the types are not on the classpath.
     * Returns {@code null} unless the shared ancestor will also exist at runtime. This prevents
     * pre-remap intermediary names from leaking into stack-map frames.
     */
    static String commonSuperViaBytes(String type1, String type2,
                                      Function<String, byte[]> provider) {
        if (type1 == null || type2 == null) {
            return null;
        }
        if (type1.equals(type2)) {
            return type1;
        }
        LinkedHashSet<String> firstChain = superChain(type1, provider);
        if (firstChain.isEmpty()) {
            return null;
        }

        String name = type2;
        for (int guard = 0; guard < 64 && name != null && !"java/lang/Object".equals(name); guard++) {
            if (firstChain.contains(name) && isRuntimeResolvable(name, provider)) {
                return name;
            }
            name = superOf(name, provider);
        }
        return null;
    }

    private static LinkedHashSet<String> superChain(String type, Function<String, byte[]> provider) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        String name = type;
        for (int guard = 0; guard < 64 && name != null && !"java/lang/Object".equals(name); guard++) {
            if (!chain.add(name)) {
                break;
            }
            name = superOf(name, provider);
        }
        return chain;
    }

    private static String superOf(String name, Function<String, byte[]> provider) {
        byte[] bytes = readClassBytes(name, provider);
        if (bytes == null) {
            return null;
        }
        try {
            return new ClassReader(bytes).getSuperName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] readClassBytes(String name, Function<String, byte[]> provider) {
        if (provider != null) {
            byte[] bytes = provider.apply(name);
            if (bytes != null) {
                return bytes;
            }
        }
        try (InputStream in = RetromodTransformer.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean isRuntimeResolvable(String name, Function<String, byte[]> provider) {
        if (name == null) {
            return false;
        }
        if (name.startsWith("java/")) {
            return true;
        }
        if (provider != null && provider.apply(name) != null) {
            return true;
        }
        try {
            Class.forName(name.replace('/', '.'), false, RetromodTransformer.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Tri-state result of {@link #classifyThrowable}. */
    private static final int THROWABLE_NO = 0, THROWABLE_YES = 1, THROWABLE_UNKNOWN = 2;

    /**
     * Fallback common-supertype for when ASM's {@code getCommonSuperClass} can't resolve a type
     * via {@code Class.forName} (it lives in a Fabric Jar-in-Jar, is a mod class, or was remapped
     * off the transform classpath).
     *
     * <p>Two opposite failure modes have to be threaded here, so the answer is not simply "return
     * Throwable if either looks like one":
     * <ul>
     *   <li><b>Merging two exception types must not collapse to {@code Object}</b> ({@code #94},
     *       forge-config-api-port {@code ConfigTracker}): a catch-handler join where the caught
     *       value is later consumed as a {@code Throwable} fails verification if ASM typed it as
     *       {@code Object}. So two Throwables merge to {@code Throwable}.
     *   <li><b>Merging a {@code Throwable} with a plain non-exception must not over-generalize to
     *       {@code Throwable}</b> (jade {@code CommonProxy.lambda$loadComplete$5}): a try body's
     *       {@code goto} and the catch's rethrow both fall into the same trailing {@code return},
     *       so ASM merges {@code ModMetadata} (try, local 3) with {@code Throwable} (catch, local
     *       3). The real common supertype is {@code Object}; typing it {@code Throwable} is too
     *       specific and a predecessor providing {@code ModMetadata} then fails the frame check
     *       (a {@code VerifyError} at load).
     * </ul>
     *
     * <p>The distinguisher is whether an operand is <em>provably</em> a Throwable (bytes read), a
     * <em>provable non</em>-Throwable (bytes read, isn't one), or <em>unresolvable</em> (a
     * JiJ/mod class we can't read). Only the unresolvable case is ambiguous, and there we lean on
     * Java's near-universal exception naming convention ({@code *Exception}/{@code *Error}): a
     * {@code JijParsingException} merged with a JDK exception stays {@code Throwable}; a
     * {@code ModMetadata} merged with a {@code Throwable} becomes {@code Object}.
     */
    static String commonSuperFallback(String type1, String type2) {
        int c1 = classifyThrowable(type1);
        int c2 = classifyThrowable(type2);
        // Both provably Throwables: their common supertype is at least Throwable (#94).
        if (c1 == THROWABLE_YES && c2 == THROWABLE_YES) {
            return "java/lang/Throwable";
        }
        // Exactly one is a provable Throwable; the other decides the merge.
        if (c1 == THROWABLE_YES || c2 == THROWABLE_YES) {
            int otherClass = (c1 == THROWABLE_YES) ? c2 : c1;
            String other = (c1 == THROWABLE_YES) ? type2 : type1;
            if (otherClass == THROWABLE_NO) {
                // Provably not an exception (e.g. ModMetadata): common supertype is Object.
                return "java/lang/Object";
            }
            // Unresolvable: guess from the name. Exception-named -> Throwable (#94), else Object.
            return looksLikeThrowable(other) ? "java/lang/Throwable" : "java/lang/Object";
        }
        // Neither is a provable Throwable. Two unresolvable, exception-named operands are almost
        // certainly a catch-handler join of two JiJ exceptions, so keep them Throwable (same
        // spirit as #94); anything else is Object.
        if (c1 == THROWABLE_UNKNOWN && c2 == THROWABLE_UNKNOWN
                && looksLikeThrowable(type1) && looksLikeThrowable(type2)) {
            return "java/lang/Throwable";
        }
        return "java/lang/Object";
    }

    /**
     * Tri-state: is {@code internalName} {@code java/lang/Throwable} or a subclass? Returns
     * {@link #THROWABLE_YES} / {@link #THROWABLE_NO} when the type (and its superclass chain) can
     * be read, and {@link #THROWABLE_UNKNOWN} when a link can't be resolved (a JiJ/mod class off
     * the transform classpath). The old boolean version folded UNKNOWN into NO, which made a
     * {@code Throwable}+unresolvable merge indistinguishable from a {@code Throwable}+known-non-
     * exception one; {@link #commonSuperFallback} needs to tell those apart.
     */
    private static int classifyThrowable(String internalName) {
        if (internalName == null) {
            return THROWABLE_NO;
        }
        // Classloading handles the common case (JDK exceptions and anything on the transform
        // classpath) and must come first: under Java 9+ modules getResourceAsStream doesn't
        // return JDK classes, so a byte-walk alone would miss java/io/IOException.
        try {
            return Throwable.class.isAssignableFrom(
                    Class.forName(internalName.replace('/', '.'), false,
                            RetromodTransformer.class.getClassLoader()))
                    ? THROWABLE_YES : THROWABLE_NO;
        } catch (Throwable ignored) {
            // not loadable via forName; fall through to a byte-level superclass walk
        }
        // Walk the superclass chain from class bytes, for types readable as a resource but not
        // loadable via forName. UNKNOWN if any link can't be read (a JiJ/mod class).
        String name = internalName;
        for (int guard = 0; guard < 64 && name != null && !"java/lang/Object".equals(name); guard++) {
            if ("java/lang/Throwable".equals(name)) {
                return THROWABLE_YES;
            }
            java.io.InputStream in = RetromodTransformer.class.getClassLoader()
                    .getResourceAsStream(name + ".class");
            if (in == null) {
                in = ClassLoader.getSystemResourceAsStream(name + ".class");
            }
            if (in == null) {
                return THROWABLE_UNKNOWN;
            }
            try (java.io.InputStream stream = in) {
                name = new ClassReader(stream).getSuperName();
            } catch (java.io.IOException | RuntimeException ex) {
                return THROWABLE_UNKNOWN;
            }
        }
        // Fully walked to Object (or a null super, i.e. the top) without hitting Throwable.
        if (name == null || "java/lang/Object".equals(name)) {
            return THROWABLE_NO;
        }
        return THROWABLE_UNKNOWN; // guard exhausted on a pathological chain
    }

    /**
     * Heuristic for an <em>unresolvable</em> type: does its simple name follow Java's exception
     * naming convention ({@code *Exception} / {@code *Error} / {@code *Throwable})? Only consulted
     * when the bytes can't be read, so a provable non-Throwable that happens to be so named is
     * already handled by {@link #classifyThrowable} returning {@code NO} first.
     */
    private static boolean looksLikeThrowable(String internalName) {
        if (internalName == null) {
            return false;
        }
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        int dollar = simple.lastIndexOf('$'); // Outer$SomeException -> SomeException
        if (dollar >= 0) {
            simple = simple.substring(dollar + 1);
        }
        return simple.endsWith("Exception") || simple.endsWith("Error") || simple.endsWith("Throwable");
    }

    /**
     * ASM ClassVisitor that rewrites method calls, field accesses,
     * and superclass references (for class-to-interface migrations).
     */
    // Classes that became interfaces in newer MC/DFU (e.g. DataResult). The JVM needs
    // INVOKEINTERFACE for interface methods and INVOKEVIRTUAL for class methods; old bytecode
    // uses INVOKEVIRTUAL for these, which would crash with IncompatibleClassChangeError, so we
    // fix the opcode. MutableComponent is deliberately not here: it's still a class (implements
    // Component), so INVOKEVIRTUAL on it is correct.
    private static final Set<String> KNOWN_INTERFACES = Set.of(
        "com/mojang/serialization/DataResult",
        "com/mojang/serialization/DynamicOps",
        "com/mojang/serialization/MapLike",
        "com/mojang/serialization/Lifecycle",
        "net/minecraft/core/Registry",  // Registry became an interface in newer MC
        // Component (the old yarn Text class) became an interface in 26.1; without this,
        // INVOKEVIRTUAL on .copy()/.append() after remap fails verification.
        "net/minecraft/network/chat/Component",
        // java.util.function.Supplier: the removed net.minecraft.util.LazyLoadedValue (a class)
        // is redirected to Supplier (an interface), so a mod's INVOKEVIRTUAL LazyLoadedValue.get()
        // becomes an owner=Supplier call that must be INVOKEINTERFACE. Only calls whose owner was
        // rewritten to Supplier are affected; a mod's own java.util.function.Supplier calls are
        // already INVOKEINTERFACE (unchanged by the class->interface fix).
        "java/util/function/Supplier"
    );

    // Types that became interfaces only on 26.x - consulted only on 26.x hosts. On 1.20.x-1.21.x
    // hosts these are still abstract CLASSES, and rewriting a valid INVOKEVIRTUAL/Methodref call
    // to interface form there corrupts working translations (review finding, empirically
    // reproduced: IntProvider.codec itf=true / getMinValue INVOKEINTERFACE on a class).
    // HeightProvider stayed a class even on 26.2 and stays out.
    private static final Set<String> KNOWN_INTERFACES_26X = Set.of(
        // Found on the headless 26.2 server: YungJigsawStructure's codec calls
        // IntProvider.codec(int,int) and died "must be InterfaceMethodref".
        "net/minecraft/util/valueproviders/IntProvider",
        "net/minecraft/util/valueproviders/FloatProvider"
    );

    /** Owner is an interface on the CURRENT host (static set + host-gated 26.x additions). */
    private static boolean isKnownInterface(String owner) {
        return KNOWN_INTERFACES.contains(owner)
                || (KNOWN_INTERFACES_26X.contains(owner)
                    && RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION));
    }

    private class RetromodClassVisitor extends ClassVisitor {

        /** Whether this thread's pass emitted frame-invalidating rewrites (see the field). */
        boolean structurallyChangedFrames() {
            return frameInvalidatingRewrite.get();
        }

        // Bridge adapter generator for this class; handles method signature changes
        // (e.g., mouseClicked(double,double,int) -> mouseClicked(MouseButtonEvent,boolean)).
        // Reset for each class in visit(). Bounded by BridgeAdapterGenerator.BRIDGES size.
        private final BridgeAdapterGenerator bridgeGenerator = new BridgeAdapterGenerator();

        // Track the current class name for bridge generation
        private String currentClassName;

        public RetromodClassVisitor(int api, ClassVisitor classVisitor) {
            super(api, classVisitor);
        }

        // Stored for fixing invokespecial on indirect interface methods.
        // currentSuperName = EFFECTIVE direct superclass (post-rebase); used by the
        // non-<init> super-call fixup so it targets the real direct super.
        // currentSuperNameOriginal = the super as written in the source bytecode; used
        // to recognize a super() <init> call that needs its owner rebased (#70).
        private String currentSuperName = null;
        private String currentSuperNameOriginal = null;
        private Set<String> currentDirectInterfaces = Set.of();

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            this.currentClassName = name;
            this.currentSuperName = superName;            // effective (overwritten below if rebased)
            this.currentSuperNameOriginal = superName;    // as written in source
            this.currentDirectInterfaces = interfaces != null
                ? Set.of(interfaces) : Set.of();
            // Reset bridge generator for each new class
            bridgeGenerator.reset();

            // Filter out self-referential interfaces (caused by class remapping)
            // E.g., ConfigScreenFactory extends io.github.prospector...ConfigScreenFactory
            // After remap, both resolve to com.terraformersmc...ConfigScreenFactory
            if (interfaces != null && interfaces.length > 0) {
                String[] filtered = java.util.Arrays.stream(interfaces)
                    .filter(iface -> !iface.equals(name))
                    .toArray(String[]::new);
                if (filtered.length < interfaces.length) {
                    LOGGER.debug("Removed self-referential interface from {}", name);
                    interfaces = filtered;
                }
            }

            // Check if superclass needs to be rewritten (class-to-interface polyfill)
            if (superName != null && !superclassRedirects.isEmpty()) {
                SuperclassRedirect redirect = superclassRedirects.get(superName);
                if (redirect != null) {
                    LOGGER.debug("Rewriting superclass of {} from {} to {}",
                            name, superName, redirect.newSuperclass());
                    String newSuper = redirect.newSuperclass();
                    this.currentSuperName = newSuper; // effective direct super is the new base
                    String[] newInterfaces = mergeInterfaces(interfaces, redirect.addInterfaces());
                    super.visit(version, access, name, signature, newSuper, newInterfaces);
                    return;
                }
            }
            super.visit(version, access, name, signature, superName, interfaces);
        }

        private String[] mergeInterfaces(String[] existing, String[] additional) {
            if (additional == null || additional.length == 0) {
                return existing;
            }
            Set<String> merged = new LinkedHashSet<>();
            if (existing != null) {
                Collections.addAll(merged, existing);
            }
            Collections.addAll(merged, additional);
            return merged.toArray(new String[0]);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            // After class remapping, some inner/outer class pairs may collapse to the same name.
            // For example: class_5253$class_5254 → ARGB, class_5253 → ARGB
            // This creates an invalid "class is both outer and inner" entry.
            // Check the resolved (post-remap) names and skip self-referential entries.
            if (outerName != null) {
                String resolvedName = classRedirects.getOrDefault(name, name);
                String resolvedOuter = classRedirects.getOrDefault(outerName, outerName);
                if (resolvedName.equals(resolvedOuter)) {
                    LOGGER.debug("Skipping self-referential InnerClass entry: {} of {} (both resolve to {})",
                            name, outerName, resolvedName);
                    return;
                }
            }
            super.visitInnerClass(name, outerName, innerName, access);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {

            // BridgeAdapterGenerator disabled: causes VerifyErrors by renaming methods
            // that already have the correct signature. Needs more work before enabling.
            // TODO: Only apply bridges when the method ACTUALLY has the old descriptor

            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Only wrap if we have redirects that act inside a method body. superclassRedirects
            // is included because a class->class rebase rewrites super(...) <init> owners in the
            // body (#70); superCtorRedirects likewise because it rewrites the super()/this()
            // descriptor there (the 1.12.2 Block(Material)->Block(Properties) bridge). Omitting
            // superCtorRedirects was a latent bug: a redirect set with only super-ctor + class
            // redirects skipped the wrapper and never applied the super-ctor rewrite. It was
            // masked whenever the same shim also registered a method redirect, and surfaced once
            // the phantom-target sweep dropped those (Forge_1_12_2_to_1_13_2's FlatteningShim).
            if (methodRedirects.isEmpty() && fieldRedirects.isEmpty() && constructorRedirects.isEmpty()
                    && fieldAccessorRedirects.isEmpty() && superclassRedirects.isEmpty()
                    && superCtorRedirects.isEmpty()
                    && neutralizedMethods.isEmpty() && staticFieldAccessors.isEmpty()
                    && argDropRedirects.isEmpty()
                    && fieldHopAccessors.isEmpty() && fieldStaticBridges.isEmpty()
                    && convertingRedirects.isEmpty() && singletonRedirects.isEmpty()) {
                return mv;
            }
            return new RetromodMethodVisitor(api, mv, currentClassName, currentSuperName,
                    currentSuperNameOriginal, currentDirectInterfaces);
        }

        @Override
        public void visitEnd() {
            // BridgeAdapterGenerator disabled; see TODO in visitMethod
            super.visitEnd();
        }
    }
    

    /**
     * Resolve class references in a descriptor through classRedirects.
     * E.g., "(Lclass_3702$class_3703;)V" → "(Lcom/mojang/blaze3d/platform/InputConstants$Type;)V"
     */
    private String resolveDescriptor(String descriptor) {
        if (classRedirects.isEmpty()) return descriptor;
        // Quick check: does descriptor contain any 'L' type references?
        if (descriptor.indexOf('L') < 0) return descriptor;

        StringBuilder sb = new StringBuilder(descriptor.length());
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                // Find the closing ';'
                int end = descriptor.indexOf(';', i);
                if (end < 0) break;
                String className = descriptor.substring(i + 1, end);
                String resolved = classRedirects.getOrDefault(className, className);
                sb.append('L').append(resolved).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Constructor-to-factory rewriting pass, placed UPSTREAM of the {@link ClassRemapper}.
     *
     * <p>It must see PRE-remap owner names: when several old classes class-redirect onto ONE
     * new class but their constructors map to DIFFERENT factories (the pre-1.19 Text bridge
     * retypes both {@code class_2588} TranslatableText and {@code class_2585} LiteralText to
     * {@code class_5250} MutableText, but their {@code <init>(String)}s go to
     * {@code Text.translatable} vs {@code Text.literal}), only the pre-remap owner keeps the
     * two {@code <init>}s distinct. Downstream of the remapper they collapse into one key.
     * Registrations keyed on POST-remap names (the 26.1 Identifier redirects) still match:
     * owners and descriptors are resolved through the remapper before lookup.
     *
     * <p>In JVM bytecode, {@code new Foo(args)} compiles to:
     * <pre>
     *   NEW Foo          // allocate uninitialized object
     *   DUP              // duplicate reference (one for <init>, one stays on stack)
     *   [push args]      // push constructor arguments
     *   INVOKESPECIAL Foo.<init>(args)V  // call constructor
     * </pre>
     * To redirect to a static factory we suppress NEW+DUP, let the args flow, and replace the
     * INVOKESPECIAL with INVOKESTATIC. The suppression state is a STACK, not a single slot:
     * the args of a deferred {@code new} can themselves contain redirect-eligible {@code new}s,
     * and javac brackets them properly (inner {@code <init>} always precedes the outer one).
     * Crucially, an inner PLAIN NEW (no redirect) must not flush the outer entry: the args may
     * contain branches (ternaries), and emitting the buffered NEW+DUP mid-expression puts them
     * on ONE path only, so the paths reach the convergent {@code <init>} with different stack
     * heights (ASM Frame.merge AIOOBE; hit live on Collective's DuskConfig, snapshot.8). The
     * only branch-safe flush point is the {@code <init>} site itself, where every path has
     * converged with the args on the stack.
     *
     * <p>Known gap: constructor REFERENCES ({@code TranslatableText::new}) are rewritten by
     * {@link RetromodMethodVisitor}'s indy handling downstream, which sees post-remap names;
     * a pre-remap-keyed registration won't match there. No mod in the acceptance corpus uses
     * the removed Text constructors as method refs.</p>
     */
    private class CtorRedirectPrePass extends ClassVisitor {

        /** Resolves pre-remap names to what the downstream remapper will emit; nullable. */
        private final org.objectweb.asm.commons.Remapper resolver;

        CtorRedirectPrePass(int api, ClassVisitor cv,
                org.objectweb.asm.commons.Remapper resolver) {
            super(api, cv);
            this.resolver = resolver;
        }

        private String resolveType(String type) {
            return resolver != null ? resolver.mapType(type)
                    : classRedirects.getOrDefault(type, type);
        }

        private String resolveDesc(String desc) {
            return resolver != null ? resolver.mapMethodDesc(desc) : resolveDescriptor(desc);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }
            return new PrePassMethodVisitor(api, mv);
        }

        /** One deferred NEW awaiting its {@code <init>} (redirect-eligible owner). */
        private static final class PendingNew {
            final String type;
            boolean dupSeen;
            PendingNew(String type) { this.type = type; }
        }

        private class PrePassMethodVisitor extends MethodVisitor {

            private final java.util.ArrayDeque<PendingNew> pendingNews =
                    new java.util.ArrayDeque<>();

            PrePassMethodVisitor(int api, MethodVisitor mv) {
                super(api, mv);
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                if (opcode == Opcodes.NEW
                        && (constructorRedirectOwners.contains(type)
                            || constructorRedirectOwners.contains(resolveType(type)))) {
                    pendingNews.push(new PendingNew(type));
                    return;
                }
                // A plain NEW inside a deferred one's args (nested constructor, e.g. the
                // StringBuilder in `new TranslatableText("" + x)`) just emits through; the
                // outer entry stays deferred until ITS <init>. Never flush it here: the args
                // may hold branches, and a mid-expression flush lands on one path only
                // (frame corruption). Other TypeInsns (CHECKCAST, ...) likewise pass through.
                super.visitTypeInsn(opcode, type);
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.DUP && !pendingNews.isEmpty()
                        && !pendingNews.peek().dupSeen) {
                    // javac emits DUP directly after NEW, so the first DUP while the top
                    // entry is un-dup'd is that entry's dup. Any later DUP (e.g. after a
                    // plain nested NEW) passes through below.
                    pendingNews.peek().dupSeen = true;
                    return;
                }
                super.visitInsn(opcode);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                    String descriptor, boolean isInterface) {
                if (opcode != Opcodes.INVOKESPECIAL || !"<init>".equals(name)
                        || pendingNews.isEmpty()) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }
                PendingNew top = pendingNews.peek();
                String resolvedOwner = resolveType(owner);
                if (!owner.equals(top.type) && !resolvedOwner.equals(resolveType(top.type))) {
                    // An <init> for some OTHER class while a NEW is deferred: a nested plain
                    // constructor inside the deferred args (its NEW emitted through above).
                    // Pass it through; the deferred entry waits for its own <init>.
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }
                // This <init> completes the deferred NEW on top of the stack. Try the lookup
                // in post-remap terms first (how the 26.1 shims register), then fully
                // pre-remap (how the Text bridge must register, see the class javadoc).
                String resolvedDesc = resolveDesc(descriptor);
                FactoryTarget factory = constructorRedirects.get(
                        new ConstructorKey(resolvedOwner, resolvedDesc));
                if (factory == null && !resolvedDesc.equals(descriptor)) {
                    factory = constructorRedirects.get(
                            new ConstructorKey(resolvedOwner, descriptor));
                }
                if (factory == null && !resolvedOwner.equals(owner)) {
                    factory = constructorRedirects.get(new ConstructorKey(owner, descriptor));
                }
                if (factory != null) {
                    // The pending NEW and DUP are removed when the redirect is registered.
                    String originalClass = pendingNews.pop().type;
                    markFrameInvalidatingRewrite();
                    LOGGER.debug("Redirected constructor {}{} to {}.{}{}",
                            owner, descriptor, factory.factoryClass(), factory.factoryMethod(),
                            factory.factoryDesc());
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, factory.factoryClass(),
                            factory.factoryMethod(), factory.factoryDesc(),
                            factory.factoryIsInterface());
                    // A factory returning Object needs a cast to keep the verifier's stack type
                    // precise. The later remap updates this original class name.
                    String factoryReturnType = factory.factoryDesc().substring(
                            factory.factoryDesc().lastIndexOf(')') + 1);
                    if (factoryReturnType.equals("Ljava/lang/Object;")) {
                        super.visitTypeInsn(Opcodes.CHECKCAST, originalClass);
                    }
                    return;
                }
                // Owner matches but no redirect for THIS descriptor: emit the deferred
                // NEW+DUP below the already-emitted args (branch-safe here, at the
                // convergence point), then the original call.
                flushPendingNewBeforeArgs(pendingNews.pop(), descriptor);
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            /**
         * Flush a deferred NEW+DUP when constructor args are already on the stack.
         *
         * <p>This handles the case where a class has SOME constructor redirects registered
         * (causing NEW to be deferred) but the actual constructor descriptor doesn't match
         * any redirect. The args were already emitted during the deferral period, so they're
         * on the stack. We need NEW+DUP to appear BELOW the args for correct JVM verification.</p>
         *
         * <p>Strategy: store args to temp locals (reverse order), emit NEW+DUP,
         * reload args from temp locals (forward order). COMPUTE_FRAMES handles max locals.</p>
         *
         * <p>Only ever called AT the {@code <init>} site: that is the point where every
         * branch inside the arg expressions has converged with the args on the stack, so the
         * insertion is consistent across paths (unlike a mid-expression flush).</p>
         *
         * @param entry the deferred NEW being materialized (already popped by the caller)
         * @param constructorDesc the constructor descriptor, e.g. "(Ljava/lang/String;I)V"
         */
        private void flushPendingNewBeforeArgs(PendingNew entry, String constructorDesc) {
            markFrameInvalidatingRewrite();

            // Parse parameter types from the descriptor
            Type[] paramTypes = Type.getArgumentTypes(constructorDesc);
            if (paramTypes.length == 0) {
                // No args on stack; just emit the deferred NEW (+DUP)
                super.visitTypeInsn(Opcodes.NEW, entry.type);
                if (entry.dupSeen) {
                    super.visitInsn(Opcodes.DUP);
                }
                return;
            }

            // Use high base slot to avoid conflicts with existing locals.
            // COMPUTE_FRAMES / COMPUTE_MAXS will adjust maxLocals automatically.
            int tempSlotBase = 200;
            int slot = tempSlotBase;

            // Allocate slots for each parameter (some types use 2 slots)
            int[] paramSlots = new int[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                paramSlots[i] = slot;
                slot += paramTypes[i].getSize(); // 1 for int/float/object, 2 for long/double
            }

            // Store args from stack to temp locals (reverse order, top of stack first)
            for (int i = paramTypes.length - 1; i >= 0; i--) {
                super.visitVarInsn(paramTypes[i].getOpcode(Opcodes.ISTORE), paramSlots[i]);
            }

            // Now the stack is clear of args; emit the deferred NEW + DUP
            super.visitTypeInsn(Opcodes.NEW, entry.type);
            if (entry.dupSeen) {
                super.visitInsn(Opcodes.DUP);
            }

            // Reload args from temp locals (forward order)
            for (int i = 0; i < paramTypes.length; i++) {
                super.visitVarInsn(paramTypes[i].getOpcode(Opcodes.ILOAD), paramSlots[i]);
            }

            // Stack is now: [..., uninit, uninit, arg1, arg2, ..., argN]
            // which is the correct order for INVOKESPECIAL <init>
            }
        }
    }

    /**
     * ASM MethodVisitor that rewrites individual method invocations.
     *
     * <p><b>Constructor→factory pattern:</b> In JVM bytecode, {@code new Foo(args)} compiles to:
     * <pre>
     *   NEW Foo          // allocate uninitialized object
     *   DUP              // duplicate reference (one for <init>, one stays on stack)
     *   [push args]      // push constructor arguments
     *   INVOKESPECIAL Foo.<init>(args)V  // call constructor
     * </pre>
     * To redirect to a static factory ({@code Foo.create(args)}), we need to:
     * <ol>
     *   <li>Suppress the NEW and DUP instructions</li>
     *   <li>Let the argument-pushing instructions pass through</li>
     *   <li>Replace the INVOKESPECIAL with INVOKESTATIC to the factory method</li>
     * </ol>
     * We achieve this by "buffering" the NEW/DUP when we see a class with constructor
     * redirects, then deciding at the INVOKESPECIAL whether to emit them or replace them.
     * If no redirect matches the specific descriptor, we flush (emit) the buffered NEW+DUP.
     *
     * <p><b>Performance:</b> Uses fast owner lookup ({@code methodRedirectOwners}) to skip
     * expensive ConcurrentHashMap lookups for method calls to classes with no redirects.</p>
     */
    private class RetromodMethodVisitor extends MethodVisitor {

        /**
         * Event type reified by the Consumer LambdaMetafactory indy seen as the LAST instruction,
         * armed for an indy-typed call rewrite (see registerIndyTypedCallRewrite). Any other
         * stack-affecting instruction clears it: javac always emits the indy directly before the
         * call when a lambda/method-ref is the trailing argument, so adjacency is the guard
         * against attaching a stale type to an unrelated Consumer-taking call.
         */
        private String pendingIndyConsumerType = null;

        // Inherited from enclosing RetromodClassVisitor for invokespecial fixups
        private final String classOwnName;
        private final String classSuperName;          // effective (post-rebase) direct super
        private final String classSuperNameOriginal;  // super as written in source (for #70 rebase match)
        private final Set<String> classDirectInterfaces;

        public RetromodMethodVisitor(int api, MethodVisitor methodVisitor,
                String className, String superName, String superNameOriginal,
                Set<String> directInterfaces) {
            super(api, methodVisitor);
            this.classOwnName = className;
            this.classSuperName = superName;
            this.classSuperNameOriginal = superNameOriginal;
            this.classDirectInterfaces = directInterfaces;
        }

        /**
         * Push default values for parameter types described in a JVM descriptor fragment.
         * Used when a constructor gains new parameters; we insert defaults for them.
         * E.g., "Ljava/lang/String;[Ljava/lang/Object;" → push ACONST_NULL, then empty Object[]
         */
        private void pushDefaultsForDescriptor(String paramFragment) {
            int i = 0;
            while (i < paramFragment.length()) {
                char c = paramFragment.charAt(i);
                switch (c) {
                    case 'L' -> {
                        int end = paramFragment.indexOf(';', i);
                        if (end < 0) return; // malformed (no ';'): indexOf+1 would reset i to 0 and spin forever emitting ACONST_NULL
                        pushDefaultObject(paramFragment.substring(i + 1, end));
                        i = end + 1;
                    }
                    case '[' -> {
                        // Array type → push empty array
                        i++; // skip '['
                        if (i < paramFragment.length() && paramFragment.charAt(i) == 'L') {
                            // Object array: push ICONST_0 + ANEWARRAY
                            int end = paramFragment.indexOf(';', i);
                            if (end < 0) return; // malformed
                            String elementType = paramFragment.substring(i + 1, end);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitTypeInsn(Opcodes.ANEWARRAY, elementType);
                            i = end + 1;
                        } else {
                            // Primitive array: push ICONST_0 + NEWARRAY
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT); // default to int[]
                            i++;
                        }
                    }
                    case 'I' -> { super.visitInsn(Opcodes.ICONST_0); i++; }
                    case 'J' -> { super.visitInsn(Opcodes.LCONST_0); i++; }
                    case 'F' -> { super.visitInsn(Opcodes.FCONST_0); i++; }
                    case 'D' -> { super.visitInsn(Opcodes.DCONST_0); i++; }
                    case 'Z' -> { super.visitInsn(Opcodes.ICONST_0); i++; } // false
                    case 'B', 'C', 'S' -> { super.visitInsn(Opcodes.ICONST_0); i++; }
                    default -> i++; // skip unknown
                }
            }
        }

        /**
         * Push a default value for an object-typed parameter. Most types get {@code null}, but a
         * Block/Item {@code Properties} can't be null (the constructor dereferences it), so we
         * construct a real default: {@code new Item.Properties()} / {@code BlockBehaviour.Properties.of()}.
         * Used by the 1.12.2 super-constructor bridge, where {@code super(Material)} / {@code super()}
         * become {@code super(Properties)} (26.1 made Block/Item Properties-constructed). The block/item
         * gets vanilla-default properties; behaviour-specific settings are lost but it constructs.
         */
        private void pushDefaultObject(String internalType) {
            switch (internalType) {
                case "net/minecraft/world/item/Item$Properties" -> {
                    super.visitTypeInsn(Opcodes.NEW, internalType);
                    super.visitInsn(Opcodes.DUP);
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, internalType, "<init>", "()V", false);
                }
                case "net/minecraft/world/level/block/state/BlockBehaviour$Properties" ->
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, internalType, "of",
                            "()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;", false);
                default -> super.visitInsn(Opcodes.ACONST_NULL);
            }
        }

        /** Pop the argument values of a descriptor off the stack (the implicit {@code this} is left). */
        private void popArgsForDescriptor(String desc) {
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
            for (int i = args.length - 1; i >= 0; i--) {
                super.visitInsn(args[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
            }
        }

        /**
         * Neutralize a call to a removed method: emit no call, instead discard the arguments
         * (and the receiver for instance calls) and push a default for the return type, turning
         * a would-be {@code NoSuchMethodError} into a no-op so the mod loads (the call's side
         * effect is lost). Applied only to methods absent on the host, registered via
         * {@link #registerRemovedMethodNeutralize}.
         *
         * <p>Plain pops/pushes in a single basic block, so no stack-map frames are introduced
         * and {@code COMPUTE_MAXS} absorbs the stack delta.
         */
        private void neutralizeCall(int opcode, String descriptor) {
            markFrameInvalidatingRewrite();
            Type methodType = Type.getMethodType(descriptor);
            Type[] argTypes = methodType.getArgumentTypes();
            // Pop arguments off the stack, last-pushed first.
            for (int i = argTypes.length - 1; i >= 0; i--) {
                super.visitInsn(argTypes[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
            }
            // Instance calls (virtual/special/interface) also have the receiver
            // below the args; static calls don't. The objectref is always 1 slot.
            if (opcode != Opcodes.INVOKESTATIC) {
                super.visitInsn(Opcodes.POP);
            }
            // Push a default for the return type so consumers of the result (and
            // the verifier) stay balanced.
            Type ret = methodType.getReturnType();
            switch (ret.getSort()) {
                case Type.VOID -> { /* nothing to push */ }
                case Type.OBJECT, Type.ARRAY -> super.visitInsn(Opcodes.ACONST_NULL);
                case Type.LONG -> super.visitInsn(Opcodes.LCONST_0);
                case Type.FLOAT -> super.visitInsn(Opcodes.FCONST_0);
                case Type.DOUBLE -> super.visitInsn(Opcodes.DCONST_0);
                default -> super.visitInsn(Opcodes.ICONST_0); // int/boolean/byte/char/short
            }
        }

        /**
         * Fix INVOKEVIRTUAL → INVOKEINTERFACE for classes that became interfaces.
         * E.g., com.mojang.serialization.DataResult was a class, now an interface in newer DFU.
         */
        private int fixClassToInterfaceOpcode(int opcode, String owner) {
            if (opcode == Opcodes.INVOKEINTERFACE && classShimTargets.contains(owner)) {
                return Opcodes.INVOKEVIRTUAL;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL && isKnownInterface(owner)) {
                return Opcodes.INVOKEINTERFACE;
            }
            return opcode;
        }

        /** Return the constant-pool owner kind after a redirect or class remap. */
        private boolean isInterfaceTarget(int opcode, int originalOpcode, String owner,
                boolean originalIsInterface) {
            if (classShimTargets.contains(owner)) {
                return false;
            }
            return opcode == Opcodes.INVOKEINTERFACE
                    || originalIsInterface
                    || interfaceSyntheticTargets.contains(owner)
                    || (originalOpcode == Opcodes.INVOKESTATIC && isKnownInterface(owner));
        }

        /**
         * Emit a method instruction, applying the invokespecial-on-non-direct-supertype
         * fixup if needed.
         *
         * <p>The JVM verifier requires that {@code INVOKESPECIAL} on a non-{@code <init>}
         * method target a <i>direct</i> supertype of the calling class: either the
         * direct superclass, a directly-declared superinterface, or {@code this} class
         * itself (for private methods). Anything else fails verification with:
         *
         * <pre>
         *   Bad invokespecial instruction:
         *   interface method to invoke is not in a direct superinterface.
         * </pre>
         *
         * <p>That can happen after a method redirect changes the owner. Example:
         * ModMenu's {@code ModListWidget.keyPressed(III)Z} was a normal super-call to
         * its concrete superclass. We redirected it to the new {@code keyPressed(KeyEvent)Z}
         * signature whose owner is now {@code GuiEventListener} (an interface that
         * {@code ModListWidget} only implements <i>indirectly</i>, through several
         * superclasses). The resulting INVOKESPECIAL fails verification.
         *
         * <p>Fix: rewrite the owner to {@link #classSuperName} so the JVM does a normal
         * class-hierarchy method resolution starting from the direct superclass. That walk
         * finds the inherited default method the same way a regular super-call does, but
         * without the direct-superinterface restriction.
         *
         * <p>Constructor calls ({@code <init>}), private-method calls on {@code this},
         * and legitimate super/interface calls pass through unchanged.
         */
        private void emitMethodInsn(int opcode, String owner, String name,
                String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL
                    && !"<init>".equals(name)
                    && classSuperName != null
                    && !owner.equals(classSuperName)
                    && !owner.equals(classOwnName)
                    && !classDirectInterfaces.contains(owner)) {
                LOGGER.debug("Fixing invokespecial on non-direct supertype: {}.{}{} → super {}",
                        owner, name, descriptor, classSuperName);
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, classSuperName, name,
                        descriptor, false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            pendingIndyConsumerType = null;
            // NEW/<init> constructor redirects are handled by CtorRedirectPrePass UPSTREAM of
            // the ClassRemapper (it must see pre-remap owner names; see that class's javadoc).
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(int opcode) {
            pendingIndyConsumerType = null;
            super.visitInsn(opcode);
        }

        /**
         * Rewrites a constructor reference ({@code X::new}) whose constructor has a
         * registered constructor→factory redirect, so it points at the static factory.
         *
         * <p>{@code X::new} compiles to an {@code invokedynamic} whose implementation method
         * handle is {@code H_NEWINVOKESPECIAL X.<init>(args)V}; LambdaMetafactory adapts that
         * to the SAM {@code (args)->X}. Swapping it for {@code H_INVOKESTATIC factory(args)X}
         * yields the identical SAM, so the lambda links against the factory instead of the
         * removed constructor. This is the reference-form analogue of the direct
         * {@code new X(...)} rewrite in {@link #visitMethodInsn} (which only sees
         * {@code NEW}+{@code INVOKESPECIAL}, not the {@code invokedynamic} handle). Without it,
         * codecs that capture {@code ChunkPos::new} (Resourceful Lib's {@code ExtraByteCodecs})
         * still link to the deleted {@code ChunkPos(long)} ctor on 26.x. Same Mojang-named
         * lookup as the direct path, so it's effective wherever that one is.</p>
         */
        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            if (!constructorRedirects.isEmpty() && bootstrapMethodArguments != null) {
                for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                    if (!(bootstrapMethodArguments[i] instanceof org.objectweb.asm.Handle h)) continue;
                    if (h.getTag() != Opcodes.H_NEWINVOKESPECIAL || !"<init>".equals(h.getName())) continue;
                    String resolvedOwner = classRedirects.getOrDefault(h.getOwner(), h.getOwner());
                    String resolvedDesc = resolveDescriptor(h.getDesc());
                    FactoryTarget factory = constructorRedirects.get(new ConstructorKey(resolvedOwner, resolvedDesc));
                    if (factory == null && !resolvedDesc.equals(h.getDesc())) {
                        factory = constructorRedirects.get(new ConstructorKey(resolvedOwner, h.getDesc()));
                    }
                    if (factory != null) {
                        bootstrapMethodArguments[i] = new org.objectweb.asm.Handle(
                                Opcodes.H_INVOKESTATIC,
                                factory.factoryClass(), factory.factoryMethod(), factory.factoryDesc(),
                                factory.factoryIsInterface());
                        LOGGER.debug("Redirected constructor reference {}::new to {}.{}{}",
                                h.getOwner(), factory.factoryClass(), factory.factoryMethod(), factory.factoryDesc());
                    }
                }
            }

            // Method references store their targets in invokedynamic arguments, so visitMethodInsn
            // never sees them. A pure target or descriptor change can be applied to the handle.
            // Redirects that need inserted conversion bytecode are left alone.
            if (!convertingRedirects.isEmpty() && bootstrapMethodArguments != null) {
                for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                    if (!(bootstrapMethodArguments[i] instanceof org.objectweb.asm.Handle h)) continue;
                    String resolvedOwner = classRedirects.getOrDefault(h.getOwner(), h.getOwner());
                    String resolvedDesc = resolveDescriptor(h.getDesc());
                    ConvertingTarget conv = convertingRedirects.get(
                            new MethodKey(resolvedOwner, h.getName(), resolvedDesc));
                    if (conv == null && !resolvedDesc.equals(h.getDesc())) {
                        conv = convertingRedirects.get(new MethodKey(resolvedOwner, h.getName(), h.getDesc()));
                    }
                    if (conv == null || conv.argConvOpcode() != 0 || conv.retAdaptOpcode() != 0
                            || conv.unwrapOwner() != null) continue;
                    // Editing a handle cannot change a constructor reference into a factory call.
                    if ("<init>".equals(h.getName()) != "<init>".equals(conv.name())) continue;
                    bootstrapMethodArguments[i] = new org.objectweb.asm.Handle(
                            h.getTag(), conv.owner(), conv.name(), conv.desc(), h.isInterface());
                    LOGGER.debug("Redirected method reference {}.{}{} to {}.{}{}",
                            h.getOwner(), h.getName(), h.getDesc(), conv.owner(), conv.name(), conv.desc());
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);

            // Indy-typed rewrite arming: a LambdaMetafactory indy producing a Consumer carries the
            // event type in its instantiatedMethodType (bsm arg 2). Remember it for the call that
            // IMMEDIATELY follows (any other stack-affecting instruction clears it), so an
            // addListener(lambda) call can be retargeted with the type as a Class constant.
            pendingIndyConsumerType = null;
            if (!indyTypedRewrites.isEmpty()
                    && descriptor.endsWith(")Ljava/util/function/Consumer;")
                    && "java/lang/invoke/LambdaMetafactory".equals(bootstrapMethodHandle.getOwner())
                    && bootstrapMethodArguments != null && bootstrapMethodArguments.length >= 3
                    && bootstrapMethodArguments[2] instanceof org.objectweb.asm.Type t
                    && t.getSort() == org.objectweb.asm.Type.METHOD
                    && t.getArgumentTypes().length == 1
                    && t.getArgumentTypes()[0].getSort() == org.objectweb.asm.Type.OBJECT) {
                pendingIndyConsumerType = t.getArgumentTypes()[0].getInternalName();
            }
        }

        /**
         * Intercepts every method call instruction in the bytecode.
         *
         * <p>Processing order:</p>
         * <ol>
         *   <li><b>Constructor→factory:</b> If we have a pending NEW and this is an
         *       INVOKESPECIAL &lt;init&gt;, check if the constructor should be replaced
         *       with a static factory method (e.g., {@code new Identifier(s)} ->
         *       {@code Identifier.parse(s)}). The NEW+DUP are suppressed and replaced
         *       with INVOKESTATIC.</li>
         *   <li><b>Super constructor changes:</b> If this is a super() call (INVOKESPECIAL
         *       &lt;init&gt; without pending NEW), check if the parent class gained new
         *       required parameters. Push extra args before the call.</li>
         *   <li><b>Fast-path skip:</b> If the call's owner class has no registered method
         *       redirects, pass through immediately (only fixing class→interface opcode).</li>
         *   <li><b>Method redirect lookup:</b> Match against (owner, name, descriptor).
         *       If not found, try resolving intermediary class names in the descriptor
         *       since redirects are registered with Mojang names but bytecode may still
         *       have intermediary names in descriptors.</li>
         *   <li><b>Devirtualize:</b> If the redirect has devirtualize=true, change
         *       INVOKEVIRTUAL to INVOKESTATIC (the receiver becomes the first arg).</li>
         * </ol>
         */
        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                String descriptor, boolean isInterface) {

            // Consume the armed indy Consumer type (whatever happens below, a method call ends
            // the adjacency window). If this exact call has an indy-typed rewrite, retarget it
            // with the reified event type appended as a Class constant.
            final String indyConsumerType = pendingIndyConsumerType;
            pendingIndyConsumerType = null;

            if (indyConsumerType != null && !indyTypedRewrites.isEmpty()
                    && indyTypedOwners.contains(owner)) {
                MethodTarget typed = indyTypedRewrites.get(new MethodKey(owner, name, descriptor));
                if (typed != null) {
                    LOGGER.debug("Indy-typed rewrite {}.{}{} -> {}.{}{} (event type {})",
                            owner, name, descriptor, typed.owner, typed.name, typed.desc,
                            indyConsumerType);
                    markFrameInvalidatingRewrite();
                    super.visitLdcInsn(org.objectweb.asm.Type.getObjectType(indyConsumerType));
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, typed.owner, typed.name,
                            typed.desc, false);
                    return;
                }
            }

            // Check for super() constructor descriptor changes. Matches both genuine
            // super()/this() calls AND plain `new X(...)` <init>s (dual use, e.g. the 1.12.2
            // Block(Material) bridge); factory-redirected <init>s were already consumed by
            // CtorRedirectPrePass and never reach this visitor.
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)
                    && !superCtorRedirects.isEmpty()) {
                String resolvedOwner = classRedirects.getOrDefault(owner, owner);
                String resolvedDesc = resolveDescriptor(descriptor);
                ConstructorKey skey = new ConstructorKey(resolvedOwner, resolvedDesc);
                SuperCtorRedirect scr = superCtorRedirects.get(skey);
                if (scr == null) {
                    skey = new ConstructorKey(resolvedOwner, descriptor);
                    scr = superCtorRedirects.get(skey);
                }
                if (scr != null) {
                    if ("__INSERT_DEFAULTS__".equals(scr.extraFieldOwner())) {
                        // Insert default values for new parameters.
                        // Compare old and new descriptors to determine what's missing.
                        // For each new parameter type: push null (Object), 0 (int), etc.
                        String newParams = scr.newDesc().substring(1, scr.newDesc().indexOf(')'));
                        String oldParams = descriptor.substring(1, descriptor.indexOf(')'));
                        // Find the extra params that are in newDesc but not oldDesc
                        // Simple approach: parse parameter types from both
                        String extra = newParams.substring(oldParams.length());
                        pushDefaultsForDescriptor(extra);
                    } else if ("__REPLACE_DEFAULTS__".equals(scr.extraFieldOwner())) {
                        // New ctor params differ in TYPE from the old (not an append): pop every old
                        // arg, then push a fresh default for each new param. 1.12.2 Block(Material) ->
                        // Block(Properties): pop the Material, push BlockBehaviour.Properties.of().
                        popArgsForDescriptor(descriptor);
                        String newParams = scr.newDesc().substring(1, scr.newDesc().indexOf(')'));
                        pushDefaultsForDescriptor(newParams);
                    } else if (scr.extraFieldOwner() != null) {
                        // Push extra argument from a static field
                        super.visitFieldInsn(Opcodes.GETSTATIC,
                            scr.extraFieldOwner(), scr.extraFieldName(), scr.extraFieldDesc());
                    }
                    markFrameInvalidatingRewrite();
                    LOGGER.debug("Redirected superclass constructor {}{} to {}",
                        owner, descriptor, scr.newDesc());
                    super.visitMethodInsn(opcode, owner, name, scr.newDesc(), isInterface);
                    return;
                }
            }

            // #70: rebased superclass; rewrite the super(...) <init> owner to the new base.
            // Scoped to the DIRECT super() call (owner == the class's original superclass,
            // and no NEW deferred so it's not a `new`), so it never touches other
            // references to the base type (e.g. a mixin @Inject handler capturing it as a
            // param, the Arcanus crash). Only fires for class→class rebases that opted in.
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)
                    && classSuperNameOriginal != null && owner.equals(classSuperNameOriginal)
                    && !superclassRedirects.isEmpty()) {
                SuperclassRedirect rebase = superclassRedirects.get(owner);
                if (rebase != null && rebase.rewriteSuperCtor()) {
                    LOGGER.debug("Rebased super() owner: {}.<init>{} -> {}",
                            owner, descriptor, rebase.newSuperclass());
                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, rebase.newSuperclass(),
                            name, descriptor, false);
                    return;
                }
            }

            // Removed-method NEUTRALIZATION (soft-fail): if this exact call
            // targets a method deleted on the host (e.g. a removed RenderSystem
            // state setter), drop it: pop the args (and receiver, for instance
            // calls) and push a default return, so the mod loads instead of
            // hitting NoSuchMethodError. Checked before the redirect fast-path
            // because neutralized owners are tracked in their own set.
            if (!neutralizedMethods.isEmpty()
                    && neutralizedMethodOwners.contains(owner)
                    && neutralizedMethods.contains(new MethodKey(owner, name, descriptor))) {
                LOGGER.trace("Neutralizing removed-method call {}.{}{}", owner, name, descriptor);
                neutralizeCall(opcode, descriptor);
                return;
            }

            // Fast path: owner not in the redirect set. Still try pattern heuristics and fuzzy
            // resolution for unresolved references, since they cover the whole MC API surface.
            if (!methodRedirectOwners.contains(owner) && !argDropOwners.contains(owner)
                    && !convertingOwners.contains(owner) && !singletonOwners.contains(owner)) {
                // Try pattern heuristics FIRST; faster and more reliable than fuzzy
                PatternHeuristics patterns = patternHeuristics;
                if (patterns != null) {
                    PatternHeuristics.PatternResult patternMatch = patterns.resolveMethod(owner, name, descriptor);
                    if (patternMatch != null && patternMatch.confidence() >= 0.6) {
                        int patternOpcode = fixClassToInterfaceOpcode(opcode, patternMatch.newOwner());
                        boolean patternIsInterface = isInterfaceTarget(
                                patternOpcode, opcode, patternMatch.newOwner(), isInterface);
                        emitMethodInsn(patternOpcode, patternMatch.newOwner(), patternMatch.newName(),
                                patternMatch.newDescriptor(), patternIsInterface);
                        return;
                    }
                }

                // Try fuzzy resolution if available (only for MC classes)
                FuzzyMethodResolver resolver = fuzzyResolver;
                if (resolver != null && resolver.isIndexed()
                        && (owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/"))) {
                    FuzzyMethodResolver.MethodInfo fuzzyMatch =
                            resolver.resolveMethod(owner, name, descriptor);
                    if (fuzzyMatch != null) {
                        LOGGER.debug("[Retromod-Fuzzy] Resolved {}.{}{} -> {}.{}{} (confidence: {}%)",
                                owner, name, descriptor,
                                fuzzyMatch.owner(), fuzzyMatch.name(), fuzzyMatch.descriptor(),
                                fuzzyMatch.score());
                        int fuzzyOpcode = fixClassToInterfaceOpcode(opcode, fuzzyMatch.owner());
                        boolean fuzzyIsInterface = isInterfaceTarget(
                                fuzzyOpcode, opcode, fuzzyMatch.owner(), isInterface);
                        emitMethodInsn(fuzzyOpcode, fuzzyMatch.owner(), fuzzyMatch.name(),
                                fuzzyMatch.descriptor(), fuzzyIsInterface);
                        return;
                    }
                }

                // Still fix class→interface opcode even on fast path. INVOKESTATIC needs the
                // isInterface flag too (static interface methods take an InterfaceMethodref):
                // IntProvider.codec(int,int) is a static factory on a became-interface type, and
                // the mismatch dies at datapack load with IncompatibleClassChangeError "must be
                // InterfaceMethodref" (YungJigsawStructure on the 26.2 dedicated server).
                int fixedOpcode = fixClassToInterfaceOpcode(opcode, owner);
                boolean fixedIsInterface = isInterfaceTarget(
                        fixedOpcode, opcode, owner, isInterface);
                emitMethodInsn(fixedOpcode, owner, name, descriptor, fixedIsInterface);
                return;
            }

            // Check if this method call needs to be redirected
            MethodKey key = new MethodKey(owner, name, descriptor);
            MethodTarget target = methodRedirects.get(key);

            // If not found, try resolving the descriptor through classRedirects
            // (bytecode has intermediary names like class_437 in descriptors,
            // but redirects are registered with Mojang names like Screen)
            if (target == null && !classRedirects.isEmpty()) {
                String resolvedDesc = resolveDescriptor(descriptor);
                if (!resolvedDesc.equals(descriptor)) {
                    MethodKey resolvedKey = new MethodKey(owner, name, resolvedDesc);
                    target = methodRedirects.get(resolvedKey);
                }
            }

            // Arg-dropping redirect: the new method dropped trailing param(s) the call still passes.
            if (target == null && !argDropRedirects.isEmpty()) {
                MethodTarget drop = argDropRedirects.get(key);
                if (drop == null && !classRedirects.isEmpty()) {
                    String rd = resolveDescriptor(descriptor);
                    if (!rd.equals(descriptor)) drop = argDropRedirects.get(new MethodKey(owner, name, rd));
                }
                if (drop != null) {
                    org.objectweb.asm.Type[] oldArgs = org.objectweb.asm.Type.getArgumentTypes(descriptor);
                    org.objectweb.asm.Type[] newArgs = org.objectweb.asm.Type.getArgumentTypes(drop.desc);
                    markFrameInvalidatingRewrite();
                    // Pop the dropped trailing values (top of stack), last-pushed first, then call.
                    for (int i = oldArgs.length - 1; i >= newArgs.length; i--) {
                        super.visitInsn(oldArgs[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                    }
                    super.visitMethodInsn(opcode, drop.owner, drop.name, drop.desc, isInterface);
                    return;
                }
            }

            // Converting redirect: retarget + adapt the last arg and/or the return value.
            if (target == null && !convertingRedirects.isEmpty()) {
                ConvertingTarget conv = convertingRedirects.get(key);
                if (conv == null && !classRedirects.isEmpty()) {
                    String rd = resolveDescriptor(descriptor);
                    if (!rd.equals(descriptor)) conv = convertingRedirects.get(new MethodKey(owner, name, rd));
                }
                if (conv != null) {
                    markFrameInvalidatingRewrite();
                    if (conv.argConvOpcode() != 0) super.visitInsn(conv.argConvOpcode());
                    super.visitMethodInsn(opcode, conv.owner(), conv.name(), conv.desc(), isInterface);
                    if (conv.retAdaptOpcode() != 0) super.visitInsn(conv.retAdaptOpcode());
                    // Post-call unwrap: the retargeted call returned a WRAPPER; pull the original
                    // type back out (e.g. TracingExecutor.service() -> ExecutorService). Interface
                    // wrappers (ClientAsset$Texture) need INVOKEINTERFACE.
                    if (conv.unwrapOwner() != null) {
                        super.visitMethodInsn(
                                conv.unwrapItf() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                                conv.unwrapOwner(), conv.unwrapName(), conv.unwrapDesc(),
                                conv.unwrapItf());
                        String originalReturn = Type.getReturnType(descriptor).getDescriptor();
                        String unwrapReturn = Type.getReturnType(conv.unwrapDesc()).getDescriptor();
                        if ("Ljava/lang/Object;".equals(unwrapReturn)
                                && originalReturn.startsWith("L")
                                && !"Ljava/lang/Object;".equals(originalReturn)) {
                            super.visitTypeInsn(Opcodes.CHECKCAST,
                                    Type.getReturnType(descriptor).getInternalName());
                        }
                    }
                    return;
                }
            }

            // Singleton-static redirect: no-arg static helper -> SINGLETON.getInstance().method().
            if (target == null && !singletonRedirects.isEmpty()) {
                SingletonTarget s = singletonRedirects.get(key);
                if (s != null) {
                    markFrameInvalidatingRewrite();
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, s.singletonOwner(), s.getter(),
                            s.getterDesc(), false);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, s.singletonOwner(), s.name(),
                            s.desc(), false);
                    return;
                }
            }

            if (target != null) {
                // Redirect the call
                LOGGER.trace("Redirecting {}.{}{} -> {}.{}{}",
                        owner, name, descriptor,
                        target.owner, target.name, target.desc);

                // Auto-devirtualize: an instance call whose redirect target takes exactly one
                // extra parameter can only mean "receiver becomes arg 0". Many bridge redirects
                // were registered through the 6-arg form without the devirtualize flag; emitting
                // those as INVOKEVIRTUAL underflows the stack (ASM Frame.merge AIOOBE when Mixin
                // recomputes frames). Deciding at emit time fixes them all at once.
                boolean devirt = target.devirtualize();
                if (!devirt
                        && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE)
                        && org.objectweb.asm.Type.getArgumentTypes(target.desc()).length
                           == org.objectweb.asm.Type.getArgumentTypes(descriptor).length + 1) {
                    LOGGER.debug("Auto-devirtualizing redirect {}.{}{} -> {}.{}{} (receiver-as-arg0 shape)",
                            owner, name, descriptor, target.owner, target.name, target.desc);
                    devirt = true;
                }

                if (devirt) {
                    // Instance → static: change opcode and use static descriptor.
                    // INVOKESTATIC is never INVOKESPECIAL, so the helper is a no-op
                    // here, but use it for consistency with the other emission sites.
                    emitMethodInsn(Opcodes.INVOKESTATIC, target.owner, target.name,
                            target.desc, false);
                    // If return type changed (e.g., Object vs Either), emit CHECKCAST
                    // to satisfy the verifier's type checking
                    String origReturn = descriptor.substring(descriptor.lastIndexOf(')') + 1);
                    String newReturn = target.desc.substring(target.desc.lastIndexOf(')') + 1);
                    if (!origReturn.equals(newReturn) && origReturn.startsWith("L")) {
                        String origReturnClass = origReturn.substring(1, origReturn.length() - 1);
                        markFrameInvalidatingRewrite();
                        super.visitTypeInsn(Opcodes.CHECKCAST, origReturnClass);
                    }
                } else {
                    int fixedOpcode = fixClassToInterfaceOpcode(opcode, target.owner);
                    // The itf flag must describe the TARGET: preserve the original call's flag,
                    // but also force it for interface synthetics (WorldgenTypeBridge - a pre-1.19.3
                    // mod's itf=false Registry.register call otherwise emits a plain Methodref
                    // against the interface) and known-interface INVOKESTATIC targets.
                    boolean targetIsInterface = isInterfaceTarget(
                            fixedOpcode, opcode, target.owner, isInterface);
                    emitMethodInsn(fixedOpcode, target.owner, target.name,
                            target.desc, targetIsInterface);
                    // Emit CHECKCAST when return type changed (e.g., Object vs Event)
                    String origReturn = descriptor.substring(descriptor.lastIndexOf(')') + 1);
                    String newReturn = target.desc.substring(target.desc.lastIndexOf(')') + 1);
                    if (!origReturn.equals(newReturn) && origReturn.startsWith("L")) {
                        String origReturnClass = origReturn.substring(1, origReturn.length() - 1);
                        markFrameInvalidatingRewrite();
                        super.visitTypeInsn(Opcodes.CHECKCAST, origReturnClass);
                    }
                }
            } else {
                // No hardcoded redirect found; try pattern heuristics first (fast, deterministic),
                // then fuzzy resolver as last resort (slower, probabilistic).
                // Neither will EVER override a hardcoded redirect (we only get here if none matched).

                // Pattern heuristics: deterministic naming convention rules (e.g., render* -> extract*)
                PatternHeuristics patterns = patternHeuristics;
                if (patterns != null) {
                    PatternHeuristics.PatternResult patternMatch = patterns.resolveMethod(owner, name, descriptor);
                    if (patternMatch != null && patternMatch.confidence() >= 0.6) {
                        LOGGER.debug("[Retromod-Pattern] Resolved {}.{}{} -> {}.{}{} (rule: {}, confidence: {})",
                                owner, name, descriptor,
                                patternMatch.newOwner(), patternMatch.newName(), patternMatch.newDescriptor(),
                                patternMatch.rule(), patternMatch.confidence());
                        int patternOpcode = fixClassToInterfaceOpcode(opcode, patternMatch.newOwner());
                        boolean patternIsInterface = isInterfaceTarget(
                                patternOpcode, opcode, patternMatch.newOwner(), isInterface);
                        emitMethodInsn(patternOpcode, patternMatch.newOwner(), patternMatch.newName(),
                                patternMatch.newDescriptor(), patternIsInterface);
                        return;
                    }
                }

                // Fuzzy resolver: scans the target MC JAR to find probable matches
                // based on class, name similarity, and parameter type matching.
                FuzzyMethodResolver resolver = fuzzyResolver;
                if (resolver != null && resolver.isIndexed()) {
                    FuzzyMethodResolver.MethodInfo fuzzyMatch =
                            resolver.resolveMethod(owner, name, descriptor);
                    if (fuzzyMatch != null) {
                        // Fuzzy match found with confidence >= 70%; apply the redirect
                        LOGGER.debug("[Retromod-Fuzzy] Resolved {}.{}{} -> {}.{}{} (confidence: {}%)",
                                owner, name, descriptor,
                                fuzzyMatch.owner(), fuzzyMatch.name(), fuzzyMatch.descriptor(),
                                fuzzyMatch.score());
                        int fuzzyOpcode = fixClassToInterfaceOpcode(opcode, fuzzyMatch.owner());
                        boolean fuzzyIsInterface = isInterfaceTarget(
                                fuzzyOpcode, opcode, fuzzyMatch.owner(), isInterface);
                        emitMethodInsn(fuzzyOpcode, fuzzyMatch.owner(), fuzzyMatch.name(),
                                fuzzyMatch.descriptor(), fuzzyIsInterface);
                        return;
                    }
                }

                // No redirect and no fuzzy match; pass through with opcode fixup.
                // The invokespecial-on-non-direct-supertype fixup is handled by
                // emitMethodInsn(); see its javadoc for the full reasoning.
                int fixedOpcode = fixClassToInterfaceOpcode(opcode, owner);

                // For KNOWN_INTERFACES (classes that became interfaces like DataResult):
                // - INVOKEVIRTUAL → INVOKEINTERFACE (handled by fixClassToInterfaceOpcode)
                // - INVOKESTATIC stays INVOKESTATIC but needs isInterface=true
                boolean fixedIsInterface = isInterfaceTarget(
                        fixedOpcode, opcode, owner, isInterface);
                emitMethodInsn(fixedOpcode, owner, name, descriptor, fixedIsInterface);
            }
        }
        
        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            pendingIndyConsumerType = null;
            // Don't flush; ALOAD/ILOAD etc. are constructor arguments
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            pendingIndyConsumerType = null;
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            pendingIndyConsumerType = null;
            // Don't flush; LDC is commonly a constructor argument
            super.visitLdcInsn(value);
        }

        /**
         * Intercepts every field access instruction (GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC).
         *
         * <p>Checks three redirect types in order:</p>
         * <ol>
         *   <li><b>Field accessor redirect:</b> The field became private in newer MC,
         *       so GETFIELD is replaced with INVOKEVIRTUAL getter() and PUTFIELD with
         *       INVOKEVIRTUAL setter(). This preserves the same stack behavior.</li>
         *   <li><b>Field-to-method redirect:</b> The field was removed entirely and
         *       replaced with a static method. Detected when newDesc starts with "(".
         *       GETSTATIC/GETFIELD becomes INVOKESTATIC.</li>
         *   <li><b>Field-to-field redirect:</b> The field was renamed or moved to a
         *       different class. The opcode stays the same.</li>
         * </ol>
         */
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            pendingIndyConsumerType = null;
            // Don't flush; field accesses can be constructor arguments
            FieldKey key = new FieldKey(owner, name);

            // Removed static field -> accessor-call sequence (26.2 ColorCollection:
            // Blocks.WHITE_CANDLE -> Blocks.DYED_CANDLE.pick(DyeColor.WHITE), cast Block).
            if (opcode == Opcodes.GETSTATIC) {
                StaticFieldAccessor sfa = staticFieldAccessors.get(key);
                if (sfa != null) {
                    super.visitFieldInsn(Opcodes.GETSTATIC,
                            sfa.collectionOwner(), sfa.collectionField(), sfa.collectionDesc());
                    if (sfa.argField() != null) {
                        super.visitFieldInsn(Opcodes.GETSTATIC,
                                sfa.argOwner(), sfa.argField(), sfa.argDesc());
                    }
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            sfa.methodOwner(), sfa.methodName(), sfa.methodDesc(), false);
                    if (sfa.castType() != null) {
                        super.visitTypeInsn(Opcodes.CHECKCAST, sfa.castType());
                    }
                    LOGGER.trace("Static-field accessor redirect: {}.{} -> {}.{}.{}()",
                            owner, name, sfa.collectionOwner(), sfa.collectionField(), sfa.methodName());
                    return;
                }
                // Removed class's static constant -> push the field type's default (no class ref).
                // 1.12.2 super(Material.IRON): Material is gone in 26.1; the super-ctor replace bridge
                // pops this default and substitutes a real Properties.
                if (staticFieldNullers.contains(owner)) {
                    switch (descriptor.charAt(0)) {
                        case 'J' -> super.visitInsn(Opcodes.LCONST_0);
                        case 'D' -> super.visitInsn(Opcodes.DCONST_0);
                        case 'F' -> super.visitInsn(Opcodes.FCONST_0);
                        case 'I', 'Z', 'B', 'C', 'S' -> super.visitInsn(Opcodes.ICONST_0);
                        default -> super.visitInsn(Opcodes.ACONST_NULL); // object or array
                    }
                    LOGGER.trace("Static-field nuller: GETSTATIC {}.{} -> default", owner, name);
                    return;
                }
            }

            // Check field-to-accessor redirects first (GETFIELD→getter, PUTFIELD→setter)
            FieldAccessorTarget accessor = fieldAccessorRedirects.get(key);
            if (accessor != null) {
                if (opcode == Opcodes.GETFIELD) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            accessor.getterOwner(), accessor.getterName(), accessor.getterDesc(), false);
                    LOGGER.trace("Field accessor redirect: GETFIELD {}.{} -> {}.{}()",
                            owner, name, accessor.getterOwner(), accessor.getterName());
                } else if (opcode == Opcodes.PUTFIELD) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            accessor.setterOwner(), accessor.setterName(), accessor.setterDesc(), false);
                    LOGGER.trace("Field accessor redirect: PUTFIELD {}.{} -> {}.{}()",
                            owner, name, accessor.setterOwner(), accessor.setterName());
                } else {
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                }
                return;
            }

            // Field moved behind a public sibling field's accessors (26.2 Minecraft.screen ->
            // mc.gui.screen()/setScreen). Pure-bytecode hop: no reflection on hot paths.
            FieldHopAccessor hop = fieldHopAccessors.get(key);
            if (hop != null) {
                if (opcode == Opcodes.GETFIELD) {
                    // [recv] -> [hopObj] -> getter()
                    super.visitFieldInsn(Opcodes.GETFIELD, owner, hop.hopField(), hop.hopDesc());
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            hop.hopClass(), hop.getterName(), hop.getterDesc(), false);
                    LOGGER.trace("Field hop redirect: GETFIELD {}.{} -> .{}.{}()",
                            owner, name, hop.hopField(), hop.getterName());
                    return;
                }
                if (opcode == Opcodes.PUTFIELD && hop.setterName() != null) {
                    // [recv, value] -> SWAP -> GETFIELD hop -> SWAP -> [hopObj, value] -> setter().
                    // Registration rejects long/double field types, so SWAP is always legal.
                    super.visitInsn(Opcodes.SWAP);
                    super.visitFieldInsn(Opcodes.GETFIELD, owner, hop.hopField(), hop.hopDesc());
                    super.visitInsn(Opcodes.SWAP);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            hop.hopClass(), hop.setterName(), hop.setterDesc(), false);
                    LOGGER.trace("Field hop redirect: PUTFIELD {}.{} -> .{}.{}()",
                            owner, name, hop.hopField(), hop.setterName());
                    return;
                }
                // GETSTATIC/PUTSTATIC (or a read-only hop's PUTFIELD): pass through unchanged.
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }

            // Field state moved somewhere the receiver can't reach (26.2 Options.hideGui ->
            // Hud.isHidden): opcode-aware static bridge consuming the receiver as an ignored arg.
            FieldStaticBridge bridge = fieldStaticBridges.get(key);
            if (bridge != null) {
                if (opcode == Opcodes.GETFIELD) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            bridge.owner(), bridge.getterName(), bridge.getterDesc(), false);
                    // Same widened-return CHECKCAST rule as the field-to-method redirect below.
                    String newReturn = bridge.getterDesc()
                            .substring(bridge.getterDesc().lastIndexOf(')') + 1);
                    if (descriptor != null && descriptor.startsWith("L")
                            && !descriptor.equals(newReturn)) {
                        super.visitTypeInsn(Opcodes.CHECKCAST,
                                descriptor.substring(1, descriptor.length() - 1));
                    }
                    LOGGER.trace("Field static bridge: GETFIELD {}.{} -> {}.{}",
                            owner, name, bridge.owner(), bridge.getterName());
                    return;
                }
                if (opcode == Opcodes.PUTFIELD) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                            bridge.owner(), bridge.setterName(), bridge.setterDesc(), false);
                    LOGGER.trace("Field static bridge: PUTFIELD {}.{} -> {}.{}",
                            owner, name, bridge.owner(), bridge.setterName());
                    return;
                }
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }

            // Check if this field access needs to be redirected
            FieldTarget target = fieldRedirects.get(key);

            if (target != null) {
                // Check if this is a field-to-method redirect
                if (target.newDesc != null && target.newDesc.startsWith("(")) {
                    // Convert GETSTATIC/GETFIELD to INVOKESTATIC
                    // (Field access becomes a static method call that returns the value)
                    int newOpcode = Opcodes.INVOKESTATIC;
                    super.visitMethodInsn(newOpcode, target.owner, target.name, target.newDesc, false);
                    LOGGER.trace("Redirected field {}.{} -> method {}.{}{}",
                            owner, name, target.owner, target.name, target.newDesc);
                    // If the polyfill method returns a wider type than the
                    // original field (e.g. Object vs Enchantment), the JVM
                    // verifier needs a CHECKCAST to satisfy the type expected
                    // at the use site. Skipping this emit causes
                    //   VerifyError: Bad type on operand stack
                    // when the next instruction tries to use the result as
                    // the original field's type. This mirrors the same
                    // CHECKCAST emit pattern used in method-to-method redirects.
                    String newReturn = target.newDesc.substring(target.newDesc.lastIndexOf(')') + 1);
                    if (descriptor != null && descriptor.startsWith("L")
                            && !descriptor.equals(newReturn)) {
                        String origClass = descriptor.substring(1, descriptor.length() - 1);
                        super.visitTypeInsn(Opcodes.CHECKCAST, origClass);
                    }
                } else {
                    // Standard field-to-field redirect
                    // Use new descriptor if provided, otherwise keep original
                    String newDescriptor = (target.newDesc != null) ? target.newDesc : descriptor;
                    super.visitFieldInsn(opcode, target.owner, target.name, newDescriptor);
                }
            } else {
                // No hardcoded field redirect; try fuzzy resolver as last resort.
                FuzzyMethodResolver resolver = fuzzyResolver;
                if (resolver != null && resolver.isIndexed()) {
                    FuzzyMethodResolver.FieldInfo fuzzyMatch =
                            resolver.resolveField(owner, name, descriptor);
                    if (fuzzyMatch != null) {
                        LOGGER.debug("[Retromod-Fuzzy] Resolved field {}.{} {} -> {}.{} {} (confidence: {}%)",
                                owner, name, descriptor,
                                fuzzyMatch.owner(), fuzzyMatch.name(), fuzzyMatch.descriptor(),
                                fuzzyMatch.score());
                        super.visitFieldInsn(opcode, fuzzyMatch.owner(), fuzzyMatch.name(),
                                fuzzyMatch.descriptor());
                        return;
                    }
                }

                // No redirect and no fuzzy match; pass through unchanged
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }
    }
    
    // Key/target records for redirect lookups; records give equals()/hashCode() for free,
    // which matters since these are looked up on every method/field instruction.

    /** Lookup key for method redirects: matches (owner class, method name, descriptor). */
    public record MethodKey(String owner, String name, String desc) {}
    /** Target of a method redirect. If devirtualize=true, instance calls become static. */
    public record MethodTarget(String owner, String name, String desc, boolean devirtualize) {
        /** Convenience constructor without devirtualize flag */
        public MethodTarget(String owner, String name, String desc) {
            this(owner, name, desc, false);
        }
    }
    /**
     * Target of a converting redirect: retarget plus one optional primitive conversion on the last
     * arg ({@code argConvOpcode}, emitted before the call) and one on the return value
     * ({@code retAdaptOpcode}, emitted after; may be {@code POP}/{@code POP2} to discard). 0 = none.
     *
     * <p>{@code unwrapOwner}/{@code unwrapName}/{@code unwrapDesc} (all null, or all set) express a
     * post-call UNWRAP: a vanilla method whose return type became a WRAPPER gets an
     * {@code INVOKEVIRTUAL} appended to pull the old type back out, e.g. 26.x
     * {@code Util.backgroundExecutor()} returns {@code TracingExecutor} (not {@code ExecutorService});
     * append {@code TracingExecutor.service()} to recover the {@code ExecutorService} the caller
     * expects. Not expressible as {@code retAdaptOpcode} (that's a single insn, not a call). A
     * method HANDLE (a {@code ::} reference) can't carry this, so the invokedynamic path skips
     * unwrap-bearing targets.
     */
    public record ConvertingTarget(String owner, String name, String desc,
            int argConvOpcode, int retAdaptOpcode,
            String unwrapOwner, String unwrapName, String unwrapDesc, boolean unwrapItf) {
        /** Converting target with no post-call unwrap (the common case). */
        public ConvertingTarget(String owner, String name, String desc,
                int argConvOpcode, int retAdaptOpcode) {
            this(owner, name, desc, argConvOpcode, retAdaptOpcode, null, null, null, false);
        }
        /** Converting target with a class-typed (INVOKEVIRTUAL) unwrap. */
        public ConvertingTarget(String owner, String name, String desc,
                int argConvOpcode, int retAdaptOpcode,
                String unwrapOwner, String unwrapName, String unwrapDesc) {
            this(owner, name, desc, argConvOpcode, retAdaptOpcode,
                    unwrapOwner, unwrapName, unwrapDesc, false);
        }
    }
    /** Target of a singleton-static redirect: {@code singletonOwner.getter():..} then instance call. */
    public record SingletonTarget(String singletonOwner, String getter, String getterDesc,
            String name, String desc) {}
    /** Lookup key for field redirects: matches (owner class, field name). */
    public record FieldKey(String owner, String name) {}
    /** Target of a field redirect. If newDesc starts with "(", this is a field-to-method redirect. */
    public record FieldTarget(String owner, String name, String oldDesc, String newDesc) {}
    /** Target of a field accessor redirect: field access becomes getter/setter method call. */
    public record FieldAccessorTarget(
        String getterOwner, String getterName, String getterDesc,
        String setterOwner, String setterName, String setterDesc
    ) {}
    /**
     * Target of a field hop accessor: GETFIELD becomes GETFIELD hopField + INVOKEVIRTUAL getter on
     * the hop's class; PUTFIELD becomes SWAP + GETFIELD hopField + SWAP + INVOKEVIRTUAL setter.
     * {@code hopClass} is precomputed from {@code hopDesc}. {@code setterName} null = read-only hop.
     */
    public record FieldHopAccessor(
        String hopField, String hopDesc, String hopClass,
        String getterName, String getterDesc,
        String setterName, String setterDesc
    ) {}
    /**
     * Target of a field static bridge: GETFIELD becomes INVOKESTATIC getter, PUTFIELD becomes
     * INVOKESTATIC setter; both descs consume the receiver as their first (ignored) argument.
     */
    public record FieldStaticBridge(
        String owner,
        String getterName, String getterDesc,
        String setterName, String setterDesc
    ) {}
    /**
     * A removed static field rewritten to an accessor call. GETSTATIC of the removed
     * field becomes: GETSTATIC collectionOwner.collectionField, optional GETSTATIC
     * argOwner.argField, INVOKEVIRTUAL methodOwner.methodName(methodDesc), optional
     * CHECKCAST castType. (argField/castType may be null.)
     */
    public record StaticFieldAccessor(
        String collectionOwner, String collectionField, String collectionDesc,
        String argOwner, String argField, String argDesc,
        String methodOwner, String methodName, String methodDesc,
        String castType
    ) {}
    /** Superclass rewrite: changes extends + adds interface implementations. */
    public record SuperclassRedirect(String newSuperclass, String[] addInterfaces, boolean rewriteSuperCtor) {}
    /** Lookup key for constructor redirects: matches (class being constructed, constructor descriptor). */
    public record ConstructorKey(String className, String constructorDesc) {}
    /** Target of a constructor→factory redirect: the static method to call instead. */
    public record FactoryTarget(String factoryClass, String factoryMethod, String factoryDesc,
            boolean factoryIsInterface) {
        public FactoryTarget(String factoryClass, String factoryMethod, String factoryDesc) {
            this(factoryClass, factoryMethod, factoryDesc, false);
        }
    }
    /**
     * Describes a super() constructor descriptor change.
     * When a subclass calls super(oldDesc), we redirect to super(newDesc)
     * by pushing extra arguments before the INVOKESPECIAL.
     *
     * @param newDesc the new constructor descriptor
     * @param extraFieldOwner class owning the extra static field to push (or null)
     * @param extraFieldName name of the static field to push as extra arg
     * @param extraFieldDesc descriptor of the static field
     */
    public record SuperCtorRedirect(String newDesc, String extraFieldOwner,
                                     String extraFieldName, String extraFieldDesc) {}
    
    public int getMethodRedirectCount() {
        return methodRedirects.size();
    }
    
    public int getClassRedirectCount() {
        return classRedirects.size();
    }
    
    public int getFieldRedirectCount() {
        return fieldRedirects.size();
    }
    
    public Map<MethodKey, MethodTarget> getMethodRedirects() {
        return Collections.unmodifiableMap(methodRedirects);
    }
    
    public Map<String, String> getClassRedirects() {
        return Collections.unmodifiableMap(classRedirects);
    }

    public Map<FieldKey, FieldTarget> getFieldRedirects() {
        return Collections.unmodifiableMap(fieldRedirects);
    }

    /**
     * Get intermediary method name mappings (method_XXXX → Mojang name).
     */
    public Map<String, String> getIntermediaryMethodNames() {
        return Collections.unmodifiableMap(intermediaryMethodNames);
    }

    /**
     * Get intermediary field name mappings (field_XXXX → Mojang name).
     */
    public Map<String, String> getIntermediaryFieldNames() {
        return Collections.unmodifiableMap(intermediaryFieldNames);
    }
}
