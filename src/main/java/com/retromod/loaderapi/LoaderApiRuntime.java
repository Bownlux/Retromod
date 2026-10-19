/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import com.google.gson.JsonPrimitive;
import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodConfig;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The loader API runtime switch and the one hook every transform path calls first. */
public final class LoaderApiRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");

    /** Manifest attribute naming the runtime rows a Fabric jar was translated with. */
    public static final String MANIFEST_ATTRIBUTE = "Retromod-Loader-Api-Runtime";

    private static final Object LOCK = new Object();
    private static volatile LoaderApiSetting.Effective effective;
    private static volatile LoaderApiContext defaultContext =
            new LoaderApiContext("unknown", null, LoaderApiContext.TransformPath.CLASS_LOAD, null);
    private static volatile List<LoaderApiProvider> providers;
    private static final Map<RetromodTransformer, Applied> APPLIED = new ConcurrentHashMap<>();

    /** One provider's registrations on one transformer. */
    public record Ledger(String identity, Set<RedirectKey> declared, Set<RedirectKey> suppressed) {}

    /** What one context registered. */
    private record ContextRows(LoaderApiContext context, List<Ledger> ledgers) {}

    /**
     * What was registered in one registration epoch, per context, in registration order. A Forge
     * or unknown context adds to the Fabric rows instead of replacing them, since one transformer
     * serves every jar a process translates.
     */
    private record Applied(int epoch, List<ContextRows> contexts) {

        boolean covers(LoaderApiContext context) {
            String key = context.applicabilityKey();
            return contexts.stream().anyMatch(rows -> rows.context().applicabilityKey().equals(key));
        }

        Applied with(LoaderApiContext context, List<Ledger> ledgers) {
            List<ContextRows> next = new ArrayList<>(contexts);
            next.add(new ContextRows(context, List.copyOf(ledgers)));
            return new Applied(epoch, List.copyOf(next));
        }

        List<Ledger> ledgers() {
            List<Ledger> all = new ArrayList<>();
            contexts.forEach(rows -> all.addAll(rows.ledgers()));
            return List.copyOf(all);
        }
    }

    private LoaderApiRuntime() {}

    // ---- Setting -------------------------------------------------------------------------

    /**
     * Decides the setting for a game launch from the system property and
     * {@code config/retromod/config.json}, and logs one line when the runtime is on.
     */
    public static LoaderApiSetting.Effective configureForGame(Path gameDir, String loader,
            String hostVersion) {
        Optional<JsonPrimitive> config = gameDir == null ? Optional.empty()
                : RetromodConfig.findSetting(RetromodConfig.configPath(gameDir),
                        LoaderApiSetting.CONFIG_KEY);
        LoaderApiSetting.Effective decided = LoaderApiSetting.resolve(
                System.getProperty(LoaderApiSetting.SYSTEM_PROPERTY), null, config,
                RetromodVersion.RETROMOD_VERSION);
        defaultContext = new LoaderApiContext(loader, hostVersion,
                LoaderApiContext.TransformPath.FABRIC_PRELAUNCH, null);
        publish(decided, false);
        return decided;
    }

    /** Decides the setting for the CLI from the system property and the CLI flag. */
    public static LoaderApiSetting.Effective configureForCli(String flagValue) {
        LoaderApiSetting.Effective decided = LoaderApiSetting.resolve(
                System.getProperty(LoaderApiSetting.SYSTEM_PROPERTY), flagValue, Optional.empty(),
                RetromodVersion.RETROMOD_VERSION);
        publish(decided, true);
        return decided;
    }

    private static void publish(LoaderApiSetting.Effective decided, boolean cli) {
        effective = decided;
        if (anyProviderOn()) {
            String reason = decided.on() ? decided.reason()
                    : "auto: graduated by " + decided.retromodVersion();
            LOGGER.info("Loader API runtime is on ({}). {}", reason, offHint(decided, cli));
        } else {
            LOGGER.debug("Loader API runtime is off ({})", decided.reason());
        }
    }

    /** How to turn the runtime off where it runs. The CLI never reads the game's config file. */
    static String offHint(LoaderApiSetting.Effective decided, boolean cli) {
        // The system property beats every other source, so only it can turn this run off.
        if (decided.source() == LoaderApiSetting.Source.SYSTEM_PROPERTY) {
            return "Set -D" + LoaderApiSetting.SYSTEM_PROPERTY + "=false to turn it off.";
        }
        if (cli) {
            return "Pass " + LoaderApiSetting.CLI_FLAG + "=false to turn it off.";
        }
        return "Set " + LoaderApiSetting.CONFIG_KEY
                + " to false in config/retromod/config.json to turn it off.";
    }

    /**
     * The decided setting. A process that never configured it, such as a test or a tool, uses the
     * system property, then {@code auto}.
     */
    public static LoaderApiSetting.Effective effective() {
        LoaderApiSetting.Effective current = effective;
        if (current == null) {
            current = LoaderApiSetting.resolve(System.getProperty(LoaderApiSetting.SYSTEM_PROPERTY),
                    null, Optional.empty(), RetromodVersion.RETROMOD_VERSION);
            effective = current;
        }
        return current;
    }

    /** Whether any provider is on in this process, by the setting or by graduation. */
    public static boolean isEffectivelyOn() {
        return anyProviderOn();
    }

    /**
     * Whether any provider can run. False returns every hook at once: an explicit false never
     * loads a provider, and auto in a release build asks each provider only whether it graduated.
     */
    private static boolean anyProviderOn() {
        LoaderApiSetting.Effective current = effective();
        if (current.on()) return true;
        if (current.mode() != LoaderApiSetting.Mode.AUTO) return false;
        for (LoaderApiProvider provider : providers()) {
            if (isProviderOn(provider)) return true;
        }
        return false;
    }

    // ---- The hook ------------------------------------------------------------------------

    /**
     * The single registration hook. Registers every applicable provider's rows on
     * {@code transformer}, unless they are already in place for this context. Call it before a
     * transform path reads the transformer.
     */
    public static void prepare(RetromodTransformer transformer, LoaderApiContext context) {
        if (!anyProviderOn()) return;
        LoaderApiContext resolved = context == null ? defaultContext : context;
        synchronized (LOCK) {
            int epoch = transformer.registrationEpoch();
            Applied current = APPLIED.get(transformer);
            if (current == null || current.epoch() != epoch) current = new Applied(epoch, List.of());
            if (current.covers(resolved)) return;
            // Another host for the same loader replaces that loader's rows; another loader adds.
            current = releaseOtherHosts(transformer, current, resolved);
            List<Ledger> ledgers = registerProviders(transformer, resolved, false);
            APPLIED.put(transformer, current.with(resolved, ledgers));
            transformer.setPreTransformHook(LoaderApiRuntime::beforeTransform);
            // Claims removed redirects; let the next transform drop any phantom left behind.
            transformer.requestTargetRevalidation();
            transformer.invalidateReflectionRemapper();
        }
    }

    /**
     * {@link #prepare} with this process's own loader and host, for paths that do not know which
     * loader a mod was built for.
     */
    public static void prepareForProcess(RetromodTransformer transformer,
            LoaderApiContext.TransformPath path) {
        if (!anyProviderOn()) return;
        prepare(transformer, defaultContext.withPath(path));
    }

    /** Runs before each class transform; registers again only when something cleared the rows. */
    private static void beforeTransform(RetromodTransformer transformer) {
        Applied current = APPLIED.get(transformer);
        if (current != null && current.epoch() == transformer.registrationEpoch()) return;
        if (current == null || current.contexts().isEmpty()) {
            prepare(transformer, defaultContext);
            return;
        }
        // Every context comes back in its first order, so the same rows win as before the reset.
        for (ContextRows rows : current.contexts()) {
            prepare(transformer, rows.context());
        }
    }

    /**
     * Registers every provider that applies to {@code context}, whatever the setting, and returns
     * what each one claimed. For the coverage report and tests; installs no hook.
     */
    public static List<Ledger> prepareForced(RetromodTransformer transformer,
            LoaderApiContext context) {
        synchronized (LOCK) {
            List<Ledger> ledgers = registerProviders(transformer, context, true);
            transformer.invalidateReflectionRemapper();
            return List.copyOf(ledgers);
        }
    }

    private static List<Ledger> registerProviders(RetromodTransformer transformer,
            LoaderApiContext context, boolean force) {
        List<Ledger> ledgers = new ArrayList<>();
        for (LoaderApiProvider provider : providers()) {
            if (!(force || isProviderOn(provider)) || !provider.appliesTo(context)) continue;
            LoaderApiRegistration registration = new LoaderApiRegistration(transformer, provider);
            try (var scope = transformer.ownedRegistrations()) {
                provider.register(registration, context);
            } catch (RuntimeException e) {
                LOGGER.warn("Loader API provider {} could not register its rows: {}",
                        provider.identity(), e.toString());
            }
            ledgers.add(new Ledger(provider.identity(),
                    Set.copyOf(registration.declaredKeys()),
                    Set.copyOf(registration.suppressedKeys())));
            LOGGER.debug("Loader API provider {} registered {} key(s) for {}",
                    provider.identity(), registration.declaredKeys().size(),
                    context.applicabilityKey());
        }
        return ledgers;
    }

    /** Takes back the rows of earlier contexts for the same loader on another host. */
    private static Applied releaseOtherHosts(RetromodTransformer transformer, Applied applied,
            LoaderApiContext context) {
        List<ContextRows> kept = new ArrayList<>();
        for (ContextRows rows : applied.contexts()) {
            if (!rows.context().loader().equals(context.loader())) {
                kept.add(rows);
                continue;
            }
            for (Ledger ledger : rows.ledgers()) {
                for (RedirectKey key : ledger.declared()) {
                    transformer.release(key);
                }
            }
        }
        return new Applied(applied.epoch(), List.copyOf(kept));
    }

    // ---- Identity ------------------------------------------------------------------------

    /**
     * The translation identity for a mod, such as {@code fabric-26x/1}, or null when no provider
     * applies or the runtime is off. It goes into the jar's manifest and the AOT cache context.
     */
    public static String translationIdentity(String modLoaderType, String hostVersion) {
        if (!anyProviderOn()) return null;
        LoaderApiContext context = new LoaderApiContext(modLoaderType, hostVersion,
                LoaderApiContext.TransformPath.FABRIC_JAR, null);
        List<String> identities = new ArrayList<>();
        for (LoaderApiProvider provider : providers()) {
            if (isProviderOn(provider) && provider.appliesTo(context)) {
                identities.add(provider.identity());
            }
        }
        return identities.isEmpty() ? null : String.join(",", identities);
    }

    /**
     * The suffix every AOT cache stamp gets for this process, such as
     * {@code |loaderApi=fabric-26x/1}, or an empty string, so turning the runtime on or off
     * rebuilds the caches.
     */
    public static String cacheStampSuffix() {
        LoaderApiContext context = defaultContext;
        String identity = translationIdentity(context.loader(),
                context.hostVersion() != null ? context.hostVersion()
                        : RetromodVersion.TARGET_MC_VERSION);
        return identity == null ? "" : "|loaderApi=" + identity;
    }

    // ---- Providers -----------------------------------------------------------------------

    /** Whether {@code provider} is on: the setting, or {@code auto} after it graduated. */
    static boolean isProviderOn(LoaderApiProvider provider) {
        LoaderApiSetting.Effective current = effective();
        if (current.mode() != LoaderApiSetting.Mode.AUTO) return current.on();
        if (current.on()) return true;
        return hasGraduated(provider.graduatedIn(), current.retromodVersion());
    }

    static boolean hasGraduated(String graduatedIn, String retromodVersion) {
        if (graduatedIn == null || retromodVersion == null) return false;
        int compare = RetromodVersion.compareMcVersions(retromodVersion, graduatedIn);
        // A release candidate of the graduating version is still before it.
        return compare > 0 || (compare == 0 && !retromodVersion.contains("-"));
    }

    static List<LoaderApiProvider> providers() {
        List<LoaderApiProvider> loaded = providers;
        if (loaded != null) return loaded;
        synchronized (LOCK) {
            if (providers != null) return providers;
            List<LoaderApiProvider> found = new ArrayList<>();
            var iterator = ServiceLoader.load(LoaderApiProvider.class,
                    LoaderApiRuntime.class.getClassLoader()).iterator();
            while (iterator.hasNext()) {
                try {
                    found.add(iterator.next());
                } catch (ServiceConfigurationError e) {
                    LOGGER.debug("Skipping unavailable loader API provider: {}", e.getMessage());
                }
            }
            providers = List.copyOf(found);
            return providers;
        }
    }

    /** The ledgers of what the runtime registered on {@code transformer}, for reports and tests. */
    public static List<Ledger> ledgers(RetromodTransformer transformer) {
        Applied current = APPLIED.get(transformer);
        if (current == null || current.epoch() != transformer.registrationEpoch()) {
            return List.of();
        }
        return current.ledgers();
    }

    /** Whether {@code internalName} is a compiled type that every mod shares. */
    public static boolean isSharedTypeName(String internalName) {
        return internalName != null && internalName.startsWith("com/retromod/loaderapi/")
                && internalName.contains("/shared/");
    }

    // ---- Test seams ----------------------------------------------------------------------

    /** Sets the decided value and providers for a test; the returned handle restores both. */
    static AutoCloseable overrideForTesting(LoaderApiSetting.Effective value,
            List<LoaderApiProvider> testProviders) {
        LoaderApiSetting.Effective previousEffective = effective;
        List<LoaderApiProvider> previousProviders = providers;
        LoaderApiContext previousContext = defaultContext;
        synchronized (LOCK) {
            effective = value;
            if (testProviders != null) providers = List.copyOf(testProviders);
            APPLIED.clear();
        }
        return () -> {
            synchronized (LOCK) {
                effective = previousEffective;
                providers = previousProviders;
                defaultContext = previousContext;
                APPLIED.clear();
            }
            RetromodTransformer.getInstance().setPreTransformHook(null);
        };
    }

    /** Sets the context the class-load hook uses when no transform path set one. */
    static void setDefaultContextForTesting(LoaderApiContext context) {
        defaultContext = context;
    }

    static List<LoaderApiProvider> loadedProvidersOrNull() {
        return providers == null ? null : Collections.unmodifiableList(providers);
    }
}
