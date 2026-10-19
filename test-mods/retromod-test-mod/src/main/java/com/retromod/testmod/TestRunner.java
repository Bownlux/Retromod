/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod;

import com.retromod.testmod.tests.BasicTests;
import com.retromod.testmod.tests.BlockItemTests;
import com.retromod.testmod.tests.BlockTests;
import com.retromod.testmod.tests.CodecTests;
import com.retromod.testmod.tests.DeferredItemStackTests;
import com.retromod.testmod.tests.EnchantmentTests;
import com.retromod.testmod.tests.EntityTypeTests;
import com.retromod.testmod.tests.EnumTests;
import com.retromod.testmod.tests.GuiTests;
import com.retromod.testmod.tests.IdentifierTests;
import com.retromod.testmod.tests.ItemTests;
import com.retromod.testmod.tests.LoaderTests;
import com.retromod.testmod.tests.MathTests;
import com.retromod.testmod.tests.MiscApiTests;
import com.retromod.testmod.tests.NbtTests;
import com.retromod.testmod.tests.RegistryTests;
import com.retromod.testmod.tests.SoundParticleTests;
import com.retromod.testmod.tests.StatusEffectTests;
import com.retromod.testmod.tests.TagTests;
import com.retromod.testmod.tests.Test05SuperKeyPressed;
import com.retromod.testmod.tests.Test06RenderPipelineBridge;
import com.retromod.testmod.tests.Test07PlayerModelPartsAccessor;
import com.retromod.testmod.tests.Test08AutomaticMixinDescriptor;
import com.retromod.testmod.tests.Test09ConstructorHeuristicIsolation;
import com.retromod.testmod.tests.Test10ExactTargetPrefixCapture;
import com.retromod.testmod.tests.Test11ChatOptionsSuperclass;
import com.retromod.testmod.tests.Test12ClientCommandParserAccessor;
import com.retromod.testmod.tests.Test13WindowHandleShim;
import com.retromod.testmod.tests.Test14RevampedEntityBridges;
import com.retromod.testmod.tests.Test15InheritedEntityWorld;
import com.retromod.testmod.tests.Test16SingleFabricApiProvider;
import com.retromod.testmod.tests.Test17BlockRandomTicksAccessor;
import com.retromod.testmod.tests.Test18GameRulesField;
import com.retromod.testmod.tests.Test20BlockUseOverride;
import com.retromod.testmod.tests.Test21KeyCategoryName;
import com.retromod.testmod.tests.Test22LegacyMobApi;
import com.retromod.testmod.tests.Test23GlfwPolls;
import com.retromod.testmod.tests.Test24JomlAxisWidening;
import com.retromod.testmod.tests.Test25StateEntriesCopy;
import com.retromod.testmod.tests.Test26LegacyItemModels;
import com.retromod.testmod.tests.Test27OwnOverrideMob;
import com.retromod.testmod.tests.Test28LegacyProcedureMembers;
import com.retromod.testmod.tests.Test29OwnDamageOverride;
import com.retromod.testmod.tests.Test30LegacySwinging;
import com.retromod.testmod.tests.Test31LegacyTrimAtlas;
import com.retromod.testmod.tests.Test32CrossClassRegistryId;
import com.retromod.testmod.tests.Test33EntityTypeBuildKey;
import com.retromod.testmod.tests.Test19RemovedItemBase;
import com.retromod.testmod.tests.Test43ClientHostTints;
import com.retromod.testmod.tests.Test45ClientHudAndScreenEvents;
import com.retromod.testmod.tests.TextTests;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs each test when the Minecraft APIs it uses are ready. Most tests run
 * during client initialization. Tests that need dynamic registries wait until
 * a world is joined. Each phase logs a run ID so mixed logs remain traceable.
 */
public final class TestRunner {

    private static final List<Test> IMMEDIATE      = buildImmediateSuite();
    private static final List<Test> CLIENT_STARTED = buildClientStartedSuite();
    private static final List<Test> WORLD_JOIN     = buildWorldJoinSuite();

    private TestRunner() {}

    // Test suites

