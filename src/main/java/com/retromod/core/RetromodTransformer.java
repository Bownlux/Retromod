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
import java.util.function.Predicate;

import com.retromod.util.RetromodLog;
import com.retromod.util.ZipSecurity;

/** Rewrites the classes, methods and fields an old mod uses to the ones the running Minecraft has. */
public class RetromodTransformer implements ClassFileTransformer {

    // Never reference Retromod here: it implements ModInitializer, and this class also runs in the
    // standalone CLI, where Fabric classes are not on the classpath.
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Transformer");
    private static final RetromodTransformer INSTANCE = new RetromodTransformer();
    static final long MAX_HIERARCHY_CLASS_BYTES = ZipSecurity.DEFAULT_MAX_ENTRY_SIZE;

    // Redirect maps below are populated by version shims and polyfill providers and read
    // during transformation. ConcurrentHashMap because shims register from ServiceLoader threads
    // while classes load. They cover method renames and removals, class moves, signature
    // changes, constructor-to-factory rewrites, field accessors, and intermediary to Mojang names.

    // oldOwner.oldName(oldDesc) -> newOwner.newName(newDesc). Checked manually in
    // visitMethodInsn since matching needs owner+name+descriptor, not just name.
    private final Map<MethodKey, MethodTarget> methodRedirects = new ConcurrentHashMap<>(256);
    /** Redirects to a helper that wraps a host method which still exists; super calls keep it. */
    private final Set<MethodKey> wrappingMethodRedirects = ConcurrentHashMap.newKeySet();
    /** Redirects kept for mods built before a version, mapped to the first version without it. */
    private final Map<MethodKey, String> legacyOnlyRedirects = new ConcurrentHashMap<>();
    /** Class redirects kept for mods built before a version, by old class name. */
    private final Map<String, String> legacyOnlyClassRedirects = new ConcurrentHashMap<>();
    /** Class redirects a library translated with the mod turns off, by old class name: a class that library defines. */
    private final Map<String, String> libraryYieldingClassRedirects = new ConcurrentHashMap<>();
    /** Helper methods whose super calls go to another method on the same helper, by that name. */
    private final Map<MethodKey, String> superCallHelperNames = new ConcurrentHashMap<>();
    /** The Minecraft version the mod being transformed was built for, or null when unknown. */
    private volatile String modSourceVersion;
    /** The newest Minecraft version the mod's jar shows it was built for, or null to use its source version. */
    private volatile String modBuildVersion;
    /** The translated jars the current mod was linked with, kept past the class pass for the embedder. */
    private volatile SyntheticLibraries modLibraries = SyntheticLibraries.NONE;

    // Exact method aliases that also apply when javac writes the CALLER'S subclass as the
    // constant-pool owner of an inherited invocation. The ordinary redirect table cannot match
    // that shape because the owner is a mod class, not the Minecraft class that declared the
    // renamed method. Entries remain descriptor-qualified, and a rewrite is allowed only after
    // the current jar or target index proves that the call owner inherits the registered owner.
    private final Map<MethodShape, Set<InheritedMethodRedirect>> inheritedMethodRedirects =
            new ConcurrentHashMap<>();

    // Calls to a method deleted on the host: discard the args (and receiver), push a default
    // return, so the mod loads instead of hitting NoSuchMethodError. Motivating case: the
    // RenderSystem state setters (enableBlend/blendFunc/depthMask) removed in the 26.x
    // GpuDevice/RenderPipeline refactor have no redirect target. Exact owner+name+desc match,
    // so a live overload is untouched; owners tracked separately for an O(1) skip.
    private final Set<MethodKey> neutralizedMethods = ConcurrentHashMap.newKeySet();
    // Implemented-interface rewrites: interface -> replacement, or "" to drop it from the list.
    private final Map<String, String> implementedInterfaceRewrites = new ConcurrentHashMap<>();
    // Concrete methods a class gets when it implements an interface, keyed by that interface.
    private final Map<String, List<ImplementedMethodBridge>> implementedMethodBridges =
            new ConcurrentHashMap<>();
    // Default methods an interface gets when it extends an interface, keyed by that interface.
    private final Map<String, List<ImplementedMethodBridge>> interfaceDefaultBridges =
            new ConcurrentHashMap<>();
    private final Set<String> neutralizedMethodOwners = ConcurrentHashMap.newKeySet();

    // Like methodRedirects, but the new method dropped one or more TRAILING parameters the call
    // still passes. On match the extra trailing values are popped before the (renamed) call, so a
    // method that was renamed AND lost a tail arg still links. Chunk force-loading needs this:
    // ServerChunkCache.addRegionTicket(TicketType,ChunkPos,int,T) -> addTicketWithRadius(TicketType,
    // ChunkPos,int) on 1.21.5 (the value arg was dropped). Keyed/owner-tracked like methodRedirects.
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

    // A bundled compatibility provider must keep its own implementation links intact. Redirects
    // into these packages still apply to mod callers, but references between classes inside the
    // same registered package are preserved.
    private final Set<String> redirectProviderPackages = ConcurrentHashMap.newKeySet();

    // oldOwner.oldName -> newOwner.newName. When newDesc starts with "(" this is a
    // field-to-method redirect (the field was removed and replaced with a method).
    private final Map<FieldKey, FieldTarget> fieldRedirects = new ConcurrentHashMap<>(64);
    /** Field redirects that leave an access with any other descriptor alone. */
    private final Set<FieldKey> exactDescriptorFieldRedirects = ConcurrentHashMap.newKeySet();
    /** Field-to-getter redirects whose result replaces the old field type, so no cast follows. */
    private final Set<FieldKey> standInFieldGetters = ConcurrentHashMap.newKeySet();
    /**
     * Exact redirects by {@code owner.name:descriptor}. A field can carry one per descriptor it has
     * had, as {@code CauldronInteraction.EMPTY} does: a 1.20.1 {@code Map} read and a 1.21
     * {@code InteractionMap} read go to different places, and the owner-and-name map holds only
     * the last one registered.
     */
    private final Map<String, FieldTarget> descriptorFieldRedirects = new ConcurrentHashMap<>();

    // A moved accessor setter may target a field that is final on the host. Shims opt exact
    // destinations into Mixin's @Mutable handling instead of weakening every moved setter.
    private final Set<FieldDestination> mutableAccessorDestinations = ConcurrentHashMap.newKeySet();

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

    // Mojang owner + name + descriptor -> the SRG member name exposed by a specific
    // Forge 1.20 to 1.20.4 host. This is the second half of old SRG -> Mojang -> target SRG.
    // Owner and descriptor are required because readable member names are not unique.
    private final Map<String, String> targetSrgMethodNames = new ConcurrentHashMap<>(8000);
    private final Map<String, String> targetSrgFieldNames = new ConcurrentHashMap<>(8000);

    // Mojang->Mojang method renames on a kept class, keyed "owner#name" -> newName (e.g.
    // ResourceKey.location -> identifier on 26.x). Owner-scoped, not a global dictionary,
    // since the names are ordinary words. Applied via the ClassRemapper's mapMethodName so
    // it also rewrites method references (ResourceKey::location), which methodRedirects
    // can't reach. Only for 26.1+ targets, where the old name is gone.
    private final Map<String, String> mojangMethodRenames = new ConcurrentHashMap<>(16);
    /** {@code interface#oldName} to the new abstract method name, for lambdas implementing it. */
    private final Map<String, String> functionalInterfaceRenames = new ConcurrentHashMap<>(16);
    // Minecraft members a Mojang-named mod knows by an older name, for the current mod's namespace.
    private volatile com.retromod.mapping.MojangRenameTable mojangRenames;
    // Walk results per jar, by mod source version and reference, and the host check per row.
    private final Map<Object, Map<String, Optional<com.retromod.mapping.MojangRenameTable.Row>>>
            inheritedMojangRenames = new WeakHashMap<>();
    private final Map<com.retromod.mapping.MojangRenameTable.Row, Boolean> mojangRenameOnHost =
            new ConcurrentHashMap<>();
    // Old class names the rename table redirects, which a mixin targeting one keeps.
    private final Set<String> mojangClassRenames = ConcurrentHashMap.newKeySet();
    // A host method an old mod overrides with its old meaning, keyed by the name and descriptor the mod declares it with.
    private final Map<String, OwnOverrideRename> ownOverrideRenames = new ConcurrentHashMap<>();
    /** {@code interface#samName#oldSamDescriptor} to the adapter for lambdas written against it. */
    private final Map<String, LambdaAdapter> lambdaAdapters = new ConcurrentHashMap<>(8);

    // Class-to-interface migrations: when a class becomes an interface in newer MC,
    // mods extending it get their superclass changed to a bridge plus the interface added.
    private final Map<String, SuperclassRedirect> superclassRedirects = new ConcurrentHashMap<>(16);
    /** Rebases keyed on a class redirect's destination, mapped to the removed class whose version limit they follow. */
    private final Map<String, String> rebasesThroughRedirects = new ConcurrentHashMap<>();

    /** Memoized answer to "is this call owner an interface on the host". */
    private final Map<String, Boolean> interfaceKinds = new ConcurrentHashMap<>(64);
    /** Memoized answer to "does the host prove this call owner a class". */
    private final Map<String, Boolean> classKinds = new ConcurrentHashMap<>(64);
    /** Host class shapes read for the final-override pass, empty when the host could not be read. */
    private final Map<String, Optional<com.retromod.shim.common.HostFinalOverrideAdapter.Shape>> hostClassShapes =
            new ConcurrentHashMap<>(64);
    /** Final-override shapes per jar scope, keyed by the scope's class bytes provider and dropped with it. */
    private final Map<Function<String, byte[]>, Map<String, Optional<com.retromod.shim.common.HostFinalOverrideAdapter.Shape>>>
            jarShapeCaches = Collections.synchronizedMap(new WeakHashMap<>());
    /** Whether the line about unreadable Minecraft classes was logged. */
    private final AtomicBoolean finalOverrideHostNoted = new AtomicBoolean();
    /** Host shape of names seen in an {@code extends} slot. See {@link SuperclassSafety}. */
    private final Map<String, SuperclassSafety.Verdict> superclassVerdicts = new ConcurrentHashMap<>(16);
    /** Ceiling on constructor shapes absorbed into one generated base. See absorbLegacyCtor. */
    private static final int MAX_ABSORBED_LEGACY_CTORS = 64;

    /** Generated stand-in bases, keyed by the generated name. See {@link LegacyBaseSynthesizer}. */
    private final Map<String, LegacyBaseSpec> generatedLegacyBases = new ConcurrentHashMap<>(4);
    /** Bases already reported as unusable, so the explanation is logged once per base. */
    private final Set<String> reportedIllegalSuperclasses = ConcurrentHashMap.newKeySet();

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

    /** Read one jar-local class for a conservative post-remap adapter. */
    public static byte[] readCurrentJarClassForAdapter(String internalName) {
        Function<String, byte[]> provider = INSTANCE.currentJarClassBytesProvider();
        return provider != null ? provider.apply(internalName) : null;
    }

    // Translated jars the jar being transformed builds on. A 1.12 addon's block can extend its
    // core mod's base block, so an adapter that has to see the whole chain up to Minecraft reads
    // the core's classes here. They are already translated, unlike the jar-local ones above.
    private volatile SyntheticLibraries linkedLibraries = SyntheticLibraries.NONE;
    /** Fields and methods of linked library classes already read, keyed by class. */
    private final Map<String, Set<String>> linkedLibraryMembers = new ConcurrentHashMap<>();

    public void setLinkedLibraries(SyntheticLibraries libraries) {
        this.linkedLibraries = libraries == null ? SyntheticLibraries.NONE : libraries;
        linkedLibraryMembers.clear();
        // They stay the mod's libraries until the next mod starts, so the embedder decides as the class pass did.
        this.modLibraries = this.linkedLibraries;
    }

    public void clearLinkedLibraries() {
        this.linkedLibraries = SyntheticLibraries.NONE;
        linkedLibraryMembers.clear();
    }

    /**
     * Whether a linked library's {@code owner} declares {@code member}, written as
     * {@code name:descriptor} for a field and {@code name(args)return} for a method.
     */
    private boolean linkedLibraryDeclares(String owner, String member) {
        return linkedLibraryMembers.computeIfAbsent(owner, this::readLinkedLibraryMembers).contains(member);
    }

    private Set<String> readLinkedLibraryMembers(String owner) {
        byte[] bytes = linkedLibraries.classBytes(owner);
        if (bytes == null) return Set.of();
        Set<String> members = new HashSet<>();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    members.add(name + ':' + descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    members.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return Set.of();
        }
        return members;
    }

    /** Read one translated class of a library the current jar builds on, or null. */
    public static byte[] readLinkedLibraryClassForAdapter(String internalName) {
        return INSTANCE.linkedLibraries.classBytes(internalName);
    }

    /**
     * A class the jar being transformed builds on, with the names the class pass gives it, or null: the jar's own
     * class, else a linked library's translated class, else the class the hierarchy lookup reads from Retromod's
     * class loader, such as another mod in the game. An adapter that looks for a method by its Mojang name in a
     * parent sees what that parent declares, where a Forge jar's own class still has SRG names.
     */
    public static byte[] readJarOrLibraryClassRemapped(String internalName) {
        return INSTANCE.jarOrLibraryClassRemapped(internalName);
    }

    private byte[] jarOrLibraryClassRemapped(String internalName) {
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        byte[] own = provider != null ? provider.apply(internalName) : null;
        if (own == null) {
            byte[] library = linkedLibraries.classBytes(internalName);
            if (library != null) return library;
            // The same reader inheritsFrom falls back to; a class read there may still carry its old names.
            own = readClassBytes(internalName, null);
            if (own == null) return null;
        }
        Remapper remapper = providerPreservingRemapper(globalRemapper(), internalName);
        if (remapper == null) return own;
        try {
            // Only the declarations are wanted, so the code is left out.
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(own).accept(new ClassRemapper(writer, remapper), ClassReader.SKIP_CODE);
            return writer.toByteArray();
        } catch (RuntimeException unreadable) {
            return own;
        }
    }

    // The block and item ids the jar being transformed registers its static fields under. Scoped
    // exactly like the class bytes provider: instance-wide for a parallel class pass, per thread
    // for a nested jar.
    private volatile com.retromod.shim.common.StaticFieldRegistryIds jarRegistryIds =
            com.retromod.shim.common.StaticFieldRegistryIds.NONE;
    private final ThreadLocal<ArrayDeque<com.retromod.shim.common.StaticFieldRegistryIds>> scopedJarRegistryIds =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** Indexes the static fields a jar registers as blocks, items, or entity types, and its namespace. */
    public com.retromod.shim.common.StaticFieldRegistryIds indexJarRegistryIds(Iterable<byte[]> classes) {
        // No registry id bridge is active on a host that does not require the id.
        if (classes == null || !registryIdBridgeActive()) {
            return com.retromod.shim.common.StaticFieldRegistryIds.NONE;
        }
        // Every class is read as shipped, before any is transformed. A field's class can initialize
        // before the class that registers it ever runs, so the id has to be known up front.
        Set<String> names = new HashSet<>();
        for (byte[] bytes : classes) {
            try {
                if (bytes != null) names.add(new ClassReader(bytes).getClassName());
            } catch (RuntimeException unreadable) {
                // not a class this pass can read; the class pass reports it
            }
        }
        // Each class is indexed with its names remapped, the way the class pass will see them.
        var index = com.retromod.shim.common.StaticFieldRegistryIds.builder(names);
        for (byte[] bytes : classes) {
            try {
                if (bytes != null) index.add(remappedView(bytes));
            } catch (RuntimeException unreadable) {
                // as above
            }
        }
        com.retromod.shim.common.StaticFieldRegistryIds ids = index.build();
        if (ids.fieldCount() > 0) {
            LOGGER.debug("Found registry ids for {} static fields filled outside their registration",
                    ids.fieldCount());
        }
        return ids;
    }

    private boolean registryIdBridgeActive() {
        return syntheticClasses.containsKey(com.retromod.shim.fabric.FabricRegistryIdSynthetic.INTERNAL)
                || postProcessFlags.contains(com.retromod.shim.forge.RegistryIdBridgeSynthetic.STATIC_FIELDS_FLAG)
                || postProcessFlags.contains(com.retromod.shim.forge.RegistryIdBridgeSynthetic.FORGE_STATIC_FIELDS_FLAG)
                // 1.21.2 and newer build an entity type's translation key and loot table from its id.
                || syntheticClasses.containsKey(com.retromod.shim.common.EntityTypeBuildKeyAdapter.BRIDGE);
    }

    /** A class with only its names remapped, the way the class pass will name them. */
    private org.objectweb.asm.tree.ClassNode remappedView(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        // No redirect or adapter runs, so a jar-wide pre-pass can recognize Minecraft calls in
        // any mapping.
        Remapper remapper = providerPreservingRemapper(globalRemapper(), reader.getClassName());
        reader.accept(remapper == null ? node : new ClassRemapper(node, remapper),
                ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return node;
    }

    /** Use {@code ids} for every class this transformer handles until {@link #clearJarRegistryIds}. */
    public void setJarRegistryIds(com.retromod.shim.common.StaticFieldRegistryIds ids) {
        this.jarRegistryIds = ids != null ? ids : com.retromod.shim.common.StaticFieldRegistryIds.NONE;
    }

    public void clearJarRegistryIds() {
        this.jarRegistryIds = com.retromod.shim.common.StaticFieldRegistryIds.NONE;
    }

    /** Use {@code ids} on the current thread, restoring the previous ones on close. */
    public JarClassBytesScope pushJarRegistryIds(com.retromod.shim.common.StaticFieldRegistryIds ids) {
        ArrayDeque<com.retromod.shim.common.StaticFieldRegistryIds> scoped = scopedJarRegistryIds.get();
        scoped.push(ids != null ? ids : com.retromod.shim.common.StaticFieldRegistryIds.NONE);
        // Closing the scope twice pops only once.
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            ArrayDeque<com.retromod.shim.common.StaticFieldRegistryIds> current = scopedJarRegistryIds.get();
            if (!current.isEmpty()) current.pop();
            if (current.isEmpty()) scopedJarRegistryIds.remove();
        };
    }

    private com.retromod.shim.common.StaticFieldRegistryIds currentJarRegistryIds() {
        // A nested jar's per-thread ids win over the instance-wide ones.
        ArrayDeque<com.retromod.shim.common.StaticFieldRegistryIds> scoped = scopedJarRegistryIds.get();
        return scoped.isEmpty() ? jarRegistryIds : scoped.peek();
    }

    // The final host methods the jar being transformed opens through the access files its loader
    // applies. Scoped like the registry ids above. Null means no jar is in scope, so nothing is known.
    private volatile Set<String> jarOpenedFinals;
    private final ThreadLocal<ArrayDeque<Optional<Set<String>>>> scopedJarOpenedFinals =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** Use {@code opened} for every class this transformer handles until {@link #clearJarOpenedFinals}. */
    public void setJarOpenedFinals(Set<String> opened) {
        this.jarOpenedFinals = opened;
    }

    public void clearJarOpenedFinals() {
        this.jarOpenedFinals = null;
    }

    /** Use {@code opened} on the current thread, restoring the previous set on close; null means unknown. */
    public JarClassBytesScope pushJarOpenedFinals(Set<String> opened) {
        ArrayDeque<Optional<Set<String>>> scoped = scopedJarOpenedFinals.get();
        scoped.push(Optional.ofNullable(opened));
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            ArrayDeque<Optional<Set<String>>> current = scopedJarOpenedFinals.get();
            if (!current.isEmpty()) current.pop();
            if (current.isEmpty()) scopedJarOpenedFinals.remove();
        };
    }

    private Set<String> currentJarOpenedFinals() {
        ArrayDeque<Optional<Set<String>>> scoped = scopedJarOpenedFinals.get();
        return scoped.isEmpty() ? jarOpenedFinals : scoped.peek().orElse(null);
    }

