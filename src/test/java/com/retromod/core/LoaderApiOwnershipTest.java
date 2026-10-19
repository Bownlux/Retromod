/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.embedder.ModVersionInfo;
import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.loaderapi.LoaderApiTestSupport.OverlapProvider;
import com.retromod.polyfill.PolyfillRegistry;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runtime keeps its keys on every registration order the transform paths use. */
class LoaderApiOwnershipTest {

    private static final String HOST = "26.3";
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @BeforeEach
    void setUp() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = HOST;
        transformer.clearRedirectsForTesting();
        runtime = LoaderApiTestSupport.runtimeOn(new OverlapProvider(),
                LoaderApiTestSupport.fabricProvider());
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("Fabric pre-launch, then onInitialize registering everything again")
    void preLaunchThenOnInitialize() {
        RetromodPreLaunch.registerShimsForTransform(HOST);
        prepare(LoaderApiContext.TransformPath.FABRIC_PRELAUNCH);
        assertOwned("after pre-launch");

        // Retromod.onInitialize registers every Fabric and common shim, its built-ins, and polyfills.
        registerFabricShims();
        transformer.registerMethodRedirect("net/fabricmc/loader/api/FabricLoader", "getModContainer",
                "(Ljava/lang/String;)Ljava/util/Optional;",
                "com/retromod/shim/fabric/FabricLoaderShim", "getModContainer",
                "(Ljava/lang/String;)Ljava/util/Optional;");
        new PolyfillRegistry().loadAndRegister(transformer);
        assertOwned("after onInitialize registered every shim again");
        var modContainer = transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(
                "net/fabricmc/loader/api/FabricLoader", "getModContainer",
                "(Ljava/lang/String;)Ljava/util/Optional;"));
        assertEquals("com/retromod/compat/VersionSpoofer",
                modContainer == null ? null : modContainer.owner(),
                "FabricLoader.getModContainer must stay on the version spoofer, not the built-in "
                        + "redirect to a class that was never written");