    private static List<Test> buildImmediateSuite() {
        List<Test> all = new ArrayList<>();
        all.addAll(BasicTests.all());
        all.addAll(TextTests.all());
        all.addAll(IdentifierTests.all());
        all.addAll(RegistryTests.all());
        all.addAll(BlockItemTests.all());
        all.addAll(BlockTests.all());
        all.addAll(ItemTests.all());
        all.addAll(EntityTypeTests.all());
        all.addAll(EnumTests.all());
        all.addAll(MathTests.all());
        all.addAll(NbtTests.all());
        all.addAll(SoundParticleTests.all());
        all.addAll(GuiTests.all());
        all.addAll(LoaderTests.all());
        all.addAll(CodecTests.all());
        all.addAll(TagTests.all());
        all.addAll(MiscApiTests.all());
        all.add(new Test05SuperKeyPressed());
        all.add(new Test06RenderPipelineBridge());
        all.add(new Test07PlayerModelPartsAccessor());
        all.add(new Test08AutomaticMixinDescriptor());
        all.add(new Test09ConstructorHeuristicIsolation());
        all.add(new Test10ExactTargetPrefixCapture());
        all.add(new Test11ChatOptionsSuperclass());
        all.add(new Test12ClientCommandParserAccessor());
        all.add(new Test14RevampedEntityBridges());
        all.add(new Test16SingleFabricApiProvider());
        all.add(new Test17BlockRandomTicksAccessor());
        all.add(new Test18GameRulesField());
        all.add(new Test20BlockUseOverride());
        all.add(new Test21KeyCategoryName());
        all.add(new Test22LegacyMobApi());
        all.add(new Test24JomlAxisWidening());
        all.add(new Test25StateEntriesCopy());
        all.add(new Test26LegacyItemModels());
        all.add(new Test27OwnOverrideMob());
        all.add(new Test28LegacyProcedureMembers());
        all.add(new Test29OwnDamageOverride());
        all.add(new Test30LegacySwinging());
        // These register content, so they have to run before the registries freeze after initialization.
        all.add(new Test32CrossClassRegistryId());
        all.add(new Test33EntityTypeBuildKey());
        return List.copyOf(all);
    }

    private static List<Test> buildClientStartedSuite() {
        // The GLFW window is assigned after client entry points run during Minecraft
        // construction. CLIENT_STARTED is the first harness phase where its native handle
        // can be read safely, and the GLFW polls take that handle.
        // The host builds its block colors during construction, after client entry points run.
        // With newer Fabric API, Retromod applies old block colors at CLIENT_STARTED through a
        // listener it registered when Test41 ran at common init, so ahead of this suite's.
        // The old HUD and screen callbacks need the client's screens, which exist once it has started.
        return List.of(new Test13WindowHandleShim(), new Test23GlfwPolls(), new Test43ClientHostTints(),
                new Test45ClientHudAndScreenEvents());
    }

    private static List<Test> buildWorldJoinSuite() {
        List<Test> all = new ArrayList<>();
        all.addAll(DeferredItemStackTests.all());
        all.addAll(EnchantmentTests.all());
        all.addAll(StatusEffectTests.all());
        all.add(Test14RevampedEntityBridges.entityTagMembership());
        all.add(new Test15InheritedEntityWorld());
        all.add(new Test19RemovedItemBase());
        // The rebuilt armor trim atlas is uploaded once the client has loaded its own atlases.
        all.add(new Test31LegacyTrimAtlas());
        return List.copyOf(all);
    }

    // Lifecycle entry points

    /** {@code onInitializeClient} entry point. Runs all init-phase tests. */
    public static void runImmediate() {
        runPhase("init", IMMEDIATE);
    }

    /** {@code ClientLifecycleEvents.CLIENT_STARTED} entry point. */
    public static void runOnClientStarted() {
        runPhase("client-started", CLIENT_STARTED);
    }

    /** Runs world tests after each join. The suite does not keep state between worlds. */
    public static void runOnWorldJoin() {
        runPhase("world-join", WORLD_JOIN);
    }

    // Shared runner

    private static void runPhase(String phaseName, List<Test> tests) {
        TestPhases.run(phaseName, tests);
    }
}