    /** Whether {@code classBytes} is a class of the jar in scope, whose access files the opened finals came from. */
    private boolean inCurrentJar(byte[] classBytes) {
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        if (provider == null) return false;
        try {
            return provider.apply(new ClassReader(classBytes).getClassName()) != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Prove from jar-local bytes and the host hierarchy that one class extends another. */
    public static boolean currentJarClassInheritsFrom(String child, String ancestor) {
        return INSTANCE.inheritsFrom(child, ancestor);
    }

    /** A class's shape for the final-override pass: the jar's own as the class pass names it, a linked library's, or the host's. */
    private com.retromod.shim.common.HostFinalOverrideAdapter.Shape finalOverrideShape(String internalName) {
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        if (provider == null) return lookUpFinalOverrideShape(null, internalName);
        // Each jar's answers are kept for its whole pass, so a base class is read once, not once per subclass.
        return jarShapeCaches.computeIfAbsent(provider, scope -> new ConcurrentHashMap<>())
                .computeIfAbsent(internalName, name -> Optional.ofNullable(lookUpFinalOverrideShape(provider, name)))
                .orElse(null);
    }

    private com.retromod.shim.common.HostFinalOverrideAdapter.Shape lookUpFinalOverrideShape(
            Function<String, byte[]> provider, String internalName) {
        byte[] jarClass = provider != null ? provider.apply(internalName) : null;
        if (jarClass != null) return jarClassShape(jarClass, internalName);
        byte[] library = linkedLibraries.classBytes(internalName);
        if (library != null) return jarClassShape(library, null);
        return hostClassShapes.computeIfAbsent(internalName, name -> Optional.ofNullable(readHostClassShape(name)))
                .orElse(null);
    }

    /** A jar class with its names remapped as the class pass will; {@code remapAs} null keeps them as they are. */
    private com.retromod.shim.common.HostFinalOverrideAdapter.Shape jarClassShape(byte[] bytes, String remapAs) {
        try {
            ClassReader reader = new ClassReader(bytes);
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            Remapper remapper = remapAs == null ? null : providerPreservingRemapper(globalRemapper(), remapAs);
            reader.accept(remapper == null ? node : new ClassRemapper(node, remapper),
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return com.retromod.shim.common.HostFinalOverrideAdapter.shapeOf(node, false);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A host class's shape, from the indexed target jar first, as {@link SuperclassSafety} reads it, then from loader resources. */
    private com.retromod.shim.common.HostFinalOverrideAdapter.Shape readHostClassShape(String internalName) {
        // The JDK is left out: its finals do not move between Minecraft versions.
        if (internalName.startsWith("java/")) return null;
        FuzzyMethodResolver resolver = fuzzyResolver;
        if (resolver != null && resolver.isIndexed() && resolver.hasClass(internalName)) {
            Map<String, Integer> methods = new HashMap<>();
            for (FuzzyMethodResolver.MethodInfo method : resolver.getDeclaredMethods(internalName)) {
                methods.put(method.name() + method.descriptor(), method.access());
            }
            boolean isInterface = (resolver.classAccess(internalName) & Opcodes.ACC_INTERFACE) != 0;
            List<String> parents = resolver.directSupertypes(internalName);
            String superName = isInterface || parents.isEmpty() ? null : parents.get(0);
            return new com.retromod.shim.common.HostFinalOverrideAdapter.Shape(superName, methods, true, false);
        }
        // A Minecraft name the index lacks would be answered by Retromod's own classpath here.
        if (resolver != null && resolver.isIndexed() && internalName.startsWith("net/minecraft/")) return null;
        org.objectweb.asm.tree.ClassNode node = ClassResourceInspector.read(internalName);
        if (node == null && internalName.startsWith("net/minecraft/")) noteUnreadableMinecraft();
        return node == null ? null : com.retromod.shim.common.HostFinalOverrideAdapter.shapeOf(node, true);
    }

    /** Whether the host has this exact method on {@code owner} or a superclass, as far as its classes can be read. */
    private boolean hostHasMethod(String owner, String name, String descriptor) {
        FuzzyMethodResolver resolver = fuzzyResolver;
        if (resolver != null && resolver.isIndexed()) return resolver.hasMethod(owner, name, descriptor);
        Set<String> seen = new HashSet<>();
        for (String current = owner; current != null && seen.add(current); ) {
            com.retromod.shim.common.HostFinalOverrideAdapter.Shape shape = hostClassShapes
                    .computeIfAbsent(current, type -> Optional.ofNullable(readHostClassShape(type))).orElse(null);
            if (shape == null) return false;
            if (shape.methods().containsKey(name + descriptor)) return true;
            current = shape.superName();
        }
        return false;
    }

    /** Says once when no Minecraft class can be read at all, as in an offline transform with no --mc-jar. */
    private void noteUnreadableMinecraft() {
        if (finalOverrideHostNoted.get()) return;
        if (ClassResourceInspector.read("net/minecraft/world/entity/Entity") != null
                || ClassResourceInspector.read("net/minecraft/class_1297") != null) {
            return;
        }
        if (finalOverrideHostNoted.compareAndSet(false, true)) {
            LOGGER.warn("No Minecraft classes could be read, so Retromod left overrides of methods Minecraft made "
                    + "final as they are. Pass --mc-jar to check them.");
        }
    }

    /**
     * Find one uniquely proven inherited redirect for an instance call, or for a static call that
     * names a subclass, as {@code blit(...)} inside a mod's screen does.
     */
    private MethodTarget inheritedMethodTarget(
            int opcode, String owner, String name, String descriptor) {
        boolean superCall = opcode == Opcodes.INVOKESPECIAL && !name.startsWith("<");
        boolean staticCall = opcode == Opcodes.INVOKESTATIC;
        if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE && !superCall && !staticCall) {
            return null;
        }
        Set<InheritedMethodRedirect> candidates =
                inheritedMethodRedirects.get(new MethodShape(name, descriptor));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        MethodTarget match = null;
        for (InheritedMethodRedirect candidate : candidates) {
            // A static call has no receiver to move, so only a static-to-static redirect fits it.
            if (staticCall && (candidate.target().devirtualize()
                    || !candidate.target().desc().equals(descriptor))) {
                continue;
            }
            // A class the current mod keeps under its old name takes no redirect keyed on it.
            if (!inheritsFrom(owner, candidate.oldOwner()) || keepsOldName(candidate.oldOwner())) {
                continue;
            }
            // A redirect limited to older mods leaves a newer mod's call alone whatever class it names.
            if (skipsCurrentMod(new MethodKey(candidate.oldOwner(), name, descriptor))) {
                continue;
            }
            // A super call can only become a static helper call; an instance target would need a
            // receiver type the INVOKESPECIAL rules do not allow.
            if (superCall && (!takesReceiverAsFirstArgument(candidate.target(), descriptor)
                    || !superCallReaches(owner, candidate.oldOwner(), name, descriptor))) {
                continue;
            }
            // A class on the way that declares the method runs it, as with a mob that overrides the
            // old finalizeSpawn and calls it on itself. The redirect would skip that method.
            if ((opcode == Opcodes.INVOKEVIRTUAL || staticCall)
                    && jarClassDeclaresOnTheWay(owner, candidate.oldOwner(), name, descriptor)) {
                continue;
            }
            if (match != null && !match.equals(candidate.target())) {
                LOGGER.debug("Declined ambiguous inherited method redirect for {}.{}{}",
                        owner, name, descriptor);
                return null;
            }
            match = candidate.target();
        }
        return match;
    }

    private static boolean takesReceiverAsFirstArgument(MethodTarget target, String descriptor) {
        return target.devirtualize() || Type.getArgumentTypes(target.desc()).length
                == Type.getArgumentTypes(descriptor).length + 1;
    }

    /**
     * A {@code super.m()} call names the direct superclass, which is often a mod base class that
     * never declared {@code m}. The call then reached the removed method on {@code ancestor}, and
     * only then may it take that method's redirect. Any class on the way that declares the method
     * keeps the call, since that override is what it runs. A class that cannot be read stops the
     * walk: a Minecraft class there cannot declare the removed method, anything else is unproven.
     */
    private boolean superCallReaches(String owner, String ancestor, String name, String descriptor) {
        Function<String, byte[]> provider = hierarchyClasses();
        String current = owner;
        for (int depth = 0; current != null && !current.equals(ancestor) && depth < 64; depth++) {
            byte[] bytes = readClassBytes(current, provider);
            if (bytes == null) return current.startsWith("net/minecraft/");
            ClassReader reader;
            try {
                reader = new ClassReader(bytes);
            } catch (RuntimeException e) {
                return false;
            }
            if (declaresMethod(reader, name, descriptor)) return false;
            current = reader.getSuperName();
        }
        return ancestor.equals(current);
    }

    /** Whether a class from the call's owner up to, not including, {@code ancestor} declares the method. */
    private boolean jarClassDeclaresOnTheWay(
            String owner, String ancestor, String name, String descriptor) {
        // The JVM resolves a virtual or static call by walking up from the class it names. Classes
        // are read the way inheritsFrom reads them, so a base class from another mod or library
        // that proves the hierarchy is checked too.
        Function<String, byte[]> provider = hierarchyClasses();
        String current = owner;
        for (int depth = 0; current != null && !current.equals(ancestor) && depth < 64; depth++) {
            // Minecraft's classes cannot declare the removed member, and an unreadable class
            // proves nothing, so either ends the walk.
            if (current.startsWith("net/minecraft/")) return false;
            byte[] bytes = readClassBytes(current, provider);
            if (bytes == null) return false;
            ClassReader reader;
            try {
                reader = new ClassReader(bytes);
            } catch (RuntimeException e) {
                return false;
            }
            if (declaresMethod(reader, name, descriptor)) return true;
            current = reader.getSuperName();
        }
        return false;
    }

    /** Whether a class declares the method a call names after the remap. */
    private boolean declaresMethod(ClassReader reader, String name, String descriptor) {
        // The jar's bytes are as the mod shipped them, so a declaration can still carry
        // intermediary or SRG names and old class names. Compare it as written and as remapped.
        String className = reader.getClassName();
        Remapper remapper = cachedRemapper;
        boolean[] declares = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String desc,
                    String signature, String[] exceptions) {
                if (declares[0]) return null;
                if (method.equals(name) && (desc.equals(descriptor)
                        || resolveDescriptor(desc).equals(descriptor))) {
                    declares[0] = true;
                } else if (remapper != null && !method.startsWith("<")) {
                    String remappedDesc = remapper.mapMethodDesc(desc);
                    declares[0] = remapper.mapMethodName(className, method, desc).equals(name)
                            && (remappedDesc.equals(descriptor)
                                || resolveDescriptor(remappedDesc).equals(descriptor));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return declares[0];
    }

    /**
     * Prove a subtype relationship from class bytes in the current mod first, then continue through
     * the indexed Minecraft hierarchy. No name similarity or package heuristic is accepted here.
     */
    private boolean inheritsFrom(String child, String ancestor) {
        if (child == null || ancestor == null) return false;
        Function<String, byte[]> provider = hierarchyClasses();
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(child);

        while (!pending.isEmpty() && visited.size() < 128) {
            String current = pending.pop();
            if (!visited.add(current)) continue;
            if (ancestor.equals(current)) return true;

            byte[] bytes = readClassBytes(current, provider);
            if (bytes != null) {
                try {
                    ClassReader reader = new ClassReader(bytes);
                    String superName = reader.getSuperName();
                    if (superName != null && !visited.contains(superName)) pending.push(superName);
                    for (String iface : reader.getInterfaces()) {
                        if (iface != null && !visited.contains(iface)) pending.push(iface);
                    }
                    continue;
                } catch (RuntimeException ignored) {
                    // One unreadable side branch, such as a newer JDK's java/lang/Object reached
                    // through an interface, cannot disprove a superclass path still on the stack.
                    continue;
                }
            }

            FuzzyMethodResolver resolver = fuzzyResolver;
            if (resolver != null && resolver.isIndexed()
                    && resolver.isSubtypeOf(current, ancestor)) {
                return true;
            }
            // A jar's own classes are read as shipped, so a Fabric mod's block names its
            // superclass in intermediary, such as class_2346, which a 26.x host has only under
            // the name this transform gives it. That name is the same class, so the walk goes on
            // from there.
            String renamed = classRedirects.get(current);
            if (renamed != null && !visited.contains(renamed)) pending.push(renamed);
        }
        return false;
    }

    /** Reads the class entries of an in-memory nested jar for frame hierarchy resolution. */
    public static Map<String, byte[]> readJarClassBytes(byte[] jarData) throws IOException {
        return readJarClassBytes(jarData, NestedArchiveBudget.defaults());
    }

    /**
     * Reads nested classes while charging the caller's shared archive budget. Callers that recurse
     * through several bundled jars can reuse one budget so each sibling cannot claim a fresh cap.
     */
    public static Map<String, byte[]> readJarClassBytes(
            byte[] jarData, NestedArchiveBudget budget) throws IOException {
        Objects.requireNonNull(jarData, "jarData");
        Objects.requireNonNull(budget, "budget");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Set<String> entryNames = new HashSet<>();
        try (var jar = new java.util.zip.ZipInputStream(new ByteArrayInputStream(jarData))) {
            java.util.zip.ZipEntry entry;
            while ((entry = jar.getNextEntry()) != null) {
                String entryName = ZipSecurity.safeEntryName(entry.getName());
                String canonicalName = ZipSecurity.canonicalEntryName(entryName);
                // A repeated folder entry carries no content, so it is skipped.
                if (!ZipSecurity.firstSighting(entryNames, canonicalName, entry.isDirectory(),
                        () -> "duplicate nested JAR entry: " + entryName)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    budget.reserve(0, entryName);
                    continue;
                }
                long allowance = budget.beginRead(
                        ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, entryName);
                byte[] bytes = ZipSecurity.safeReadAllBytes(jar, allowance);
                budget.completeRead(allowance, bytes.length);
                if (!entryName.endsWith(".class")) {
                    continue;
                }
                String name = entryName.substring(0, entryName.length() - 6);
                if (classes.putIfAbsent(name, bytes) != null) {
                    throw new IOException("duplicate nested JAR class: " + name);
                }
            }
        }
        return classes;
    }

    /** Shared byte and entry budget for one nested-archive tree. */
    public static final class NestedArchiveBudget {
        private static final int DEFAULT_MAX_ENTRIES = 100_000;

        private final long maxBytes;
        private final int maxEntries;
        private long usedBytes;
        private int usedEntries;

        public NestedArchiveBudget(long maxBytes, int maxEntries) {
            if (maxBytes < 0 || maxEntries < 0) {
                throw new IllegalArgumentException("nested archive limits cannot be negative");
            }
            this.maxBytes = maxBytes;
            this.maxEntries = maxEntries;
        }

        public static NestedArchiveBudget defaults() {
            return new NestedArchiveBudget(
                    ZipSecurity.DEFAULT_MAX_TOTAL_SIZE, DEFAULT_MAX_ENTRIES);
        }

        public synchronized void reserve(long bytes, String entryName) throws IOException {
            if (bytes < 0) {
                throw new IllegalArgumentException("nested archive entry size cannot be negative");
            }
            if (usedEntries >= maxEntries) {
                throw new IOException("nested archive exceeds " + maxEntries
                        + " entries at " + entryName);
            }
            if (bytes > maxBytes - usedBytes) {
                throw new IOException("nested archive exceeds " + maxBytes
                        + " expanded bytes at " + entryName);
            }
            usedEntries++;
            usedBytes += bytes;
        }

        /** Begins a bounded archive-entry read and returns the permitted byte count. */
        public synchronized long beginRead(long requestedBytes, String entryName)
                throws IOException {
            if (requestedBytes <= 0) {
                throw new IllegalArgumentException("nested archive read limit must be positive");
            }
            if (usedEntries >= maxEntries) {
                throw new IOException("nested archive exceeds " + maxEntries
                        + " entries at " + entryName);
            }
            usedEntries++;
            long remaining = maxBytes - usedBytes;
            if (remaining <= 0) {
                throw new IOException("nested archive exceeds " + maxBytes
                        + " expanded bytes at " + entryName);
            }
            long allowance = Math.min(requestedBytes, remaining);
            usedBytes += allowance;
            return allowance;
        }

        /** Records the actual size of a completed bounded read. */
        public synchronized void completeRead(long allowance, long actualBytes) {
            if (allowance <= 0 || actualBytes < 0 || actualBytes > allowance) {
                throw new IllegalArgumentException("invalid nested archive read completion");
            }
            usedBytes -= allowance - actualBytes;
        }

        public synchronized long usedBytes() {
            return usedBytes;
        }

        public synchronized int usedEntries() {
            return usedEntries;
        }
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
        if (INSTANCE == null) {
            return commonSuperFallback(type1, type2);
        }
        // A writer that runs after the remap joins renamed types, while the jar's bytes still name
        // the old ones (a Fabric screen's intermediary superclass), so the walk renames them too.
        Remapper renames = INSTANCE.globalRemapper();
        String shared = commonSuperViaBytes(type1, type2, INSTANCE.frameHierarchy(),
                renames == null ? Function.identity() : renames::mapType, INSTANCE::targetSuperclass);
        return shared != null ? shared : commonSuperFallback(type1, type2);
    }

    /**
     * The jar's own classes, then the translated jars it builds on. An addon's join of two of its
     * library's types (Sophisticated Backpacks returning Sophisticated Core's results) has its
     * answer only there, and widening it to {@code Object} fails verification.
     */
    static Function<String, byte[]> withLibraries(Function<String, byte[]> jar, SyntheticLibraries libraries) {
        if (libraries == null || libraries.isEmpty()) return jar;
        return name -> {
            byte[] own = jar == null ? null : jar.apply(name);
            return own != null ? own : libraries.classBytes(name);
        };
    }

    /** The class hierarchy a frame join reads: the jar's own classes, then its linked libraries. */
    private Function<String, byte[]> frameHierarchy() {
        return hierarchyClasses();
    }

    /**
     * The classes a redirect's hierarchy walks read before the class loader: the jar's own, then the translated
     * libraries it builds on. inheritsFrom and the walks that look for a declaration on the way read the same ones,
     * so an addon is bridged alike whether its library is linked or on the class path.
     */
    private Function<String, byte[]> hierarchyClasses() {
        // A join of two types from a jar's bundled library (Create's BlockEntry and ItemEntry from
        // Registrate) has its answer only in that library, so the walk reads bundled jars too.
        return withLibraries(BundledJarClasses.withBundled(currentJarClassBytesProvider()), linkedLibraries);
    }

    /** The shared ancestor from the jar's classes and the target Minecraft index, or the conservative guess. */
    static String resolveCommonSuperClass(String type1, String type2,
            Function<String, byte[]> provider, Function<String, String> targetSuperclass) {
        // Offline, Minecraft's classes are only in the index, so a mod class that extends one
        // needs it to reach the ancestor it shares with a Minecraft class.
        String shared = commonSuperViaBytes(type1, type2, provider, Function.identity(), targetSuperclass);
        return shared != null ? shared : commonSuperFallback(type1, type2);
    }

    // new Foo(args) -> Foo.factory(args), for constructors replaced by static factories
    // (e.g. new ResourceLocation(s) -> Identifier.parse(s) in 26.1).
    private final Map<ConstructorKey, FactoryTarget> constructorRedirects = new ConcurrentHashMap<>(16);

    // GETFIELD -> getter(), PUTFIELD -> setter(), for fields that became private.
    private final Map<FieldKey, FieldAccessorTarget> fieldAccessorRedirects = new ConcurrentHashMap<>(16);
    // Field accessors that also apply when the bytecode names a subclass as the field's owner,
    // keyed by "name:descriptor". Each is applied only once the owner's hierarchy is proven.
    private final Map<String, Set<InheritedFieldAccessor>> inheritedFieldAccessors = new ConcurrentHashMap<>(4);
    // Field static bridges that also apply when the bytecode names a subclass as the field's owner,
    // keyed by "name:descriptor" and applied only once the owner's hierarchy is proven.
    private final Map<String, Set<InheritedFieldStaticBridge>> inheritedFieldStaticBridges = new ConcurrentHashMap<>(4);
    // Static field redirects that also apply when the bytecode names a mod subtype as the owner,
    // keyed by field name. The target is looked up in fieldRedirects, so a claim removes both.
    private final Map<String, Set<InheritedStaticField>> inheritedStaticFields = new ConcurrentHashMap<>(4);
    /** GETSTATIC of a removed static field -> a (collection-field + optional enum-arg + accessor-call + cast) sequence. */
    private final Map<FieldKey, StaticFieldAccessor> staticFieldAccessors = new ConcurrentHashMap<>(64);
    /** Instance field moved behind a public sibling field's accessors: GETFIELD owner.name ->
     *  GETFIELD owner.hopField + INVOKEVIRTUAL getter (26.2 Minecraft.screen -> mc.gui.screen()). */
    private final Map<FieldKey, FieldHopAccessor> fieldHopAccessors = new ConcurrentHashMap<>(8);
    /** Instance field whose state moved somewhere the receiver can't reach: GETFIELD/PUTFIELD become
     *  INVOKESTATIC bridge calls that consume the receiver as an ignored arg (26.2 Options.hideGui). */
    private final Map<FieldKey, FieldStaticBridge> fieldStaticBridges = new ConcurrentHashMap<>(8);
    /** Removed static field read and written through static calls: GETSTATIC takes the getter, PUTSTATIC the setter. */
    private final Map<FieldKey, FieldStaticBridge> staticFieldBridges = new ConcurrentHashMap<>(4);
    /** Static field bridges that stand in for a removed field only until another bridge claims the field. */
    private final Set<FieldKey> standInStaticFieldBridges = ConcurrentHashMap.newKeySet();
    /** The only descriptors a field static bridge applies to, for a field that kept its name but changed type. */
    private final Map<FieldKey, Set<String>> fieldStaticBridgeTypes = new ConcurrentHashMap<>(4);
    /** Globally unique (SRG) instance field names read through a public getter, any owner. */
    private final Map<String, MethodTarget> uniqueFieldGetters = new ConcurrentHashMap<>(4);
    private final Map<String, FieldStaticBridge> uniqueFieldStaticBridges = new ConcurrentHashMap<>(4);
    /** Static fields that kept their name but changed type, read through a converter call. */
    private final List<StaticFieldTypeDrift> staticFieldTypeDrifts = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Readers for another mod's static fields, keyed by the descriptor the old mod reads them at. */
    private final Map<String, MethodTarget> libraryStaticFieldReaders = new ConcurrentHashMap<>(2);
    private static final String LIBRARY_FIELD_READER_DESC = "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;";

    // Super constructor descriptor changes: a parent ctor gained required params in newer
    // MC; pushes extra args before INVOKESPECIAL (e.g. Button gained CreateNarration).
    private final Map<ConstructorKey, SuperCtorRedirect> superCtorRedirects = new ConcurrentHashMap<>(8);
    /** Argument shuffles for super-constructor changes that reorder parameters, keyed like above. */
    private final Map<ConstructorKey, java.util.function.Consumer<MethodVisitor>> superCtorStackAdapters =
            new ConcurrentHashMap<>(4);
    private static final String STACK_ADAPTER_MODE = "__STACK_ADAPTER__";
    /** Types whose {@code instanceof} checks go to a static helper, keyed by the type after redirects. */
    private final Map<String, InstanceofHelperAdapter.Helper> instanceofHelpers = new ConcurrentHashMap<>(2);
    /** Field renames that apply to one descriptor only, keyed {@code owner.name:desc}. */
    private final Map<String, String> typedFieldRenames = new ConcurrentHashMap<>(16);

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
    /** Post-process adapters a shim turned on that register no synthetic class to key on. */
    private final Set<String> postProcessFlags = ConcurrentHashMap.newKeySet();
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
    /** Phantom targets already named in a warning. Offline resets keep them, so a batch names each once. */
    private final Set<String> reportedPhantoms = ConcurrentHashMap.newKeySet();
    /** Held by the thread running the phantom sweep (see ensureTargetsValidated). */
    private final java.util.concurrent.locks.ReentrantLock sweepLock =
            new java.util.concurrent.locks.ReentrantLock();
    /** True while this thread is inside the Java agent's class-load callback. */
    private static final ThreadLocal<Boolean> IN_CLASS_LOAD_CALLBACK =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

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
                LOGGER.debug(RetromodLog.DETAIL, "MC JAR not found on classpath, fuzzy resolver disabled");
                return;
            }

            FuzzyMethodResolver resolver = new FuzzyMethodResolver();
            resolver.indexJar(mcJarPath);
            if (resolver.isIndexed()) {
                this.fuzzyResolver = resolver;
                LOGGER.debug(RetromodLog.DETAIL, "Fuzzy resolver initialized: {} classes, {} methods, {} fields",
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
     * The pattern rules describe 26.1 renames. On an older host the original call is the correct
     * one, and a rule such as displayClientMessage to sendSystemMessage turns a working call into a
     * VerifyError (#306), so the transform pass applies them only where the host is 26.1 or newer.
     * {@link AutoFixEngine} keeps the ungated rules: it acts only on a logged missing-member error.
     */
    PatternHeuristics patternHeuristicsForHost() {
        return RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)
                ? patternHeuristics : null;
    }

    /**
     * Register a method redirect: oldOwner.oldName(oldDesc) -> newOwner.newName(newDesc).
     */
    public void registerMethodRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (!admitsMethod(oldOwner, oldName, oldDesc)) return;
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
        recordHelperOwnerKind(newOwner);
        
        LOGGER.debug("Registered method redirect: {}.{}{} -> {}.{}{}",
                oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
    }

    /**
     * Register a redirect to a static helper that wraps a host method which still exists, taking
     * the receiver as its first argument. A {@code super.m()} call in a mod's override of that
     * method keeps its {@code INVOKESPECIAL}: the helper reaches the method by virtual dispatch,
     * which would run the override again and recurse until the stack overflows.
     */
    public void registerWrappingMethodRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (!admitsMethod(oldOwner, oldName, oldDesc)) return;
        registerMethodRedirect(oldOwner, oldName, oldDesc, newOwner, newName, newDesc, true);
        wrappingMethodRedirects.add(new MethodKey(oldOwner, oldName, oldDesc));
    }

    /** Applies an exact-owner redirect, or with {@code <init>} a constructor redirect, only to mods built before {@code firstVersionWithout}. */
    public void limitRedirectToModsBefore(String owner, String name, String desc,
            String firstVersionWithout) {
        if (!admitsMethod(owner, name, desc)) return;
        // The host still has the member. A running game registers every shim its host can take,
        // whatever version a mod was built for, so a mod written for the newer behavior would
        // be rewritten too.
        legacyOnlyRedirects.put(new MethodKey(owner, name, desc), firstVersionWithout);
    }

    /** Points a {@code super} call that a redirect sends to a helper at that helper's {@code superName}. */
    public void registerSuperCallHelper(String helperOwner, String helperName, String helperDesc,
            String superName) {
        // A helper that runs the receiver's own override of a removed method cannot also answer
        // that override's super call, because it would run the override again.
        superCallHelperNames.put(new MethodKey(helperOwner, helperName, helperDesc), superName);
    }

    /** Sets the Minecraft version the mod about to be transformed was built for, or null when unknown. */
    public void setModSourceVersion(String declared) {
        // Each transform path sets it per mod and clears it afterwards. A declared range such as
        // ~1.20.1, >=1.21 <1.22 or [1.20.1,1.21) counts as its lowest version.
        java.util.regex.Matcher version =
                declared == null ? null : FIRST_VERSION_NUMBER.matcher(declared);
        modSourceVersion = version != null && version.find() ? version.group() : null;
        modBuildVersion = null;
        modLibraries = SyntheticLibraries.NONE;
    }

    /** The Minecraft version the mod being transformed was built for, or null when unknown. */
    public String getModSourceVersion() {
        return modSourceVersion;
    }

    /**
     * Reads the jar the mod about to be transformed came from, after its source version is set. A declared floor can
     * lag far behind the build, and the data the mod saved is read as data of the newest version the jar shows.
     */
    public void setModSourceJar(java.nio.file.Path jar) {
        modBuildVersion = jar == null ? null : ModVersionDetector.metadataOnly().buildVersion(jar, modSourceVersion);
        modLibraries = SyntheticLibraries.NONE;
    }

    /** The Minecraft version the mod's saved data comes from: the newest its jar shows, else its source version. */
    public String getModBuildVersion() {
        String build = modBuildVersion;
        return build != null ? build : modSourceVersion;
    }

    private static final java.util.regex.Pattern FIRST_VERSION_NUMBER =
            java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)+");

    /** Whether a redirect limited by {@link #limitRedirectToModsBefore} skips the current mod. */
    private boolean skipsCurrentMod(MethodKey key) {
        // A redirect keyed on a class the current mod keeps under its old name skips it too.
        if (keepsOldName(key.owner())) return true;
        if (legacyOnlyRedirects.isEmpty()) return false;
        String firstVersionWithout = legacyOnlyRedirects.get(key);
        String source = limitVersion();
        // A mod whose version is unknown keeps the redirect, as it keeps every shim for its host.
        return firstVersionWithout != null && source != null
                && RetromodVersion.compareMcVersions(source, firstVersionWithout) >= 0;
    }

    /** The version a redirect limited to older mods is compared with, or null when the mod declares none. */
    private String limitVersion() {
        // A declared floor such as >=1.21 can lag the build, so the newest version the jar shows counts. A mod that
        // declares no version keeps every shim, even where its metadata format alone implies a floor, such as 1.20.5
        // for neoforge.mods.toml, so the same jar takes the same redirects on every path.
        return modSourceVersion == null ? null : getModBuildVersion();
    }

    /** Applies a class redirect, and every redirect keyed on that class, only to mods built before {@code firstVersionWithout}. */
    public void limitClassRedirectToModsBefore(String oldName, String firstVersionWithout) {
        if (!admitsType(oldName)) return;
        // The host has a class under the old name again, as Forge 1.20.2 did when it rebuilt its
        // networking around a new PacketDistributor. A mod written for that class keeps it.
        legacyOnlyClassRedirects.put(oldName, firstVersionWithout);
    }

    /** Leaves {@code oldName} alone in a jar that ships, or is linked with, a library defining {@code libraryClass}. */
    public void yieldClassRedirectToLibrary(String oldName, String libraryClass) {
        if (!admitsType(oldName)) return;
        // The redirect was chosen without seeing that library, which a translation pass can carry.
        libraryYieldingClassRedirects.put(oldName, libraryClass);
    }

    /** Whether the current mod keeps {@code name} instead of taking the class redirect keyed on it. */
    private boolean keepsOldName(String name) {
        return keepsOldName(name, transformedJarDefines);
    }

    // One predicate for the remap path, which asks for every redirected name.
    private final Predicate<String> transformedJarDefines = this::currentJarDefines;

    private boolean keepsOldName(String name, Predicate<String> jarDefines) {
        if (name == null) return false;
        return definesOwnLibraryClass(name, jarDefines) || yieldsToLibrary(name, jarDefines)
                || limitedToOlderMods(name);
    }

    /** Whether the jar being translated defines a class a redirect would move away from it. */
    private boolean definesOwnLibraryClass(String name, Predicate<String> jarDefines) {
        // A library is never moved away from itself: GeckoLib 4 moved onto com.geckolib could not load its own
        // classes. A copy of a host name, such as Forge Config API Port's ForgeConfigSpec, keeps following its
        // users, which the redirect moves too, so the calls between them still link.
        return classRedirects.containsKey(name) && !isHostName(name) && jarDefines.test(name);
    }

    /** Whether the redirect keyed on {@code name} yields to a library this mod ships or is linked with. */
    private boolean yieldsToLibrary(String name, Predicate<String> jarDefines) {
        if (libraryYieldingClassRedirects.isEmpty()) return false;
        String libraryClass = libraryYieldingClassRedirects.get(name);
        return libraryClass != null && (jarDefines.test(libraryClass) || modLibraries.defines(libraryClass));
    }

    /** Whether the redirect keyed on {@code name} is for mods older than the current one. */
    private boolean limitedToOlderMods(String name) {
        if (legacyOnlyClassRedirects.isEmpty()) return false;
        String firstVersionWithout = legacyOnlyClassRedirects.get(name);
        String source = limitVersion();
        // A mod whose version is unknown keeps the redirect, as it keeps every shim for its host.
        return firstVersionWithout != null && source != null
                && RetromodVersion.compareMcVersions(source, firstVersionWithout) >= 0;
    }

    /** Whether the jar the current thread transforms has a class file for {@code name}. */
    private boolean currentJarDefines(String name) {
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        if (provider == null) return false;
        // The CLI and AOT providers read and inflate the entry on every call, so each answer is kept per jar.
        JarDefinesCache cache = jarDefinesCache;
        if (cache == null || cache.provider() != provider) {
            cache = new JarDefinesCache(provider, new ConcurrentHashMap<>());
            jarDefinesCache = cache;
        }
        return cache.answers().computeIfAbsent(name, key -> provider.apply(key) != null);
    }

    /** The answers {@link #currentJarDefines} gave for one jar's class bytes provider. */
    private record JarDefinesCache(Function<String, byte[]> provider, Map<String, Boolean> answers) {}

    private volatile JarDefinesCache jarDefinesCache;

    // Names Minecraft, a loader, Fabric API, Mixin, or Retromod supply to every mod.
    private static final String[] HOST_NAME_PREFIXES = {
            "net/minecraft/", "com/mojang/", "net/minecraftforge/", "net/neoforged/", "cpw/mods/",
            "net/fabricmc/", "org/quiltmc/", "org/spongepowered/", "com/llamalad7/mixinextras/", "com/retromod/"};

    private static boolean isHostName(String name) {
        for (String prefix : HOST_NAME_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The name the current mod's reference to {@code name} takes once class redirects apply. */
    private String redirectedClass(String name) {
        String renamed = classRedirects.get(name);
        return renamed == null || keepsOldName(name) ? name : renamed;
    }

    /** One class redirect hop for the current mod, honoring its version limits; {@code name} when none applies. */
    public String classRedirectForCurrentMod(String name) {
        return redirectedClass(name);
    }

    /** The same hop for a pass after the class pass, where {@code jarDefines} answers which classes the jar holds. */
    public String classRedirectForJar(String name, Predicate<String> jarDefines) {
        String renamed = classRedirects.get(name);
        return renamed == null || keepsOldName(name, jarDefines) ? name : renamed;
    }

    /**
     * Register an exact method redirect that may also match an inherited call whose bytecode owner
     * is a subclass from the mod jar. Java commonly emits {@code SomeModEntity.oldMethod()} with
     * {@code SomeModEntity} as the constant-pool owner even though Minecraft's base entity declares
     * the method. Such a call is rewritten only when the available class hierarchy proves that the
     * bytecode owner inherits {@code oldOwner}. Unknown and ambiguous hierarchies are left alone.
     */
    public void registerInheritedMethodRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (!admitsMethod(oldOwner, oldName, oldDesc)) return;
        if (oldOwner.equals(newOwner) && oldName.equals(newName) && oldDesc.equals(newDesc)) {
            LOGGER.debug("Ignored identity inherited method-redirect registration for: {}.{}{}",
                    oldOwner, oldName, oldDesc);
            return;
        }

        MethodTarget target = new MethodTarget(newOwner, newName, newDesc);
        inheritedMethodRedirects
                .computeIfAbsent(new MethodShape(oldName, oldDesc),
                        ignored -> ConcurrentHashMap.newKeySet())
                .add(new InheritedMethodRedirect(oldOwner, target));

        // Keep the ordinary exact-owner behavior, including descriptor aliases and diagnostics.
        registerMethodRedirect(oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
        LOGGER.debug("Registered hierarchy-aware method redirect: {}.{}{} -> {}.{}{}",
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
        if (!admitsMethod(oldOwner, oldName, oldDesc)) return;

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
        recordHelperOwnerKind(newOwner);
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
        if (!admitsMethod(remappedOwner, oldName, remappedDesc)) return;
        // A wrapper class folded into its target leaves an alias that points at itself, such as
        // FabricBlockSettings.of() becoming Properties.of() -> Properties.of(). It rewrites nothing,
        // and storing it replaced a real redirect on that key: on NeoForge it erased the
        // Properties.of() id bridge, so every block built from it failed "Block id not set".
        if (!target.devirtualize() && target.owner().equals(remappedOwner)
                && target.name().equals(oldName) && target.desc().equals(remappedDesc)) {
            return;
        }
        MethodKey alias = new MethodKey(remappedOwner, oldName, remappedDesc);
        // The alias keeps the target written for the old names. transformClass's next pass fixes
        // a stale descriptor, but such a target can name a different member than the one written
        // for this call shape (FabricApiShim's ScreenEventsShim.beforeRender over 26.1's
        // ScreenEvents.beforeExtract), and single-pass readers of this table (transformClassSimple,
        // JitRuntime, InstructionLevelTransformer, HybridCompiler) emit it as written. So a target
        // that still names a redirected class never replaces one that names none.
        if (namesRedirectedClass(target)) {
            MethodTarget current = methodRedirects.get(alias);
            if (current != null && !namesRedirectedClass(current)) {
                LOGGER.debug("Kept {} for {}; the alias target {} names a redirected class",
                        current, alias, target);
                return;
            }
        }
        methodRedirects.put(alias, target);
        methodRedirectOwners.add(remappedOwner);
    }

    /** Whether a redirect target's owner or descriptor names a class this pass redirects away. */
    private boolean namesRedirectedClass(MethodTarget target) {
        if (redirectsAway(target.owner())) return true;
        Type method = Type.getMethodType(target.desc());
        for (Type argument : method.getArgumentTypes()) {
            if (redirectsAway(argument)) return true;
        }
        return redirectsAway(method.getReturnType());
    }

    private boolean redirectsAway(Type type) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        return element.getSort() == Type.OBJECT && redirectsAway(element.getInternalName());
    }

    // A redirect limited to older mods names a class the host still has.
    private boolean redirectsAway(String internalName) {
        return classRedirects.containsKey(internalName)
                && !legacyOnlyClassRedirects.containsKey(internalName);
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
     * Use where a method was renamed AND lost a tail arg (e.g. 1.21.5
     * {@code ServerChunkCache.addRegionTicket(TicketType,ChunkPos,int,T)} ->
     * {@code addTicketWithRadius(TicketType,ChunkPos,int)}).
     */
    public void registerArgDropMethodRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (!admitsMethod(oldOwner, oldName, oldDesc)) return;
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
        if (!admitsMethod(oldOwner, oldName, oldDesc)) return;
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
        if (!admitsMethod(owner, name, oldDesc)) return;
        convertingRedirects.put(new MethodKey(owner, name, oldDesc),
                new ConvertingTarget(owner, newName, newDesc, 0, 0,
                        unwrapOwner, unwrapName, unwrapDesc, unwrapItf));
        convertingOwners.add(owner);
    }

    /**
     * Retargets a call and passes its result through a static adapter that takes the result and
     * returns it, as {@code ofLegacyCopy(block)} followed by a guard on the copied properties. The
     * retargeted call stays an ordinary call, so later redirects of it still apply.
     */
    public void registerReturnAdapterRedirect(
            String owner, String name, String oldDesc, String newName, String newDesc,
            String adapterOwner, String adapterName, String adapterDesc) {
        if (!admitsMethod(owner, name, oldDesc)) return;
        convertingRedirects.put(new MethodKey(owner, name, oldDesc),
                new ConvertingTarget(owner, newName, newDesc, 0, 0,
                        adapterOwner, adapterName, adapterDesc, false, true));
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
        if (!admitsMethod(oldOwner, oldName, oldDesc)) return;
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
        if (!admitsMethod(owner, name, desc)) return;
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
        if (!admitsMemberName(owner, name)) return;
        mojangMethodRenames.put(owner + "#" + name, newName);
        LOGGER.debug("Registered method rename: {}.{} -> {}", owner, name, newName);
    }

    /**
     * The name {@link #registerMethodRename} gives {@code owner.name}, or null when none is
     * registered. A bare Mixin selector names no descriptor, so this name-only table is the one
     * rename source that can answer for it.
     */
    public String registeredMethodRename(String owner, String name) {
        return mojangMethodRenames.get(owner + "#" + name);
    }

    /** Renames older Minecraft member names from {@code table} in the mods transformed next; null turns it off. */
    public void setMojangRenames(com.retromod.mapping.MojangRenameTable table) {
        // Each offline mod picks its namespace, so a Fabric mod in the same batch gets none.
        mojangRenames = table == null || table.isEmpty() ? null : table;
        synchronized (inheritedMojangRenames) {
            inheritedMojangRenames.clear();
        }
        mojangRenameOnHost.clear();
        cachedRemapper = null;
    }

    /** The rename table in use, or null when the current namespace takes none. */
    public com.retromod.mapping.MojangRenameTable getMojangRenames() {
        return mojangRenames;
    }

    /** A host method whose meaning changed at {@code firstVersionWithout} while its id stayed. */
    public record OwnOverrideRename(String declaredName, String hostName, String desc, String oldName,
            String firstVersionWithout, int hostMinusOld) {}

    /**
     * Keeps an older mod's own override of {@code declaredName} under {@code oldName}, with every call
     * that names the mod's class, and gives the class a {@code hostName} that answers the host's
     * meaning as {@code oldName() + hostMinusOld}. Calls through host types reach that method.
     */
    public void registerOwnOverrideRename(String declaredName, String hostName, String desc, String oldName,
            String firstVersionWithout, int hostMinusOld) {
        ownOverrideRenames.put(declaredName + desc,
                new OwnOverrideRename(declaredName, hostName, desc, oldName, firstVersionWithout, hostMinusOld));
        cachedRemapper = null;
    }

    /** The old name for a reference to the current mod's own override, or null when it is not one. */
    private String ownOverrideName(String owner, String name, String descriptor) {
        if (ownOverrideRenames.isEmpty()) return null;
        OwnOverrideRename rename = ownOverrideRenames.get(name + descriptor);
        if (rename == null || !builtBefore(rename.firstVersionWithout())) return null;
        return jarFamilyDeclares(owner, rename) ? rename.oldName() : null;
    }

    /** Whether the current mod was built before {@code version}; a mod that declares none counts as older. */
    private boolean builtBefore(String version) {
        String source = limitVersion();
        return source == null || RetromodVersion.compareMcVersions(source, version) < 0;
    }

    /** Whether {@code owner} or a supertype in the current jar declares the override, as the jar shipped it. */
    private boolean jarFamilyDeclares(String owner, OwnOverrideRename rename) {
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        if (provider == null) return false;
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        while (!pending.isEmpty() && visited.size() < 32) {
            String type = pending.poll();
            if (!visited.add(type)) continue;
            byte[] bytes = provider.apply(type);
            if (bytes == null) continue;
            ClassReader reader = new ClassReader(bytes);
            if (declaresAsShipped(reader, rename.declaredName(), rename.desc())) return true;
            if (reader.getSuperName() != null) pending.add(reader.getSuperName());
            pending.addAll(Arrays.asList(reader.getInterfaces()));
        }
        return false;
    }

    /** Whether the class declares {@code name + desc} exactly as its bytes spell it. */
    private static boolean declaresAsShipped(ClassReader reader, String name, String desc) {
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                    String[] exceptions) {
                if (method.equals(name) && descriptor.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /** Gives a class whose own override was renamed a host-meaning method that calls it. */
    private byte[] addOwnOverrideHostMethods(byte[] classBytes) {
        if (ownOverrideRenames.isEmpty()) return classBytes;
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        // Some paths pass the jar entry name, so the class names itself.
        byte[] original = provider == null ? null : provider.apply(new ClassReader(classBytes).getClassName());
        if (original == null) return classBytes;
        ClassReader originalReader = new ClassReader(original);
        org.objectweb.asm.tree.ClassNode node = null;
        for (OwnOverrideRename rename : ownOverrideRenames.values()) {
            // Only a class that shipped the override gets the method, and only once.
            if (!builtBefore(rename.firstVersionWithout())
                    || !declaresAsShipped(originalReader, rename.declaredName(), rename.desc())) {
                continue;
            }
            if (node == null) {
                node = new org.objectweb.asm.tree.ClassNode();
                new ClassReader(classBytes).accept(node, 0);
            }
            org.objectweb.asm.tree.MethodNode old = findMethod(node, rename.oldName(), rename.desc());
            if (old == null || (old.access & Opcodes.ACC_ABSTRACT) != 0
                    || findMethod(node, rename.hostName(), rename.desc()) != null) {
                continue;
            }
            boolean itf = (node.access & Opcodes.ACC_INTERFACE) != 0;
            org.objectweb.asm.tree.MethodNode host = new org.objectweb.asm.tree.MethodNode(
                    Opcodes.ACC_PUBLIC, rename.hostName(), rename.desc(), null, null);
            host.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
            host.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(
                    itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, node.name, rename.oldName(),
                    rename.desc(), itf));
            host.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(rename.hostMinusOld()));
            host.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IADD));
            host.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
            host.maxStack = 2;
            host.maxLocals = 1;
            node.methods.add(host);
        }
        if (node == null) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static org.objectweb.asm.tree.MethodNode findMethod(org.objectweb.asm.tree.ClassNode node, String name,
            String desc) {
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    /** Records that the rename table redirects {@code oldName}, so a mixin targeting it stays on the old name. */
    public void markMojangClassRename(String oldName) {
        mojangClassRenames.add(oldName);
    }

    /** Whether the rename table redirects {@code oldName}. */
    public boolean isMojangClassRename(String oldName) {
        return mojangClassRenames.contains(oldName);
    }

    /**
     * Keeps a mixin whose target the rename table redirects on its old names. The renamed classes
     * were reworked as well, and such a mixin never applied to them before, so its handlers would
     * start running against code nobody checked them against. It stays inert, as it was.
     */
    private Remapper renamedTargetMixinRemapper(Remapper delegate, ClassReader reader) {
        if (delegate == null || mojangClassRenames.isEmpty()) return delegate;
        boolean targetsRenamed = false;
        for (String target : declaredMixinTargets(reader)) {
            if (mojangClassRenames.contains(target)) targetsRenamed = true;
        }
        if (!targetsRenamed) return delegate;
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return mojangClassRenames.contains(internalName) ? internalName : delegate.map(internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return delegate.mapMethodName(owner, name, descriptor);
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                return delegate.mapInvokeDynamicMethodName(name, descriptor);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return delegate.mapFieldName(owner, name, descriptor);
            }
        };
    }

    /** Whether a shim rebases {@code name} or rewrites where classes implement it, which a plain rename must leave alone. */
    public boolean handlesRemovedClass(String name) {
        return superclassRedirects.containsKey(name) || implementedInterfaceRewrites.containsKey(name);
    }

    /**
     * The host name of a bare or described mixin selector on {@code owner} from the rename table,
     * or null when it has none. Without a descriptor every overload must agree on the new name.
     */
    public String mojangSelectorRename(String owner, String name, String descriptor) {
        com.retromod.mapping.MojangRenameTable table = mojangRenames;
        if (table == null || name.startsWith("<") || !table.renamesName(false, name)) return null;
        if (descriptor != null && !descriptor.isEmpty()) return mojangQualifiedRename(false, owner, name, descriptor);
        String source = limitVersion();
        // The target can be a subclass that overrides a method the table names on its base.
        com.retromod.mapping.MojangRenameTable.Row row = table.findMethodByName(owner, name, source);
        if (row == null) {
            row = inheritedMojangRename("n" + source, owner, name, "",
                    type -> table.findMethodByName(type, name, source));
        }
        return row != null && mojangRenameApplies(row, false, row.owner(), name, row.desc()) ? row.newName() : null;
    }

    /** The host name of a member reference exactly as a mod's bytecode carries it, or null. */
    private String mojangMemberRename(boolean field, String owner, String name, String desc) {
        com.retromod.mapping.MojangRenameTable table = mojangRenames;
        if (table == null || name.startsWith("<") || !table.renamesName(field, name)) return null;
        String source = limitVersion();
        com.retromod.mapping.MojangRenameTable.Row row = table.find(field, owner, name, desc, source);
        // A call through a subclass, or a mod's override, names a class below the declaring one.
        if (row == null) {
            row = inheritedMojangRename((field ? "f" : "m") + source, owner, name, desc,
                    type -> table.findOnHostOwner(field, type, name, desc, source));
        }
        return row != null && mojangRenameApplies(row, field, owner, name, desc) ? row.newName() : null;
    }

    /** The same for selector text, whose owner and descriptor may already carry host class names. */
    private String mojangQualifiedRename(boolean field, String owner, String name, String desc) {
        com.retromod.mapping.MojangRenameTable table = mojangRenames;
        if (table == null || desc == null || name.startsWith("<") || !table.renamesName(field, name)) return null;
        String source = limitVersion();
        com.retromod.mapping.MojangRenameTable.Row row = table.find(field, owner, name, desc, source);
        if (row == null) row = table.findOnHost(field, owner, name, desc, source);
        if (row == null) {
            row = inheritedMojangRename((field ? "F" : "M") + source, owner, name, desc,
                    type -> table.findOnHost(field, type, name, desc, source));
        }
        return row != null && mojangRenameApplies(row, field, row.owner(), name, row.desc()) ? row.newName() : null;
    }

    /** The host name of a lambda's method when its Minecraft interface renamed it, or null. */
    private String mojangLambdaRename(String iface, String name) {
        com.retromod.mapping.MojangRenameTable table = mojangRenames;
        if (table == null || !table.renamesName(false, name)) return null;
        com.retromod.mapping.MojangRenameTable.Row row = table.findMethodByName(iface, name, limitVersion());
        return row != null && mojangRenameApplies(row, false, row.owner(), name, row.desc()) ? row.newName() : null;
    }

    /** Whether a found row may rename this reference: no shim claims the member, and the host lacks the old name. */
    private boolean mojangRenameApplies(com.retromod.mapping.MojangRenameTable.Row row, boolean field,
            String owner, String name, String desc) {
        // A shim written for the old name does more than rename it, so it keeps the reference.
        if (redirectedByShim(field, owner, name, desc) || redirectedByShim(field, row.owner(), name, row.desc())) {
            return false;
        }
        // A class Retromod rebuilt keeps the member names the mod was built with.
        if (rebuiltByRetromod(owner) || rebuiltByRetromod(row.owner())) return false;
        return mojangRenameOnHost.computeIfAbsent(row, this::hostLacksOldName);
    }

    private boolean rebuiltByRetromod(String owner) {
        return redirectedClass(owner).startsWith("com/retromod/");
    }

    private boolean redirectedByShim(boolean field, String owner, String name, String desc) {
        if (field) {
            // A shim matched on the field's name and type, whatever class the access names.
            String shape = name + ':' + desc;
            if (inheritedFieldAccessors.containsKey(shape) || inheritedFieldStaticBridges.containsKey(shape)
                    || inheritedStaticFields.containsKey(name) || uniqueFieldGetters.containsKey(shape)
                    || uniqueFieldStaticBridges.containsKey(shape)
                    || typedFieldRenames.containsKey(owner + "." + name + ":" + desc)) {
                return true;
            }
            for (FieldKey key : List.of(new FieldKey(owner, name), new FieldKey(redirectedClass(owner), name))) {
                if (fieldRedirects.containsKey(key) || fieldAccessorRedirects.containsKey(key)
                        || staticFieldAccessors.containsKey(key) || fieldHopAccessors.containsKey(key)
                        || fieldStaticBridges.containsKey(key) || staticFieldBridges.containsKey(key)) {
                    return true;
                }
            }
            return false;
        }
        if (mojangMethodRenames.containsKey(owner + "#" + name)
                || inheritedMethodRedirects.containsKey(new MethodShape(name, desc))) {
            return true;
        }
        for (MethodKey key : List.of(new MethodKey(owner, name, desc),
                new MethodKey(redirectedClass(owner), name, resolveDescriptor(desc)))) {
            if (methodRedirects.containsKey(key) || argDropRedirects.containsKey(key)
                    || convertingRedirects.containsKey(key) || singletonRedirects.containsKey(key)
                    || neutralizedMethods.contains(key) || indyTypedRewrites.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the host class no longer declares the row's old name; a host Retromod cannot read is trusted. */
    private boolean hostLacksOldName(com.retromod.mapping.MojangRenameTable.Row row) {
        // A loader can patch an old name back in. A row carried past a later move names a class a
        // shim moves again, so the class is read under its redirected name.
        String owner = classRedirects.getOrDefault(row.hostOwner(), row.hostOwner());
        boolean field = row.kind() == com.retromod.mapping.MojangRenameTable.Kind.FIELD;
        org.objectweb.asm.tree.ClassNode node = ClassResourceInspector.read(owner);
        if (node != null) {
            if (field) {
                return node.fields.stream().noneMatch(f -> f.name.equals(row.name()) && f.desc.equals(row.hostDesc()));
            }
            return node.methods.stream().noneMatch(m -> m.name.equals(row.name()) && m.desc.equals(row.hostDesc()));
        }
        FuzzyMethodResolver index = fuzzyResolver;
        if (index == null || !index.isIndexed() || !index.hasClass(owner)) return true;
        if (field) {
            return index.getDeclaredFields(owner).stream()
                    .noneMatch(f -> f.name().equals(row.name()) && f.descriptor().equals(row.hostDesc()));
        }
        return index.getDeclaredMethods(owner).stream()
                .noneMatch(m -> m.name().equals(row.name()) && m.descriptor().equals(row.hostDesc()));
    }

    /**
     * Walks {@code owner}'s supertypes for the first host class {@code lookup} knows. A mixin
     * counts its targets as supertypes, so its shadows, overwrites and own calls follow the target.
     */
    private com.retromod.mapping.MojangRenameTable.Row inheritedMojangRename(String tag, String owner,
            String name, String desc, Function<String, com.retromod.mapping.MojangRenameTable.Row> lookup) {
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        Map<String, Optional<com.retromod.mapping.MojangRenameTable.Row>> answers;
        synchronized (inheritedMojangRenames) {
            answers = inheritedMojangRenames.computeIfAbsent(provider == null ? NO_JAR : provider,
                    ignored -> new ConcurrentHashMap<>());
        }
        return answers.computeIfAbsent(tag + '\0' + owner + '\0' + name + '\0' + desc, ignored -> {
            Set<String> visited = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>();
            pending.add(hostSupertypeName(owner));
            pending.addAll(directSupertypes(owner, provider));
            pending.addAll(mixinTargets(owner, provider));
            while (!pending.isEmpty() && visited.size() < 64) {
                String type = pending.poll();
                if (!visited.add(type)) continue;
                com.retromod.mapping.MojangRenameTable.Row row = lookup.apply(type);
                if (row != null) return Optional.of(row);
                pending.addAll(directSupertypes(type, provider));
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static final Object NO_JAR = new Object();

    /** The classes a mixin in the current jar targets, after class redirects, or none for any other class. */
    private List<String> mixinTargets(String type, Function<String, byte[]> provider) {
        byte[] bytes = provider == null ? null : provider.apply(type);
        if (bytes == null) return List.of();
        List<String> targets = new ArrayList<>();
        try {
            for (String target : declaredMixinTargets(new ClassReader(bytes))) targets.add(redirectedClass(target));
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        return targets;
    }

    /** The class names a class's {@code @Mixin} annotation lists, as written, or none. */
    private static List<String> declaredMixinTargets(ClassReader reader) {
        List<String> targets = new ArrayList<>();
        try {
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitArray(String key) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String ignored, Object value) {
                                    if ("value".equals(key) && value instanceof Type target) {
                                        targets.add(target.getInternalName());
                                    } else if ("targets".equals(key) && value instanceof String target) {
                                        targets.add(target.replace('.', '/'));
                                    }
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        return targets;
    }

    /**
     * Rename an interface's single abstract method, for callers and for implementations written
     * as a lambda or method reference. A lambda names the method it implements in its
     * {@code invokedynamic}, and {@code LambdaMetafactory} binds that name against the runtime
     * interface, so a stale name builds an object that throws {@code AbstractMethodError} on
     * first call. Fabric API's {@code HudElement.render} became {@code extractRenderState} at
     * 26.1, and a 1.21.x HUD lambda failed that way on every frame (#281, Hold My Items).
     * A class that implements the interface directly is not covered.
     */
    public void registerInterfaceMethodRename(String iface, String name, String newName) {
        if (!admitsMemberName(iface, name)) return;
        registerMethodRename(iface, name, newName);
        functionalInterfaceRenames.put(iface + "#" + name, newName);
    }

    /**
     * A lambda adapter for a functional interface whose abstract method changed shape.
     * {@code adapterIface}, {@code adapterName} and {@code adapterSamDesc} describe what the
     * rewritten {@code invokedynamic} builds. When {@code wrapOwner} is null the lambda keeps its
     * interface and only its erased method type changes. Otherwise the built object is passed to
     * the static {@code wrapOwner.wrapName(wrapDesc)}, which returns the original interface type.
     */
    public record LambdaAdapter(String adapterIface, String adapterName, String adapterSamDesc,
            String wrapOwner, String wrapName, String wrapDesc) {}

    /**
     * Retypes lambdas whose interface became generic. {@code LambdaMetafactory} implements the
     * method type the {@code invokedynamic} names, so a lambda compiled against
     * {@code create(int, Inventory, FriendlyByteBuf)} never implements the host's erased
     * {@code create(int, Inventory, Object)} and throws {@code AbstractMethodError} when called.
     * The lambda's instantiated type is kept, so the metafactory still casts the argument.
     * Keyed on the old method type, so a lambda written for the new interface is untouched.
     */
    public void registerLambdaSamRetype(String iface, String samName, String oldSamDesc,
            String newSamDesc) {
        registerLambdaSamRetype(iface, samName, oldSamDesc, samName, newSamDesc);
    }

    /**
     * Retypes lambdas as above and also renames the method they implement, for an interface whose
     * single abstract method now has another name. The lambda keeps its interface.
     */
    public void registerLambdaSamRetype(String iface, String samName, String oldSamDesc,
            String newSamName, String newSamDesc) {
        if (!admitsMethod(iface, samName, oldSamDesc)) return;
        lambdaAdapters.put(iface + "#" + samName + "#" + oldSamDesc,
                new LambdaAdapter(iface, newSamName, newSamDesc, null, null, null));
    }

    /**
     * Rebuilds lambdas for an interface whose abstract method was replaced by differently named
     * or shaped methods. The lambda is built as {@code adapterIface} instead and handed to a
     * static wrapper that implements the modern interface on top of it. A class that implements
     * the old interface directly is not covered.
     */
    public void registerLambdaAdapter(String iface, String samName, String oldSamDesc,
            LambdaAdapter adapter) {
        if (!admitsMethod(iface, samName, oldSamDesc)) return;
        lambdaAdapters.put(iface + "#" + samName + "#" + oldSamDesc, adapter);
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
        if (!admitsMethod(owner, name, desc)) return;
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
     * Drop an interface from the {@code implements} list of every transformed class.
     *
     * <p>For an API interface the host turned into a class: the mod class still declares the
     * methods, but claiming the type fails class definition with
     * {@code IncompatibleClassChangeError}. Callers then adapt the object where it reaches the API.
     */
    public void registerDroppedInterface(String iface) {
        if (!admitsType(iface)) return;
        implementedInterfaceRewrites.put(iface, "");
    }

    /**
     * Replace an interface in the {@code implements} list of every transformed class, for example
     * with a generated subinterface that implements a changed abstract method on top of the old
     * one. Unlike a class redirect, references to the interface elsewhere stay unchanged.
     */
    public void registerInterfaceReplacement(String iface, String replacement) {
        if (!admitsType(iface)) return;
        implementedInterfaceRewrites.put(iface, replacement);
    }

    /**
     * Gives each class that lists {@code iface} among its interfaces, after the rewrites above, a
     * concrete {@code name(desc)} that calls {@code iface.targetName(targetDesc)} on itself. A class
     * method wins over interface defaults, so a class that reaches two copies of a per-mod bridge
     * interface, one from each jar, still has exactly one method to run. Interfaces, and classes that
     * declare the method themselves, are left alone.
     */
    public void registerImplementedMethodBridge(String iface, String name, String desc,
            String targetName, String targetDesc) {
        registerImplementedMethodBridge(iface, name, desc, targetName, targetDesc, (List<Integer>) null);
    }

    /**
     * As above, but the target takes only the bridge parameters at {@code sources}, in that order,
     * for a host method that passes arguments the old method never had.
     */
    public void registerImplementedMethodBridge(String iface, String name, String desc,
            String targetName, String targetDesc, List<Integer> sources) {
        if (!admitsType(iface)) return;
        addBridge(implementedMethodBridges, new ImplementedMethodBridge(iface, name, desc, targetName,
                targetDesc, sources));
    }

    /** As above, returning {@code unwrap} called on what the target returns, for a host method that wants a part of it. */
    public void registerImplementedMethodBridge(String iface, String name, String desc,
            String targetName, String targetDesc, ImplementedMethodBridge.Unwrap unwrap) {
        if (!admitsType(iface)) return;
        addBridge(implementedMethodBridges, new ImplementedMethodBridge(iface, name, desc, targetName,
                targetDesc, null, unwrap));
    }

    /**
     * Gives each interface that lists {@code iface} among its superinterfaces, after the rewrites
     * above, a default {@code name(desc)} that calls {@code iface.targetName(targetDesc)} on itself.
     * Another jar can then call the old method through that interface, though only the
     * interface's own jar knows it extends {@code iface}. Interfaces that declare the method keep
     * their own.
     */
    public void registerInterfaceDefaultBridge(String iface, String name, String desc,
            String targetName, String targetDesc) {
        if (!admitsType(iface)) return;
        addBridge(interfaceDefaultBridges, new ImplementedMethodBridge(iface, name, desc, targetName,
                targetDesc));
    }

    private static void addBridge(Map<String, List<ImplementedMethodBridge>> table,
            ImplementedMethodBridge bridge) {
        List<ImplementedMethodBridge> bridges = table.computeIfAbsent(bridge.iface(),
                key -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (!bridges.contains(bridge)) bridges.add(bridge);
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
        if (!admitsType(oldClass)) return;
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
     * Preserve internal links inside a bundled API provider while retaining its redirects for
     * callers outside the provider package.
     *
     * <p>The prefix uses JVM package form, such as {@code com/example/provider/}. A missing
     * trailing slash is added. Register the package before transforming provider classes.
     */
    public void registerRedirectProviderPackage(String packagePrefix) {
        Objects.requireNonNull(packagePrefix, "packagePrefix");
        if (packagePrefix.isBlank()) {
            throw new IllegalArgumentException("Redirect provider package must not be blank");
        }
        String normalized = packagePrefix.replace('.', '/');
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        redirectProviderPackages.add(normalized);
        LOGGER.debug("Registered redirect provider package: {}", normalized);
    }

    /** The registered adapter for a {@code LambdaMetafactory.metafactory} lambda, or null. */
    private LambdaAdapter lambdaAdapterFor(String name, String descriptor,
            Handle bootstrap, Object[] arguments) {
        if (lambdaAdapters.isEmpty() || arguments == null || arguments.length < 3
                || !"java/lang/invoke/LambdaMetafactory".equals(bootstrap.getOwner())
                || !"metafactory".equals(bootstrap.getName())
                || !(arguments[0] instanceof Type samType)) {
            return null;
        }
        Type built = Type.getReturnType(descriptor);
        if (built.getSort() != Type.OBJECT) return null;
        String iface = built.getInternalName();
        String suffix = "#" + name + "#" + samType.getDescriptor();
        LambdaAdapter adapter = lambdaAdapters.get(iface + suffix);
        if (adapter == null) {
            adapter = lambdaAdapters.get(redirectedClass(iface) + suffix);
        }
        return adapter;
    }

    private boolean preservesProviderReference(String className, String referencedOwner) {
        if (className == null || referencedOwner == null || redirectProviderPackages.isEmpty()) {
            return false;
        }
        for (String packagePrefix : redirectProviderPackages) {
            if (className.startsWith(packagePrefix) && referencedOwner.startsWith(packagePrefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean preservesProviderInvokeDynamicName(String className, String descriptor) {
        if (className == null || descriptor == null || redirectProviderPackages.isEmpty()) {
            return false;
        }
        Type returnType = Type.getReturnType(descriptor);
        return returnType.getSort() == Type.OBJECT
                && preservesProviderReference(className, returnType.getInternalName());
    }

    /** Apply the cached global mappings through the package boundary of one provider class. */
    private Remapper providerPreservingRemapper(Remapper delegate, String className) {
        if (delegate == null || redirectProviderPackages.isEmpty()) {
            return delegate;
        }
        boolean providerClass = redirectProviderPackages.stream()
                .anyMatch(className::startsWith);
        if (!providerClass) {
            return delegate;
        }
        return new Remapper() {
            @Override
            public String map(String internalName) {
                if (preservesProviderReference(className, internalName)) {
                    return internalName;
                }
                return delegate.map(internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (preservesProviderReference(className, owner)) {
                    return name;
                }
                return delegate.mapMethodName(owner, name, descriptor);
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                if (preservesProviderInvokeDynamicName(className, descriptor)) {
                    return name;
                }
                return delegate.mapInvokeDynamicMethodName(name, descriptor);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                if (preservesProviderReference(className, owner)) {
                    return name;
                }
                return delegate.mapFieldName(owner, name, descriptor);
            }
        };
    }

    /** First local slot a deferred {@code NEW} spills its constructor arguments into. */
    public static final int CONSTRUCTOR_ARG_SPILL_SLOT = 200;

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
        if (!admitsMethod(className, "<init>", constructorDesc)) return;
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
        if (!admitsMethod(className, "<init>", oldDesc)) return;
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
        if (!admitsMethod(className, "<init>", oldDesc)) return;
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
        if (!admitsMethod(className, "<init>", oldDesc)) return;
        superCtorRedirects.put(
            new ConstructorKey(className, oldDesc),
            new SuperCtorRedirect(newDesc, "__REPLACE_DEFAULTS__", "", ""));
        LOGGER.debug("Registered super ctor replace: {}.{} -> {} (pop old, push defaults)",
            className, oldDesc, newDesc);
    }

    /**
     * Register a super-constructor change that reorders the old arguments, which the append and
     * replace modes above cannot express because they keep or discard the old values in place.
     *
     * <p>At the {@code super(oldDesc)} call the old arguments sit on the stack above the
     * uninitialized receiver, which cannot be passed to a helper method. {@code rewriteArguments}
     * therefore emits stack instructions (swaps, static reads) that turn the old argument list into
     * the new one, and the call is then made with {@code newDesc}. Modern calls with any other
     * descriptor pass through untouched.
     */
    public void registerSuperConstructorAdapter(String className, String oldDesc, String newDesc,
            java.util.function.Consumer<MethodVisitor> rewriteArguments) {
        if (!admitsMethod(className, "<init>", oldDesc)) return;
        ConstructorKey key = new ConstructorKey(className, oldDesc);
        superCtorStackAdapters.put(key, rewriteArguments);
        superCtorRedirects.put(key, new SuperCtorRedirect(newDesc, STACK_ADAPTER_MODE, "", ""));
        LOGGER.debug("Registered super ctor adapter: {}.{} -> {}", className, oldDesc, newDesc);
    }

    /** Answers {@code instanceof type} with {@code helperOwner.helperName(Object)Z}; {@code type} is the name after class redirects. */
    public void registerInstanceofHelper(String type, String helperOwner, String helperName) {
        // For a rebuilt class each mod embeds a copy of, so the check also accepts another mod's copy.
        instanceofHelpers.put(type, new InstanceofHelperAdapter.Helper(helperOwner, helperName));
        LOGGER.debug("Registered instanceof helper: {} -> {}.{}", type, helperOwner, helperName);
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
        LOGGER.debug(RetromodLog.DETAIL, "Registered {} intermediary method names and {} field names for bytecode remapping",
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
    /** One Mojang name an SRG id had over a range of Minecraft versions. */
    public record SrgDrift(String firstVersion, String lastVersion, String mojangName) {}

    private final Map<String, List<SrgDrift>> srgDrift = new ConcurrentHashMap<>();
    private volatile String srgSourceVersion;

    /**
     * Register the names an SRG id had in different versions. Forge kept some ids while the member
     * behind them changed, so the one name in the main table is wrong for a mod from the other side
     * of the change: {@code m_7366_} is {@code getDurabilityForSlot} to 1.19.2 and
     * {@code getDefenseForType} from 1.19.4.
     */
    public void registerSrgVersionDrift(String kind, String srgName, List<SrgDrift> ranges) {
        srgDrift.put(kind + ' ' + srgName, List.copyOf(ranges));
        cachedRemapper = null;
    }

    /** The Minecraft version the mod being transformed was built for, or null when unknown. */
    public void setSrgSourceVersion(String version) {
        srgSourceVersion = version;
        inheritedTargetSrgNames.clear();
    }

    private String srgMethodName(String srgName) {
        String drifted = driftedSrgName("METHOD", srgName);
        return drifted != null ? drifted : srgMethodNames.get(srgName);
    }

    /**
     * The Mojang name of a record accessor that an SRG mod calls by its component field's id.
     * Forge's SRG names a record accessor after the component field, so an old
     * {@code tagKey.f_203868_()} call carries an {@code f_} id where every other method carries an
     * {@code m_} id. On a Mojang-named host the accessor shares the component field's Mojang name,
     * so the field table resolves it; otherwise the call keeps the id and fails with
     * {@code NoSuchMethodError}.
     */
    private String srgRecordAccessorName(String name, String descriptor) {
        if (srgFieldNames.isEmpty() || descriptor == null || !descriptor.startsWith("()")
                || name.length() <= 3 || !name.startsWith("f_") || !name.endsWith("_")) {
            return null;
        }
        return srgFieldName(name);
    }

    private String srgFieldName(String srgName) {
        String drifted = driftedSrgName("FIELD", srgName);
        return drifted != null ? drifted : srgFieldNames.get(srgName);
    }

    /**
     * The name an SRG id had in the mod's own version: the range that contains it, else the latest
     * range that starts before it. Null when the id never drifted or the version is unknown.
     */
    private String driftedSrgName(String kind, String srgName) {
        String version = srgSourceVersion;
        if (version == null || srgDrift.isEmpty()) return null;
        List<SrgDrift> ranges = srgDrift.get(kind + ' ' + srgName);
        if (ranges == null) return null;
        SrgDrift preceding = null;
        for (SrgDrift range : ranges) {
            if (RetromodVersion.compareMcVersions(version, range.firstVersion()) < 0) continue;
            if (RetromodVersion.compareMcVersions(version, range.lastVersion()) <= 0) {
                return range.mojangName();
            }
            if (preceding == null || RetromodVersion.compareMcVersions(
                    range.firstVersion(), preceding.firstVersion()) > 0) {
                preceding = range;
            }
        }
        return preceding != null ? preceding.mojangName() : null;
    }

    public void registerSrgNameMappings(
            Map<String, String> methodNames, Map<String, String> fieldNames) {
        srgMethodNames.putAll(methodNames);
        srgFieldNames.putAll(fieldNames);
        cachedRemapper = null; // Invalidate cached remapper
        LOGGER.debug(RetromodLog.DETAIL, "Registered {} SRG method names and {} SRG field names for bytecode remapping",
            methodNames.size(), fieldNames.size());
    }

    /** Register owner-qualified Mojang to target-SRG mappings for a Forge 1.20 to 1.20.4 host. */
    public void registerTargetSrgNameMappings(
            Map<String, String> methodNames, Map<String, String> fieldNames) {
        targetSrgMethodNames.putAll(methodNames);
        targetSrgFieldNames.putAll(fieldNames);
        targetSrgSamIndex = null;
        targetSrgMethodIds = null;
        targetSrgFieldIds = null;
        cachedRemapper = null;
        inheritedTargetSrgNames.clear();
        LOGGER.debug(RetromodLog.DETAIL, "Registered {} target SRG methods and {} target SRG fields",
                methodNames.size(), fieldNames.size());
    }

    /**
     * Resolve a method selector that ClassRemapper cannot visit, such as text stored in a
     * Mixin annotation. The owner and descriptor make the target SRG lookup unambiguous.
     */
    public String remapQualifiedMethodName(String owner, String name, String descriptor) {
        String srg = srgMethodName(name);
        // An SRG host still names the accessor by its field id, and this path has no field-table
        // way back to that id, so only a Mojang-named host takes the component name.
        if (srg == null && targetSrgFieldNames.isEmpty()) srg = srgRecordAccessorName(name, descriptor);
        String mappedName = srg != null ? srg : name;
        String mappedOwner = redirectedClass(owner);
        String renamedOnHost = mojangQualifiedRename(false, owner, mappedName, descriptor);
        if (renamedOnHost != null) mappedName = renamedOnHost;
        mappedName = mojangMethodRenames.getOrDefault(
                mappedOwner + "#" + mappedName, mappedName);
        String mappedDescriptor = remapDescriptorClasses(descriptor);
        return targetSrgMethodNames.getOrDefault(
                com.retromod.mapping.TargetSrgMapper.memberKey(
                        mappedOwner, mappedName, mappedDescriptor),
                mappedName);
    }

    /**
     * The target SRG name of a record component accessor. On an SRG host the accessor carries the
     * component field's SRG name, and the target table lists only that field. 1.19.3 turned
     * classes such as {@code LevelStem} into records, so an old {@code stem.generator()} call
     * otherwise keeps its Mojang name and fails with {@code NoSuchMethodError}.
     */
    private String recordAccessorTargetSrgName(String owner, String name, String descriptor) {
        if (descriptor == null || !descriptor.startsWith("()") || name.startsWith("<")) return null;
        return targetSrgFieldNames.get(com.retromod.mapping.TargetSrgMapper.memberKey(
                owner, name, descriptor.substring(2)));
    }

    /** Owner, descriptor and SRG id of every target method, for {@link #isLiveTargetSrgMethod}. */
    private volatile Set<String> targetSrgMethodIds;

    /**
     * Whether a source SRG method id still names this method on the host. Mojang sometimes
     * renames a method without changing it, and the SRG id then survives: 1.19.2
     * {@code Entity.getLevel} and 1.20.1 {@code Entity.level} are both {@code m_9236_}. The Mojang
     * hop alone lands on the old name, which the host lacks, so a call the host could still
     * resolve fails with {@code NoSuchMethodError} (#290). The id is kept only when the owner, or
     * a supertype, declares it with the same descriptor, so a mod's own override keeps the Mojang
     * name the other bridges match on.
     */
    private boolean isLiveTargetSrgMethod(String owner, String srgName, String descriptor) {
        if (targetSrgMethodNames.isEmpty() || !srgName.startsWith("m_")) return false;
        Set<String> ids = targetSrgMethodIds;
        if (ids == null) {
            ids = new HashSet<>();
            for (Map.Entry<String, String> entry : targetSrgMethodNames.entrySet()) {
                String key = entry.getKey();
                int ownerEnd = key.indexOf('\0');
                int nameEnd = ownerEnd < 0 ? -1 : key.indexOf('\0', ownerEnd + 1);
                if (nameEnd < 0) continue;
                ids.add(key.substring(0, ownerEnd) + '\0' + key.substring(nameEnd + 1)
                        + '\0' + entry.getValue());
            }
            targetSrgMethodIds = ids;
        }
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        while (!pending.isEmpty() && visited.size() < 64) {
            String type = pending.poll();
            if (!visited.add(type)) continue;
            if (ids.contains(type + '\0' + descriptor + '\0' + srgName)) return true;
            pending.addAll(directSupertypes(type, provider));
        }
        return false;
    }

    /** Owner, descriptor and SRG id of every target field, for {@link #isLiveTargetSrgField}. */
    private volatile Set<String> targetSrgFieldIds;

    /**
     * Whether a source SRG field id still names this field on the host, as for methods above.
     * 1.19.4 {@code DamageTypes.OUT_OF_WORLD} and 1.20.1 {@code FELL_OUT_OF_WORLD} are both
     * {@code f_268724_}, so the 1.19.4 name the Mojang hop gives has no target name.
     */
    private boolean isLiveTargetSrgField(String owner, String srgName, String descriptor) {
        if (targetSrgFieldNames.isEmpty() || !srgName.startsWith("f_")) return false;
        Set<String> ids = targetSrgFieldIds;
        if (ids == null) {
            ids = new HashSet<>();
            for (Map.Entry<String, String> entry : targetSrgFieldNames.entrySet()) {
                String key = entry.getKey();
                int ownerEnd = key.indexOf('\0');
                int nameEnd = ownerEnd < 0 ? -1 : key.indexOf('\0', ownerEnd + 1);
                if (nameEnd < 0) continue;
                ids.add(key.substring(0, ownerEnd) + '\0' + key.substring(nameEnd + 1)
                        + '\0' + entry.getValue());
            }
            targetSrgFieldIds = ids;
        }
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        while (!pending.isEmpty() && visited.size() < 64) {
            String type = pending.poll();
            if (!visited.add(type)) continue;
            if (ids.contains(type + '\0' + descriptor + '\0' + srgName)) return true;
            pending.addAll(directSupertypes(type, provider));
        }
        return false;
    }

    /** Target SRG method names by owner and Mojang name, or "" when the name is overloaded. */
    private volatile Map<String, String> targetSrgSamIndex;

    /**
     * The name a lambda's invokedynamic must use for a Minecraft functional interface. A Forge
     * mod's lambda names the interface method in SRG, which is translated to Mojang with the rest
     * of the class. On an SRG host the interface still declares the SRG name, so a lambda left
     * on the Mojang name links but throws {@code AbstractMethodError} when called (#264: every
     * MCreator {@code EntityType.Builder.of(Entity::new, ...)} factory). The invokedynamic
     * descriptor carries the interface but not the method descriptor, so the lookup is by owner
     * and name and is skipped when that name is overloaded.
     */
    private String targetSrgSamName(String indyDescriptor, String mojangName) {
        if (targetSrgMethodNames.isEmpty()) return mojangName;
        int close = indyDescriptor.lastIndexOf(')');
        if (close < 0 || !indyDescriptor.startsWith("L", close + 1)) return mojangName;
        String iface = indyDescriptor.substring(close + 2, indyDescriptor.length() - 1);
        Map<String, String> index = targetSrgSamIndex;
        if (index == null) {
            index = new HashMap<>();
            for (Map.Entry<String, String> entry : targetSrgMethodNames.entrySet()) {
                String[] parts = entry.getKey().split("\0", 3);
                if (parts.length < 2) continue;
                index.merge(parts[0] + '#' + parts[1], entry.getValue(),
                        (first, second) -> first.equals(second) ? first : "");
            }
            targetSrgSamIndex = index;
        }
        String target = index.get(redirectedClass(iface) + '#' + mojangName);
        return target == null || target.isEmpty() ? mojangName : target;
    }

    /** Inherited target SRG lookups, keyed by table, jar, owner, name, and descriptor. */
    private final Map<String, Optional<String>> inheritedTargetSrgNames = new ConcurrentHashMap<>();

    /**
     * The target SRG name of a member that {@code owner} inherits rather than declares.
     *
     * <p>The target table is keyed by the declaring class. A mod class overriding
     * {@code getDrops}, or reading an inherited field such as {@code level}, names itself as the
     * owner, so the exact lookup missed and the member kept its Mojang name. On an SRG host such
     * an override was never called and such a field read failed. The walk follows the mod's own
     * classes, Retromod's generated bases, and the host's classes, through class moves and
     * superclass rebases, and stops at the first declaring class the table knows.
     */
    private String inheritedTargetSrgName(Map<String, String> table, String owner, String name,
            String descriptor) {
        Function<String, byte[]> provider = currentJarClassBytesProvider();
        String key = System.identityHashCode(table) + "\0" + System.identityHashCode(provider)
                + "\0" + owner + "\0" + name + "\0" + descriptor;
        return inheritedTargetSrgNames.computeIfAbsent(key, ignored -> {
            Set<String> visited = new HashSet<>();
            List<String> start = directSupertypes(owner, provider);
            String moved = hostSupertypeName(owner);
            if (start.isEmpty() && !moved.equals(owner)) start = directSupertypes(moved, provider);
            Deque<String> pending = new ArrayDeque<>(start);
            while (!pending.isEmpty() && visited.size() < 64) {
                String type = pending.poll();
                if (!visited.add(type)) continue;
                String target = table.get(com.retromod.mapping.TargetSrgMapper.memberKey(
                        type, name, descriptor));
                if (target != null) return Optional.of(target);
                pending.addAll(directSupertypes(type, provider));
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** A class's superclass and interfaces, after the class moves and rebases this host applies. */
    private List<String> directSupertypes(String type, Function<String, byte[]> provider) {
        List<String> parents = new ArrayList<>();
        byte[] bytes = syntheticClasses.get(type);
        if (bytes == null) bytes = readClassBytes(type, provider);
        if (bytes != null) {
            try {
                ClassReader reader = new ClassReader(bytes);
                if (reader.getSuperName() != null) parents.add(reader.getSuperName());
                parents.addAll(Arrays.asList(reader.getInterfaces()));
            } catch (RuntimeException unreadable) {
                return List.of();
            }
        } else {
            FuzzyMethodResolver resolver = fuzzyResolver;
            if (resolver != null) parents.addAll(resolver.directSupertypes(type));
        }
        List<String> resolved = new ArrayList<>(parents.size());
        for (String parent : parents) {
            String host = hostSupertypeName(parent);
            // A rebase base such as LegacyDoorBlock extends the very class it replaces. Rebasing
            // its own parent would point the walk back at itself and hide every inherited member,
            // so a mod's super.getDrops() kept its Mojang name on an SRG host.
            if (host.equals(type)) host = classRedirects.getOrDefault(parent, parent);
            resolved.add(host);
        }
        return resolved;
    }

    private String hostSupertypeName(String type) {
        SuperclassRedirect rebase = superclassRebase(type, type);
        if (rebase != null) return rebase.newSuperclass();
        String moved = classRedirects.getOrDefault(type, type);
        rebase = superclassRebase(moved, type);
        return rebase != null ? rebase.newSuperclass() : moved;
    }

    /** Whether the host runs target SRG member names, as Forge 1.20 to 1.20.4 do. */
    public boolean hasTargetSrgMappings() {
        return !targetSrgMethodNames.isEmpty() || !targetSrgFieldNames.isEmpty();
    }

    /**
     * Resolve an SRG member name that a Forge refmap stores without its owner. SRG names are
     * unique, so the Mojang name is safe on a host that runs Mojang names. A Forge 1.20 to 1.20.4
     * host needs the owner to find its own SRG name, so the old name stays there.
     */
    public String remapOwnerlessSrgName(String name, boolean field) {
        if (!targetSrgMethodNames.isEmpty() || !targetSrgFieldNames.isEmpty()) return name;
        String mapped = field ? srgFieldName(name) : srgMethodName(name);
        return mapped != null ? mapped : name;
    }

    /**
     * Whether {@code name} is an intermediary field name. Record components are named
     * {@code comp_N} instead of {@code field_N}, and a mixin without a refmap shadows them by
     * that name (Minepathy's {@code Consumable} and {@code Equippable} mixins).
     */
    private static boolean isIntermediaryFieldName(String name) {
        return name.startsWith("field_") || name.startsWith("comp_");
    }

    /** Resolve a field selector stored outside ordinary bytecode instructions. */
    public String remapQualifiedFieldName(String owner, String name, String descriptor) {
        String mappedName = name;
        if (!intermediaryFieldNames.isEmpty() && isIntermediaryFieldName(name)) {
            String mojang = null;
            if (descriptor != null && ambiguousIntermediaryFieldNames.contains(name)) {
                mojang = intermediaryFieldNamesByDesc.get(memberDescKey(name, descriptor));
            }
            if (mojang == null) mojang = intermediaryFieldNames.get(name);
            if (mojang != null) mappedName = mojang;
        }
        String srgField = srgFieldName(mappedName);
        if (srgField != null) mappedName = srgField;
        String renamedOnHost = mojangQualifiedRename(true, owner, mappedName, descriptor);
        if (renamedOnHost != null) mappedName = renamedOnHost;
        String mappedOwner = redirectedClass(owner);
        String mappedDescriptor = remapDescriptorClasses(descriptor);

        // A Mixin field name cannot express a field-to-method rewrite. Accept only an exact
        // same-owner field rename with the descriptor expected by the registered shim.
        FieldTarget redirect = fieldRedirects.get(new FieldKey(mappedOwner, mappedName));
        if (redirect != null
                && redirect.owner().equals(mappedOwner)
                && (redirect.newDesc() == null || !redirect.newDesc().startsWith("("))
                && (redirect.oldDesc() == null || redirect.oldDesc().equals(mappedDescriptor))) {
            mappedName = redirect.name();
            if (redirect.newDesc() != null) mappedDescriptor = redirect.newDesc();
        }

        return targetSrgFieldNames.getOrDefault(
                com.retromod.mapping.TargetSrgMapper.memberKey(
                        mappedOwner, mappedName, mappedDescriptor),
                mappedName);
    }

    private String remapDescriptorClasses(String descriptor) {
        if (descriptor == null || descriptor.indexOf('L') < 0 || classRedirects.isEmpty()) {
            return descriptor;
        }
        StringBuilder result = new StringBuilder(descriptor.length());
        int offset = 0;
        while (offset < descriptor.length()) {
            int typeStart = descriptor.indexOf('L', offset);
            if (typeStart < 0) {
                result.append(descriptor, offset, descriptor.length());
                break;
            }
            int typeEnd = descriptor.indexOf(';', typeStart);
            if (typeEnd < 0) return descriptor;
            result.append(descriptor, offset, typeStart + 1);
            String type = descriptor.substring(typeStart + 1, typeEnd);
            result.append(redirectedClass(type)).append(';');
            offset = typeEnd + 1;
        }
        return result.toString();
    }

    /** Drops the source SRG half when an offline SRG-named Forge target has no matching target table. */
    public void clearSrgNameMappings() {
        // Leaving Mojang names in that jar would be worse than keeping the original SRG references
        // for Forge's own remapper to attempt.
        srgMethodNames.clear();
        srgFieldNames.clear();
        srgDrift.clear();
        // srgSourceVersion stays: it belongs to the mod being transformed, not to a namespace, and
        // the offline paths select the namespace after setting it.
        targetSrgMethodNames.clear();
        targetSrgFieldNames.clear();
        targetSrgSamIndex = null;
        targetSrgMethodIds = null;
        targetSrgFieldIds = null;
        cachedRemapper = null;
    }

    /** Drop Fabric intermediary member names before configuring another loader's mod. */
    public void clearIntermediaryNameMappings() {
        intermediaryMethodNames.clear();
        intermediaryFieldNames.clear();
        intermediaryMethodNamesByDesc.clear();
        intermediaryFieldNamesByDesc.clear();
        ambiguousIntermediaryMethodNames.clear();
        ambiguousIntermediaryFieldNames.clear();
        cachedRemapper = null;
    }
    
    /**
     * Register a field redirect (field to field).
     */
    public void registerFieldRedirect(
            String oldOwner, String oldName,
            String newOwner, String newName) {
        if (!admitsField(oldOwner, oldName)) return;
        
        FieldKey key = new FieldKey(oldOwner, oldName);
        FieldTarget target = new FieldTarget(newOwner, newName, null, null);
        yieldStandIn(key);
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
        if (!admitsField(oldOwner, oldName)) return;
        
        FieldKey key = new FieldKey(oldOwner, oldName);
        FieldTarget target = new FieldTarget(newOwner, newName, oldDesc, newDesc);
        yieldStandIn(key);
        fieldRedirects.put(key, target);
        
        LOGGER.debug("Registered field redirect: {}.{} {} -> {}.{} {}",
                oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
    }

    /**
     * Register a read of a removed static field as a call to a getter whose result stands in for
     * the field's old type. A plain field-to-method redirect casts the result back to that type;
     * this one does not, because the old type means something else on the host now. The
     * pre-1.20.5 {@code ArmorMaterials} enum constants are the case: the getter returns a stand-in
     * for the old {@code ArmorMaterial} interface, and 1.21's {@code ArmorMaterials} is an unrelated
     * holder class, so the cast threw {@code ClassCastException}.
     */
    public void registerStandInFieldGetter(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (!admitsField(oldOwner, oldName)) return;
        registerExactFieldRedirect(oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
        standInFieldGetters.add(new FieldKey(oldOwner, oldName));
    }

    /**
     * Register a field redirect that applies only to an access with {@code oldDesc}. A field that
     * kept its name but changed type, such as {@code Attributes.MAX_HEALTH} becoming a
     * {@code Holder} in 1.20.5, needs this: a newer mod that reads the field with its current type
     * must keep the plain read.
     */
    public void registerExactFieldRedirect(
            String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (!admitsField(oldOwner, oldName)) return;
        registerFieldRedirect(oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
        exactDescriptorFieldRedirects.add(new FieldKey(oldOwner, oldName));
        descriptorFieldRedirects.put(oldOwner + "." + oldName + ":" + oldDesc,
                new FieldTarget(newOwner, newName, oldDesc, newDesc));
    }

    /**
     * Rename a field only where it is read or written with {@code descriptor}. Use this when the
     * same owner and name already carry a redirect written for an older type of the field, as
     * {@code MobEffects.DIG_SPEED} does: its pre-1.20.5 {@code MobEffect} read goes through a
     * lookup, while the {@code Holder} read of a 1.20.5 to 1.21.4 mod needs the 1.21.5 name
     * {@code HASTE}.
     */
    public void registerFieldRenameForType(String owner, String name, String descriptor,
            String newName) {
        if (!admitsField(owner, name)) return;
        yieldStandIn(new FieldKey(owner, name));
        typedFieldRenames.put(owner + "." + name + ":" + descriptor, newName);
    }

    /** Mark one exact moved field as requiring {@code @Mutable} on generated accessor setters. */
    public void registerMutableAccessorDestination(String owner, String name, String descriptor) {
        mutableAccessorDestinations.add(new FieldDestination(owner, name, descriptor));
    }

    /** Whether a moved accessor setter must clear the host field's final modifier. */
    public boolean isMutableAccessorDestination(String owner, String name, String descriptor) {
        return mutableAccessorDestinations.contains(new FieldDestination(owner, name, descriptor));
    }
    
    /** Register a field accessor redirect, for a field that became private in newer Minecraft. */
    public void registerFieldAccessorRedirect(
            String fieldOwner, String fieldName,
            String getterName, String getterDesc,
            String setterName, String setterDesc) {
        if (!admitsField(fieldOwner, fieldName)) return;
        // GETFIELD becomes an INVOKEVIRTUAL getter and PUTFIELD a setter. A null setter suits a
        // field that can be read but has no safe way to be written: a PUTFIELD then stays as
        // written, so it fails where it runs instead of doing something the old write never did.
        FieldKey key = new FieldKey(fieldOwner, fieldName);
        FieldAccessorTarget target = new FieldAccessorTarget(
            fieldOwner, getterName, getterDesc,
            setterName == null ? null : fieldOwner, setterName, setterDesc
        );
        yieldStandIn(key);
        fieldAccessorRedirects.put(key, target);
        LOGGER.debug("Registered field accessor redirect: {}.{} -> get={}, set={}",
                fieldOwner, fieldName, getterName, setterName);
    }

    /** Register a field accessor redirect that also matches an access naming a subclass as the owner. */
    public void registerInheritedFieldAccessorRedirect(
            String fieldOwner, String fieldName, String fieldDesc,
            String getterName, String getterDesc,
            String setterName, String setterDesc) {
        if (!admitsField(fieldOwner, fieldName)) return;
        // javac writes player.swinging with Player as the owner, though the field belongs to
        // LivingEntity. A null setter makes the bridge read-only.
        registerFieldAccessorRedirect(fieldOwner, fieldName, getterName, getterDesc, setterName, setterDesc);
        FieldAccessorTarget target = fieldAccessorRedirects.get(new FieldKey(fieldOwner, fieldName));
        inheritedFieldAccessors
                .computeIfAbsent(fieldName + ':' + fieldDesc, ignored -> ConcurrentHashMap.newKeySet())
                .add(new InheritedFieldAccessor(fieldOwner, target));
    }

    /**
     * Register a static field redirect that also matches an access naming a mod type that inherits
     * {@code oldOwner}, as javac writes an interface constant read inside an implementing class or
     * through a sub-interface. Only a hierarchy the jar's own classes prove is rewritten.
     */
    public void registerInheritedStaticFieldRedirect(String oldOwner, String oldName, String oldDesc,
            String newOwner, String newName, String newDesc) {
        if (!admitsField(oldOwner, oldName)) return;
        registerFieldRedirect(oldOwner, oldName, oldDesc, newOwner, newName, newDesc);
        inheritedStaticFields.computeIfAbsent(oldName, ignored -> ConcurrentHashMap.newKeySet())
                .add(new InheritedStaticField(oldOwner, oldDesc));
    }

    /** The one proven redirect for a static field read or written through a mod subtype of its owner. */
    private FieldTarget inheritedStaticFieldTarget(String owner, String name, String descriptor) {
        if (inheritedStaticFields.isEmpty() || !isModOwnedType(owner)) return null;
        Set<InheritedStaticField> candidates = inheritedStaticFields.get(name);
        if (candidates == null) return null;
        FieldTarget match = null;
        for (InheritedStaticField candidate : candidates) {
            FieldTarget target = fieldRedirects.get(new FieldKey(candidate.owner(), name));
            if (target == null || (candidate.desc() != null && !candidate.desc().equals(descriptor))) continue;
            // The JVM resolves the field from the named type up, so a type on the way that declares it wins.
            if (!inheritsFrom(owner, candidate.owner())
                    || typeDeclaresFieldBefore(owner, candidate.owner(), name, descriptor)) {
                continue;
            }
            if (match != null && !match.equals(target)) {
                LOGGER.debug("Declined ambiguous inherited static field redirect for {}.{}", owner, name);
                return null;
            }
            match = target;
        }
        return match;
    }

    /** Whether a class or interface a mod defines, rather than Java, Minecraft, a loader or Retromod. */
    private static boolean isModOwnedType(String owner) {
        return !owner.startsWith("[") && !owner.startsWith("java/") && !owner.startsWith("javax/")
                && !owner.startsWith("net/minecraft/") && !owner.startsWith("com/mojang/")
                && !owner.startsWith("net/fabricmc/") && !SyntheticEmbedder.isRetromodClass(owner);
    }

    /**
     * Whether a type between {@code owner} and {@code ancestor}, through superclasses and
     * superinterfaces alike, declares the field. Field resolution checks interfaces too, so a
     * constant a mod interface redeclares is the one its subtypes read.
     */
    private boolean typeDeclaresFieldBefore(String owner, String ancestor, String name, String descriptor) {
        Function<String, byte[]> provider = hierarchyClasses();
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty() && visited.size() < 64) {
            String current = pending.pop();
            if (current.equals(ancestor) || !visited.add(current)) continue;
            byte[] bytes = readClassBytes(current, provider);
            if (bytes == null) continue;
            ClassReader reader;
            try {
                reader = new ClassReader(bytes);
            } catch (RuntimeException e) {
                continue;
            }
            if (declaresField(reader, name, descriptor)) return true;
            if (reader.getSuperName() != null) pending.push(reader.getSuperName());
            for (String iface : reader.getInterfaces()) pending.push(iface);
        }
        return false;
    }

    /**
     * Register a field static bridge that also applies where an access names a subclass of
     * {@code fieldOwner}, as a mod's {@code Item.Properties} subclass writing an inherited field does.
     */
    public void registerInheritedFieldStaticBridge(String fieldOwner, String fieldName, String fieldDesc,
            String bridgeOwner, String getterName, String getterDesc, String setterName, String setterDesc) {
        if (!admitsField(fieldOwner, fieldName)) return;
        registerFieldStaticBridge(fieldOwner, fieldName, bridgeOwner, getterName, getterDesc, setterName, setterDesc);
        inheritedFieldStaticBridges
                .computeIfAbsent(fieldName + ':' + fieldDesc, ignored -> ConcurrentHashMap.newKeySet())
                .add(new InheritedFieldStaticBridge(fieldOwner, fieldStaticBridges.get(new FieldKey(fieldOwner, fieldName))));
    }

    /** Register an inherited field static bridge that applies only to accesses with {@code fieldDesc}. */
    public void registerTypedFieldStaticBridge(String fieldOwner, String fieldName, String fieldDesc,
            String bridgeOwner, String getterName, String getterDesc, String setterName, String setterDesc) {
        if (!admitsField(fieldOwner, fieldName)) return;
        // A field that kept its name but changed type needs this, as lootTable did when 1.20.5 made it a
        // registry key: a mod built for the new type reads the field as written.
        registerInheritedFieldStaticBridge(fieldOwner, fieldName, fieldDesc, bridgeOwner,
                getterName, getterDesc, setterName, setterDesc);
        fieldStaticBridgeTypes.computeIfAbsent(new FieldKey(fieldOwner, fieldName),
                ignored -> ConcurrentHashMap.newKeySet()).add(fieldDesc);
    }

    /** Register a field static bridge on exactly {@code owner} that applies only to accesses with {@code fieldDesc}. */
    public void registerExactTypedFieldStaticBridge(String owner, String name, String fieldDesc,
            String bridgeOwner, String getterName, String getterDesc, String setterName, String setterDesc) {
        if (!admitsField(owner, name)) return;
        // A subclass that declares a field of the same name keeps reading its own, and an access
        // with another type, such as an old array, keeps its read.
        registerFieldStaticBridge(owner, name, bridgeOwner, getterName, getterDesc, setterName, setterDesc);
        fieldStaticBridgeTypes.computeIfAbsent(new FieldKey(owner, name),
                ignored -> ConcurrentHashMap.newKeySet()).add(fieldDesc);
    }

    /** Whether a field static bridge on {@code key} applies to an access with {@code descriptor}. */
    private boolean staticBridgeTakesType(FieldKey key, String descriptor) {
        Set<String> types = fieldStaticBridgeTypes.get(key);
        return types == null || types.contains(descriptor) || types.contains(resolveDescriptor(descriptor));
    }

    /** The one proven inherited static bridge for an access whose owner is a subclass of the field's owner. */
    private FieldStaticBridge inheritedFieldStaticBridge(String owner, String name, String descriptor) {
        Set<InheritedFieldStaticBridge> candidates = inheritedFieldStaticBridges.get(name + ':' + descriptor);
        if (candidates == null) return null;
        FieldStaticBridge match = null;
        for (InheritedFieldStaticBridge candidate : candidates) {
            // Same proof as an inherited accessor: the owner inherits the field, and nothing on
            // the way declares one of its own.
            if (!inheritsFrom(owner, candidate.fieldOwner())
                    || classDeclaresFieldOnTheWay(owner, candidate.fieldOwner(), name, descriptor)) {
                continue;
            }
            if (match != null && !match.equals(candidate.bridge())) return null;
            match = candidate.bridge();
        }
        return match;
    }

    /** The one proven inherited accessor for an access whose owner is a subclass of the field's owner. */
    private FieldAccessorTarget inheritedFieldAccessor(String owner, String name, String descriptor) {
        if (inheritedFieldAccessors.isEmpty()) return null;
        Set<InheritedFieldAccessor> candidates = inheritedFieldAccessors.get(name + ':' + descriptor);
        if (candidates == null) return null;
        FieldAccessorTarget match = null;
        for (InheritedFieldAccessor candidate : candidates) {
            // The jar or the host must prove the owner inherits the field's owner. A class on the
            // way that declares the same field is the one the access links to, so it is skipped.
            if (!inheritsFrom(owner, candidate.fieldOwner())
                    || classDeclaresFieldOnTheWay(owner, candidate.fieldOwner(), name, descriptor)) {
                continue;
            }
            if (match != null && !match.equals(candidate.target())) {
                LOGGER.debug("Declined ambiguous inherited field accessor for {}.{}", owner, name);
                return null;
            }
            match = candidate.target();
        }
        return match;
    }

    /** Whether a class from the access's owner up to, not including, {@code ancestor} declares the field. */
    private boolean classDeclaresFieldOnTheWay(String owner, String ancestor, String name, String descriptor) {
        // The JVM resolves a field by walking up from the class the access names, so such a field,
        // the mod's or one the host added, is what the access reads. An unreadable class ends the walk.
        Function<String, byte[]> provider = hierarchyClasses();
        String current = owner;
        for (int depth = 0; current != null && !current.equals(ancestor) && depth < 64; depth++) {
            byte[] bytes = readClassBytes(current, provider);
            if (bytes == null) return false;
            ClassReader reader;
            try {
                reader = new ClassReader(bytes);
            } catch (RuntimeException e) {
                return false;
            }
            if (declaresField(reader, name, descriptor)) return true;
            current = reader.getSuperName();
        }
        return false;
    }

    /** Whether a class declares the field an access names after the remap. */
    private boolean declaresField(ClassReader reader, String name, String descriptor) {
        // Jar bytes can still carry intermediary or SRG names, so compare each field as written
        // and as remapped.
        String className = reader.getClassName();
        Remapper remapper = cachedRemapper;
        boolean[] declares = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String field, String desc, String signature, Object value) {
                if (declares[0]) return null;
                if (field.equals(name) && (desc.equals(descriptor) || resolveDescriptor(desc).equals(descriptor))) {
                    declares[0] = true;
                } else if (remapper != null) {
                    declares[0] = remapper.mapFieldName(className, field, desc).equals(name)
                            && remapper.mapDesc(desc).equals(descriptor);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return declares[0];
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
        if (!admitsField(owner, name)) return;
        yieldStandIn(new FieldKey(owner, name));
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
     * <p>While the sibling field is still null, as {@code Minecraft.gui} is while mods construct, a
     * read gives the field type's default, which is what the old field held then, and a write does nothing.
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
        if (!admitsField(owner, name)) return;
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
        if (!admitsField(owner, name)) return;
        fieldStaticBridges.put(new FieldKey(owner, name), new FieldStaticBridge(
                bridgeOwner, getterName, getterDesc, setterName, setterDesc));
        LOGGER.debug("Registered field static bridge: {}.{} -> {}.{}/{}",
                owner, name, bridgeOwner, getterName, setterName);
    }

    /**
     * Register a removed static field that mods read and write: {@code GETSTATIC owner.name} becomes
     * {@code INVOKESTATIC bridgeOwner.getterName getterDesc}, cast back to the field's type when the
     * getter returns a wider one, and {@code PUTSTATIC} becomes {@code INVOKESTATIC setterName setterDesc},
     * which takes the value. 1.20.5 turned the animals' food fields into item tags, and Farmer's Delight
     * reads and replaces them to add its foods.
     */
    public void registerStaticFieldBridge(String owner, String name, String bridgeOwner,
            String getterName, String getterDesc, String setterName, String setterDesc) {
        if (!admitsField(owner, name)) return;
        yieldStandIn(new FieldKey(owner, name));
        staticFieldBridges.put(new FieldKey(owner, name), new FieldStaticBridge(
                bridgeOwner, getterName, getterDesc, setterName, setterDesc));
    }

    /**
     * Register a read-only static field bridge that stands in for a removed field, such as an empty registry for one
     * the host dropped. It yields to any other redirect of the same field, whichever registers first, because a
     * static bridge is applied before every other field redirect and would silently replace the real one.
     */
    public void registerStandInStaticFieldBridge(String owner, String name, String bridgeOwner,
            String getterName, String getterDesc) {
        if (!admitsField(owner, name)) return;
        FieldKey key = new FieldKey(owner, name);
        if (redirectsField(key)) {
            LOGGER.debug("Skipped stand-in for {}.{}: another bridge already redirects it", owner, name);
            return;
        }
        staticFieldBridges.put(key, new FieldStaticBridge(bridgeOwner, getterName, getterDesc, null, null));
        standInStaticFieldBridges.add(key);
    }

    /** Whether any field redirect, rename, accessor, or static bridge is registered for {@code key}. */
    private boolean redirectsField(FieldKey key) {
        if (fieldRedirects.containsKey(key) || staticFieldBridges.containsKey(key)
                || staticFieldAccessors.containsKey(key) || fieldAccessorRedirects.containsKey(key)) {
            return true;
        }
        String typedPrefix = key.owner() + "." + key.name() + ":";
        return typedFieldRenames.keySet().stream().anyMatch(typed -> typed.startsWith(typedPrefix));
    }

    /** Drops a stand-in registered for {@code key}, because the bridge now being registered is the real one. */
    private void yieldStandIn(FieldKey key) {
        if (standInStaticFieldBridges.isEmpty() || !standInStaticFieldBridges.remove(key)) return;
        staticFieldBridges.remove(key);
        LOGGER.debug("Dropped stand-in for {}.{}: another bridge redirects it", key.owner(), key.name());
    }

    /**
     * Register a static field that kept its name but changed type. A {@code GETSTATIC} under
     * {@code ownerPrefix} that still asks for {@code oldDesc} reads the field at {@code newDesc}
     * and passes the value through {@code converterOwner.converterName}, which must take the new
     * type and return the old one. 1.19.3 turned every vanilla worldgen constant such as
     * {@code VegetationFeatures.PATCH_GRASS} from a {@code Holder} into a {@code ResourceKey}, so
     * an old mod's read fails with {@code NoSuchFieldError} unless the key is wrapped back into a
     * holder. Matching by package prefix covers the hundreds of constants without listing them.
     * A prefix that does not end in {@code /} names one class and its nested classes.
     */
    public void registerStaticFieldTypeDrift(String ownerPrefix, String oldDesc, String newDesc,
            String converterOwner, String converterName, String converterDesc) {
        staticFieldTypeDrifts.add(new StaticFieldTypeDrift(
                ownerPrefix, oldDesc, newDesc, converterOwner, converterName, converterDesc));
    }

    /**
     * Register a by-name reader for another mod's static fields that its current build may declare
     * with a different type. A {@code GETSTATIC} of {@code descriptor} on a class that is not in the
     * jar being transformed, not in a library Retromod translated with it, and not part of Java,
     * Minecraft, a loader or Retromod becomes {@code readerOwner.readerName(Class, String)} followed
     * by a cast back to {@code descriptor}. A drift registration cannot do this: a Forge library's
     * NeoForge port retypes its {@code RegistryObject} fields one by one, as {@code Supplier},
     * {@code Holder} or {@code DeferredHolder} in the same class, and only the running library knows
     * which. Reads of the jar's own classes are never rewritten, so their private fields stay readable.
     */
    public void registerLibraryStaticFieldReader(String descriptor, String readerOwner, String readerName) {
        libraryStaticFieldReaders.put(descriptor,
                new MethodTarget(readerOwner, readerName, LIBRARY_FIELD_READER_DESC));
    }

    /** Whether a static read names another mod's field that a library field reader should read by name. */
    private boolean readsOtherModField(String owner, String name, String descriptor, String readingClass) {
        if (owner.equals(readingClass) || !isModClass(owner)) return false;
        // Without the jar's class list, a read of the jar's own class cannot be told apart.
        Function<String, byte[]> jarClasses = currentJarClassBytesProvider();
        if (jarClasses == null || jarClasses.apply(owner) != null) return false;
        // A library that still declares the field as read, such as one Retromod translated too,
        // needs no reader.
        return !linkedLibraryDeclares(owner, name + ':' + descriptor);
    }

    /** A mod's class, as opposed to one of Java, Minecraft, a loader or Retromod. */
    private static boolean isModClass(String owner) {
        return isModOwnedType(owner)
                && !owner.startsWith("net/minecraftforge/") && !owner.startsWith("net/neoforged/");
    }

    /** Whether the jar being transformed ships {@code owner}, a mod class, itself. */
    private boolean jarDefinesModClass(String owner) {
        if (owner == null || !isModClass(owner)) return false;
        Function<String, byte[]> jarClasses = currentJarClassBytesProvider();
        return jarClasses != null && jarClasses.apply(owner) != null;
    }

    /**
     * Whether a constructor redirect registered for another mod's class must leave this call alone.
     * The redirect describes that mod's current build, but the class this jar runs against may
     * still have the constructor as called: the jar ships the class itself, or links to a copy that
     * keeps it, such as a library Retromod translated too. Farmer's Delight 1.20.1, translated,
     * still builds knives from damage and speed.
     */
    private boolean keepsConstructor(String owner, String descriptor) {
        if (owner == null || !isModClass(owner)) return false;
        return jarDefinesModClass(owner) || linkedLibraryDeclares(owner, "<init>" + descriptor);
    }

    /** Whether a constructor redirect limited by {@link #limitRedirectToModsBefore} with {@code <init>} skips the current mod. */
    private boolean skipsConstructorRedirect(ConstructorKey key) {
        return skipsCurrentMod(new MethodKey(key.className(), "<init>", key.constructorDesc()));
    }

    /**
     * Register a getter for an instance field that became private, matched by name and
     * descriptor whatever class the read names as owner. Only safe for a name that identifies one
     * member everywhere, which an SRG field id does: {@code f_19853_} is {@code Entity.level},
     * and a mod reads it through {@code Entity}, {@code Player} and its own entity classes alike.
     */
    public void registerUniqueFieldGetter(String fieldName, String fieldDesc,
            String getterOwner, String getterName, String getterDesc) {
        uniqueFieldGetters.put(fieldName + ':' + fieldDesc,
                new MethodTarget(getterOwner, getterName, getterDesc));
    }

    /**
     * Register a static bridge for an instance field, as {@link #registerFieldStaticBridge} does,
     * matched by name and descriptor whatever class the access names as owner. Only safe for an SRG
     * field id, which names one member everywhere: a 1.12.2 item sets {@code field_77777_bU}
     * ({@code maxStackSize}) through its own class, which no owner-keyed bridge would match.
     */
    public void registerUniqueFieldStaticBridge(String fieldName, String fieldDesc, String bridgeOwner,
            String getterName, String getterDesc, String setterName, String setterDesc) {
        uniqueFieldStaticBridges.put(fieldName + ':' + fieldDesc,
                new FieldStaticBridge(bridgeOwner, getterName, getterDesc, setterName, setterDesc));
    }

    private StaticFieldTypeDrift staticFieldTypeDrift(String owner, String descriptor) {
        for (StaticFieldTypeDrift drift : staticFieldTypeDrifts) {
            if (driftCovers(drift.ownerPrefix(), owner) && drift.oldDesc().equals(descriptor)) {
                return drift;
            }
        }
        return null;
    }

    /** A package prefix covers its classes; a class name covers itself and its nested classes, so class_39 never takes class_390. */
    private static boolean driftCovers(String ownerPrefix, String owner) {
        if (ownerPrefix.endsWith("/")) return owner.startsWith(ownerPrefix);
        return owner.equals(ownerPrefix) || owner.startsWith(ownerPrefix + "$");
    }

    /**
     * Owners of removed classes whose static-field reads should become a pushed default (null / 0)
     * rather than fault on the missing class. Used by the 1.12.2 Block bridge: {@code super(Material.IRON)}
     * reads {@code Material.IRON} before the super call, but Material was removed in 26.1, so the
     * GETSTATIC would NoClassDefFoundError. We push the field type's default; the super-ctor replace
     * bridge then pops it and substitutes a default {@code Properties}.
     */
    public void registerStaticFieldNuller(String owner) {
        if (!admitsType(owner)) return;
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

    /**
     * Records whether a Retromod helper a method redirect calls is a class or an interface. The call
     * keeps the old owner's interface flag otherwise, so a static interface method sent to a helper
     * class, such as Cloth Config's {@code ConfigBuilder.create()}, fails with
     * {@code IncompatibleClassChangeError}. Read from the class file, so nothing is loaded.
     */
    private void recordHelperOwnerKind(String internalName) {
        if (internalName == null || !internalName.startsWith("com/retromod/")
                || classShimTargets.contains(internalName) || interfaceSyntheticTargets.contains(internalName)
                || syntheticClasses.containsKey(internalName)) {
            return;
        }
        byte[] bytes = readClassBytes(internalName, null);
        if (bytes == null) return;
        try {
            boolean isInterface = (new ClassReader(bytes).getAccess() & Opcodes.ACC_INTERFACE) != 0;
            (isInterface ? interfaceSyntheticTargets : classShimTargets).add(internalName);
        } catch (RuntimeException unreadable) {
            LOGGER.debug("Could not read redirect target kind for {}", internalName, unreadable);
        }
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
     * Record a host class that replaces an old interface, such as NeoForge's final {@code Lazy}
     * class for Forge's {@code Lazy} interface. A plain class redirect keeps the old
     * InterfaceMethodref, which the JVM rejects with {@code IncompatibleClassChangeError}.
     */
    public void registerClassOwner(String internalName) {
        classShimTargets.add(internalName);
    }

    /**
     * Register a synthetic class generated via ASM bytecode.
     * These classes are embedded under a per-mod name during transformation and
     * can have fields/methods with MC-typed signatures that can't be
     * compiled from Java source (since MC isn't on the compile classpath).
     *
     * @param internalName the class internal name (e.g., "com/retromod/polyfill/...")
     * @param classBytes the generated class file bytes
     */
    public void registerSyntheticClass(String internalName, byte[] classBytes) {
        if (!admitsType(internalName)) return;
        // The embedder copies these bytes into mods. A class read from the release jar names
        // Retromod's relocated Gson and SLF4J, which a mod cannot see, so it names the game's.
        classBytes = com.retromod.util.ShadedLibraryNames.toHostNames(classBytes);
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
        SyntheticEmbedder.registerSharedHelpers(this, internalName, classBytes);
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
        if (!admitsType(oldSuperclass)) return;
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
        if (!admitsType(oldSuperclass)) return;
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
        if (!admitsType(oldSuperclass)) return;
        superclassRedirects.put(oldSuperclass, new SuperclassRedirect(newSuperclass, addInterfaces, true));
        LOGGER.debug("Registered superclass REBASE (extends + super ctor + {} interfaces): {} -> {}",
                addInterfaces.length, oldSuperclass, newSuperclass);
    }

    /**
     * Moves the subclasses of a removed base onto {@code newBase}, while every other reference to the
     * removed base follows its class redirect to {@code redirectedName}.
     *
     * <p>The remapper writes the redirect's destination into the extends slot before the rebase is
     * consulted, so the rebase is keyed on both names. A mod that the redirect skips for its version
     * names {@code redirectedName} itself, so its own subclasses of it stay where they are.
     */
    public void registerRedirectedSubclassRebase(String removedBase, String redirectedName, String newBase) {
        if (!admitsType(removedBase)) return;
        registerSuperclassRebase(removedBase, newBase);
        registerSuperclassRebase(redirectedName, newBase);
        rebasesThroughRedirects.put(redirectedName, removedBase);
    }

    /** The rebase for a class whose own bytes extend {@code writtenSuperName}, now {@code superName}, or null when none applies. */
    private SuperclassRedirect superclassRebase(String superName, String writtenSuperName) {
        SuperclassRedirect rebase = superName == null ? null : superclassRedirects.get(superName);
        String removedBase = rebase == null ? null : rebasesThroughRedirects.get(superName);
        if (removedBase == null) return rebase;
        // Keyed on the redirect's destination, which a newer mod can extend itself: only a class that
        // extended the removed base moves, whatever version the mod declares.
        if (!writtenAs(writtenSuperName, removedBase) || keepsOldName(removedBase)) return null;
        return rebase;
    }

    /** Whether {@code written}, or the name its class moves lead to, is {@code target}. */
    private boolean writtenAs(String written, String target) {
        String name = written;
        // A Fabric mod names the intermediary class, which reaches the removed base in one or two moves.
        for (int hop = 0; name != null && hop < 4; hop++) {
            if (name.equals(target)) return true;
            String moved = classRedirects.get(name);
            if (moved == null || moved.equals(name)) return false;
            name = moved;
        }
        return false;
    }

    /**
     * Base classes whose inheritance edge is rebased. Exposed so a test can hold the rule that a
     * rebased base must not also be a class move: the remapper runs first and the rename would win.
     */
    public java.util.Set<String> getSuperclassRebaseSources() {
        return java.util.Set.copyOf(superclassRedirects.keySet());
    }

    /**
     * Rebase a base class that the host DELETED onto a generated stand-in.
     *
     * <p>Use this instead of {@link #registerClassRedirect} when a mod extends a class the host no
     * longer has. A redirect can only point at something that already exists, and a surviving class
     * with a different constructor cannot accept the old {@code super(...)} call. This registers the
     * inheritance edge onto a Retromod-owned class that extends {@code modernBase} and grows a
     * constructor for each {@code super(...)} descriptor actually seen during the transform, so no
     * list of historical constructor shapes has to be maintained.
     *
     * <p>A descriptor whose arguments cannot supply {@code modernCtorDesc} is refused and logged.
     * The mod then fails with a named missing constructor rather than loading against a base that
     * silently dropped an argument it needed.
     *
     * @param removedBase     internal name of the deleted class the mods extend
     * @param generatedName   Retromod-owned internal name to generate
     * @param modernBase      surviving base for the generated class to extend
     * @param modernCtorDesc  descriptor of the {@code modernBase} constructor to forward to
     */
    public void registerGeneratedLegacyBase(String removedBase, String generatedName,
            String modernBase, String modernCtorDesc) {
        if (!admitsType(removedBase)) return;
        generatedLegacyBases.put(generatedName, new LegacyBaseSpec(modernBase, modernCtorDesc,
                ConcurrentHashMap.newKeySet(), null));
        registerSyntheticClass(generatedName, LegacyBaseSynthesizer.generate(
                generatedName, modernBase, modernCtorDesc, List.of()));
        registerSuperclassRebase(removedBase, generatedName);
        // The rest of a mod names the removed class too: an instanceof, a cast, a parameter type.
        // Each would be a NoClassDefFoundError, and a parameter type fails verification for the
        // whole class. The generated base is the one class that stands in for it, so references
        // move there as well. The remapper then writes the generated name into the extends slot
        // before the rebase is consulted, so the rebase is also keyed on it, which is what lets the
        // super(...) call be absorbed.
        registerClassRedirect(removedBase, generatedName);
        registerSuperclassRebase(generatedName, generatedName);
        LOGGER.debug("Registered generated legacy base: {} -> {} extends {}",
                removedBase, generatedName, modernBase);
    }

    /** Turns on a post-process adapter for every class this transformer handles. */
    public void enablePostProcess(String flag) {
        postProcessFlags.add(flag);
    }

    /**
     * Like {@link #registerGeneratedLegacyBase}, for a removed base whose other references already
     * move to a modern class that cannot be extended, such as a 1.12 {@code WorldProvider} whose
     * type references become the final {@code DimensionType}. Only subclasses move onto the
     * generated base; the rebase is keyed on both names, because the class redirect writes the
     * modern name into the extends slot first.
     */
    public void registerGeneratedLegacySubclassBase(String removedBase, String redirectedName,
            String generatedName, String modernBase, String modernCtorDesc) {
        if (!admitsType(removedBase)) return;
        generatedLegacyBases.put(generatedName, new LegacyBaseSpec(modernBase, modernCtorDesc,
                ConcurrentHashMap.newKeySet(), null));
        registerSyntheticClass(generatedName, LegacyBaseSynthesizer.generate(
                generatedName, modernBase, modernCtorDesc, List.of()));
        registerSuperclassRebase(removedBase, generatedName);
        registerSuperclassRebase(redirectedName, generatedName);
        registerSuperclassRebase(generatedName, generatedName);
    }

    /**
     * Gives a generated legacy base an {@link LegacyBaseSynthesizer.ArgumentAdapter}, so its
     * constructors rebuild the removed base's behavior from the arguments they used to drop. Call
     * it after {@link #registerGeneratedLegacyBase}; constructors already absorbed are rebuilt.
     */
    public void attachLegacyBaseAdapter(String generatedName, LegacyBaseSynthesizer.ArgumentAdapter adapter) {
        LegacyBaseSpec spec = generatedLegacyBases.get(generatedName);
        if (spec == null) return;
        synchronized (spec) {
            LegacyBaseSpec adapted = new LegacyBaseSpec(spec.modernBase(), spec.modernCtorDesc(),
                    spec.observedCtorDescs(), adapter);
            generatedLegacyBases.put(generatedName, adapted);
            registerSyntheticClass(generatedName, LegacyBaseSynthesizer.generate(generatedName,
                    adapted.modernBase(), adapted.modernCtorDesc(), List.copyOf(adapted.observedCtorDescs()), adapter));
        }
    }

    /**
     * Whether the rebase target can accept this {@code super(...)} descriptor: either it is not a
     * generated base, in which case it was declared to have matching constructors, or it is one and
     * just grew the constructor.
     */
    private boolean absorbsOrIsNotGenerated(String newSuperclass, String descriptor) {
        return !generatedLegacyBases.containsKey(newSuperclass)
                || absorbLegacyCtor(newSuperclass, descriptor);
    }

    /** The surviving class a generated legacy base extends, or null when the name is not one. */
    public String generatedLegacyBaseParent(String generatedName) {
        if (generatedName == null) return null;
        LegacyBaseSpec spec = generatedLegacyBases.get(generatedName);
        if (spec != null) return spec.modernBase();
        // A rebase onto a generated subclass of the same base, such as the 26.3 codec bases for chunk
        // generators and biome sources, names the base it rebased as the parent.
        byte[] generated = syntheticClasses.get(generatedName);
        if (generated == null) return null;
        String parent = new ClassReader(generated).getSuperName();
        SuperclassRedirect rebase = parent == null ? null : superclassRedirects.get(parent);
        return rebase != null && generatedName.equals(rebase.newSuperclass()) ? parent : null;
    }

    /**
     * Add {@code descriptor} to a generated legacy base and rebuild it, so the rebased
     * {@code super(...)} call has a constructor to land on.
     *
     * @return true when the base now declares that constructor
     */
    private boolean absorbLegacyCtor(String generatedName, String descriptor) {
        LegacyBaseSpec spec = generatedLegacyBases.get(generatedName);
        if (spec == null) return false;

        // One lock per base, because the whole read, decide, generate and publish sequence has to be
        // atomic. The AOT compilers transform a jar's classes on several threads against this one
        // shared transformer, and a check-then-act here would let two threads each generate from
        // their own snapshot and publish, silently dropping the other's constructor.
        synchronized (spec) {
            if (spec.observedCtorDescs().contains(descriptor)) return true;

            // The descriptor is the mod's own super(...) call, so it only has to satisfy ASM's
            // parser. Reject anything the JVM would not accept before it can reach the shared base,
            // which every mod extending the same removed class links against.
            if (!LegacyBaseSynthesizer.isUsableCtorDescriptor(descriptor)) {
                LOGGER.warn("Refusing super{} for {}: not a usable constructor descriptor.",
                        descriptor, generatedName);
                return false;
            }
            // The class is rebuilt on each new shape, so many distinct shapes cost quadratic time
            // and grow the class without limit. A real mod uses a handful for one base.
            if (spec.observedCtorDescs().size() >= MAX_ABSORBED_LEGACY_CTORS) {
                LOGGER.warn("Refusing to absorb super{} into {}: already carrying {} constructors, "
                        + "which is past the limit. The mod extends a removed base with an "
                        + "implausible number of constructor shapes.",
                        descriptor, generatedName, MAX_ABSORBED_LEGACY_CTORS);
                return false;
            }

            // Generate before committing. A descriptor that survives validation and still fails to
            // emit must not be left in the set, or it poisons the base for every later mod.
            List<String> candidate = new ArrayList<>(spec.observedCtorDescs());
            candidate.add(descriptor);
            byte[] generated;
            try {
                generated = LegacyBaseSynthesizer.generate(generatedName, spec.modernBase(),
                        spec.modernCtorDesc(), candidate, spec.adapter());
            } catch (RuntimeException e) {
                LOGGER.warn("Cannot absorb super{} into {}: {}", descriptor, generatedName,
                        e.toString());
                return false;
            }
            spec.observedCtorDescs().add(descriptor);
            registerSyntheticClass(generatedName, generated);
            return true;
        }
    }

    /**
     * Whether a generated base has taken all the constructor shapes it will take.
     *
     * <p>Consulted before the {@code extends} edge is rebased, so a saturated base behaves as if the
     * bridge were not registered at all. Rebasing the edge while refusing the matching
     * {@code super(...)} rewrite would leave a class extending the generated base and calling a
     * constructor on the old one, which fails verification rather than linkage and is harder to read.
     */
    private boolean isLegacyBaseSaturated(String generatedName) {
        LegacyBaseSpec spec = generatedLegacyBases.get(generatedName);
        if (spec == null) return false;
        synchronized (spec) {
            return spec.observedCtorDescs().size() >= MAX_ABSORBED_LEGACY_CTORS;
        }
    }

    /**
     * Starts a standalone offline input with no registrations from the previous input.
     * Call this only at an offline transform boundary, before registering that input's complete
     * shim, polyfill, and mapping set. Runtime loader entry points keep one host-wide set instead.
     */
    public void resetOfflineRegistrations() {
        clearRegisteredState();
    }

    /** Test-only alias for clearing the singleton between independent cases. */
    public void clearRedirectsForTesting() {
        clearRegisteredState();
    }

    /** Gates outside this class that belong to the current registration set. */
    private final List<Runnable> registrationResetHooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Runs {@code hook} once when the current registrations are cleared. A bridge that keeps a
     * static gate registers one here, so an offline batch does not apply one input's bridge to
     * the next input after its redirects and synthetics are gone.
     */
    public void onRegistrationReset(Runnable hook) {
        registrationResetHooks.add(hook);
    }

    // NeoForge registers its own and Forge's chains on one transformer, and both call the same
    // bridges. The second call still registers, because a later registration wins a shared key.
    /** Bridge notices already logged for the current registration pass. */
    private final Set<String> registrationNotices = ConcurrentHashMap.newKeySet();

    /** Whether a bridge should log its registration notice, which happens once per registration pass. */
    public boolean firstRegistrationNotice(String key) {
        return registrationNotices.add(key);
    }

    private synchronized void clearRegisteredState() {
        methodRedirects.clear();
        wrappingMethodRedirects.clear();
        legacyOnlyRedirects.clear();
        legacyOnlyClassRedirects.clear();
        libraryYieldingClassRedirects.clear();
        superCallHelperNames.clear();
        modSourceVersion = null;
        modBuildVersion = null;
        modLibraries = SyntheticLibraries.NONE;
        inheritedMethodRedirects.clear();
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
        functionalInterfaceRenames.clear();
        mojangRenames = null;
        synchronized (inheritedMojangRenames) {
            inheritedMojangRenames.clear();
        }
        mojangRenameOnHost.clear();
        mojangClassRenames.clear();
        ownOverrideRenames.clear();
        lambdaAdapters.clear();
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
        srgDrift.clear();
        srgSourceVersion = null;
        targetSrgMethodNames.clear();
        targetSrgFieldNames.clear();
        targetSrgSamIndex = null;
        targetSrgMethodIds = null;
        targetSrgFieldIds = null;
        classRedirects.clear();
        redirectProviderPackages.clear();
        fieldRedirects.clear();
        exactDescriptorFieldRedirects.clear();
        standInFieldGetters.clear();
        descriptorFieldRedirects.clear();
        mutableAccessorDestinations.clear();
        superclassRedirects.clear();
        rebasesThroughRedirects.clear();
        inheritedTargetSrgNames.clear();
        superclassVerdicts.clear();
        interfaceKinds.clear();
        classKinds.clear();
        hostClassShapes.clear();
        jarShapeCaches.clear();
        generatedLegacyBases.clear();
        reportedIllegalSuperclasses.clear();
        constructorRedirects.clear();
        constructorRedirectOwners.clear();
        fieldAccessorRedirects.clear();
        inheritedFieldAccessors.clear();
        inheritedFieldStaticBridges.clear();
        inheritedStaticFields.clear();
        staticFieldAccessors.clear();
        fieldHopAccessors.clear();
        fieldStaticBridges.clear();
        staticFieldBridges.clear();
        standInStaticFieldBridges.clear();
        fieldStaticBridgeTypes.clear();
        staticFieldTypeDrifts.clear();
        libraryStaticFieldReaders.clear();
        uniqueFieldGetters.clear();
        uniqueFieldStaticBridges.clear();
        superCtorRedirects.clear();
        superCtorStackAdapters.clear();
        instanceofHelpers.clear();
        typedFieldRenames.clear();
        syntheticClasses.clear();
        postProcessFlags.clear();
        embeddedShimClasses.clear();
        methodRedirectOwners.clear();
        neutralizedMethods.clear();
        neutralizedMethodOwners.clear();
        implementedInterfaceRewrites.clear();
        implementedMethodBridges.clear();
        interfaceDefaultBridges.clear();
        indyTypedRewrites.clear();
        indyTypedOwners.clear();
        claimedTypes.clear();
        claimedMembers.clear();
        claimedMemberNames.clear();
        suppressedKeys.clear();
        registrationEpoch.incrementAndGet();
        classRedirectsVersion.incrementAndGet();
        cachedRemapper = null;
        reflectionRemapper = null;
        // A fresh redirect set must be re-validated for phantom targets on the next transform
        // (the sweep is otherwise once per transformer lifetime).
        targetsValidated.set(false);
        // Each offline input logs its own bridge notices.
        registrationNotices.clear();
        List<Runnable> hooks = new ArrayList<>(registrationResetHooks);
        registrationResetHooks.clear();
        hooks.forEach(Runnable::run);
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
        boolean outerCallback = !IN_CLASS_LOAD_CALLBACK.get();
        IN_CLASS_LOAD_CALLBACK.set(Boolean.TRUE);
        try {
            HybridTransformationEngine hybrid = HybridTransformationEngine.getInstance();
            String modId = guessModFromClass(className);
            return hybrid.transform(className, classfileBuffer, modId);
        } catch (Exception e) {
            LOGGER.error("Failed to transform class: {}", className, e);
            return null;
        } finally {
            if (outerCallback) IN_CLASS_LOAD_CALLBACK.remove();
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
     * embedded) nor a class file Retromod ships, turning a latent hard crash into a one-line
     * warning and a no-op (the original call is left intact, which for a redundant redirect is
     * exactly right and for a genuinely-removed method is no worse than the broken redirect was).
     * Runs once, off the hot path, at the first {@link #transformClass}.
     */
    private void dropPhantomComRetromodTargets() {
        java.util.function.Predicate<String> isPhantom = phantomTargetTest();
        java.util.Set<String> dropped = new java.util.TreeSet<>();

        methodRedirects.entrySet().removeIf(e -> {
            if (isPhantom.test(e.getValue().owner())) { dropped.add(e.getValue().owner()); return true; }
            return false;
        });
        inheritedMethodRedirects.values().forEach(entries -> entries.removeIf(entry -> {
            if (isPhantom.test(entry.target().owner())) {
                dropped.add(entry.target().owner());
                return true;
            }
            return false;
        }));
        inheritedMethodRedirects.entrySet().removeIf(e -> e.getValue().isEmpty());
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
        descriptorFieldRedirects.entrySet().removeIf(e -> {
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
            // Each offline input registers the same shims again, so name a dead target once per process.
            Set<String> unreported = new TreeSet<>(dropped);
            unreported.removeAll(reportedPhantoms);
            reportedPhantoms.addAll(dropped);
            if (unreported.isEmpty()) {
                LOGGER.debug("Dropped redirects to {} phantom com/retromod target(s) again: {}",
                        dropped.size(), dropped);
            } else {
                LOGGER.warn("Dropped redirects to {} phantom com/retromod target(s) that would have "
                        + "crashed at first use: {}", unreported.size(), unreported);
            }
        }
    }

    /** Flags a com/retromod target that is neither a class file Retromod ships nor a registered synthetic. */
    private java.util.function.Predicate<String> phantomTargetTest() {
        ClassLoader loader = RetromodTransformer.class.getClassLoader();
        Map<String, Boolean> resolvable = new HashMap<>();
        return owner -> {
            if (owner == null || !owner.startsWith("com/retromod/")) return false;
            // Look for the class file instead of loading it: the CLI has no Mixin or host classes,
            // so a shipped helper such as MixinExtrasShim$PluginCompat cannot link there but does in game.
            return !resolvable.computeIfAbsent(owner, o ->
                    syntheticClasses.containsKey(o) || loader.getResource(o + ".class") != null);
        };
    }

    /**
     * Runs the phantom sweep once, and makes parallel transform workers wait until it has finished.
     *
     * <p>Mod classes are transformed in parallel, and the first worker to arrive runs the sweep.
     * Letting the other workers proceed meanwhile made the output depend on timing: a class
     * transformed mid-sweep could still be rewritten to a phantom target, and the owner-set
     * rebuild at the end of the sweep briefly empties {@code constructorRedirectOwners}, so a
     * class caught in that window kept some {@code new X(...)} calls unredirected. Constructor
     * redirects only run in pass 1, so later passes never repaired them.
     *
     * <p>The Java agent path cannot wait. The agent transforms {@code com/retromod/shim/} classes
     * as they load, and its callback runs while the JVM holds that class's loading lock, so it must
     * not block behind a sweep on another thread. A class-load callback therefore runs the sweep
     * itself when the lock is free and otherwise continues without it, as it did before. A
     * callback on the sweeping thread returns at once instead of sweeping a half-swept table.
     */
    private void ensureTargetsValidated() {
        if (targetsValidated.get()) return;
        if (IN_CLASS_LOAD_CALLBACK.get()) {
            if (!sweepLock.tryLock()) return;
        } else {
            sweepLock.lock();
        }
        try {
            if (targetsValidated.get() || sweepLock.getHoldCount() > 1) return;
            try {
                dropPhantomComRetromodTargets();
            } finally {
                // As before, a failed sweep is not retried on every later class.
                targetsValidated.set(true);
            }
        } finally {
            sweepLock.unlock();
        }
    }

    /**
     * Read-only counterpart of {@link #dropPhantomComRetromodTargets}: returns every currently
     * registered redirect target under {@code com/retromod/} that is neither a registered
     * synthetic nor a shipped class file, without removing anything. The runtime guard makes these
     * fail-safe; this exists so a CI test can assert the set is empty and prevent NEW phantom
     * targets from being introduced (the shim tree carried a long tail of them, #119). Sorted for
     * stable output.
     */
    public java.util.SortedSet<String> collectPhantomComRetromodTargets() {
        java.util.function.Predicate<String> isPhantom = phantomTargetTest();
        java.util.SortedSet<String> phantoms = new java.util.TreeSet<>();
        methodRedirects.values().forEach(v -> { if (isPhantom.test(v.owner())) phantoms.add(v.owner()); });
        inheritedMethodRedirects.values().forEach(entries -> entries.forEach(entry -> {
            if (isPhantom.test(entry.target().owner())) phantoms.add(entry.target().owner());
        }));
        argDropRedirects.values().forEach(v -> { if (isPhantom.test(v.owner())) phantoms.add(v.owner()); });
        classRedirects.values().forEach(v -> { if (isPhantom.test(v)) phantoms.add(v); });
        fieldRedirects.values().forEach(v -> { if (isPhantom.test(v.owner())) phantoms.add(v.owner()); });
        descriptorFieldRedirects.values().forEach(v -> { if (isPhantom.test(v.owner())) phantoms.add(v.owner()); });
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
        // The loader API runtime registers again here when something cleared its rows.
        PreTransformHook hook = preTransformHook;
        if (hook != null) hook.beforeTransform(this);

        // One-time safety sweep: drop any redirect whose target is a com/retromod/* class
        // that will never resolve at runtime (see dropPhantomComRetromodTargets).
        ensureTargetsValidated();

        // No redirects registered: no pass would do anything, skip the loop.
        // constructorRedirects + fieldAccessorRedirects are included here too, or a
        // ctor-only / field-accessor-only redirect set would skip the transform entirely.
        if (methodRedirects.isEmpty() && classRedirects.isEmpty() &&
                fieldRedirects.isEmpty() && superclassRedirects.isEmpty() &&
                neutralizedMethods.isEmpty() && staticFieldAccessors.isEmpty() &&
                mojangMethodRenames.isEmpty() && mojangRenames == null && argDropRedirects.isEmpty() &&
                convertingRedirects.isEmpty() &&
                constructorRedirects.isEmpty() && fieldAccessorRedirects.isEmpty() &&
                superCtorRedirects.isEmpty() && typedFieldRenames.isEmpty() &&
                fieldHopAccessors.isEmpty() && fieldStaticBridges.isEmpty() && staticFieldBridges.isEmpty() &&
                uniqueFieldGetters.isEmpty() && uniqueFieldStaticBridges.isEmpty() &&
                intermediaryMethodNames.isEmpty() && intermediaryFieldNames.isEmpty() &&
                srgMethodNames.isEmpty() && srgFieldNames.isEmpty() &&
                targetSrgMethodNames.isEmpty() && targetSrgFieldNames.isEmpty() &&
                lambdaAdapters.isEmpty() && postProcessFlags.isEmpty()
                && implementedInterfaceRewrites.isEmpty() && implementedMethodBridges.isEmpty()
                && interfaceDefaultBridges.isEmpty() && instanceofHelpers.isEmpty()
                && staticFieldTypeDrifts.isEmpty() && libraryStaticFieldReaders.isEmpty()) {
            // Texture paths follow the host's vanilla files, not the redirects, so they still apply.
            return com.retromod.resources.VanillaTexturePathConstants.apply(
                    com.retromod.shim.common.LegacyInputEventCallAdapter.apply(originalBytes),
                    RetromodVersion.TARGET_MC_VERSION);
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
        byte[] current = legacyModelLocationPrePass(legacyEventBasePrePass(legacyMaterialPrePass(originalBytes)));
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
                byte[] baseline = identityReserialize(current, className);
                if (baseline != null && Arrays.equals(next, baseline)) {
                    return postProcess(current, className);
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

    private static final byte[] MATERIAL_CLASS_NAME = "net/minecraft/world/level/material/Material"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * 1.20 removed {@code Material}. An SRG-named Forge mod's material queries are moved onto the
     * block state before the remap, which would otherwise null the {@code Material.AIR} it
     * compares against. Only classes that name {@code Material} are parsed.
     */
    private byte[] legacyMaterialPrePass(byte[] classBytes) {
        if (srgMethodNames.isEmpty()
                || RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20") < 0
                || !containsBytes(classBytes, MATERIAL_CLASS_NAME)) {
            return classBytes;
        }
        return com.retromod.shim.common.LegacyMaterialAdapter.apply(classBytes,
                this::srgMethodName, this::srgFieldName);
    }

    /** Moves a mod's events from Forge 1.20.1's event base, which Forge 26.2 removed, onto the rebuilt one. */
    private byte[] legacyEventBasePrePass(byte[] classBytes) {
        if (!postProcessFlags.contains(com.retromod.shim.forge.LegacyEventBaseBridge.FLAG)) return classBytes;
        // A mod event on a Forge class that became an interface moves to its rebuilt class first, so the pass below
        // sees an event on the rebuilt base like any other.
        byte[] rebased = com.retromod.shim.forge.LegacyInterfaceEventBases.apply(classBytes);
        // It runs before the remap, while the old names still say which types were Event and GenericEvent.
        return com.retromod.shim.forge.LegacyEventBaseAdapter.apply(rebased);
    }

    /** Answers calls on Forge 1.20.1's model location type before the remap makes that type Identifier. */
    private byte[] legacyModelLocationPrePass(byte[] classBytes) {
        String oldType = com.retromod.shim.forge.LegacyModelLocationAdapter.OLD_TYPE;
        if (!postProcessFlags.contains(com.retromod.shim.forge.LegacyModelLocationAdapter.FLAG) || keepsOldName(oldType)) {
            return classBytes;
        }
        // SRG-named mods name the members by id here, before the remap.
        return com.retromod.shim.forge.LegacyModelLocationAdapter.apply(classBytes,
                name -> { String mojang = srgMethodName(name); return mojang == null ? name : mojang; },
                name -> { String mojang = srgFieldName(name); return mojang == null ? name : mojang; });
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
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
        // After the loop, so the check names the redirected type and is rewritten once.
        repaired = InstanceofHelperAdapter.apply(repaired, instanceofHelpers);
        if (syntheticClasses.containsKey(
                com.retromod.shim.fabric.embedded.LegacyBlockRandomTickBridge.INTERNAL_NAME)) {
            repaired = com.retromod.shim.fabric.LegacyBlockRandomTickCallAdapter.apply(
                    repaired, currentJarClassBytesProvider());
        }
        repaired = com.retromod.shim.common.LegacyInputEventCallAdapter.apply(repaired);
        repaired = addOwnOverrideHostMethods(repaired);
        // Only on hosts from 1.20.5 to 1.21.4, where Tier gained an abstract method.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyTierBridge.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyTierBridge.apply(repaired);
        }
        // Only on hosts from 1.21.2, whose reload listeners lost the profilers and, from 1.21.9, take a shared state.
        if (postProcessFlags.contains(com.retromod.shim.common.LegacyReloadListenerBridge.STATE_FLAG)) {
            repaired = com.retromod.shim.common.LegacyReloadListenerAdapter.apply(repaired,
                    com.retromod.shim.common.LegacyReloadListenerBridge.Shape.STATE);
        } else if (postProcessFlags.contains(com.retromod.shim.common.LegacyReloadListenerBridge.BARRIER_FLAG)) {
            repaired = com.retromod.shim.common.LegacyReloadListenerAdapter.apply(repaired,
                    com.retromod.shim.common.LegacyReloadListenerBridge.Shape.BARRIER);
        }
        // Only where the host changed MCreator's dropExperience call, on Forge-family hosts.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyEntityTemplateAdapter.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyEntityTemplateAdapter.apply(repaired);
        }
        // Only where a Forge mod moves to NeoForge, whose FML builds a @Mod class from a container, not a context.
        if (postProcessFlags.contains(com.retromod.shim.forge.NeoForgeModConstructorArgs.FLAG)) {
            repaired = com.retromod.shim.forge.NeoForgeModConstructorArgs.apply(repaired);
        }
        // Only on a Forge host whose ItemStack lost capNBT, which Sophisticated Core finds by reflection.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyCapNbtFieldAdapter.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyCapNbtFieldAdapter.apply(repaired);
        }
        // Only when a pre-1.16 shim registered the world type stand-in, so newer mods are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.forge.LegacyWorldTypeBridge.BASE)) {
            repaired = com.retromod.shim.forge.LegacyWorldTypeBridge.apply(repaired);
        }
        // Only when the Fabric id bridge is registered, which is the 26.x Fabric path.
        if (syntheticClasses.containsKey(
                com.retromod.shim.fabric.FabricRegistryIdSynthetic.INTERNAL)) {
            repaired = com.retromod.shim.fabric.FabricRegistryIdAdapter.apply(
                    repaired, currentJarRegistryIds());
        } else if (postProcessFlags.contains(
                com.retromod.shim.forge.RegistryIdBridgeSynthetic.STATIC_FIELDS_FLAG)) {
            // NeoForge 1.21.11 and newer, where the Properties factories read the deferred register's bridge.
            repaired = com.retromod.shim.common.StaticFieldIdStamper.apply(repaired,
                    currentJarRegistryIds(), com.retromod.shim.forge.RegistryIdBridgeSynthetic.INTERNAL);
            // What a RegisterEvent consumer builds and registers gets its id while it is built.
            repaired = com.retromod.shim.common.ConsumerRegistrationIdStamper.apply(repaired,
                    currentJarRegistryIds(), com.retromod.shim.forge.RegistryIdBridgeSynthetic.INTERNAL);
            repaired = com.retromod.shim.common.ConstructorIdStamper.apply(repaired,
                    com.retromod.shim.forge.RegistryIdBridgeSynthetic.INTERNAL);
        } else if (postProcessFlags.contains(
                com.retromod.shim.forge.RegistryIdBridgeSynthetic.FORGE_STATIC_FIELDS_FLAG)) {
            // Forge 1.21.11 and newer, the same through Forge's own bridge.
            repaired = com.retromod.shim.common.StaticFieldIdStamper.apply(repaired,
                    currentJarRegistryIds(), com.retromod.shim.forge.RegistryIdBridgeSynthetic.FORGE_INTERNAL);
            repaired = com.retromod.shim.common.ConsumerRegistrationIdStamper.apply(repaired,
                    currentJarRegistryIds(), com.retromod.shim.forge.RegistryIdBridgeSynthetic.FORGE_INTERNAL);
            repaired = com.retromod.shim.common.ConstructorIdStamper.apply(repaired,
                    com.retromod.shim.forge.RegistryIdBridgeSynthetic.FORGE_INTERNAL);
        }
        // Only where an EntityType.Builder.build(String) bridge is registered, which is 1.21.2 and newer.
        if (syntheticClasses.containsKey(com.retromod.shim.common.EntityTypeBuildKeyAdapter.BRIDGE)) {
            repaired = com.retromod.shim.common.EntityTypeBuildKeyAdapter.apply(repaired, currentJarRegistryIds());
        }
        // Only when a 1.21.1 to 1.21.2 shim registered the helper, so newer mods are untouched.
        if (syntheticClasses.containsKey(
                com.retromod.shim.common.LegacyBlockApiSynthetic.INTERNAL)) {
            repaired = com.retromod.shim.common.LegacyBlockApiAdapter.apply(repaired);
        }
        // Only when the 1.19.4 shim registered the armor base, so newer mods are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyArmorBridge.ARMOR_ITEM_BASE)) {
            repaired = com.retromod.shim.common.LegacyArmorBridge.adaptMaterial(repaired, this);
        }
        // Only on hosts from 1.20.5, where a pre-1.20.5 tooltip's Level became a context. It runs
        // first so the 1.21.5 adapter below can carry the result to the newest shape.
        if (postProcessFlags.contains(com.retromod.shim.common.LegacyTooltipAdapter.LEVEL_FLAG)) {
            repaired = com.retromod.shim.common.LegacyTooltipAdapter.applyLevelShape(repaired,
                    postProcessFlags.contains(com.retromod.shim.common.LegacyTooltipAdapter.CONTEXT_LEVEL_FLAG));
        }
        // Only when a 1.21.4 to 1.21.5 shim registered the helper, so newer mods are untouched.
        if (syntheticClasses.containsKey(
                com.retromod.shim.common.LegacyTooltipSynthetic.INTERNAL)) {
            repaired = com.retromod.shim.common.LegacyTooltipAdapter.apply(repaired);
        }
        // Only where the 26.1 EitherHolder stand-in is registered, so older hosts are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyEitherHolderBridge.STAND_IN)) {
            repaired = com.retromod.shim.common.LegacyEitherHolderBridge.adaptComponentCalls(repaired);
        }
        // Only where the 1.20.5 KeyDispatchDataCodec helper is registered, so older hosts are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyKeyDispatchCodecBridge.HELPER)) {
            repaired = com.retromod.shim.common.LegacyKeyDispatchCodecBridge.adaptRegistrations(repaired);
        }
        // Only on hosts from 26.1, where the world gen options moved from the level data to the server.
        if (postProcessFlags.contains(com.retromod.shim.common.LegacyWorldGenOptionsAdapter.FLAG)) {
            repaired = com.retromod.shim.common.LegacyWorldGenOptionsAdapter.apply(repaired);
        }
        // Only where the 26.3 density function rebuild is registered: the axes an old function reads itself.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyDensityFunctionAxesAdapter.TYPE)) {
            repaired = com.retromod.shim.common.LegacyDensityFunctionAxesAdapter.apply(repaired);
        }
        // Only where the 26.3 surface rule rebuild registered its helper: old reads of a biome condition's keys.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyBiomeConditionReadAdapter.HELPER)) {
            repaired = com.retromod.shim.common.LegacyBiomeConditionReadAdapter.apply(repaired);
        }
        // Only where the 26.3 loot builder bridge registered its helper, so older hosts are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyLootBuilderBridge.HELPER)) {
            repaired = com.retromod.shim.common.LegacyLootBuilderBridge.adaptNestedTableReferences(repaired);
        }
        // Only where the key polyfill is registered; the adapter itself checks for a 26.3 host.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyKeyCodeAdapter.POLY)) {
            repaired = com.retromod.shim.common.LegacyKeyCodeAdapter.apply(repaired);
        }
        // Only where the 26.1 particle bridge registered its polyfill, so older hosts are untouched.
        if (syntheticClasses.containsKey(
                com.retromod.shim.common.LegacyParticleRenderTypeAdapter.POLY)) {
            repaired = com.retromod.shim.common.LegacyParticleRenderTypeAdapter.apply(repaired);
        }
        // Only where the Forge capability bridge is registered, which is the Forge-on-NeoForge path.
        if (com.retromod.shim.forge.capability.LegacyCapabilitySynthetics.isRegistered(this)) {
            repaired = com.retromod.shim.forge.capability.LegacyCapabilityCallAdapter.apply(repaired);
            repaired = com.retromod.shim.forge.LegacyEventCancelAdapter.apply(repaired);
            repaired = com.retromod.shim.forge.LegacyLivingTickAdapter.apply(repaired);
            repaired = com.retromod.shim.forge.LegacyLazyLambdaAdapter.apply(repaired);
        }
        // Only where the Forge event API shim embedded the result stand-in, which is NeoForge only.
        if (syntheticClasses.containsKey(com.retromod.shim.forge.LegacyEventResultAdapter.RESULT)) {
            repaired = com.retromod.shim.forge.LegacyEventResultAdapter.apply(repaired);
        }
        // Only where the Forge custom registry bridge is registered, which is the NeoForge 1.21+ path.
        if (com.retromod.shim.forge.LegacyCustomRegistryBridge.isRegistered(this)) {
            repaired = com.retromod.shim.forge.LegacyCustomRegistryBridge.retargetRegistryCasts(repaired);
        }
        // Only where the 26.1 recipe serializer bridge is registered, so older hosts are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyRecipeSerializerShim.BRIDGE)) {
            repaired = com.retromod.shim.common.LegacyRecipeSubclassAdapter.apply(repaired);
            repaired = com.retromod.shim.common.LegacyRecipeSerializerFlowAdapter.apply(repaired);
        }
        // Matches Mojang member names, so it only acts after the SRG or intermediary remap.
        repaired = com.retromod.shim.common.LegacySynchedEntityDataMixinRepair.apply(repaired);
        // GUI override repair matches Mojang descriptors and proven post-remap base classes.
        repaired = com.retromod.shim.common.LegacyGuiOverrideAdapter.apply(repaired);
        // Only where the 26.x GUI bridge is registered: old pose() and text-selection calls, then old widget overrides.
        if (postProcessFlags.contains(com.retromod.shim.common.LegacyGuiExtractBridge.FLAG)) {
            repaired = com.retromod.shim.common.LegacyGuiCallAdapter.apply(repaired);
            repaired = com.retromod.shim.common.LegacyWidgetOverrideAdapter.apply(repaired);
        }
        // Only where the vanilla member bridge found the host reshaped fall damage or left hurtServer abstract.
        boolean fallFlag = postProcessFlags.contains(com.retromod.shim.common.LegacyEntityOverrideAdapter.FALL_FLAG);
        boolean hurtFlag = postProcessFlags.contains(com.retromod.shim.common.LegacyEntityOverrideAdapter.HURT_FLAG);
        if (fallFlag || hurtFlag) {
            repaired = com.retromod.shim.common.LegacyEntityOverrideAdapter.apply(repaired, fallFlag, hurtFlag, modSourceVersion);
        }
        // Only where the GeckoLib 4 bridge registered its textures (1.21.11+): GeckoLib 4's own texture, buffer and query code.
        if (postProcessFlags.contains(com.retromod.shim.api.common.LegacyGeckoLib4Bridge.FLAG)) {
            repaired = com.retromod.shim.api.common.LegacyGeckoLib4Adapter.apply(repaired, modSourceVersion,
                    postProcessFlags.contains(com.retromod.shim.api.common.LegacyGeckoLib4Bridge.OUTLINES_GONE_FLAG));
        }
        // Draws where its helper is registered (26.1+ clients), and otherwise only keeps the renderer loadable.
        repaired = com.retromod.shim.common.LegacyEntityRendererAdapter.apply(repaired,
                syntheticClasses.containsKey(com.retromod.shim.common.LegacyEntityRendererAdapter.HELPER), modSourceVersion);
        // Only where the mob loot bridge registered the host's shape (1.20.5+), and only for mods built before 1.20.5.
        repaired = com.retromod.shim.common.LegacyMobLootAdapter.apply(repaired, postProcessFlags,
                modSourceVersion == null ? null : getModBuildVersion());
        // Only where the rebuilt vertex calls are registered (1.21+), so the interface it adds to an old consumer can embed.
        repaired = com.retromod.shim.common.LegacyVertexConsumerAdapter.apply(repaired,
                syntheticClasses.containsKey(com.retromod.shim.common.LegacyVertexApiBridge.HELPER),
                syntheticClasses.containsKey(com.retromod.shim.common.LegacyVertexApiBridge.INTERMEDIARY_HELPER));
        // Only where its helper is registered (26.1+), so the render state and submit it adds can embed it.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyBlockEntityRendererAdapter.HELPER)) {
            repaired = com.retromod.shim.common.LegacyBlockEntityRendererAdapter.apply(repaired, modSourceVersion);
        }
        // Only where the layer bridge registered its helper (26.1+), so the submit it adds to an old layer can embed it.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyPlayerLayersBridge.LAYERS)) {
            repaired = com.retromod.shim.common.LegacyRenderLayerAdapter.apply(repaired, modSourceVersion);
        }
        repaired = com.retromod.shim.common.LegacyParticleProviderAdapter.apply(repaired);
        // Only on hosts from 26.3, whose pack suppliers open metadata and resource streams instead of single packs.
        if (postProcessFlags.contains(com.retromod.shim.common.LegacyPackSupplierAdapter.FLAG)) {
            repaired = com.retromod.shim.common.LegacyPackSupplierAdapter.apply(repaired, limitVersion());
        }
        // Only on hosts from 26.3, where an old pack's base became the metadata-only AbstractPackMetadataResources.
        if (postProcessFlags.contains(com.retromod.shim.common.LegacyPackResourcesBaseAdapter.FLAG)) {
            repaired = com.retromod.shim.common.LegacyPackResourcesBaseAdapter.apply(repaired, limitVersion());
        }
        // Only where its texture helper is registered (26.1+), so the injected call can embed.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyLivingRendererAdapter.TEXTURES)) {
            repaired = com.retromod.shim.common.LegacyLivingRendererAdapter.apply(repaired);
        }
        // Only moves the host made, so a path that still exists on an older host is untouched.
        repaired = com.retromod.resources.VanillaTexturePathConstants.apply(
                repaired, RetromodVersion.TARGET_MC_VERSION);
        repaired = com.retromod.shim.common.FinalOverrideHookAdapter.apply(repaired);
        // After the hook adapter, so a living entity's getDimensions is already getDefaultDimensions.
        repaired = com.retromod.shim.common.FinalEyeHeightAdapter.apply(repaired);
        repaired = com.retromod.shim.common.FinalBaseConstructionAdapter.apply(repaired);
        repaired = com.retromod.shim.common.LegacyBreathingOverrideAdapter.apply(repaired);
        // Matches intermediary names too, since pre-26.1 Fabric mods never reach the Mojang remap.
        repaired = com.retromod.shim.common.LegacyBlockUseAdapter.apply(repaired);
        // Same reason, and it reads the host's getValues() shape itself, so older hosts are untouched.
        repaired = com.retromod.shim.common.LegacyStateValuesAdapter.apply(repaired,
                helper -> SyntheticEmbedder.registerClassResource(this, helper, RetromodTransformer.class));
        repaired = com.retromod.shim.common.LegacyEquipmentBridge.apply(repaired);
        if (com.retromod.shim.common.LegacyEquipmentBridge.isRegistered(this)) {
            repaired = com.retromod.shim.common.LegacyEquipmentBridge.retargetToolAttributes(repaired);
        }
        repaired = com.retromod.shim.common.LegacyFeatureBridge.apply(repaired);
        // Once per class, after the passes settle, so a key 26.3 reused is renamed exactly once.
        repaired = com.retromod.shim.common.LegacyRegistryKeyRenames263.apply(repaired);
        repaired = com.retromod.shim.forge.LegacySpawnDataAdapter.apply(repaired);
        // Only where a 1.20.5 or 1.21.2 bridge found the host changed: old contexts and providers get the new members.
        if (com.retromod.shim.common.LegacyRegistryProviderAdapter.enabled(postProcessFlags::contains)) {
            repaired = com.retromod.shim.common.LegacyRegistryProviderAdapter.apply(repaired,
                    postProcessFlags::contains, limitVersion());
        }
        // NeoForge refuses a listener on an abstract event, so an old one moves to the events below it.
        if (postProcessFlags.contains(com.retromod.shim.forge.NeoForgeAbstractEventListeners.FLAG)) {
            repaired = com.retromod.shim.forge.NeoForgeAbstractEventListeners.apply(repaired);
        }
        // Forge 26.2 listener passes run in this order: call-site rewrites first, then passes that
        // rewrite a listener and still read its raw old annotation (receiveCanceled, the old
        // priority), then the normalizer last. ForgeModTransformer and RetromodCli run
        // stripLenientAutoSubscriber, NeoForgeAutoSubscriberGuard and LegacyTradeBridge.watchHandlers
        // after transformClass, so those always see normalized annotations.
        // Forge 26.2 events keep no cancel flag, so old cancel calls on them go to Retromod's helper.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyCancelBridge.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyCancelBridge.apply(repaired, modSourceVersion);
        }
        // Some Forge 26.2 events lost the result Forge 1.20.1 gave every event, so old result calls go to a helper.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyForgeEventResultBridge.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyForgeEventResultBridge.apply(repaired);
        }
        // Forge 26.2 has no ForgeGui, so old casts of Minecraft.gui to it go to the rebuilt one's helpers, and an old
        // LayeredDraw layer, now a ForgeLayer, gets the extract method that calls its render.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyGuiOverlayBridge.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyGuiOverlayBridge.apply(repaired, modSourceVersion);
        }
        // Forge 26.2 fires Pre and Post tick events; an old tick listener gets one of each.
        repaired = com.retromod.shim.forge.LegacyTickEventBridge.apply(repaired);
        // Forge 26.2 fires one attach event per holder kind; an old attach listener gets one per kind its type names.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyAttachListenerSplit.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyAttachListenerSplit.apply(repaired);
        }
        // An old listener that can cancel gets a boolean twin, whose answer EventBus 7 reads as the cancel. It reads
        // receiveCanceled, so it runs before the normalizer, and it looks for the cancel calls rewritten above.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyCancelBridge.FLAG)) {
            repaired = com.retromod.shim.forge.CancellableListenerSiblings.apply(repaired, modSourceVersion);
        }
        // The last listener pass: EventBus 7 reads a byte priority and has no receiveCanceled.
        if (postProcessFlags.contains(com.retromod.shim.forge.ForgeSubscribeAnnotations.FLAG)) {
            repaired = com.retromod.shim.forge.ForgeSubscribeAnnotations.apply(repaired);
        }
        // Only when a 1.20.5+ host registered the provider helper, so newer mods are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.common.LegacyRegistryNbtBridge.HELPER)) {
            // Parents are read under the names the class pass gives them, from the jar or the libraries it builds on,
            // so a super call to a mod parent that declares the old method keeps reaching it.
            repaired = com.retromod.shim.common.LegacyRegistryNbtBridge.apply(repaired,
                    RetromodTransformer::currentJarClassInheritsFrom, RetromodTransformer::readJarOrLibraryClassRemapped,
                    syntheticClasses.containsKey(com.retromod.shim.common.LegacyRegistryNbtBridge.MEMBERS), getModBuildVersion());
        }
        // Only where the 1.20.5 gameplay bridge registered its helper: old arrow, effect and death loot overrides.
        if (syntheticClasses.containsKey(com.retromod.shim.common.Legacy1205GameplayBridge.HELPER)) {
            repaired = com.retromod.shim.common.Legacy1205OverrideAdapter.apply(repaired,
                    RetromodTransformer::currentJarClassInheritsFrom, RetromodTransformer::readJarOrLibraryClassRemapped,
                    getModBuildVersion());
        }
        // Only on a Forge host whose capability, handler and entity saves take registries, for mods built before.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyNbtSerializableBridge.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyNbtSerializableBridge.apply(repaired, modSourceVersion,
                    currentJarClassBytesProvider(), RetromodTransformer::currentJarClassInheritsFrom);
        }
        // Only on a Forge host that asks items for getCapabilityProvider; an old initCapabilities answers it.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyItemCapabilityHookAdapter.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyItemCapabilityHookAdapter.apply(repaired,
                    currentJarClassBytesProvider());
        }
        // Only on a Forge host that asks an ingredient for serializer(); an old getSerializer answers it.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyIngredientSerializerBridge.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyIngredientSerializerBridge.apply(repaired);
        }
        // Only when a 1.21.6+ host registered the ValueIO save helper, so older hosts are untouched.
        if (com.retromod.shim.common.LegacyValueIoOverrideAdapter.isRegistered(syntheticClasses::containsKey)) {
            repaired = com.retromod.shim.common.LegacyValueIoOverrideAdapter.apply(repaired,
                    syntheticClasses::containsKey, modSourceVersion);
        }
        // After the ValueIO overrides exist: on Forge 26.2 a mod's first block entity class saves and loads ForgeData.
        if (postProcessFlags.contains(com.retromod.shim.forge.LegacyBlockEntityDataBridge.FLAG)) {
            repaired = com.retromod.shim.forge.LegacyBlockEntityDataBridge.apply(repaired,
                    RetromodTransformer::currentJarClassInheritsFrom);
        }
        // Only when a NeoForge 1.21 host registered the attribute helper, so newer mods are untouched.
        if (com.retromod.shim.common.LegacyItemAttributeOverrideAdapter.isRegistered(syntheticClasses::containsKey)) {
            repaired = com.retromod.shim.common.LegacyItemAttributeOverrideAdapter.apply(repaired);
        }
        // Only when a 1.20.5+ host registered the builder helper, so newer mods are untouched.
        if (com.retromod.shim.common.LegacySynchedDataBridge.isRegistered(syntheticClasses::containsKey)) {
            repaired = com.retromod.shim.common.LegacySynchedDataBridge.apply(repaired);
        }
        // Pairs with the accessor removal in MixinCompatibilityTransformer.applyPostRemapRepairs.
        repaired = com.retromod.mixin.MixinAccessorFieldMigration.rewriteCallSites(
                repaired, RetromodVersion.TARGET_MC_VERSION, currentJarClassBytesProvider(),
                bridge -> SyntheticEmbedder.registerClassResource(this, bridge, RetromodTransformer.class));
        // Pairs with the surface rule accessor retarget in MixinMaterialRuleRepair: callers follow it to the holder.
        repaired = com.retromod.mixin.MixinRuleHolderAccessors.rewriteCalls(repaired, currentJarClassBytesProvider());
        // Only when the Forge 1.19 bridge registered the Random view, so newer mods are untouched.
        if (syntheticClasses.containsKey(com.retromod.shim.forge.LegacyRandomSourceAdapter.VIEW)) {
            repaired = com.retromod.shim.forge.LegacyRandomSourceAdapter.apply(
                    repaired, this, currentJarClassBytesProvider());
        }
        // 1.20 made Entity.level, maxUpStep, and onGround private; older mods use them directly.
        if (syntheticClasses.containsKey(com.retromod.shim.forge.LegacyRandomSourceAdapter.VIEW)
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20") >= 0) {
            repaired = com.retromod.shim.forge.LegacyEntityPrivateFieldAdapter.apply(
                    repaired, this, currentJarClassBytesProvider());
        }
        // Only when the 1.20 shim registered the entity helpers, so newer mods are untouched.
        if (com.retromod.shim.forge.LegacyEntityApi120Adapter.isRegistered(this)) {
            repaired = com.retromod.shim.forge.LegacyEntityApi120Adapter.apply(
                    repaired, this, currentJarClassBytesProvider());
        }
        // Only where NeoForge answers crafting remainders from an item instance.
        if (com.retromod.shim.neoforge.LegacyCraftingRemainderBridge.isRegistered(syntheticClasses::containsKey)) {
            repaired = com.retromod.shim.neoforge.LegacyCraftingRemainderBridge.apply(repaired);
        }
        // Only when a host from 1.20.5 on registered the recipe helpers.
        if (com.retromod.shim.common.LegacyRecipeApiBridge.isRegistered(syntheticClasses::containsKey)) {
            repaired = com.retromod.shim.common.LegacyRecipeApiBridge.apply(repaired);
        }
        if (com.retromod.shim.common.LegacyCraftingRecipeBridge.adaptsAssemble(this)) {
            repaired = com.retromod.shim.common.LegacyCraftingRecipeBridge.apply(repaired, this);
        }
        if (com.retromod.shim.common.LegacyVillagerBridge.isRegistered(this)) {
            repaired = com.retromod.shim.common.LegacyVillagerBridge.apply(repaired);
        }
        // Only when the 1.19.3 tab bridge is registered; the adapter also skips classes that
        // never call the bridged Item.Properties.tab(...).
        if (syntheticClasses.containsKey(com.retromod.shim.forge.CreativeModeTabBridge.CONTENTS)) {
            repaired = com.retromod.shim.forge.LegacyCreativeTabClaimAdapter.apply(repaired);
        }
        // Only when the 1.19.4 shim registered the damage constants, so newer mods are untouched.
        if (com.retromod.shim.common.LegacyDamageConstantBridge.isRegistered(this)) {
            repaired = com.retromod.shim.common.LegacyDamageConstantBridge.apply(repaired);
        }
        // 1.20 made Entity.positionRider(Entity) final; older hosts still expect the override.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20") >= 0) {
            repaired = com.retromod.shim.common.LegacyPositionRiderAdapter.apply(repaired, this);
        }
        // After every pass that renames or moves an override, so it sees the names the class keeps. Only
        // for a class of the jar in scope, whose access files decide which host finals stay final.
        Set<String> openedFinals = currentJarOpenedFinals();
        if (openedFinals != null && inCurrentJar(repaired)) {
            repaired = com.retromod.shim.common.HostFinalOverrideAdapter.apply(repaired, this::finalOverrideShape,
                    openedFinals);
        }
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
            Function<String, byte[]> classBytesProvider = frameHierarchy();
            // mirror singleTransformPass's remapper-presence condition (line ~930)
            boolean hasClassRemaps = cachedRemapper != null
                    || !classRedirects.isEmpty()
                    || !intermediaryMethodNames.isEmpty() || !intermediaryFieldNames.isEmpty()
                    || !mojangMethodRenames.isEmpty() || mojangRenames != null;
            SafeClassWriter writer = hasClassRemaps
                    ? new SafeClassWriter(ClassWriter.COMPUTE_FRAMES, classBytesProvider)
                    : new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES, classBytesProvider);
            // NO RetromodClassVisitor here: it consults the live redirect maps, which would make
            // the baseline equal pass 1 for changed classes too (false "unchanged"). When nothing
            // matches, RetromodClassVisitor is pure delegation, so leaving it out of the identity
            // chain keeps the serialization byte-comparable for untouched classes; any visitor
            // asymmetry only produces a false "changed", which falls back to today's behavior.
            ClassVisitor visitor = writer.namingFramedMethods();
            if (hasClassRemaps) {
                visitor = new ClassRemapper(visitor, new Remapper() { /* identity */ });
            }
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            byte[] result = writer.toByteArray();
            // The pass keeps javac's frames where a join was guessed, so the baseline does too.
            if (!writer.guessedTypes().isEmpty() && hasStackMapFrames(reader)) {
                org.objectweb.asm.tree.ClassNode original = new org.objectweb.asm.tree.ClassNode();
                reader.accept(hasClassRemaps ? new ClassRemapper(original, new Remapper() { /* identity */ })
                        : original, 0);
                result = spliceOriginalFrames(result, writer.guessedJoins(), writer.guessedTypes(),
                        original, Set.of(), className);
            }
            if (hasClassRemaps) {
                result = deduplicateMethods(result, className);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /** The name remapper every pass applies first, or null when nothing remaps names. */
    private Remapper globalRemapper() {
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
            boolean hasSrgNames = !srgMethodNames.isEmpty() || !srgFieldNames.isEmpty()
                    || !targetSrgMethodNames.isEmpty() || !targetSrgFieldNames.isEmpty();
            if (!classRedirects.isEmpty() || hasIntermediaryNames || hasSrgNames
                    || !mojangMethodRenames.isEmpty() || !functionalInterfaceRenames.isEmpty()
                    || mojangRenames != null || !ownOverrideRenames.isEmpty()) {
                synchronized (this) {
                    classRemapper = cachedRemapper; // re-check under lock
                    if (classRemapper == null) {
                        classRemapper = new Remapper() {
                            @Override
                            public String map(String internalName) {
                                return redirectedClass(internalName);
                            }

                            @Override
                            public String mapMethodName(String owner, String name, String descriptor) {
                                // An old mod's own override of a host method whose meaning changed keeps its old name.
                                String ownOverride = ownOverrideName(owner, name, descriptor);
                                if (ownOverride != null) return ownOverride;
                                String mappedName = name;
                                // Remap intermediary method names (method_XXXX / comp_XXXX → Mojang name)
                                if (!intermediaryMethodNames.isEmpty()
                                        && (name.startsWith("method_") || name.startsWith("comp_"))) {
                                    String mojang = null;
                                    // Ambiguous short-name: resolve by descriptor instead of the
                                    // last-writer-wins flat entry. Fall through to the flat map only
                                    // if this descriptor variant wasn't harvested.
                                    if (ambiguousIntermediaryMethodNames.contains(name)) {
                                        mojang = intermediaryMethodNamesByDesc.get(
                                                memberDescKey(name, descriptor));
                                    }
                                    if (mojang == null) mojang = intermediaryMethodNames.get(name);
                                    if (mojang != null) mappedName = mojang;
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
                                    String mojang = srgMethodName(name);
                                    if (mojang != null) mappedName = mojang;
                                }
                                String srgAccessor = srgRecordAccessorName(name, descriptor);
                                if (srgAccessor != null) mappedName = srgAccessor;
                                // A member a Mojang-named mod knows by the name of the version it was
                                // built for, on its declaring class, a subclass, or an override.
                                String renamedOnHost = mojangMemberRename(false, owner, mappedName, descriptor);
                                if (renamedOnHost != null) mappedName = renamedOnHost;
                                // Vanilla Mojang->Mojang rename (owner-scoped), e.g.
                                // ResourceKey.location -> identifier on 26.x. Routed through the
                                // ClassRemapper so it also rewrites method references
                                // (ResourceKey::location), which methodRedirects can't reach.
                                if (!mojangMethodRenames.isEmpty()) {
                                    String renamed = mojangMethodRenames.get(owner + "#" + mappedName);
                                    if (renamed != null) mappedName = renamed;
                                }
                                if (!targetSrgMethodNames.isEmpty()) {
                                    String desc = mapMethodDesc(descriptor);
                                    String target = targetSrgMethodNames.get(
                                            com.retromod.mapping.TargetSrgMapper.memberKey(
                                                    map(owner), mappedName, desc));
                                    if (target == null && !mappedName.startsWith("<")) {
                                        target = inheritedTargetSrgName(
                                                targetSrgMethodNames, owner, mappedName, desc);
                                    }
                                    if (target != null) return target;
                                }
                                if (!targetSrgFieldNames.isEmpty()) {
                                    String accessor = recordAccessorTargetSrgName(
                                            map(owner), mappedName, mapMethodDesc(descriptor));
                                    if (accessor != null) return accessor;
                                }
                                if (!mappedName.equals(name)
                                        && isLiveTargetSrgMethod(map(owner), name, mapMethodDesc(descriptor))) {
                                    return name;
                                }
                                return mappedName;
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
                                    String mojang = srgMethodName(name);
                                    if (mojang != null) return targetSrgSamName(descriptor, mojang);
                                }
                                // The descriptor returns the functional interface being built.
                                int close = descriptor.lastIndexOf(')');
                                if (!functionalInterfaceRenames.isEmpty() && close >= 0
                                        && descriptor.startsWith("L", close + 1)) {
                                    String iface = descriptor.substring(close + 2,
                                            descriptor.length() - 1);
                                    String renamed = functionalInterfaceRenames.get(iface + "#" + name);
                                    if (renamed == null) {
                                        renamed = functionalInterfaceRenames.get(
                                                map(iface) + "#" + name);
                                    }
                                    if (renamed != null) return renamed;
                                }
                                // A lambda for a Minecraft interface whose method was renamed.
                                if (mojangRenames != null && close >= 0 && descriptor.startsWith("L", close + 1)) {
                                    String renamed = mojangLambdaRename(
                                            descriptor.substring(close + 2, descriptor.length() - 1), name);
                                    if (renamed != null) return renamed;
                                }
                                return name;
                            }

                            public String mapFieldName(String owner, String name, String descriptor) {
                                // A field bridged by its SRG id keeps that id until the field pass
                                // replaces the access with the bridge call.
                                if (!uniqueFieldStaticBridges.isEmpty()
                                        && uniqueFieldStaticBridges.containsKey(name + ':' + descriptor)) {
                                    return name;
                                }
                                String mappedName = name;
                                // Remap intermediary field names (field_XXXX → Mojang name)
                                if (!intermediaryFieldNames.isEmpty() && isIntermediaryFieldName(name)) {
                                    String mojang = null;
                                    // Ambiguous short-name: resolve by descriptor first (see the
                                    // method case above), else fall through to the flat map.
                                    if (ambiguousIntermediaryFieldNames.contains(name)) {
                                        mojang = intermediaryFieldNamesByDesc.get(
                                                memberDescKey(name, descriptor));
                                    }
                                    if (mojang == null) mojang = intermediaryFieldNames.get(name);
                                    if (mojang != null) mappedName = mojang;
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
                                    String mojang = srgFieldName(name);
                                    if (mojang != null) mappedName = mojang;
                                }
                                String renamedOnHost = mojangMemberRename(true, owner, mappedName, descriptor);
                                if (renamedOnHost != null) mappedName = renamedOnHost;
                                if (!targetSrgFieldNames.isEmpty()) {
                                    String desc = mapDesc(descriptor);
                                    String target = targetSrgFieldNames.get(
                                            com.retromod.mapping.TargetSrgMapper.memberKey(
                                                    map(owner), mappedName, desc));
                                    if (target == null) {
                                        target = inheritedTargetSrgName(
                                                targetSrgFieldNames, owner, mappedName, desc);
                                    }
                                    if (target != null) return target;
                                }
                                if (!mappedName.equals(name)
                                        && isLiveTargetSrgField(map(owner), name, mapDesc(descriptor))) {
                                    return name;
                                }
                                return mappedName;
                            }
                        };
                        cachedRemapper = classRemapper;
                    }
                }
            }
        }
        return classRemapper;
    }

    /** Runs one full visitor-chain pass, which {@link #transformClass} repeats until the output settles. */
    private byte[] singleTransformPass(byte[] originalBytes, String className,
            boolean applyConstructorRedirects) {
        // originalBytes is the original input or the previous pass's output.
        ClassReader reader = new ClassReader(originalBytes);

        Remapper classRemapper = globalRemapper();

        // The global remapper remains cacheable. A small per-class wrapper suppresses only
        // references that stay within a registered provider package.
        classRemapper = providerPreservingRemapper(classRemapper, reader.getClassName());
        classRemapper = renamedTargetMixinRemapper(classRemapper, reader);

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
                    hasApplicableConstructorRedirect, false);
            if (preserved != null) {
                return preserved;
            }
        }

        // Before Java 6 the JVM infers every type itself, so such a class needs no frames, and its
        // JSR and RET instructions cannot be framed at all (Jetty 6, bundled in Replay Mod).
        if (reader.readUnsignedShort(6) < Opcodes.V1_6) {
            byte[] unframed = transformPreservingFrames(
                    reader, className, classRemapper, hasClassRemaps,
                    hasApplicableConstructorRedirect, true);
            if (unframed != null) {
                return unframed;
            }
        }

        Function<String, byte[]> classBytesProvider = frameHierarchy();
        SafeClassWriter writer = hasClassRemaps
            ? new SafeClassWriter(ClassWriter.COMPUTE_FRAMES, classBytesProvider,
                    classRemapper::mapType, this::targetSuperclass)
            : new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES, classBytesProvider);

        // The chain is built from the writer outward:
        // Reader -> ClassRemapper -> RetromodClassVisitor -> ClassWriter.
        ClassVisitor visitor = writer.namingFramedMethods();

        // RetromodClassVisitor must be inner (closer to writer) so it sees Mojang names after
        // ClassRemapper translates intermediary names.
        visitor = new RetromodClassVisitor(Opcodes.ASM9, visitor, reader.getSuperName());

        // Only add remapping visitor if we have class redirects
        if (classRemapper != null) {
            visitor = new ClassRemapper(visitor, classRemapper);
        }

        // Constructor->factory rewriting runs OUTERMOST (upstream of the remapper) so it
        // sees pre-remap owner names; see CtorRedirectPrePass for why that matters.
        if (hasApplicableConstructorRedirect) {
            visitor = new CtorRedirectPrePass(
                    Opcodes.ASM9, visitor, classRemapper, reader.getClassName());
        }

        try {
            // Use EXPAND_FRAMES to properly feed frame data into COMPUTE_FRAMES
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            byte[] result = writer.toByteArray();
            // A join of classes the hierarchy could not read was guessed. javac's frames answer it
            // exactly, so a method whose code kept its shape gets them back.
            if (!writer.guessedTypes().isEmpty() && hasStackMapFrames(reader)) {
                result = keepOriginalFrames(result, writer.guessedJoins(), writer.guessedTypes(),
                        reader, className, classRemapper, hasApplicableConstructorRedirect);
            }
            // Deduplicate methods that may have identical name+descriptor after class remapping.
            // This happens when two intermediary class names (e.g., class_316 and class_4064)
            // both map to the same Mojang name, causing return types in descriptors to collapse.
            if (hasClassRemaps) {
                result = deduplicateMethods(result, className);
            }
            return result;
        } catch (Exception e) {
            // COMPUTE_FRAMES fails most often when a mod class references types off the classpath,
            // so retry with COMPUTE_MAXS. That keeps the original StackMapTable entries, and a rewrite
            // that moved or removed instructions then ships stale frames, so the failure is logged.
            LOGGER.warn("Frame recomputation failed for {}; retrying with the original frames",
                    className, e);
            try {
                ClassWriter fallbackWriter = hasClassRemaps
                    ? new SafeClassWriter(ClassWriter.COMPUTE_MAXS)
                    : new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                ClassVisitor fallbackVisitor = fallbackWriter;
                RetromodClassVisitor bodyVisitor =
                        new RetromodClassVisitor(Opcodes.ASM9, fallbackVisitor, reader.getSuperName());
                fallbackVisitor = bodyVisitor;
                if (classRemapper != null) {
                    fallbackVisitor = new ClassRemapper(fallbackVisitor, classRemapper);
                }
                if (hasApplicableConstructorRedirect) {
                    fallbackVisitor = new CtorRedirectPrePass(Opcodes.ASM9, fallbackVisitor,
                            classRemapper, reader.getClassName());
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
                    // The rewritten code is fine; only its preserved frames are stale. Drop them
                    // and compute fresh ones from the rewritten bytes before giving up on it.
                    byte[] reframed = recomputeFrames(fallbackWriter.toByteArray(), className);
                    if (reframed != null) {
                        return hasClassRemaps ? deduplicateMethods(reframed, className) : reframed;
                    }
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
                // Ship this class untransformed, so the mod still loads.
                LOGGER.warn("Transform failed for {}, returning original: {}", className, e2.getMessage());
                return originalBytes;
            }
        }
    }

    /**
     * Frames computed from scratch for already-rewritten bytes, or null when that fails too. The
     * warning names the method that cannot be framed, so a report points at it.
     */
    private static byte[] recomputeFrames(byte[] rewritten, String className) {
        try {
            ClassReader reader = new ClassReader(rewritten);
            ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
            reader.accept(writer, ClassReader.SKIP_FRAMES);
            return writer.toByteArray();
        } catch (RuntimeException stillBroken) {
            // -Dretromod.dumpFrameFailures=<dir> keeps the rewritten class for offline analysis.
            String dumpDir = System.getProperty("retromod.dumpFrameFailures");
            if (dumpDir != null) {
                try {
                    java.nio.file.Path dump = java.nio.file.Path.of(dumpDir, className.replace('/', '_') + ".class");
                    java.nio.file.Files.createDirectories(dump.getParent());
                    java.nio.file.Files.write(dump, rewritten);
                } catch (java.io.IOException | RuntimeException ignored) {
                    // Diagnostics only.
                }
            }
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(rewritten).accept(node, ClassReader.SKIP_FRAMES);
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                org.objectweb.asm.tree.ClassNode single = new org.objectweb.asm.tree.ClassNode();
                single.version = node.version;
                single.access = node.access;
                single.name = node.name;
                single.superName = node.superName;
                single.interfaces = node.interfaces;
                single.methods.add(method);
                try {
                    single.accept(new SafeClassWriter(ClassWriter.COMPUTE_FRAMES));
                } catch (RuntimeException broken) {
                    LOGGER.warn("Cannot compute frames for {}.{}{}: {}", className, method.name, method.desc,
                            broken.toString());
                }
            }
            return null;
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
        String caller = reader.getClassName();
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
                        found[0] = matchesConstructorRedirect(caller, owner, methodDesc, remapper);
                    }

                    // A constructor reference such as Foo::new carries the same constructor in a handle.
                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                            Object... arguments) {
                        for (Object argument : arguments) {
                            if (found[0]) return;
                            if (argument instanceof Handle h && h.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
                                found[0] = matchesConstructorRedirect(caller, h.getOwner(), h.getDesc(), remapper);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /** Whether a constructor redirect is keyed on this call in any mix of pre-remap and post-remap names. */
    private boolean matchesConstructorRedirect(String caller, String owner, String methodDesc, Remapper remapper) {
        if (preservesProviderReference(caller, owner)) return false;
        String resolvedOwner = remapper == null ? owner : remapper.mapType(owner);
        String resolvedDesc;
        try {
            resolvedDesc = remapper == null ? methodDesc : remapper.mapMethodDesc(methodDesc);
        } catch (IllegalArgumentException malformed) {
            // A hostile jar's descriptor ASM cannot parse matches no redirect.
            return false;
        }
        return constructorRedirects.containsKey(new ConstructorKey(resolvedOwner, resolvedDesc))
                || (!resolvedDesc.equals(methodDesc) && constructorRedirects.containsKey(
                        new ConstructorKey(resolvedOwner, methodDesc)))
                || (!resolvedOwner.equals(owner) && constructorRedirects.containsKey(
                        new ConstructorKey(owner, methodDesc)))
                || (!resolvedOwner.equals(owner) && !resolvedDesc.equals(methodDesc)
                        && constructorRedirects.containsKey(new ConstructorKey(owner, resolvedDesc)));
    }

    /**
     * The pass with the class's own frames kept, or null when a rewrite moved the instructions they
     * describe. {@code framesUnused} is for a class too old for frames, where that never matters.
     */
    private byte[] transformPreservingFrames(ClassReader reader, String className,
            Remapper classRemapper, boolean hasClassRemaps,
            boolean applyConstructorRedirects, boolean framesUnused) {
        try {
            ClassWriter writer = hasClassRemaps
                    ? new SafeClassWriter(ClassWriter.COMPUTE_MAXS)
                    : new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = writer;
            RetromodClassVisitor bodyVisitor = new RetromodClassVisitor(Opcodes.ASM9, visitor, reader.getSuperName());
            visitor = bodyVisitor;
            if (classRemapper != null) {
                visitor = new ClassRemapper(visitor, classRemapper);
            }
            if (applyConstructorRedirects && !constructorRedirects.isEmpty()) {
                visitor = new CtorRedirectPrePass(
                        Opcodes.ASM9, visitor, classRemapper, reader.getClassName());
            }

            frameInvalidatingRewrite.set(Boolean.FALSE);
            reader.accept(visitor, 0);
            if (!framesUnused && bodyVisitor.structurallyChangedFrames()) {
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
     * {@code computed} with javac's frames back on each method that asked for a guessed join or names
     * a guessed type, when its rewrite kept every instruction where it was. Returns {@code computed}
     * itself when no method qualifies.
     */
    private byte[] keepOriginalFrames(byte[] computed, Set<String> guessedMethods,
            Set<String> guessedTypes, ClassReader reader, String className,
            Remapper classRemapper, boolean applyConstructorRedirects) {
        try {
            // The same chain again, into a tree, with the original frames passed through the remapper.
            org.objectweb.asm.tree.ClassNode original = new org.objectweb.asm.tree.ClassNode();
            Set<String> reshaped = new HashSet<>();
            ClassVisitor visitor = new ReshapedMethodRecorder(original, reshaped);
            visitor = new RetromodClassVisitor(Opcodes.ASM9, visitor);
            if (classRemapper != null) {
                visitor = new ClassRemapper(visitor, classRemapper);
            }
            if (applyConstructorRedirects) {
                visitor = new CtorRedirectPrePass(Opcodes.ASM9, visitor, classRemapper, reader.getClassName());
            }
            frameInvalidatingRewrite.set(Boolean.FALSE);
            reader.accept(visitor, 0);
            return spliceOriginalFrames(computed, guessedMethods, guessedTypes, original, reshaped, className);
        } catch (RuntimeException e) {
            return computed;
        } finally {
            frameInvalidatingRewrite.set(Boolean.FALSE);
        }
    }

    /** {@code computed} with each qualifying method replaced by its counterpart in {@code original}. */
    private byte[] spliceOriginalFrames(byte[] computed, Set<String> guessedMethods, Set<String> guessedTypes,
            org.objectweb.asm.tree.ClassNode original, Set<String> reshaped, String className) {
        try {
            org.objectweb.asm.tree.ClassNode result = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(computed).accept(result, 0);
            Map<String, Deque<org.objectweb.asm.tree.MethodNode>> originals = new HashMap<>();
            for (org.objectweb.asm.tree.MethodNode method : original.methods) {
                originals.computeIfAbsent(method.name + method.desc, key -> new ArrayDeque<>()).add(method);
            }
            int kept = 0;
            for (int i = 0; i < result.methods.size(); i++) {
                org.objectweb.asm.tree.MethodNode method = result.methods.get(i);
                String key = method.name + method.desc;
                Deque<org.objectweb.asm.tree.MethodNode> candidates = originals.get(key);
                org.objectweb.asm.tree.MethodNode same = candidates == null ? null : candidates.poll();
                if (same == null || reshaped.contains(key)) continue;
                if (!guessedMethods.contains(key) && !namesAnyType(method, guessedTypes)) continue;
                // Both passes ran the same rewrites, so a differing count means the bodies differ.
                if (instructionCount(same) != instructionCount(method)) continue;
                // A frame naming a Minecraft class the host lost would fail the whole class at load.
                if (framesNameMissingMinecraftType(same)) continue;
                result.methods.set(i, same);
                kept++;
            }
            if (kept == 0) return computed;

            ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
            result.accept(writer);
            LOGGER.debug(RetromodLog.DETAIL, "Kept the original frames of {} method(s) in {}, because "
                    + "their computed frames joined classes the hierarchy could not read", kept, className);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            return computed;
        }
    }

    /** Whether the method's descriptor, instructions or handlers name one of {@code types}. */
    private static boolean namesAnyType(org.objectweb.asm.tree.MethodNode method, Set<String> types) {
        StringBuilder text = new StringBuilder(method.desc);
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                text.append(' ').append(call.owner).append(call.desc);
            } else if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field) {
                text.append(' ').append(field.owner).append(field.desc);
            } else if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type) {
                text.append(' ').append(type.desc);
            } else if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) {
                text.append(' ').append(dynamic.desc);
            } else if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode constant
                    && constant.cst instanceof Type type) {
                text.append(' ').append(type.getDescriptor());
            } else if (instruction instanceof org.objectweb.asm.tree.MultiANewArrayInsnNode array) {
                text.append(' ').append(array.desc);
            }
        }
        for (org.objectweb.asm.tree.TryCatchBlockNode handler : method.tryCatchBlocks) {
            if (handler.type != null) text.append(' ').append(handler.type);
        }
        String names = text.toString();
        for (String type : types) {
            if (names.contains(type)) return true;
        }
        return false;
    }

    private boolean framesNameMissingMinecraftType(org.objectweb.asm.tree.MethodNode method) {
        Function<String, byte[]> classes = frameHierarchy();
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof org.objectweb.asm.tree.FrameNode frame)) continue;
            List<Object> types = new ArrayList<>();
            if (frame.local != null) types.addAll(frame.local);
            if (frame.stack != null) types.addAll(frame.stack);
            for (Object type : types) {
                if (!(type instanceof String name)) continue;
                String element = name.startsWith("[") ? Type.getType(name).getElementType().getInternalName() : name;
                if (isMinecraftType(element) && !isRuntimeResolvable(element, classes, this::targetSuperclass)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int instructionCount(org.objectweb.asm.tree.MethodNode method) {
        int count = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) count++;
        }
        return count;
    }

    /** Records the methods whose rewrite added, moved or removed instructions, which their frames describe. */
    private final class ReshapedMethodRecorder extends ClassVisitor {
        private final Set<String> reshaped;

        ReshapedMethodRecorder(ClassVisitor next, Set<String> reshaped) {
            super(Opcodes.ASM9, next);
            this.reshaped = reshaped;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            String key = name + descriptor;
            return new MethodVisitor(Opcodes.ASM9,
                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                @Override
                public void visitEnd() {
                    // The rewriters mark the flag while they emit this method's instructions.
                    if (frameInvalidatingRewrite.get()) reshaped.add(key);
                    frameInvalidatingRewrite.set(Boolean.FALSE);
                    super.visitEnd();
                }
            };
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
                    com.retromod.core.verify.LoaderApiRenames.getInstance(),
                    // Read per class, so it follows whichever mod is being transformed.
                    this::keepsOldName);
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

        LOGGER.debug(RetromodLog.DETAIL, "Deduplicating {} duplicate method(s) in {}: {}",
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
        private final Function<String, String> hierarchyNameMapper;
        private final Function<String, String> targetSuperclass;
        // The method whose frames are being computed, and the methods and types of guessed joins.
        // ASM caches each join, so a later method can reuse a guess without asking again.
        private String framingMethod;
        private final Set<String> guessedJoins = new LinkedHashSet<>();
        private final Set<String> guessedTypes = new LinkedHashSet<>();

        public SafeClassWriter(int flags) {
            this(flags, null);
        }

        public SafeClassWriter(ClassReader classReader, int flags) {
            this(classReader, flags, null);
        }

        public SafeClassWriter(int flags, Function<String, byte[]> jarClassBytes) {
            this(flags, jarClassBytes, Function.identity(), null);
        }

        public SafeClassWriter(int flags, Function<String, byte[]> jarClassBytes,
                               Function<String, String> hierarchyNameMapper,
                               Function<String, String> targetSuperclass) {
            super(flags);
            this.jarClassBytes = jarClassBytes;
            this.hierarchyNameMapper = hierarchyNameMapper;
            this.targetSuperclass = targetSuperclass;
        }

        public SafeClassWriter(ClassReader classReader, int flags,
                               Function<String, byte[]> jarClassBytes) {
            super(classReader, flags);
            this.jarClassBytes = jarClassBytes;
            this.hierarchyNameMapper = Function.identity();
            this.targetSuperclass = null;
        }

        /** The methods, as name plus descriptor, that asked for a guessed join. */
        Set<String> guessedJoins() {
            return guessedJoins;
        }

        /** The types of every guessed join, which a later method may have joined from ASM's cache. */
        Set<String> guessedTypes() {
            return guessedTypes;
        }

        /** A visitor in front of this writer that tells it which method's frames it is computing. */
        ClassVisitor namingFramedMethods() {
            SafeClassWriter writer = this;
            return new ClassVisitor(Opcodes.ASM9, this) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    String key = name + descriptor;
                    return new MethodVisitor(Opcodes.ASM9,
                            super.visitMethod(access, name, descriptor, signature, exceptions)) {
                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            // ASM computes a method's frames here, so every join it asks for belongs to this method.
                            writer.framingMethod = key;
                            try {
                                super.visitMaxs(maxStack, maxLocals);
                            } finally {
                                writer.framingMethod = null;
                            }
                        }
                    };
                }
            };
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // A dedicated server's loader refuses a client-only Minecraft type after logging an
            // ERROR, so go straight to the byte path that the refusal would reach.
            if (refusedOnThisSide(type1) || refusedOnThisSide(type2)) {
                return commonSuperWithoutLoader(type1, type2);
            }
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Exception | LinkageError e) {
                // Mod classes are often unavailable to Class.forName during transformation.
                return commonSuperWithoutLoader(type1, type2);
            }
        }

        /** The shared type read from the jar's hierarchy, or the broader fallback. */
        private String commonSuperWithoutLoader(String type1, String type2) {
            String viaBytes = commonSuperViaBytes(
                    type1, type2, jarClassBytes, hierarchyNameMapper, targetSuperclass);
            if (viaBytes != null) {
                return viaBytes;
            }
            if (framingMethod != null) guessedJoins.add(framingMethod);
            guessedTypes.add(type1);
            guessedTypes.add(type2);
            return commonSuperFallback(type1, type2);
        }
    }

    /**
     * Finds a common superclass from class bytes when the types are not on the classpath.
     * Returns {@code null} unless the shared ancestor will also exist at runtime. This prevents
     * pre-remap intermediary names from leaking into stack-map frames.
     */
    static String commonSuperViaBytes(String type1, String type2,
                                      Function<String, byte[]> provider) {
        return commonSuperViaBytes(type1, type2, provider, Function.identity());
    }

    /**
     * Finds a common superclass while translating hierarchy names read from original jar bytes.
     * Fabric input classes still declare intermediary superclasses even though frame computation
     * runs after the class remapper. Mapping each discovered parent keeps a mod class connected to
     * the official host hierarchy instead of widening a valid Entity join to Object.
     */
    static String commonSuperViaBytes(String type1, String type2,
                                      Function<String, byte[]> provider,
                                      Function<String, String> hierarchyNameMapper) {
        return commonSuperViaBytes(type1, type2, provider, hierarchyNameMapper, null);
    }

    static String commonSuperViaBytes(String type1, String type2,
                                      Function<String, byte[]> provider,
                                      Function<String, String> hierarchyNameMapper,
                                      Function<String, String> targetSuperclass) {
        if (type1 == null || type2 == null) {
            return null;
        }
        if (type1.equals(type2)) {
            return type1;
        }
        Function<String, String> mapper = hierarchyNameMapper == null
                ? Function.identity() : hierarchyNameMapper;
        LinkedHashSet<String> firstChain = superChain(
                type1, provider, mapper, targetSuperclass);
        if (firstChain.isEmpty()) {
            return null;
        }

        String name = type2;
        for (int guard = 0; guard < 64 && name != null && !"java/lang/Object".equals(name); guard++) {
            if (firstChain.contains(name)
                    && isRuntimeResolvable(name, provider, targetSuperclass)) {
                return name;
            }
            name = superOf(name, provider, mapper, targetSuperclass);
        }
        return null;
    }

    private static LinkedHashSet<String> superChain(String type, Function<String, byte[]> provider,
                                                     Function<String, String> hierarchyNameMapper,
                                                     Function<String, String> targetSuperclass) {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        String name = type;
        for (int guard = 0; guard < 64 && name != null && !"java/lang/Object".equals(name); guard++) {
            if (!chain.add(name)) {
                break;
            }
            name = superOf(name, provider, hierarchyNameMapper, targetSuperclass);
        }
        return chain;
    }

    private static String superOf(String name, Function<String, byte[]> provider,
                                  Function<String, String> hierarchyNameMapper,
                                  Function<String, String> targetSuperclass) {
        byte[] bytes = readClassBytes(name, provider);
        if (bytes == null) {
            String indexed = targetSuperclass == null ? null : targetSuperclass.apply(name);
            return indexed == null
                    ? runtimeJavaSuperclass(name, hierarchyNameMapper)
                    : hierarchyNameMapper.apply(indexed);
        }
        try {
            String superName = new ClassReader(bytes).getSuperName();
            return superName == null ? null : hierarchyNameMapper.apply(superName);
        } catch (RuntimeException e) {
            // A newer JDK can expose java.base class files with a major version newer than the
            // bundled ASM understands. Java platform classes are safe to inspect without class
            // initialization, so keep walking that hierarchy instead of widening the join.
            return runtimeJavaSuperclass(name, hierarchyNameMapper);
        }
    }

    private static String runtimeJavaSuperclass(
            String name, Function<String, String> hierarchyNameMapper) {
        if (name == null || !name.startsWith("java/")) {
            return null;
        }
        try {
            Class<?> type = Class.forName(name.replace('/', '.'), false,
                    RetromodTransformer.class.getClassLoader());
            Class<?> parent = type.getSuperclass();
            return parent == null ? null
                    : hierarchyNameMapper.apply(Type.getInternalName(parent));
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    static byte[] readClassBytes(String name, Function<String, byte[]> provider) {
        return readClassBytes(name, provider, resourceName ->
                RetromodTransformer.class.getClassLoader().getResourceAsStream(resourceName));
    }

    static byte[] readClassBytes(String name, Function<String, byte[]> provider,
            Function<String, InputStream> resourceProvider) {
        return readClassBytes(
                name, provider, resourceProvider, MAX_HIERARCHY_CLASS_BYTES);
    }

    static byte[] readClassBytes(String name, Function<String, byte[]> provider,
            Function<String, InputStream> resourceProvider, long maxBytes) {
        if (provider != null) {
            byte[] bytes = provider.apply(name);
            if (bytes != null) {
                return bytes;
            }
        }
        if (name == null || resourceProvider == null) {
            return null;
        }
        try (InputStream in = resourceProvider.apply(name + ".class")) {
            return in == null ? null
                    : ZipSecurity.safeReadAllBytes(in, maxBytes);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isRuntimeResolvable(String name, Function<String, byte[]> provider,
                                               Function<String, String> targetSuperclass) {
        if (name == null) {
            return false;
        }
        if (name.startsWith("java/")) {
            return true;
        }
        if (provider != null && provider.apply(name) != null) {
            return true;
        }
        if (targetSuperclass != null && targetSuperclass.apply(name) != null) {
            return true;
        }
        try {
            loadForFrames(name, RetromodTransformer.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Loads a type for a frame merge, refusing without asking the loader what this side refuses. */
    static Class<?> loadForFrames(String internalName, ClassLoader loader) throws ClassNotFoundException {
        if (refusedOnThisSide(internalName)) {
            throw new ClassNotFoundException(internalName.replace('/', '.')
                    + " is client-only, so this dedicated server does not load it");
        }
        return Class.forName(internalName.replace('/', '.'), false, loader);
    }

    // Forge's and NeoForge's client-only markers, alone and repeated.
    private static final Set<String> ONLY_IN = Set.of(
            "Lnet/minecraftforge/api/distmarker/OnlyIn;", "Lnet/neoforged/api/distmarker/OnlyIn;");
    private static final Set<String> ONLY_INS = Set.of(
            "Lnet/minecraftforge/api/distmarker/OnlyIns;", "Lnet/neoforged/api/distmarker/OnlyIns;");
    private static final Map<String, Boolean> REFUSED_ON_THIS_SIDE = new ConcurrentHashMap<>();

    /** Whether this side's loader refuses to load {@code internalName}, decided without asking it. */
    public static boolean refusedOnThisSide(String internalName) {
        // On a Forge or NeoForge dedicated server the dist cleaner logs an ERROR and throws for a
        // client-only Minecraft class, and a frame merge needs only the answer that refusal gives.
        if (!isMinecraftType(internalName) || !EnvironmentDetector.knownDedicatedServer()) {
            return false;
        }
        return REFUSED_ON_THIS_SIDE.computeIfAbsent(internalName,
                name -> refusedOnThisSide(name, ClassResourceInspector::read, true));
    }

    /** Forgets cached refusals, so a test that changes the side or the class source gets fresh answers. */
    static void forgetRefusedTypesForTesting() {
        REFUSED_ON_THIS_SIDE.clear();
    }

    /** {@link #refusedOnThisSide(String)} with the class source and the side given. */
    static boolean refusedOnThisSide(String internalName,
            Function<String, org.objectweb.asm.tree.ClassNode> classes, boolean dedicatedServer) {
        if (!dedicatedServer) {
            return false;
        }
        // Loading a class loads its superclasses first, so a marked ancestor refuses it too. The
        // walk stays in the packages Forge 26.2 cleans, which the older cleaners also cover.
        String name = internalName;
        for (int guard = 0; guard < 64 && isMinecraftType(name); guard++) {
            org.objectweb.asm.tree.ClassNode node = classes.apply(name);
            if (node == null) {
                return false;
            }
            if (removedOnServer(node.visibleAnnotations)) {
                return true;
            }
            name = node.superName;
        }
        return false;
    }

    private static boolean isMinecraftType(String internalName) {
        return internalName != null
                && (internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/"));
    }

    /** Whether the annotations mark the whole class client-only, alone or inside {@code @OnlyIns}. */
    private static boolean removedOnServer(List<org.objectweb.asm.tree.AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (org.objectweb.asm.tree.AnnotationNode annotation : annotations) {
            if (ONLY_IN.contains(annotation.desc) && removesClassOnServer(annotation)) {
                return true;
            }
            // @OnlyIns keeps repeated markers in its value array.
            if (ONLY_INS.contains(annotation.desc)
                    && annotationValue(annotation, "value") instanceof List<?> repeated) {
                for (Object inner : repeated) {
                    if (inner instanceof org.objectweb.asm.tree.AnnotationNode marker
                            && ONLY_IN.contains(marker.desc) && removesClassOnServer(marker)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** CLIENT removes the whole class on a server, unless {@code _interface} names one interface to strip. */
    private static boolean removesClassOnServer(org.objectweb.asm.tree.AnnotationNode onlyIn) {
        return annotationValue(onlyIn, "_interface") == null
                && annotationValue(onlyIn, "value") instanceof String[] dist
                && dist.length == 2 && "CLIENT".equals(dist[1]);
    }

    private static Object annotationValue(org.objectweb.asm.tree.AnnotationNode annotation,
            String element) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (element.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private String targetSuperclass(String name) {
        FuzzyMethodResolver resolver = fuzzyResolver;
        return resolver == null || !resolver.isIndexed()
                ? null : resolver.directSuperclass(name);
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
                    loadForFrames(internalName, RetromodTransformer.class.getClassLoader()))
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
        "com/mojang/serialization/DynamicOps",
        "com/mojang/serialization/MapLike",
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

    // Types that became interfaces at a known version, and are still CLASSES below it. Rewriting a
    // valid class call into interface form on an older host corrupts a working translation, so each
    // entry carries the Minecraft version the change landed in.
    private static final Map<String, String> INTERFACE_SINCE = Map.ofEntries(
        // DataFixerUpper 7, which Minecraft 1.20.5 introduced. The 1.20 to 1.20.4 hosts ship
        // DataFixerUpper 6, where DataResult is a plain class (verified from the shipped jars).
        Map.entry("com/mojang/serialization/DataResult", "1.20.5"),
        // Found on the headless 26.2 server: YungJigsawStructure's codec calls
        // IntProvider.codec(int,int) and died "must be InterfaceMethodref". HeightProvider stayed a
        // class even on 26.2 and stays out.
        Map.entry("net/minecraft/util/valueproviders/IntProvider", "26.1"),
        Map.entry("net/minecraft/util/valueproviders/FloatProvider", "26.1"),
        // Abstract classes through 1.21.4 and interfaces from 1.21.5 (javap on the 1.21.3 and 1.21.5
        // jars). A Fabric 1.21.5 to 1.21.11 host still runs them under their intermediary names.
        Map.entry("net/minecraft/nbt/NumericTag", "1.21.5"),
        Map.entry("net/minecraft/nbt/CollectionTag", "1.21.5"),
        Map.entry("net/minecraft/class_2514", "1.21.5"),
        Map.entry("net/minecraft/class_2483", "1.21.5"),
        // 1.21.2 split the explosion into this interface and ServerExplosion.
        Map.entry("net/minecraft/world/level/Explosion", "1.21.2"),
        Map.entry("net/minecraft/class_1927", "1.21.2"),
        // The rest come from diffing the class flags of the 1.21.11, 26.1.2, 26.2 and 26.3 client jars.
        Map.entry("net/minecraft/client/renderer/block/model/BlockModel", "26.1"),
        Map.entry("net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessor", "26.2"),
        Map.entry("net/minecraft/world/level/levelgen/feature/Feature", "26.3"),
        Map.entry("net/minecraft/world/level/levelgen/feature/AbstractHugeMushroomFeature", "26.3"),
        Map.entry("net/minecraft/world/level/levelgen/feature/stateproviders/BlockStateProvider", "26.3"),
        Map.entry("net/minecraft/world/level/levelgen/placement/PlacementModifier", "26.3"),
        Map.entry("net/minecraft/world/level/levelgen/placement/PlacementFilter", "26.3"),
        Map.entry("net/minecraft/world/level/levelgen/placement/RepeatingPlacement", "26.3"),
        Map.entry("net/minecraft/world/level/levelgen/structure/placement/StructurePlacement", "26.3"),
        Map.entry("net/minecraft/world/level/levelgen/carver/WorldCarver", "26.3"),
        Map.entry("net/minecraft/world/entity/InterpolationHandler", "26.3"),
        Map.entry("net/minecraft/gametest/framework/TestFunctionLoader", "26.3"),
        Map.entry("net/minecraft/data/worldgen/Carvers", "26.3"),
        Map.entry("net/minecraft/core/RegistrySetBuilder$RegistryStub", "26.3")
    );

    /**
     * Whether the owner is an interface on the CURRENT host.
     *
     * <p>The host answers first, because it is the only source that is right for every version. The
     * tables below are the fallback for an offline transform with no {@code --mc-jar}, where nothing
     * can be read. A flat list of names is wrong by construction on any host it was not written
     * against, which is how {@code Lifecycle} sat there for a year without ever having been an
     * interface in any version (#249), so anything that changed kind belongs in
     * {@link #INTERFACE_SINCE} with the version it changed in.
     */
    private boolean isKnownInterface(String owner) {
        // The owner comes from the mod's bytecode, so it is attacker chosen and unbounded. Probing
        // and memoizing every distinct one would let a jar full of invented owners grow the cache
        // without limit and, worse, resolve a MOD's own class through the live loader and let it
        // decide the call form. Only names the host actually owns are probed; everything else falls
        // straight through to the tables, which are small and need no memo.
        if (!isHostOwnedName(owner)) return tabledInterfaceKind(owner);
        // Called for every method instruction, and a miss can parse a class file, so memoize.
        return interfaceKinds.computeIfAbsent(owner, this::readInterfaceKind);
    }

    /** Whether {@code owner} is an interface on the target host: an answer when the host or the tables know, null otherwise. */
    public Boolean targetKindIsInterface(String owner) {
        if (owner == null) return null;
        Boolean host = SuperclassSafety.isInterfaceOnHost(owner, fuzzyResolver);
        if (host != null) return host;
        // The tables only ever prove an interface; a name they lack is unknown, not a class.
        return tabledInterfaceKind(owner) ? Boolean.TRUE : null;
    }

    /** Whether the host proves {@code owner} a class, as types it turned from interfaces into classes are. */
    private boolean isKnownClass(String owner) {
        // Only the host's own reading counts: a name it cannot read keeps the form the mod wrote.
        if (!isHostOwnedName(owner) || owner.startsWith("java/")) return false;
        return classKinds.computeIfAbsent(owner, name -> {
            if (LoaderTypeKinds.isLoaderName(name) && !LoaderTypeKinds.targetOwns(name)) return false;
            return SuperclassSafety.isInterfaceOnHost(name, fuzzyResolver) == Boolean.FALSE;
        });
    }

    /** Names the running Minecraft, its mod loader or the JDK owns, as opposed to a mod's own classes. */
    private static boolean isHostOwnedName(String owner) {
        return owner != null
                && (owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/")
                    || owner.startsWith("java/") || LoaderTypeKinds.isLoaderName(owner));
    }

    private boolean readInterfaceKind(String owner) {
        // Forge 26.2 made ModConfigEvent and other old event classes interfaces. Only the target
        // loader's own jars can say so; the memo is cleared with the registrations, so a run that
        // changes target starts over.
        if (LoaderTypeKinds.isLoaderName(owner) && !LoaderTypeKinds.targetOwns(owner)) return false;
        Boolean host = SuperclassSafety.isInterfaceOnHost(owner, fuzzyResolver);
        if (host != null) return host;
        return tabledInterfaceKind(owner);
    }

    private boolean tabledInterfaceKind(String owner) {
        if (KNOWN_INTERFACES.contains(owner)) return true;
        String since = INTERFACE_SINCE.get(owner);
        if (since != null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        }
        return LoaderTypeKinds.tabledInterface(owner);
    }

    /** Every class move that produces {@code destination}, sorted. Both weighted lists produce WeightedList. */
    static List<String> redirectSourcesOf(Map<String, String> redirects, String destination) {
        List<String> sources = new ArrayList<>();
        for (Map.Entry<String, String> entry : redirects.entrySet()) {
            if (destination.equals(entry.getValue())) sources.add(entry.getKey());
        }
        Collections.sort(sources);
        return sources;
    }

    /** Where an unusable base name came from, told only from Retromod's own move table. */
    static String unusableBaseOrigin(String base, List<String> sources) {
        if (sources.isEmpty()) {
            return "No class move in Retromod's tables produces this name.";
        }
        if (sources.size() == 1) {
            return "It came from the class move " + sources.get(0) + " -> " + base + ".";
        }
        return "Several class moves produce this name: " + String.join(", ", sources) + ".";
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
            this(api, classVisitor, null);
        }

        /** {@code writtenSuperName} is the superclass the class's own bytes name, before any class move. */
        RetromodClassVisitor(int api, ClassVisitor classVisitor, String writtenSuperName) {
            super(api, classVisitor);
            this.writtenSuperName = writtenSuperName;
        }

        private final String writtenSuperName;

        // Stored for fixing invokespecial on indirect interface methods.
        // currentSuperName = EFFECTIVE direct superclass (post-rebase); used by the
        // non-<init> super-call fixup so it targets the real direct super.
        // currentSuperNameOriginal = the super as written in the source bytecode; used
        // to recognize a super() <init> call that needs its owner rebased (#70).
        private String currentSuperName = null;
        private String currentSuperNameOriginal = null;
        private Set<String> currentDirectInterfaces = Set.of();
        // Concrete bridges this class gets for its interfaces, and the methods it declares itself.
        private List<ImplementedMethodBridge> pendingMethodBridges = List.of();
        private final Set<String> declaredMethods = new HashSet<>();

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            this.currentClassName = name;
            this.currentSuperName = superName;            // effective (overwritten below if rebased)
            this.currentSuperNameOriginal = superName;    // as written in source
            // Reset bridge generator for each new class
            bridgeGenerator.reset();
            declaredMethods.clear();
            pendingMethodBridges = List.of();

            // A remap can land an interface on the class itself (ConfigScreenFactory after the ModMenu
            // move) or on one it already lists (RegistryEntryRemovedCallback onto
            // RegistryEntryAddedCallback). The JVM rejects both, so keep the first of each.
            if (interfaces != null && interfaces.length > 0) {
                String[] filtered = java.util.Arrays.stream(interfaces)
                    .filter(iface -> !iface.equals(name))
                    .distinct()
                    .toArray(String[]::new);
                if (filtered.length < interfaces.length) {
                    LOGGER.debug("Removed self-referential or repeated interfaces from {}", name);
                    interfaces = filtered;
                }
            }
            this.currentDirectInterfaces = interfaces != null ? Set.of(interfaces) : Set.of();

            // Before the superclass rebase, which emits the header itself and returns early.
            if (ImplementedInterfaceRewrite.applies(interfaces, implementedInterfaceRewrites)) {
                interfaces = ImplementedInterfaceRewrite.interfaces(
                        name, interfaces, implementedInterfaceRewrites);
                signature = ImplementedInterfaceRewrite.signature(
                        signature, implementedInterfaceRewrites);
            }
            // A class gets concrete bridges; an interface gets defaults, from class file version 52.
            if ((access & Opcodes.ACC_INTERFACE) == 0) {
                pendingMethodBridges = methodBridgesFor(interfaces, implementedMethodBridges);
            } else if ((version & 0xFFFF) >= Opcodes.V1_8) {
                pendingMethodBridges = methodBridgesFor(interfaces, interfaceDefaultBridges);
            }

            // Check if superclass needs to be rewritten (class-to-interface polyfill)
            if (superName != null && !superclassRedirects.isEmpty()) {
                SuperclassRedirect redirect = superclassRebase(superName, writtenSuperName);
                if (redirect != null && isLegacyBaseSaturated(redirect.newSuperclass())) {
                    LOGGER.warn("Not rebasing {} onto the saturated base {}; leaving {} in place so "
                            + "the failure names the removed class instead of failing verification.",
                            name, redirect.newSuperclass(), superName);
                    redirect = null;
                }
                if (redirect != null) {
                    LOGGER.debug("Rewriting superclass of {} from {} to {}",
                            name, superName, redirect.newSuperclass());
                    String newSuper = redirect.newSuperclass();
                    this.currentSuperName = newSuper; // effective direct super is the new base
                    String[] newInterfaces = mergeInterfaces(interfaces, redirect.addInterfaces());
                    // An added interface is direct now, so a super call into it stays on it.
                    this.currentDirectInterfaces = newInterfaces != null ? Set.of(newInterfaces) : Set.of();
                    super.visit(version, access, name, signature, newSuper, newInterfaces);
                    return;
                }
            }

            reportIllegalSuperclass(name, superName);

            super.visit(version, access, name, signature, superName, interfaces);
        }

        /**
         * Report a superclass that the host cannot possibly accept, naming the redirect that
         * produced it.
         *
         * <p>{@code superName} has been through the class remapper for this pass, which is not
         * always the final name: a chained move resolves one hop per pass. A name that is itself a
         * redirect source is skipped and reported, if it still deserves it, once the chain ends. A class move is a flat rename and does not
         * know which slot it lands in, so a destination that is fine as a type can be final, an
         * interface, or absent, and therefore illegal to extend. Without this the mod loads to
         * {@code IncompatibleClassChangeError} with no mention of Retromod, the mod's real base
         * class, or the table row responsible (#260).
         *
         * <p>This reports and does not repair. Undoing the rename on the {@code extends} slot alone
         * would leave the {@code super(...)} call pointing at the new owner, which fails
         * verification instead of linkage. A base that mods genuinely extend needs a registered
         * {@link #registerSuperclassRebase} to a class that carries the old constructors, and the
         * block above applies that when one exists.
         */
        private void reportIllegalSuperclass(String className, String superName) {
            if (superName == null || classRedirects.isEmpty()) return;
            if (!superName.startsWith("net/minecraft/") && !superName.startsWith("com/mojang/")) {
                return;
            }
            // A move can be a chain. The tables take advancements/critereon/X to
            // advancements/criterion/X, and the 26.2 step takes that to advancements/triggers/X,
            // which is where the mod actually lands. A remapper resolves one hop per pass, so on
            // the pass that makes the first hop this sees a name nothing will ever extend. The
            // loop folds the rest and calls this again on the result, so a name that still has a
            // redirect of its own is not the answer yet and reporting it says a mod cannot load
            // when it transforms perfectly well.
            if (classRedirects.containsKey(superName)) return;
            SuperclassSafety.Verdict verdict = superclassVerdicts.computeIfAbsent(
                    superName, n -> SuperclassSafety.classify(n, fuzzyResolver));
            if (!SuperclassSafety.cannotBeExtended(verdict)) return;
            // Keyed on the base, not the class: a mod's whole model or item package usually extends
            // the same one, and repeating the explanation per subclass buries it.
            if (!reportedIllegalSuperclasses.add(superName)) {
                LOGGER.debug("{} also extends the unusable base {}", className, superName);
                return;
            }

            String finalBaseReason = verdict == SuperclassSafety.Verdict.FINAL_CLASS
                    ? com.retromod.shim.common.FinalBaseConstructionAdapter.finalBaseReason(
                            superName, RetromodVersion.TARGET_MC_VERSION)
                    : null;
            if (finalBaseReason != null) {
                // FinalBaseConstructionAdapter keeps the classes that build such an object
                // loadable, so only the subclass itself is lost.
                LOGGER.warn("{} extends {}, which is final on this Minecraft version. {}. Retromod "
                        + "has no replacement for it yet, so {} may fail when the game loads it, and "
                        + "code that builds one of these objects reports this reason. If the mod "
                        + "breaks, report this line with the mod and its version.",
                        className, superName, finalBaseReason, className);
                return;
            }

            // A guess, not a certainty: the class may never load, or a later pass may fix it.
            LOGGER.warn("{} extends {}, which {} on this Minecraft version. {} Retromod has no "
                    + "replacement for it yet, so {} may fail when the game loads it. If the mod "
                    + "breaks, report this line with the mod and its version. Other classes that "
                    + "extend the same base are not listed again.",
                    className, superName, describe(verdict),
                    unusableBaseOrigin(superName, redirectSourcesOf(classRedirects, superName)),
                    className);
        }

        private String describe(SuperclassSafety.Verdict verdict) {
            return switch (verdict) {
                case INTERFACE -> "is an interface and belongs in implements, not extends";
                case FINAL_CLASS -> "is final and cannot be a superclass";
                case SEALED -> "is sealed and permits only its own listed subclasses";
                case ABSENT -> "does not exist";
                case CLASS, UNKNOWN -> "is usable";
            };
        }

        /** The bridges {@code table} registers for a class header's interfaces, in interface order. */
        private List<ImplementedMethodBridge> methodBridgesFor(String[] interfaces,
                Map<String, List<ImplementedMethodBridge>> table) {
            if (interfaces == null || table.isEmpty()) return List.of();
            List<ImplementedMethodBridge> bridges = new ArrayList<>();
            for (String iface : interfaces) {
                bridges.addAll(table.getOrDefault(iface, List.of()));
            }
            return bridges;
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
                String resolvedName = redirectedClass(name);
                String resolvedOuter = redirectedClass(outerName);
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

            declaredMethods.add(name + descriptor);
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Only wrap if we have redirects that act inside a method body. superclassRedirects
            // is included because a class->class rebase rewrites super(...) <init> owners in the
            // body (#70); superCtorRedirects likewise because it rewrites the super()/this()
            // descriptor there (the 1.12.2 Block(Material)->Block(Properties) bridge). Omitting
            // superCtorRedirects was a latent bug: a redirect set with only super-ctor + class
            // redirects skipped the wrapper and never applied the super-ctor rewrite. It was
            // masked whenever the same shim also registered a method redirect, and surfaced once
            // the phantom-target sweep dropped those (Forge_1_12_2_to_1_13_2's FlatteningShim).
            if (methodRedirects.isEmpty() && inheritedMethodRedirects.isEmpty()
                    && fieldRedirects.isEmpty() && constructorRedirects.isEmpty()
                    && fieldAccessorRedirects.isEmpty() && superclassRedirects.isEmpty()
                    && superCtorRedirects.isEmpty() && typedFieldRenames.isEmpty()
                    && neutralizedMethods.isEmpty() && staticFieldAccessors.isEmpty()
                    && argDropRedirects.isEmpty()
                    && fieldHopAccessors.isEmpty() && fieldStaticBridges.isEmpty() && staticFieldBridges.isEmpty()
                    && uniqueFieldGetters.isEmpty() && uniqueFieldStaticBridges.isEmpty()
                    && convertingRedirects.isEmpty() && singletonRedirects.isEmpty()
                    && lambdaAdapters.isEmpty() && staticFieldTypeDrifts.isEmpty()
                    && libraryStaticFieldReaders.isEmpty()) {
                return mv;
            }
            return new RetromodMethodVisitor(api, mv, currentClassName, currentSuperName,
                    currentSuperNameOriginal, currentDirectInterfaces);
        }

        @Override
        public void visitEnd() {
            // BridgeAdapterGenerator disabled; see TODO in visitMethod
            // A later pass sees the method from this one as declared, so it is written once.
            for (ImplementedMethodBridge bridge : pendingMethodBridges) {
                if (declaredMethods.add(bridge.name() + bridge.desc())) bridge.emit(cv);
            }
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
                String resolved = redirectedClass(className);
                sb.append('L').append(resolved).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Turns registered constructor calls into factory calls, ahead of the {@link ClassRemapper}. */
    private class CtorRedirectPrePass extends ClassVisitor {

        // Resolves pre-remap names to what the remapper will emit, or null. The pass runs before the
        // remapper because the pre-1.19 Text bridge retypes class_2588 TranslatableText and class_2585
        // LiteralText to class_5250 MutableText, yet their <init>(String)s go to Text.translatable and
        // Text.literal, and only the pre-remap owners keep them apart. Post-remap registrations, such
        // as the 26.1 Identifier redirects, still match because lookups resolve through this first.
        private final org.objectweb.asm.commons.Remapper resolver;
        private final String className;

        CtorRedirectPrePass(int api, ClassVisitor cv,
                org.objectweb.asm.commons.Remapper resolver, String className) {
            super(api, cv);
            this.resolver = resolver;
            this.className = className;
        }

        private String resolveType(String type) {
            return resolver != null ? resolver.mapType(type)
                    : redirectedClass(type);
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
            // Constructor references (TranslatableText::new) are matched in PrePassMethodVisitor with the
            // same lookups as calls; RetromodMethodVisitor's indy handling still covers post-remap keys.
            PrePassMethodVisitor prePass = new PrePassMethodVisitor(api, mv);
            // The whole method is read first, so each NEW is checked against its own constructor call.
            return new org.objectweb.asm.tree.MethodNode(api, access, name, descriptor, signature, exceptions) {
                @Override
                public void visitEnd() {
                    prePass.plan = planNews(this);
                    accept(prePass);
                }
            };
        }

        /** Whether a NEW of this type waits for its constructor call, which may become a factory call. */
        private boolean defersNew(String type) {
            if (preservesProviderReference(className, type)) return false;
            return (constructorRedirectOwners.contains(type) || constructorRedirectOwners.contains(resolveType(type)))
                    && !jarDefinesModClass(resolveType(type));
        }

        /** The factory that replaces {@code owner.<init>(descriptor)}, or null. */
        private FactoryTarget factoryFor(String owner, String descriptor) {
            // Post-remap terms first (how the 26.1 shims register), then fully pre-remap (the Text bridge).
            String resolvedOwner = resolveType(owner);
            String resolvedDesc = resolveDesc(descriptor);
            ConstructorKey key = new ConstructorKey(resolvedOwner, resolvedDesc);
            FactoryTarget factory = constructorRedirects.get(key);
            if (factory == null && !resolvedDesc.equals(descriptor)) {
                key = new ConstructorKey(resolvedOwner, descriptor);
                factory = constructorRedirects.get(key);
            }
            if (factory == null && !resolvedOwner.equals(owner)) {
                key = new ConstructorKey(owner, descriptor);
                factory = constructorRedirects.get(key);
            }
            // A removed owner keyed with current argument names: Forge 1.20.1's new ModelResourceLocation(id, variant)
            // passes a ResourceLocation, which only the remap names Identifier.
            if (factory == null && !resolvedOwner.equals(owner) && !resolvedDesc.equals(descriptor)) {
                key = new ConstructorKey(owner, resolvedDesc);
                factory = constructorRedirects.get(key);
            }
            if (factory != null && keepsOldName(owner)) return null;
            if (factory != null && keepsConstructor(resolvedOwner, resolvedDesc)) return null;
            if (factory != null && skipsConstructorRedirect(key)) return null;
            return factory;
        }

        /** Pairs each NEW the factory can replace with its own {@code <init>}; null when the method cannot be analyzed. */
        private Map<Integer, Integer> planNews(org.objectweb.asm.tree.MethodNode method) {
            // Both maps count in instruction order: NEWs among NEWs, <init> calls among <init> calls.
            // The streaming visitor counts the same way, so the plan reaches it without node identity.
            org.objectweb.asm.tree.InsnList insns = method.instructions;
            List<org.objectweb.asm.tree.TypeInsnNode> deferred = new ArrayList<>();
            Map<org.objectweb.asm.tree.AbstractInsnNode, Integer> ordinals = new HashMap<>();
            int news = 0;
            int inits = 0;
            for (org.objectweb.asm.tree.AbstractInsnNode insn : insns) {
                if (insn.getOpcode() == Opcodes.NEW) {
                    org.objectweb.asm.tree.TypeInsnNode allocation = (org.objectweb.asm.tree.TypeInsnNode) insn;
                    ordinals.put(allocation, news++);
                    if (defersNew(allocation.desc)) deferred.add(allocation);
                } else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")) {
                    ordinals.put(call, inits++);
                }
            }
            if (deferred.isEmpty()) return Map.of();
            int[] lowWater = new int[insns.size()];
            Arrays.fill(lowWater, Integer.MAX_VALUE);
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue>[] frames;
            try {
                frames = new StackLowWaterAnalyzer(insns, lowWater).analyze(className, method);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException unanalyzable) {
                return null;
            }
            Map<Integer, Integer> plan = new HashMap<>();
            for (org.objectweb.asm.tree.TypeInsnNode allocation : deferred) {
                org.objectweb.asm.tree.MethodInsnNode init = replacedInit(allocation, insns, frames, lowWater);
                if (init != null) plan.put(ordinals.get(allocation), ordinals.get(init));
            }
            return plan;
        }

        /** The NEW's own {@code <init>} when a factory can stand in for it, or null to keep the NEW as written. */
        private org.objectweb.asm.tree.MethodInsnNode replacedInit(org.objectweb.asm.tree.TypeInsnNode allocation,
                org.objectweb.asm.tree.InsnList insns,
                org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue>[] frames,
                int[] lowWater) {
            int index = insns.indexOf(allocation);
            if (frames[index] == null) return null;
            int base = frames[index].getStackSize();
            // Walk the prefix the streaming visitor accepts, counting the copies left on the stack.
            int copies = 1;
            Set<Integer> locals = new HashSet<>();
            org.objectweb.asm.tree.AbstractInsnNode insn = allocation.getNext();
            for (; insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() < 0) continue;
                if (insn.getOpcode() == Opcodes.DUP && copies > 0) {
                    copies++;
                } else if (insn.getOpcode() == Opcodes.ASTORE && copies > 0) {
                    copies--;
                    locals.add(((org.objectweb.asm.tree.VarInsnNode) insn).var);
                } else if (insn.getOpcode() == Opcodes.ALOAD && locals.contains(((org.objectweb.asm.tree.VarInsnNode) insn).var)) {
                    copies++;
                } else {
                    break;
                }
            }
            if (copies == 0) return null;
            int floor = base + copies;
            // Until its constructor call, every instruction must leave the copies below it alone.
            for (; insn != null; insn = insn.getNext()) {
                int at = insns.indexOf(insn);
                if (insn.getOpcode() < 0 || frames[at] == null) continue;
                // Type.getArgumentCount came in ASM 9.6, and the oldest loader ASM is 9.5.
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.name.equals("<init>")
                        && frames[at].getStackSize() - Type.getArgumentTypes(call.desc).length - 1 == floor - 1) {
                    boolean sameClass = call.owner.equals(allocation.desc)
                            || resolveType(call.owner).equals(resolveType(allocation.desc));
                    return sameClass && factoryFor(call.owner, call.desc) != null ? call : null;
                }
                // The factory's result is stored into the prefix's locals afterwards, so the arguments must not use them.
                if (lowWater[at] < floor || usesLocal(insn, locals) || leavesRange(insn, allocation, insns)) return null;
            }
            return null;
        }

        /** Whether the instruction reads, writes or increments one of {@code locals}, a long or double's second slot included. */
        private boolean usesLocal(org.objectweb.asm.tree.AbstractInsnNode insn, Set<Integer> locals) {
            if (insn instanceof org.objectweb.asm.tree.IincInsnNode increment) return locals.contains(increment.var);
            if (!(insn instanceof org.objectweb.asm.tree.VarInsnNode variable)) return false;
            boolean wide = switch (variable.getOpcode()) {
                case Opcodes.LLOAD, Opcodes.DLOAD, Opcodes.LSTORE, Opcodes.DSTORE -> true;
                default -> false;
            };
            return locals.contains(variable.var) || (wide && locals.contains(variable.var + 1));
        }

        /** Whether a jump goes back above the NEW, where the deferred stack shape no longer holds. */
        private boolean leavesRange(org.objectweb.asm.tree.AbstractInsnNode insn,
                org.objectweb.asm.tree.TypeInsnNode allocation, org.objectweb.asm.tree.InsnList insns) {
            List<org.objectweb.asm.tree.LabelNode> targets = new ArrayList<>();
            if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump) targets.add(jump.label);
            if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode table) {
                targets.add(table.dflt);
                targets.addAll(table.labels);
            }
            if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) {
                targets.add(lookup.dflt);
                targets.addAll(lookup.labels);
            }
            int start = insns.indexOf(allocation);
            for (org.objectweb.asm.tree.LabelNode target : targets) {
                if (insns.indexOf(target) <= start) return true;
            }
            return false;
        }

        /** One deferred NEW awaiting its {@code <init>} (redirect-eligible owner). */
        private static final class PendingNew {
            final String type;
            // The DUP, ASTORE and ALOAD a compiler places right after NEW, as {opcode, var} pairs.
            // javac emits one DUP; other compilers may keep the new object in a local instead.
            final List<int[]> prefix = new ArrayList<>();
            // Copies of the uninitialized object on the stack, and the locals that hold it.
            int stackCopies = 1;
            final List<Integer> locals = new ArrayList<>();
            boolean prefixOpen = true;
            // True once the NEW was written back out unchanged, so its <init> passes through.
            boolean materialized;
            // The planned <init>'s order among the method's <init> calls, or -1 without a plan.
            int init = -1;
            PendingNew(String type) { this.type = type; }
        }

        private class PrePassMethodVisitor extends MethodVisitor {

            // new Foo(args) compiles to NEW, DUP, the args, then INVOKESPECIAL <init>. A redirect drops
            // NEW and its prefix, lets the args through, and calls the factory in place of <init>. This
            // is a stack because a deferred new's args can hold more redirected news, and javac always
            // closes the inner <init> before the outer one.
            private final java.util.ArrayDeque<PendingNew> pendingNews =
                    new java.util.ArrayDeque<>();
            // From planNews: each replaced NEW's order among the method's NEWs, mapped to its <init>'s
            // order among the <init> calls. Any other NEW stays as written. Null when the method could
            // not be analyzed, which leaves every NEW to the streaming rules below.
            Map<Integer, Integer> plan;
            private int newsSeen;
            private int initsSeen;

            PrePassMethodVisitor(int api, MethodVisitor mv) {
                super(api, mv);
            }

            /** Ends the top entry's prefix; a shape no factory can stand in for is written back out as it was. */
            private void closePrefix(boolean touchesTopOfStack) {
                PendingNew top = pendingNews.peek();
                if (top == null || !top.prefixOpen) return;
                top.prefixOpen = false;
                // <init> needs the object below its args, and a stack operation on it has no factory equivalent.
                if (top.stackCopies == 0 || touchesTopOfStack) {
                    emitNewAndPrefix(top);
                    top.materialized = true;
                }
            }

            private void emitNewAndPrefix(PendingNew entry) {
                super.visitTypeInsn(Opcodes.NEW, entry.type);
                for (int[] step : entry.prefix) {
                    if (step[0] == Opcodes.DUP) super.visitInsn(Opcodes.DUP);
                    else super.visitVarInsn(step[0], step[1]);
                }
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                PendingNew top = pendingNews.peek();
                if (top != null && top.prefixOpen) {
                    // An ASTORE right after NEW moves the object into a local; an ALOAD of that local copies it back.
                    if (opcode == Opcodes.ASTORE && top.stackCopies > 0) {
                        top.stackCopies--;
                        if (!top.locals.contains(var)) top.locals.add(var);
                        top.prefix.add(new int[]{opcode, var});
                        return;
                    }
                    if (opcode == Opcodes.ALOAD && top.locals.contains(var)) {
                        top.stackCopies++;
                        top.prefix.add(new int[]{opcode, var});
                        return;
                    }
                    closePrefix(false);
                }
                super.visitVarInsn(opcode, var);
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                closePrefix(false);
                super.visitIntInsn(opcode, operand);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                closePrefix(false);
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                    Object... bootstrapMethodArguments) {
                closePrefix(false);
                // A constructor reference takes the factory a call to the same constructor takes, here
                // where the pre-remap owner still tells a removed type from the type it is renamed to.
                Object[] arguments = bootstrapMethodArguments;
                for (int i = 0; arguments != null && i < arguments.length; i++) {
                    if (!(arguments[i] instanceof Handle h) || h.getTag() != Opcodes.H_NEWINVOKESPECIAL) continue;
                    if (preservesProviderReference(className, h.getOwner()) || jarDefinesModClass(resolveType(h.getOwner()))) {
                        continue;
                    }
                    FactoryTarget factory = factoryFor(h.getOwner(), h.getDesc());
                    if (factory == null) continue;
                    if (arguments == bootstrapMethodArguments) arguments = arguments.clone();
                    arguments[i] = new Handle(Opcodes.H_INVOKESTATIC, factory.factoryClass(), factory.factoryMethod(),
                            factory.factoryDesc(), factory.factoryIsInterface());
                }
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, arguments);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                closePrefix(false);
                super.visitJumpInsn(opcode, label);
            }

            @Override
            public void visitLdcInsn(Object value) {
                closePrefix(false);
                super.visitLdcInsn(value);
            }

            @Override
            public void visitIincInsn(int var, int increment) {
                closePrefix(false);
                super.visitIincInsn(var, increment);
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                closePrefix(false);
                super.visitTableSwitchInsn(min, max, dflt, labels);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                closePrefix(false);
                super.visitLookupSwitchInsn(dflt, keys, labels);
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                closePrefix(false);
                super.visitMultiANewArrayInsn(descriptor, numDimensions);
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                closePrefix(false);
                int ordinal = opcode == Opcodes.NEW ? newsSeen++ : -1;
                Integer plannedInit = plan == null ? null : plan.get(ordinal);
                if (opcode == Opcodes.NEW && defersNew(type) && (plan == null || plannedInit != null)) {
                    PendingNew entry = new PendingNew(type);
                    entry.init = plannedInit == null ? -1 : plannedInit;
                    pendingNews.push(entry);
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
                PendingNew top = pendingNews.peek();
                if (opcode == Opcodes.DUP && top != null && top.prefixOpen && top.stackCopies > 0) {
                    // A DUP right after NEW (or after its ASTORE and ALOAD) copies the new object.
                    // A later DUP, such as a plain nested NEW's, passes through below.
                    top.stackCopies++;
                    top.prefix.add(new int[]{opcode, -1});
                    return;
                }
                closePrefix(top != null && top.stackCopies > 0 && movesTopOfStack(opcode));
                super.visitInsn(opcode);
            }

            private boolean movesTopOfStack(int opcode) {
                return switch (opcode) {
                    case Opcodes.POP, Opcodes.POP2, Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2,
                            Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.SWAP -> true;
                    default -> false;
                };
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                    String descriptor, boolean isInterface) {
                closePrefix(false);
                boolean init = opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name);
                int initOrdinal = init ? initsSeen++ : -1;
                if (!init || pendingNews.isEmpty()) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }
                // With a plan, the entry is the one planned for this exact <init>, wherever it sits.
                if (plan != null) {
                    PendingNew planned = null;
                    for (PendingNew entry : pendingNews) {
                        if (entry.init == initOrdinal) planned = entry;
                    }
                    if (planned == null) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        return;
                    }
                    pendingNews.remove(planned);
                    callFactory(planned, factoryFor(owner, descriptor), owner, descriptor);
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
                // Its NEW was already written back out, so the call stays as it is.
                if (top.materialized) {
                    pendingNews.pop();
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }
                // This <init> completes the deferred NEW on top of the stack.
                FactoryTarget factory = factoryFor(owner, descriptor);
                if (factory != null) {
                    callFactory(pendingNews.pop(), factory, owner, descriptor);
                    return;
                }
                // Owner matches but no redirect for THIS descriptor: emit the deferred
                // NEW+DUP below the already-emitted args (branch-safe here, at the
                // convergence point), then the original call.
                flushPendingNewBeforeArgs(pendingNews.pop(), descriptor);
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            /** Calls the factory in place of {@code <init>}; the pending NEW and its prefix were never written out. */
            private void callFactory(PendingNew entry, FactoryTarget factory, String owner, String descriptor) {
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
                    super.visitTypeInsn(Opcodes.CHECKCAST, entry.type);
                }
                placeFactoryResult(entry);
            }

            /** Puts the factory's result where {@code <init>} left the object: the prefix's locals, then the stack. */
            private void placeFactoryResult(PendingNew entry) {
                int needed = entry.stackCopies - 1 + entry.locals.size();
                // A NEW with no DUP builds an object nobody keeps.
                if (needed == 0) {
                    super.visitInsn(Opcodes.POP);
                    return;
                }
                for (int i = 1; i < needed; i++) super.visitInsn(Opcodes.DUP);
                for (int local : entry.locals) super.visitVarInsn(Opcodes.ASTORE, local);
            }

            /**
         * Flush a deferred NEW and its prefix when constructor args are already on the stack.
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
                // No args on stack; just emit the deferred NEW and its prefix
                emitNewAndPrefix(entry);
                return;
            }

            // Use high base slot to avoid conflicts with existing locals.
            // COMPUTE_FRAMES / COMPUTE_MAXS will adjust maxLocals automatically.
            // The slot is shared so later passes that look for the start of a statement can
            // recognize this spill, which happens when no constructor redirect matched.
            int tempSlotBase = CONSTRUCTOR_ARG_SPILL_SLOT;
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

            // Now the stack is clear of args; emit the deferred NEW and its prefix
            emitNewAndPrefix(entry);

            // Reload args from temp locals (forward order)
            for (int i = 0; i < paramTypes.length; i++) {
                super.visitVarInsn(paramTypes[i].getOpcode(Opcodes.ILOAD), paramSlots[i]);
            }

            // Stack is now: [..., uninit, uninit, arg1, arg2, ..., argN]
            // which is the correct order for INVOKESPECIAL <init>
            }
        }
    }

    /** Analyzes a method and records, per instruction, the lowest stack height it pops down to. */
    private static final class StackLowWaterAnalyzer
            extends org.objectweb.asm.tree.analysis.Analyzer<org.objectweb.asm.tree.analysis.BasicValue> {

        private final org.objectweb.asm.tree.InsnList insns;
        private final int[] lowWater;

        StackLowWaterAnalyzer(org.objectweb.asm.tree.InsnList insns, int[] lowWater) {
            super(new org.objectweb.asm.tree.analysis.BasicInterpreter());
            this.insns = insns;
            this.lowWater = lowWater;
        }

        @Override
        protected org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue> newFrame(
                int numLocals, int numStack) {
            return new LowWaterFrame(numLocals, numStack);
        }

        @Override
        protected org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue> newFrame(
                org.objectweb.asm.tree.analysis.Frame<? extends org.objectweb.asm.tree.analysis.BasicValue> frame) {
            LowWaterFrame copy = new LowWaterFrame(frame.getLocals(), frame.getMaxStackSize());
            copy.init(frame);
            return copy;
        }

        private final class LowWaterFrame extends org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue> {
            private int lowest;

            LowWaterFrame(int numLocals, int numStack) {
                super(numLocals, numStack);
            }

            @Override
            public void execute(org.objectweb.asm.tree.AbstractInsnNode insn,
                    org.objectweb.asm.tree.analysis.Interpreter<org.objectweb.asm.tree.analysis.BasicValue> interpreter)
                    throws org.objectweb.asm.tree.analysis.AnalyzerException {
                lowest = getStackSize();
                super.execute(insn, interpreter);
                int index = insns.indexOf(insn);
                lowWater[index] = Math.min(lowWater[index], lowest);
            }

            @Override
            public org.objectweb.asm.tree.analysis.BasicValue pop() {
                org.objectweb.asm.tree.analysis.BasicValue value = super.pop();
                lowest = Math.min(lowest, getStackSize());
                return value;
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
         * Pops the result of a redirect target that returns a value where the old method returned
         * nothing, such as 1.12's {@code NBTTagCompound.setTag}, now {@code CompoundTag.put}, which
         * returns the previous tag. Left on the stack, it breaks every branch that merges after it.
         */
        private void discardUnexpectedResult(String origReturn, String newReturn) {
            if (!origReturn.equals("V") || newReturn.equals("V")) return;
            markFrameInvalidatingRewrite();
            super.visitInsn(newReturn.equals("J") || newReturn.equals("D") ? Opcodes.POP2 : Opcodes.POP);
        }


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
            // A Retromod class shim, or a host interface that became a class, such as 26.1's RecipeSerializer.
            if (opcode == Opcodes.INVOKEINTERFACE && (classShimTargets.contains(owner) || isKnownClass(owner))) {
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
            if (classShimTargets.contains(owner) || (originalIsInterface && isKnownClass(owner))) {
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
            // A super call into a base that became an interface, now a direct superinterface after a rebase,
            // needs the interface form.
            if (opcode == Opcodes.INVOKESPECIAL && !isInterface && !"<init>".equals(name)
                    && classDirectInterfaces.contains(owner) && isKnownInterface(owner)) {
                isInterface = true;
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
            LambdaAdapter lambdaAdapter = lambdaAdapterFor(name, descriptor,
                    bootstrapMethodHandle, bootstrapMethodArguments);
            if (lambdaAdapter != null) {
                bootstrapMethodArguments[0] = Type.getMethodType(lambdaAdapter.adapterSamDesc());
                name = lambdaAdapter.adapterName();
                if (lambdaAdapter.wrapOwner() != null) {
                    descriptor = descriptor.substring(0, descriptor.lastIndexOf(')') + 1)
                            + "L" + lambdaAdapter.adapterIface() + ";";
                }
            }
            if (!constructorRedirects.isEmpty() && bootstrapMethodArguments != null) {
                for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                    if (!(bootstrapMethodArguments[i] instanceof org.objectweb.asm.Handle h)) continue;
                    if (h.getTag() != Opcodes.H_NEWINVOKESPECIAL || !"<init>".equals(h.getName())) continue;
                    if (preservesProviderReference(classOwnName, h.getOwner())) continue;
                    if (keepsOldName(h.getOwner())) continue;
                    String resolvedOwner = redirectedClass(h.getOwner());
                    String resolvedDesc = resolveDescriptor(h.getDesc());
                    ConstructorKey key = new ConstructorKey(resolvedOwner, resolvedDesc);
                    FactoryTarget factory = constructorRedirects.get(key);
                    if (factory == null && !resolvedDesc.equals(h.getDesc())) {
                        key = new ConstructorKey(resolvedOwner, h.getDesc());
                        factory = constructorRedirects.get(key);
                    }
                    if (factory != null && (keepsConstructor(resolvedOwner, resolvedDesc)
                            || skipsConstructorRedirect(key))) {
                        factory = null;
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
                    if (preservesProviderReference(classOwnName, h.getOwner())) continue;
                    String resolvedOwner = redirectedClass(h.getOwner());
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
            if (bootstrapMethodArguments != null
                    && "java/lang/invoke/LambdaMetafactory".equals(bootstrapMethodHandle.getOwner())) {
                for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                    if (!(bootstrapMethodArguments[i] instanceof org.objectweb.asm.Handle h)) continue;
                    if (preservesProviderReference(classOwnName, h.getOwner())) continue;
                    org.objectweb.asm.Handle retargeted = retargetMethodReference(h);
                    if (retargeted == null) continue;
                    String aligned = alignCapturedArguments(descriptor, retargeted);
                    if (aligned == null) continue;
                    descriptor = aligned;
                    bootstrapMethodArguments[i] = retargeted;
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            if (lambdaAdapter != null && lambdaAdapter.wrapOwner() != null) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, lambdaAdapter.wrapOwner(),
                        lambdaAdapter.wrapName(), lambdaAdapter.wrapDesc(), false);
            }

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
         * The method-reference form of a plain method redirect. {@code registry::register}
         * compiles to an {@code invokedynamic} whose implementation handle names the method, so
         * the call rewrite in {@link #visitMethodInsn} never sees it and the reference keeps
         * linking against the removed method. LambdaMetafactory binds a captured receiver as the
         * first argument of a static method, so an instance-to-static redirect becomes an
         * {@code H_INVOKESTATIC} handle. A redirect that keeps the receiver applies only when it
         * keeps the parameter types, because the metafactory casts a return value but never a
         * parameter. Without a redirect, the handle still takes the owner kind of a type that
         * changed between class and interface. Returns null when the handle stays as it is.
         */
        private org.objectweb.asm.Handle retargetMethodReference(org.objectweb.asm.Handle h) {
            int tag = h.getTag();
            boolean instance = tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE;
            if (!instance && tag != Opcodes.H_INVOKESTATIC) return null;
            MethodKey key = new MethodKey(h.getOwner(), h.getName(), h.getDesc());
            MethodTarget target = methodRedirects.get(key);
            if (target == null && !classRedirects.isEmpty()) {
                String resolvedDesc = resolveDescriptor(h.getDesc());
                if (!resolvedDesc.equals(h.getDesc())) {
                    key = new MethodKey(h.getOwner(), h.getName(), resolvedDesc);
                    target = methodRedirects.get(key);
                }
            }
            // A redirect for mods older than this one leaves its method references as written too.
            if (target != null && skipsCurrentMod(key)) target = null;
            if (target == null) {
                if (tag == Opcodes.H_INVOKEINTERFACE && classShimTargets.contains(h.getOwner())) {
                    return new org.objectweb.asm.Handle(Opcodes.H_INVOKEVIRTUAL,
                            h.getOwner(), h.getName(), h.getDesc(), false);
                }
                // A reference to an interface that became a class takes the class form.
                if (h.isInterface() && isKnownClass(h.getOwner())) {
                    return new org.objectweb.asm.Handle(tag == Opcodes.H_INVOKEINTERFACE ? Opcodes.H_INVOKEVIRTUAL : tag,
                            h.getOwner(), h.getName(), h.getDesc(), false);
                }
                // A reference to a class that became an interface takes the interface form its calls take.
                boolean becameInterface = !h.isInterface() && !classShimTargets.contains(h.getOwner())
                        && isKnownInterface(h.getOwner());
                if (becameInterface && tag == Opcodes.H_INVOKEVIRTUAL) {
                    return new org.objectweb.asm.Handle(Opcodes.H_INVOKEINTERFACE,
                            h.getOwner(), h.getName(), h.getDesc(), true);
                }
                if (becameInterface && tag == Opcodes.H_INVOKESTATIC) {
                    return new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                            h.getOwner(), h.getName(), h.getDesc(), true);
                }
                return null;
            }
            Type[] oldArgs = Type.getArgumentTypes(h.getDesc());
            Type[] newArgs = Type.getArgumentTypes(target.desc());
            boolean receiverBecomesArgument = instance
                    && (target.devirtualize() || newArgs.length == oldArgs.length + 1);
            if (receiverBecomesArgument) {
                return new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                        target.owner(), target.name(), target.desc(), false);
            }
            if (target.devirtualize()) return null;
            // A static helper may take Object where the old method took a game type. The
            // metafactory widens a parameter, it only never narrows one, so that reference holds.
            boolean widened = !instance && widensToObject(oldArgs, newArgs)
                    && Type.getReturnType(h.getDesc()).equals(Type.getReturnType(target.desc()));
            if (!Arrays.equals(oldArgs, newArgs) && !widened) return null;
            boolean targetIsInterface = !classShimTargets.contains(target.owner())
                    && (interfaceSyntheticTargets.contains(target.owner())
                        || isKnownInterface(target.owner())
                        || (target.owner().equals(h.getOwner()) && h.isInterface()));
            int newTag = !instance ? Opcodes.H_INVOKESTATIC
                    : targetIsInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
            return new org.objectweb.asm.Handle(newTag, target.owner(), target.name(),
                    target.desc(), targetIsInterface);
        }

        /** Whether each parameter that differs is a reference type the helper takes as Object. */
        private boolean widensToObject(Type[] oldArgs, Type[] newArgs) {
            if (oldArgs.length != newArgs.length) return false;
            for (int i = 0; i < oldArgs.length; i++) {
                if (oldArgs[i].equals(newArgs[i])) continue;
                boolean reference = oldArgs[i].getSort() == Type.OBJECT || oldArgs[i].getSort() == Type.ARRAY;
                if (!reference || !"java/lang/Object".equals(newArgs[i].getInternalName())) return false;
            }
            return true;
        }

        /**
         * The call site descriptor for a retargeted method reference. LambdaMetafactory requires
         * each captured argument to have exactly the type of the matching implementation
         * parameter, while a call accepts a subtype. A bound {@code registry::register} captures
         * the registry under its own type, so after a redirect to a helper that takes
         * {@code Object}, or to a method declared on a superclass, it fails to link with
         * {@code LambdaConversionException}. The captured types are widened to the
         * implementation's, which the verifier accepts exactly when it accepts the redirected
         * direct call. Returns null when a captured primitive would have to change type.
         */
        private static String alignCapturedArguments(String factoryDesc, org.objectweb.asm.Handle impl) {
            Type[] captured = Type.getArgumentTypes(factoryDesc);
            if (captured.length == 0) return factoryDesc;
            List<Type> implParams = new ArrayList<>();
            if (impl.getTag() != Opcodes.H_INVOKESTATIC) implParams.add(Type.getObjectType(impl.getOwner()));
            implParams.addAll(Arrays.asList(Type.getArgumentTypes(impl.getDesc())));
            if (captured.length > implParams.size()) return null;
            boolean changed = false;
            for (int i = 0; i < captured.length; i++) {
                Type wanted = implParams.get(i);
                if (captured[i].equals(wanted)) continue;
                if (!isReferenceType(captured[i]) || !isReferenceType(wanted)) return null;
                captured[i] = wanted;
                changed = true;
            }
            return changed ? Type.getMethodDescriptor(Type.getReturnType(factoryDesc), captured) : factoryDesc;
        }

        private static boolean isReferenceType(Type type) {
            return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
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

            if (preservesProviderReference(classOwnName, owner)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }

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
                String resolvedOwner = redirectedClass(owner);
                String resolvedDesc = resolveDescriptor(descriptor);
                ConstructorKey skey = new ConstructorKey(resolvedOwner, resolvedDesc);
                SuperCtorRedirect scr = superCtorRedirects.get(skey);
                if (scr == null) {
                    skey = new ConstructorKey(resolvedOwner, descriptor);
                    scr = superCtorRedirects.get(skey);
                }
                if (scr != null && keepsConstructor(resolvedOwner, resolvedDesc)) {
                    scr = null;
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
                    } else if (STACK_ADAPTER_MODE.equals(scr.extraFieldOwner())) {
                        java.util.function.Consumer<MethodVisitor> adapter =
                                superCtorStackAdapters.get(skey);
                        if (adapter != null) adapter.accept(mv);
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

            // A removed base built directly, as in `new SwordItem(...)`, needs the same absorbed
            // constructor a subclass's super(...) call gets, or it fails with NoSuchMethodError.
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)
                    && !generatedLegacyBases.isEmpty()) {
                String generated = generatedLegacyBases.containsKey(owner) ? owner : null;
                SuperclassRedirect removed = generated == null ? superclassRedirects.get(owner) : null;
                if (removed != null && generatedLegacyBases.containsKey(removed.newSuperclass())) {
                    generated = removed.newSuperclass();
                }
                if (generated != null) absorbLegacyCtor(generated, descriptor);
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
                // A rebase keyed on a redirect's destination moves only the classes the header moved.
                if (rebase != null && rebasesThroughRedirects.containsKey(owner)
                        && !rebase.newSuperclass().equals(classSuperName)) {
                    rebase = null;
                }
                if (rebase != null && rebase.rewriteSuperCtor()
                        && absorbsOrIsNotGenerated(rebase.newSuperclass(), descriptor)) {
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

            // javac may record a mod subclass as the owner of an inherited call. Exact aliases
            // opted into hierarchy matching can still apply, but only after the jar or MC index
            // proves the registered base owner is in that subclass's hierarchy.
            MethodTarget inheritedTarget = inheritedMethodTarget(
                    opcode, owner, name, descriptor);

            // Fast path: owner not in the redirect set. Still try pattern heuristics and fuzzy
            // resolution for unresolved references, since they cover the whole MC API surface.
            if (!methodRedirectOwners.contains(owner) && !argDropOwners.contains(owner)
                    && !convertingOwners.contains(owner) && !singletonOwners.contains(owner)
                    && inheritedTarget == null) {
                // JVM special methods must never enter heuristic resolution. In particular,
                // rewriting a constructor call to an ordinary ()V method leaves the value from
                // NEW uninitialized and produces a VerifyError. Exact constructor redirects are
                // handled above by the constructor pre-pass and super-constructor table.
                boolean heuristicCandidate = name == null || !name.startsWith("<");

                // Try pattern heuristics FIRST; faster and more reliable than fuzzy
                PatternHeuristics patterns = patternHeuristicsForHost();
                if (heuristicCandidate && patterns != null) {
                    PatternHeuristics.PatternResult patternMatch = patterns.resolveMethod(owner, name, descriptor);
                    // A name the host still has is not stale, as the fuzzy resolver also checks.
                    if (patternMatch != null && patternMatch.confidence() >= 0.6
                            && !hostHasMethod(owner, name, descriptor)) {
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
                if (heuristicCandidate && resolver != null && resolver.isIndexed()
                        && (owner.startsWith("net/minecraft/") || owner.startsWith("com/mojang/"))) {
                    FuzzyMethodResolver.MethodInfo fuzzyMatch =
                            resolver.resolveMethod(owner, name, descriptor, callers(), this::callerMayExtend);
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
            // A call on a class the current mod keeps under its old name takes none of the redirects
            // keyed on that class, including the ones found through a resolved descriptor below. A
            // method it inherits from another class still takes the redirect the hierarchy found.
            boolean keptOwner = keepsOldName(owner);
            MethodTarget target = keptOwner ? inheritedTarget : methodRedirects.get(key);
            if (keptOwner && target == null) {
                emitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            // Leave the call as written when a wrapping helper would call it back virtually from
            // a super call, or when the redirect is for mods older than this one.
            if (!keptOwner && target != null && ((opcode == Opcodes.INVOKESPECIAL
                    && wrappingMethodRedirects.contains(key)) || skipsCurrentMod(key))) {
                emitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }

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
            // A static call has no receiver to pass, so a redirect that takes or moves one cannot apply,
            // as in inheritedMethodTarget. A newer mod calls the static method that replaced an instance
            // one under the same name, as with Forge 26.2's ModLoader.addWarning.
            if (target != null && opcode == Opcodes.INVOKESTATIC && takesReceiverAsFirstArgument(target, descriptor)) {
                target = null;
            }
            if (target == null) {
                target = inheritedTarget;
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
                        int unwrapOpcode = conv.unwrapStatic() ? Opcodes.INVOKESTATIC
                                : conv.unwrapItf() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                        super.visitMethodInsn(unwrapOpcode, conv.unwrapOwner(), conv.unwrapName(),
                                conv.unwrapDesc(), conv.unwrapItf());
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
            // Only an invokestatic qualifies. The rewrite emits an INVOKEVIRTUAL whose owner, name
            // and descriptor can be identical to what was matched (FMLLoader.getLoadingModList()
            // kept all three when it stopped being static), and a class is visited more than once,
            // so without this the rewrite matches its own output and pushes a second receiver every
            // pass. Checking the opcode is also what the registration already promises.
            if (target == null && opcode == Opcodes.INVOKESTATIC && !singletonRedirects.isEmpty()) {
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

            // A super call redirected to a helper that runs the receiver's override goes to the
            // helper's super variant, so the override does not run again.
            if (target != null && opcode == Opcodes.INVOKESPECIAL && !superCallHelperNames.isEmpty()) {
                String superName = superCallHelperNames.get(
                        new MethodKey(target.owner(), target.name(), target.desc()));
                if (superName != null) {
                    target = new MethodTarget(target.owner(), superName, target.desc(),
                            target.devirtualize());
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
                        && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
                                || (opcode == Opcodes.INVOKESPECIAL && !name.startsWith("<")))
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
                    discardUnexpectedResult(origReturn, newReturn);
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
                    discardUnexpectedResult(origReturn, newReturn);
                }
            } else {
                // No hardcoded redirect found; try pattern heuristics first (fast, deterministic),
                // then fuzzy resolver as last resort (slower, probabilistic).
                // Neither will EVER override a hardcoded redirect (we only get here if none matched).

                // Pattern heuristics: deterministic naming convention rules (e.g., render* -> extract*)
                boolean heuristicCandidate = name == null || !name.startsWith("<");
                PatternHeuristics patterns = patternHeuristicsForHost();
                if (heuristicCandidate && patterns != null) {
                    PatternHeuristics.PatternResult patternMatch = patterns.resolveMethod(owner, name, descriptor);
                    // A name the host still has is not stale, as the fuzzy resolver also checks.
                    if (patternMatch != null && patternMatch.confidence() >= 0.6
                            && !hostHasMethod(owner, name, descriptor)) {
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
                if (heuristicCandidate && resolver != null && resolver.isIndexed()) {
                    FuzzyMethodResolver.MethodInfo fuzzyMatch =
                            resolver.resolveMethod(owner, name, descriptor, callers(), this::callerMayExtend);
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
        private boolean isPrimitive(Type type) {
            return type.getSort() < Type.ARRAY;
        }

        /** Boxes the primitive on top of the stack with its wrapper's valueOf. */
        private void box(Type primitive) {
            String wrapper = wrapperOf(primitive);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                    "(" + primitive.getDescriptor() + ")L" + wrapper + ";", false);
        }

        /** Casts the object on top of the stack to the primitive's wrapper and reads the primitive out. */
        private void unbox(Type primitive) {
            String wrapper = wrapperOf(primitive);
            super.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, primitive.getClassName() + "Value",
                    "()" + primitive.getDescriptor(), false);
        }

        private String wrapperOf(Type primitive) {
            return switch (primitive.getSort()) {
                case Type.BOOLEAN -> "java/lang/Boolean";
                case Type.CHAR -> "java/lang/Character";
                case Type.BYTE -> "java/lang/Byte";
                case Type.SHORT -> "java/lang/Short";
                case Type.INT -> "java/lang/Integer";
                case Type.FLOAT -> "java/lang/Float";
                case Type.LONG -> "java/lang/Long";
                case Type.DOUBLE -> "java/lang/Double";
                default -> throw new IllegalArgumentException("not a primitive: " + primitive);
            };
        }

        private List<String> callers;

        /** The classes this code runs in: every class a mixin is merged into, or the class itself. */
        private List<String> callers() {
            if (callers == null) {
                boolean official = RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION);
                List<String> targets = new ArrayList<>();
                for (String target : mixinTargets(classOwnName, currentJarClassBytesProvider())) {
                    // A Fabric jar names its targets in intermediary, which a 26.x host no longer uses.
                    targets.add(official ? com.retromod.mapping.IntermediaryToMojangMapper.getInstance().mapClass(target)
                            : target);
                }
                callers = targets.isEmpty() ? List.of(classOwnName) : targets;
            }
            return callers;
        }

        /** Whether {@code caller} may extend {@code ancestor}: proven, or its parents cannot all be read. */
        private boolean callerMayExtend(String caller, String ancestor) {
            return inheritsFrom(caller, ancestor) || !inheritsFrom(caller, "java/lang/Object");
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            pendingIndyConsumerType = null;
            if (preservesProviderReference(classOwnName, owner)) {
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }
            // Don't flush; field accesses can be constructor arguments
            FieldKey key = new FieldKey(owner, name);

            // A removed static field that is read and written through a bridge's getter and setter.
            FieldStaticBridge staticBridge = (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC)
                    && !staticFieldBridges.isEmpty() ? staticFieldBridges.get(key) : null;
            if (staticBridge != null) {
                Type field = Type.getType(descriptor);
                // A read-only bridge leaves a write as written.
                if (opcode == Opcodes.PUTSTATIC && staticBridge.setterName() == null) {
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                    return;
                }
                if (opcode == Opcodes.PUTSTATIC) {
                    // A primitive value is boxed when the setter takes an object.
                    Type taken = Type.getArgumentTypes(staticBridge.setterDesc())[0];
                    if (isPrimitive(field) && !isPrimitive(taken)) box(field);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, staticBridge.owner(), staticBridge.setterName(),
                            staticBridge.setterDesc(), false);
                    return;
                }
                super.visitMethodInsn(Opcodes.INVOKESTATIC, staticBridge.owner(), staticBridge.getterName(),
                        staticBridge.getterDesc(), false);
                // A wider result is cast back to the field's type, and unboxed for a primitive field.
                if (!field.equals(Type.getReturnType(staticBridge.getterDesc()))) {
                    if (isPrimitive(field)) {
                        unbox(field);
                    } else {
                        super.visitTypeInsn(Opcodes.CHECKCAST, field.getInternalName());
                    }
                }
                return;
            }

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
                StaticFieldTypeDrift drift = staticFieldTypeDrift(owner, descriptor);
                // An exact redirect of this read wins, for a constant the host removed outright,
                // such as BuiltInLootTables.EMPTY from 1.21.2.
                if (drift != null && !descriptorFieldRedirects.containsKey(owner + "." + name + ":" + descriptor)) {
                    // The read arrives after the remap, so the new-typed field still needs its
                    // own host name: an SRG host keys member names by descriptor.
                    super.visitFieldInsn(Opcodes.GETSTATIC, owner,
                            remapQualifiedFieldName(owner, name, drift.newDesc()), drift.newDesc());
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, drift.converterOwner(),
                            drift.converterName(), drift.converterDesc(), false);
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

            if (opcode == Opcodes.GETFIELD && !uniqueFieldGetters.isEmpty()) {
                MethodTarget getter = uniqueFieldGetters.get(name + ':' + descriptor);
                if (getter != null) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, getter.owner(), getter.name(),
                            getter.desc(), false);
                    return;
                }
            }
            if ((opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD) && !uniqueFieldStaticBridges.isEmpty()) {
                FieldStaticBridge unique = uniqueFieldStaticBridges.get(name + ':' + descriptor);
                if (unique != null) {
                    boolean read = opcode == Opcodes.GETFIELD;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, unique.owner(),
                            read ? unique.getterName() : unique.setterName(),
                            read ? unique.getterDesc() : unique.setterDesc(), false);
                    return;
                }
            }

            // Check field-to-accessor redirects first (GETFIELD→getter, PUTFIELD→setter)
            FieldAccessorTarget accessor = fieldAccessorRedirects.get(key);
            // An access that names a subclass as the owner, as javac writes player.swinging.
            if (accessor == null && (opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD)) {
                accessor = inheritedFieldAccessor(owner, name, descriptor);
            }
            if (accessor != null) {
                if (opcode == Opcodes.GETFIELD) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            accessor.getterOwner(), accessor.getterName(), accessor.getterDesc(), false);
                    LOGGER.trace("Field accessor redirect: GETFIELD {}.{} -> {}.{}()",
                            owner, name, accessor.getterOwner(), accessor.getterName());
                } else if (opcode == Opcodes.PUTFIELD && accessor.setterName() != null) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            accessor.setterOwner(), accessor.setterName(), accessor.setterDesc(), false);
                    LOGGER.trace("Field accessor redirect: PUTFIELD {}.{} -> {}.{}()",
                            owner, name, accessor.setterOwner(), accessor.setterName());
                } else {
                    // Anything else stays as written, including a write through a read-only bridge.
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                }
                return;
            }

            // Field moved behind a public sibling field's accessors (26.2 Minecraft.screen ->
            // mc.gui.screen()/setScreen). Pure-bytecode hop: no reflection on hot paths.
            FieldHopAccessor hop = fieldHopAccessors.get(key);
            if (hop != null) {
                if (opcode == Opcodes.GETFIELD) {
                    // [recv] -> [hopObj] -> getter(). While hopObj is still null (26.2 builds
                    // Minecraft.gui after mods construct) the old field read gave its default, so this does too.
                    markFrameInvalidatingRewrite();
                    Label absent = new Label();
                    Label done = new Label();
                    super.visitFieldInsn(Opcodes.GETFIELD, owner, hop.hopField(), hop.hopDesc());
                    super.visitInsn(Opcodes.DUP);
                    super.visitJumpInsn(Opcodes.IFNULL, absent);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            hop.hopClass(), hop.getterName(), hop.getterDesc(), false);
                    super.visitJumpInsn(Opcodes.GOTO, done);
                    super.visitLabel(absent);
                    super.visitInsn(Opcodes.POP);
                    super.visitInsn(defaultValueOpcode(descriptor));
                    super.visitLabel(done);
                    LOGGER.trace("Field hop redirect: GETFIELD {}.{} -> .{}.{}()",
                            owner, name, hop.hopField(), hop.getterName());
                    return;
                }
                if (opcode == Opcodes.PUTFIELD && hop.setterName() != null) {
                    // [recv, value] -> SWAP -> GETFIELD hop -> SWAP -> [hopObj, value] -> setter().
                    // Registration rejects long/double field types, so SWAP and POP2 are always legal.
                    markFrameInvalidatingRewrite();
                    Label absent = new Label();
                    Label done = new Label();
                    super.visitInsn(Opcodes.SWAP);
                    super.visitFieldInsn(Opcodes.GETFIELD, owner, hop.hopField(), hop.hopDesc());
                    super.visitInsn(Opcodes.DUP);
                    super.visitJumpInsn(Opcodes.IFNULL, absent);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            hop.hopClass(), hop.setterName(), hop.setterDesc(), false);
                    super.visitJumpInsn(Opcodes.GOTO, done);
                    // With no hopObj yet the write has nowhere to go, so the value and the null are dropped.
                    super.visitLabel(absent);
                    super.visitInsn(Opcodes.POP2);
                    super.visitLabel(done);
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
            if (bridge != null && !staticBridgeTakesType(key, descriptor)) bridge = null;
            if (bridge == null && !inheritedFieldStaticBridges.isEmpty()
                    && (opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD)) {
                bridge = inheritedFieldStaticBridge(owner, name, descriptor);
            }
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
                // A write goes to the setter; a read-only bridge leaves it as written below.
                if (opcode == Opcodes.PUTFIELD && bridge.setterName() != null) {
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
            String typedRename = typedFieldRenames.isEmpty() ? null
                    : typedFieldRenames.get(owner + "." + name + ":" + descriptor);
            if (typedRename != null) {
                super.visitFieldInsn(opcode, owner, typedRename, descriptor);
                return;
            }

            FieldTarget target = descriptorFieldRedirects.isEmpty() ? null
                    : descriptorFieldRedirects.get(owner + "." + name + ":" + descriptor);
            if (target == null) target = fieldRedirects.get(key);
            // A constant read through a mod's own subtype, as javac writes one in an implementing class.
            if (target == null && (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC)) {
                target = inheritedStaticFieldTarget(owner, name, descriptor);
            }
            // A class redirect may already have rewritten the read's type, so both sides resolve.
            if (target != null && target.oldDesc != null && !target.oldDesc.equals(descriptor)
                    && !resolveDescriptor(target.oldDesc).equals(resolveDescriptor(descriptor))
                    && exactDescriptorFieldRedirects.contains(key)) {
                target = null;
            }
            if (target != null && fieldTypeChangedSinceRedirect(target, descriptor)) {
                // The redirect was written for an older type of this field. A newer mod reads the
                // same name with its current type: 1.20.5 turned MobEffects constants from
                // MobEffect into Holder, and the lookup's MobEffect cast to Holder threw
                // ClassCastException in Bountiful Fares' block registration. The field still
                // exists with that type, so the read stays as written.
                super.visitFieldInsn(opcode, owner, name, descriptor);
                return;
            }

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
                            && !descriptor.equals(newReturn) && !standInFieldGetters.contains(key)) {
                        String origClass = descriptor.substring(1, descriptor.length() - 1);
                        super.visitTypeInsn(Opcodes.CHECKCAST, origClass);
                    }
                } else {
                    // Standard field-to-field redirect
                    // Use new descriptor if provided, otherwise keep original
                    String newDescriptor = (target.newDesc != null) ? target.newDesc : descriptor;
                    // A value of another type on the stack makes javac's frames for this method stale.
                    if (!resolveDescriptor(newDescriptor).equals(resolveDescriptor(descriptor))) {
                        markFrameInvalidatingRewrite();
                    }
                    super.visitFieldInsn(opcode, target.owner, target.name, newDescriptor);
                }
            } else {
                MethodTarget libraryReader = opcode == Opcodes.GETSTATIC
                        ? libraryStaticFieldReaders.get(descriptor) : null;
                if (libraryReader != null && readsOtherModField(owner, name, descriptor, classOwnName)) {
                    super.visitLdcInsn(Type.getObjectType(owner));
                    super.visitLdcInsn(name);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, libraryReader.owner(), libraryReader.name(),
                            libraryReader.desc(), false);
                    super.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(descriptor).getInternalName());
                    LOGGER.trace("Library field read by name: {}.{} -> {}.{}",
                            owner, name, libraryReader.owner(), libraryReader.name());
                    return;
                }
                // No hardcoded field redirect; try fuzzy resolver as last resort.
                FuzzyMethodResolver resolver = fuzzyResolver;
                if (resolver != null && resolver.isIndexed()) {
                    FuzzyMethodResolver.FieldInfo fuzzyMatch = resolver.resolveField(owner, name, descriptor,
                            opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC, callers(),
                            this::callerMayExtend);
                    if (fuzzyMatch != null) {
                        LOGGER.debug("[Retromod-Fuzzy] Resolved field {}.{} {} -> {}.{} {} (confidence: {}%)",
                                owner, name, descriptor,
                                fuzzyMatch.owner(), fuzzyMatch.name(), fuzzyMatch.descriptor(),
                                fuzzyMatch.score());
                        // A match at another type leaves javac's frames stale too.
                        if (!fuzzyMatch.descriptor().equals(descriptor)) markFrameInvalidatingRewrite();
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
    
    /**
     * Whether a field-to-method redirect registered for one field type meets a read of a
     * different object type. Only field-to-method redirects are checked, because their helper
     * returns a value of the registered type and the inserted cast to the read type then fails.
     */
    private boolean fieldTypeChangedSinceRedirect(FieldTarget target, String descriptor) {
        if (target.oldDesc() == null || target.newDesc() == null
                || !target.newDesc().startsWith("(") || descriptor == null) {
            return false;
        }
        if (!target.oldDesc().startsWith("L") || !descriptor.startsWith("L")) return false;
        return !descriptor.equals(target.oldDesc())
                && !resolveDescriptor(descriptor).equals(resolveDescriptor(target.oldDesc()));
    }

    // Key/target records for redirect lookups; records give equals()/hashCode() for free,
    // which matters since these are looked up on every method/field instruction.

    /** Lookup key for method redirects: matches (owner class, method name, descriptor). */
    public record MethodKey(String owner, String name, String desc) {}
    /** Exact name and descriptor shared by hierarchy-aware redirect candidates. */
    private record MethodShape(String name, String desc) {}
    /** Base owner that must be proven in the call owner's hierarchy before retargeting. */
    private record InheritedMethodRedirect(String oldOwner, MethodTarget target) {}
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
            String unwrapOwner, String unwrapName, String unwrapDesc, boolean unwrapItf,
            boolean unwrapStatic) {
        /** Converting target whose unwrap is an instance call. */
        public ConvertingTarget(String owner, String name, String desc,
                int argConvOpcode, int retAdaptOpcode,
                String unwrapOwner, String unwrapName, String unwrapDesc, boolean unwrapItf) {
            this(owner, name, desc, argConvOpcode, retAdaptOpcode, unwrapOwner, unwrapName, unwrapDesc,
                    unwrapItf, false);
        }
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
    private record FieldDestination(String owner, String name, String descriptor) {}
    /** A field accessor proven to apply to accesses naming a subclass of {@code fieldOwner}. */
    private record InheritedFieldAccessor(String fieldOwner, FieldAccessorTarget target) {}

    private record InheritedFieldStaticBridge(String fieldOwner, FieldStaticBridge bridge) {}

    private record InheritedStaticField(String owner, String desc) {}
    /** Target of a field accessor redirect; a null setter makes the bridge read-only. */
    public record FieldAccessorTarget(
        String getterOwner, String getterName, String getterDesc,
        String setterOwner, String setterName, String setterDesc
    ) {}
    /**
     * Target of a field hop accessor: GETFIELD becomes GETFIELD hopField + INVOKEVIRTUAL getter on
     * the hop's class; PUTFIELD becomes SWAP + GETFIELD hopField + SWAP + INVOKEVIRTUAL setter.
     * Both skip the call while hopField is null: a read gives the field type's default and a write
     * does nothing. {@code hopClass} is precomputed from {@code hopDesc}. {@code setterName} null = read-only hop.
     */
    public record FieldHopAccessor(
        String hopField, String hopDesc, String hopClass,
        String getterName, String getterDesc,
        String setterName, String setterDesc
    ) {}

    /** The instruction that pushes a field type's default value: null, 0, or false. */
    private static int defaultValueOpcode(String fieldDescriptor) {
        return switch (fieldDescriptor.charAt(0)) {
            case 'J' -> Opcodes.LCONST_0;
            case 'D' -> Opcodes.DCONST_0;
            case 'F' -> Opcodes.FCONST_0;
            case 'I', 'Z', 'B', 'C', 'S' -> Opcodes.ICONST_0;
            default -> Opcodes.ACONST_NULL;
        };
    }
    /** A static field whose type changed under a package; see registerStaticFieldTypeDrift. */
    public record StaticFieldTypeDrift(
        String ownerPrefix, String oldDesc, String newDesc,
        String converterOwner, String converterName, String converterDesc
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

    /**
     * A generated stand-in for a deleted base class, plus every {@code super(...)} descriptor seen
     * so far. The descriptor set grows as mods are transformed, and the generated bytes are rebuilt
     * each time it does, so the stand-in ends up carrying exactly the constructors the mods in this
     * run actually call rather than a guessed list of historical shapes.
     */
    private record LegacyBaseSpec(String modernBase, String modernCtorDesc,
            java.util.Set<String> observedCtorDescs, LegacyBaseSynthesizer.ArgumentAdapter adapter) {}
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
    
    // Loader API runtime ownership. Registering last is not enough: a later shim or GUI
    // registration would replace the runtime's entries, and some side tables are not last-wins.
    // These sets stay empty while the runtime is off, so every admission check returns at once.

    private final Set<String> claimedTypes = ConcurrentHashMap.newKeySet();
    private final Set<RedirectKey> claimedMembers = ConcurrentHashMap.newKeySet();
    /** {@code owner#name} for every claimed method and field, for the name-only tables. */
    private final Set<String> claimedMemberNames = ConcurrentHashMap.newKeySet();
    private final Set<RedirectKey> suppressedKeys = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<int[]> ownedScopeDepth = ThreadLocal.withInitial(() -> new int[1]);
    /** Bumped whenever every registration is cleared, so the runtime knows to register again. */
    private final AtomicInteger registrationEpoch = new AtomicInteger();
    private volatile PreTransformHook preTransformHook;

    /** Runs before each {@link #transformClass}; installed only while the loader API runtime is on. */
    @FunctionalInterface
    public interface PreTransformHook {
        void beforeTransform(RetromodTransformer transformer);
    }

    /** An owned registration scope; registrations inside it may use claimed keys. */
    public final class OwnedScope implements AutoCloseable {
        private boolean closed;

        private OwnedScope() {
            ownedScopeDepth.get()[0]++;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            ownedScopeDepth.get()[0]--;
        }
    }

    /**
     * Opens a scope in which registrations for claimed keys are accepted. Only the loader API
     * runtime opens one; everything else registers outside it.
     */
    public OwnedScope ownedRegistrations() {
        return new OwnedScope();
    }

    /**
     * Takes ownership of {@code key}: clears every table entry registered for it and refuses later
     * registrations for it outside an owned scope. A type key also covers every member keyed on
     * that type. A method claim removes a name-only rename on the same owner and name, because that
     * rename would rewrite the claimed call before its redirect could match.
     */
    public synchronized void claim(RedirectKey key) {
        Objects.requireNonNull(key, "key");
        switch (key.kind()) {
            case TYPE -> claimedTypes.add(key.owner());
            case METHOD, FIELD -> {
                claimedMembers.add(key);
                claimedMemberNames.add(key.owner() + "#" + key.name());
            }
        }
        purgeRegistrations(key);
        LOGGER.debug("Loader API runtime claimed {}", key);
    }

    /**
     * Claims {@code key} and records that the host keeps its own member, so nothing redirects it.
     * Use it where a shim rewrites a member the host still has.
     */
    public synchronized void suppress(RedirectKey key) {
        claim(key);
        suppressedKeys.add(key);
    }

    /** Gives up ownership of {@code key} and clears what was registered for it. */
    public synchronized void release(RedirectKey key) {
        purgeRegistrations(key);
        suppressedKeys.remove(key);
        if (key.kind() == RedirectKey.Kind.TYPE) {
            claimedTypes.remove(key.owner());
        } else if (claimedMembers.remove(key)) {
            String name = key.owner() + "#" + key.name();
            if (claimedMembers.stream().noneMatch(k -> name.equals(k.owner() + "#" + k.name()))) {
                claimedMemberNames.remove(name);
            }
        }
    }

    /** Every claimed type, for reports and tests. */
    public Set<String> claimedTypes() {
        return Collections.unmodifiableSet(claimedTypes);
    }

    /** Every claimed method and field, for reports and tests. */
    public Set<RedirectKey> claimedMembers() {
        return Collections.unmodifiableSet(claimedMembers);
    }

    /** Every key claimed through {@link #suppress}. */
    public Set<RedirectKey> suppressedKeys() {
        return Collections.unmodifiableSet(suppressedKeys);
    }

    /** Changes each time every registration is cleared. */
    public int registrationEpoch() {
        return registrationEpoch.get();
    }

    /** Installs or removes the hook that runs before each class transform. */
    public void setPreTransformHook(PreTransformHook hook) {
        this.preTransformHook = hook;
    }

    /** Asks for the next transform to drop redirects to Retromod classes that do not exist. */
    public void requestTargetRevalidation() {
        targetsValidated.set(false);
    }

    private boolean inOwnedScope() {
        return ownedScopeDepth.get()[0] > 0;
    }

    private boolean nothingClaimed() {
        return claimedTypes.isEmpty() && claimedMembers.isEmpty();
    }

    /** Whether a registration keyed on type {@code owner} may proceed. */
    private boolean admitsType(String owner) {
        if (nothingClaimed() || inOwnedScope() || owner == null) return true;
        if (!claimedTypes.contains(owner)) return true;
        LOGGER.debug("Ignored registration for {}: the loader API runtime owns it", owner);
        return false;
    }

    /** Whether a registration keyed on one method may proceed. */
    private boolean admitsMethod(String owner, String name, String desc) {
        if (nothingClaimed() || inOwnedScope() || owner == null) return true;
        if (!claimedTypes.contains(owner)
                && !claimedMembers.contains(RedirectKey.method(owner, name, desc))) {
            return true;
        }
        LOGGER.debug("Ignored registration for {}.{}{}: the loader API runtime owns it",
                owner, name, desc);
        return false;
    }

    /** Whether a name-only registration, such as a rename, may proceed. */
    private boolean admitsMemberName(String owner, String name) {
        if (nothingClaimed() || inOwnedScope() || owner == null) return true;
        if (!claimedTypes.contains(owner) && !claimedMemberNames.contains(owner + "#" + name)) {
            return true;
        }
        LOGGER.debug("Ignored registration for {}.{}: the loader API runtime owns it", owner, name);
        return false;
    }

    /** Whether a registration keyed on one field may proceed. */
    private boolean admitsField(String owner, String name) {
        if (nothingClaimed() || inOwnedScope() || owner == null) return true;
        if (!claimedTypes.contains(owner)
                && !claimedMembers.contains(RedirectKey.field(owner, name))) {
            return true;
        }
        LOGGER.debug("Ignored registration for {}.{}: the loader API runtime owns it", owner, name);
        return false;
    }

    /** Clears every entry registered for {@code key}, in the main and side tables alike. */
    private void purgeRegistrations(RedirectKey key) {
        String owner = key.owner();
        switch (key.kind()) {
            case TYPE -> {
                // Members first: their remapped aliases are found through the class redirect.
                purgeMethods(k -> k.owner().equals(owner));
                constructorRedirects.keySet().removeIf(k -> k.className().equals(owner));
                superCtorRedirects.keySet().removeIf(k -> k.className().equals(owner));
                superCtorStackAdapters.keySet().removeIf(k -> k.className().equals(owner));
                purgeFields(k -> k.owner().equals(owner));
                mojangMethodRenames.keySet().removeIf(k -> k.startsWith(owner + "#"));
                functionalInterfaceRenames.keySet().removeIf(k -> k.startsWith(owner + "#"));
                lambdaAdapters.keySet().removeIf(k -> k.startsWith(owner + "#"));
                classRedirects.remove(owner);
                legacyOnlyClassRedirects.remove(owner);
                libraryYieldingClassRedirects.remove(owner);
                implementedInterfaceRewrites.remove(owner);
                implementedMethodBridges.remove(owner);
                interfaceDefaultBridges.remove(owner);
                superclassRedirects.remove(owner);
                rebasesThroughRedirects.remove(owner);
                staticFieldNullers.remove(owner);
                if (syntheticClasses.remove(owner) != null) {
                    embeddedShimClasses.remove(owner.replace('/', '.'));
                    interfaceSyntheticTargets.remove(owner);
                    classShimTargets.remove(owner);
                }
            }
            case METHOD -> {
                String name = key.name();
                String desc = key.desc();
                if ("<init>".equals(name)) {
                    ConstructorKey ctor = new ConstructorKey(owner, desc);
                    constructorRedirects.remove(ctor);
                    superCtorRedirects.remove(ctor);
                    superCtorStackAdapters.remove(ctor);
                } else {
                    purgeMethods(k -> k.owner().equals(owner) && k.name().equals(name)
                            && k.desc().equals(desc));
                    lambdaAdapters.remove(owner + "#" + name + "#" + desc);
                    mojangMethodRenames.remove(owner + "#" + name);
                    functionalInterfaceRenames.remove(owner + "#" + name);
                }
            }
            case FIELD -> {
                String name = key.name();
                purgeFields(k -> k.owner().equals(owner) && k.name().equals(name));
            }
        }
        // Cached views of the tables must be rebuilt from what is left.
        classRedirectsVersion.incrementAndGet();
        cachedRemapper = null;
        reflectionRemapper = null;
    }

    private void purgeMethods(java.util.function.Predicate<MethodKey> matches) {
        for (Map.Entry<MethodKey, MethodTarget> entry : new ArrayList<>(methodRedirects.entrySet())) {
            MethodKey key = entry.getKey();
            if (!matches.test(key)) continue;
            MethodTarget target = entry.getValue();
            methodRedirects.remove(key, target);
            // The alias that registerRemappedMethodAlias derived from this entry goes with it.
            String remappedOwner = classRedirects.getOrDefault(key.owner(), key.owner());
            MethodKey alias = new MethodKey(remappedOwner, key.name(), resolveDescriptor(key.desc()));
            if (!alias.equals(key)) methodRedirects.remove(alias, target);
            superCallHelperNames.remove(new MethodKey(target.owner(), target.name(), target.desc()));
        }
        wrappingMethodRedirects.removeIf(matches);
        legacyOnlyRedirects.keySet().removeIf(matches);
        neutralizedMethods.removeIf(matches);
        argDropRedirects.keySet().removeIf(matches);
        convertingRedirects.keySet().removeIf(matches);
        singletonRedirects.keySet().removeIf(matches);
        indyTypedRewrites.keySet().removeIf(matches);
        for (Map.Entry<MethodShape, Set<InheritedMethodRedirect>> entry
                : inheritedMethodRedirects.entrySet()) {
            MethodShape shape = entry.getKey();
            entry.getValue().removeIf(inherited -> matches.test(
                    new MethodKey(inherited.oldOwner(), shape.name(), shape.desc())));
        }
    }

    private void purgeFields(java.util.function.Predicate<FieldKey> matches) {
        fieldRedirects.keySet().removeIf(matches);
        exactDescriptorFieldRedirects.removeIf(matches);
        standInFieldGetters.removeIf(matches);
        fieldAccessorRedirects.keySet().removeIf(matches);
        staticFieldAccessors.keySet().removeIf(matches);
        fieldHopAccessors.keySet().removeIf(matches);
        fieldStaticBridges.keySet().removeIf(matches);
        fieldStaticBridgeTypes.keySet().removeIf(matches);
        typedFieldRenames.keySet().removeIf(typed -> {
            int dot = typed.lastIndexOf('.', typed.indexOf(':'));
            return dot > 0 && matches.test(new FieldKey(typed.substring(0, dot),
                    typed.substring(dot + 1, typed.indexOf(':'))));
        });
        descriptorFieldRedirects.keySet().removeIf(typed -> {
            int dot = typed.lastIndexOf('.', typed.indexOf(':'));
            return dot > 0 && matches.test(new FieldKey(typed.substring(0, dot),
                    typed.substring(dot + 1, typed.indexOf(':'))));
        });
        for (Map.Entry<String, Set<InheritedFieldAccessor>> entry : inheritedFieldAccessors.entrySet()) {
            String fieldName = entry.getKey().substring(0, entry.getKey().indexOf(':'));
            entry.getValue().removeIf(inherited -> matches.test(
                    new FieldKey(inherited.fieldOwner(), fieldName)));
        }
        inheritedStaticFields.forEach((fieldName, entries) -> entries.removeIf(
                inherited -> matches.test(new FieldKey(inherited.owner(), fieldName))));
        for (Map.Entry<String, Set<InheritedFieldStaticBridge>> entry : inheritedFieldStaticBridges.entrySet()) {
            String fieldName = entry.getKey().substring(0, entry.getKey().indexOf(':'));
            entry.getValue().removeIf(inherited -> matches.test(new FieldKey(inherited.fieldOwner(), fieldName)));
        }
    }

    /**
     * A SHA-256 over every registration table, including the static mixin registries. Two
     * transformers with the same digest rewrite every class the same way.
     */
    public String registrationDigest() {
        return RegistrationDigest.sha256(registrationLines());
    }

    /** The sorted lines behind {@link #registrationDigest}, one per table entry, for diffs. */
    public List<String> registrationLines() {
        return RegistrationDigest.lines(this);
    }

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

    /**
     * The class redirects Retromod's own synthetic and rebuilt classes take. They are written for the host,
     * so a redirect limited to older mods, which marks a name the host has again, never applies to them.
     */
    public Map<String, String> getClassRedirectsForHostCode() {
        if (legacyOnlyClassRedirects.isEmpty()) return getClassRedirects();
        Map<String, String> forHost = new HashMap<>(classRedirects);
        forHost.keySet().removeAll(legacyOnlyClassRedirects.keySet());
        return Collections.unmodifiableMap(forHost);
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