        prepare(LoaderApiContext.TransformPath.FABRIC_INIT);
        assertOwned("after the hook ran again from onInitialize");
    }

    @Test
    @DisplayName("The in-game picker registers API shims just before transforming")
    void guiPickerOrder() {
        RetromodPreLaunch.registerShimsForTransform(HOST);
        prepare(LoaderApiContext.TransformPath.FABRIC_PRELAUNCH);
        for (VersionShim shim : new ShimRegistry().findApiShimsForLoader("fabric", HOST)) {
            shim.registerRedirects(transformer);
        }
        assertOwned("after the picker's API shims");
    }

    @Test
    @DisplayName("CLI transform and batch register API shims after polyfills, then reach the hook")
    void cliOrders() throws Exception {
        ModVersionInfo info = new ModVersionInfo("fixture", "1.0", "1.21.1", "fabric", "1.0",
                java.util.Set.of(), java.util.Set.of(), false);
        new PolyfillRegistry().loadAndRegister(transformer);
        var auxiliary = com.retromod.cli.RetromodCli.class.getDeclaredMethod("registerAuxiliaryRedirects",
                RetromodTransformer.class, ModVersionInfo.class, List.class);
        auxiliary.setAccessible(true);
        auxiliary.invoke(null, transformer, info, List.of());
        prepare(LoaderApiContext.TransformPath.CLI_TRANSFORM);
        assertOwned("after the CLI order");

        // A batch clears everything between mods; the class hook registers the rows again.
        transformer.resetOfflineRegistrations();
        new PolyfillRegistry().loadAndRegister(transformer);
        registerFabricShims();
        transformer.transformClass(emptyClass(), "fixture/Empty");
        assertOwned("after a reset, through the transformClass hook");
    }

    @Test
    @DisplayName("AOT registers polyfills before the shim chain")
    void aotOrder() {
        new PolyfillRegistry().loadAndRegister(transformer);
        registerFabricShims();
        prepare(LoaderApiContext.TransformPath.AOT);
        registerFabricShims();
        assertOwned("after the AOT order");
    }

    @Test
    @DisplayName("Full AOT and the javaagent reach the hook through the process context")
    void processContext() {
        LoaderApiTestSupport.defaultContext("fabric", HOST);
        registerFabricShims();
        LoaderApiRuntime.prepareForProcess(transformer, LoaderApiContext.TransformPath.FULL_AOT);
        registerFabricShims();
        assertOwned("after full AOT");
    }

    @Test
    @DisplayName("A Forge or unknown context in the same epoch keeps the Fabric rows")
    void otherLoaderContextKeepsRows() {
        RetromodPreLaunch.registerShimsForTransform(HOST);
        prepare(LoaderApiContext.TransformPath.FABRIC_PRELAUNCH);
        // AOT of a Forge jar found in mods/, and ApiEmbedder without mod metadata.
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("forge", HOST,
                LoaderApiContext.TransformPath.AOT, null));
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext(null, HOST,
                LoaderApiContext.TransformPath.API_EMBEDDER, null));
        assertOwned("after a Forge and an unknown context");

        registerFabricShims();
        transformer.transformClass(emptyClass(), "fixture/Empty");
        assertOwned("after the shims registered again and a class transform");
    }

    @Test
    @DisplayName("Another host for the same loader replaces that loader's rows instead of adding")
    void otherHostReplacesRows() {
        prepare(LoaderApiContext.TransformPath.FABRIC_PRELAUNCH);
        int providers = LoaderApiRuntime.ledgers(transformer).size();
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", "26.2",
                LoaderApiContext.TransformPath.CLI_TRANSFORM, null));
        assertEquals(providers, LoaderApiRuntime.ledgers(transformer).size(),
                "one set of ledgers per loader");
        assertOwned("after the 26.2 rows replaced the 26.3 rows");
    }

    @Test
    @DisplayName("A claim clears side tables a later redirect could not replace")
    void claimClearsSideTables() {
        String owner = "net/fabricmc/fabric/api/object/builder/v1/entity/FabricDefaultAttributeRegistry";
        String desc = "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder;)V";
        transformer.registerWrappingMethodRedirect(owner, "register", desc,
                "com/retromod/Helper", "register", desc);
        transformer.limitRedirectToModsBefore(owner, "register", desc, "1.20.5");
        transformer.registerInheritedMethodRedirect(owner, "register", desc,
                "com/retromod/Helper", "register", desc);
        transformer.registerSuperCallHelper("com/retromod/Helper", "register", desc, "superRegister");
        transformer.registerMethodRename(owner, "register", "registerRenamed");
        transformer.registerInterfaceReplacement("net/fabricmc/fabric/api/resource/conditions/v1/ResourceCondition",
                "com/retromod/LegacyResourceCondition");

        try (var scope = transformer.ownedRegistrations()) {
            transformer.claim(RedirectKey.method(owner, "register", desc));
            transformer.claim(RedirectKey.type("net/fabricmc/fabric/api/resource/conditions/v1/ResourceCondition"));
        }
        List<String> lines = transformer.registrationLines();
        assertTrue(lines.stream().noneMatch(line -> line.contains("registerRenamed")
                        || line.contains("com/retromod/Helper") || line.contains("LegacyResourceCondition")
                        || (line.startsWith("transformer.legacyOnlyRedirects") && line.contains(owner))),
                "every table entry for a claimed key must be gone: " + lines.stream()
                        .filter(line -> line.contains(owner) || line.contains("Helper")).toList());

        // Later registrations outside an owned scope are ignored.
        transformer.registerMethodRedirect(owner, "register", desc, "com/retromod/Other", "register", desc);
        transformer.registerInterfaceReplacement(
                "net/fabricmc/fabric/api/resource/conditions/v1/ResourceCondition", "com/retromod/Other");
        assertNull(transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(owner, "register", desc)));
        assertTrue(transformer.registrationLines().stream().noneMatch(line -> line.contains("com/retromod/Other")));

        // The owner itself still registers inside its scope.
        try (var scope = transformer.ownedRegistrations()) {
            transformer.registerMethodRedirect(owner, "register", desc, "com/retromod/Owned", "register", desc);
        }
        assertEquals("com/retromod/Owned", transformer.getMethodRedirects()
                .get(new RetromodTransformer.MethodKey(owner, "register", desc)).owner());
    }

    @Test
    @DisplayName("Clearing every registration clears the claims and moves the epoch")
    void resetClearsClaims() {
        prepare(LoaderApiContext.TransformPath.TEST);
        int epoch = transformer.registrationEpoch();
        assertFalse(transformer.claimedTypes().isEmpty());
        transformer.resetOfflineRegistrations();
        assertTrue(transformer.claimedTypes().isEmpty());
        assertTrue(transformer.claimedMembers().isEmpty());
        assertTrue(epoch != transformer.registrationEpoch());
    }

    private void prepare(LoaderApiContext.TransformPath path) {
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", HOST, path, null));
    }

    private void registerFabricShims() {
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            String loader = shim.getModLoaderType();
            if (("fabric".equals(loader) || "common".equals(loader))
                    && ShimRegistry.isAvailableOnHost(shim, HOST)) {
                shim.registerRedirects(transformer);
            }
        }
    }

    /** Every overlap key still points where the runtime put it, in every table that names it. */
    private void assertOwned(String when) {
        for (String type : OverlapProvider.TYPES) {
            assertEquals(OverlapProvider.TARGET, transformer.getClassRedirects().get(type),
                    when + ": " + type + " must stay on the runtime's target");
        }
        // The bundled provider serves these two through batch S1; FabricApiShim and the 1.21.6 HUD
        // shim registered broken and per-mod targets for them on every order (D1, critique 12).
        assertEquals("com/retromod/loaderapi/fabric/v26/screen/shared/LegacyHudRenderCallback",
                transformer.getClassRedirects().get("net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback"),
                when + ": HudRenderCallback must stay on the runtime's shared type");
        var beforeRender = transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "beforeRender",
                "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/fabricmc/fabric/api/event/Event;"));
        assertEquals("beforeExtract", beforeRender == null ? null : beforeRender.name(),
                when + ": ScreenEvents.beforeRender must stay on the host's beforeExtract");
        assertNull(transformer.getClassRedirects().get(OverlapProvider.ENTITY_BUILDER.owner()),
                when + ": the suppressed FabricEntityTypeBuilder must keep the host's class");
        // The bundled provider keeps the old handler on its shared type, which a placeholder shim
        // used to replace on the AOT order (D13).
        assertEquals("com/retromod/loaderapi/fabric/v26/net/shared/ServerPlayChannelHandler",
                transformer.getClassRedirects().get(
                        "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayChannelHandler"),
                when + ": ServerPlayNetworking$PlayChannelHandler must stay on the runtime's shared type");
        // The networking polyfill and the client bridge shim register these two calls again on
        // every order; the runtime keeps them on its channels.
        assertNetworkingCall(when, "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking", "send",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/Identifier;"
                        + "Lnet/minecraft/network/FriendlyByteBuf;)V");
        assertNetworkingCall(when, "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking", "send",
                "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/network/FriendlyByteBuf;)V");
        for (String line : transformer.registrationLines()) {
            if (!line.startsWith("transformer.")) continue;
            String table = line.substring(0, line.indexOf('\t'));
            if (table.contains("claimed") || table.contains("suppressed")) continue;
            for (String type : OverlapProvider.TYPES) {
                if (line.contains("\t" + type + "\t") || line.contains("owner=" + type + ",")) {
                    assertTrue(line.contains(OverlapProvider.TARGET) || table.equals("transformer.classRedirects")
                                    || table.equals("transformer.interfaceSyntheticTargets")
                                    || table.equals("transformer.classShimTargets"),
                            when + ": " + line);
                }
            }
        }
    }

    private void assertNetworkingCall(String when, String owner, String name, String desc) {
        var target = transformer.getMethodRedirects().get(new RetromodTransformer.MethodKey(owner, name, desc));
        assertEquals("com/retromod/loaderapi/fabric/v26/net/LegacyChannels", target == null ? null : target.owner(),
                when + ": " + owner + "." + name + " must stay on the runtime's channels");
    }

    private static byte[] emptyClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Empty", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
