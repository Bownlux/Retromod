/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.mapping.TargetSrgMapper;
import com.retromod.mixin.MixinCompatibilityTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #269 (Cracker's Wither Storm for Forge 1.20.1 on NeoForge 1.21.1): the classes were remapped
 * from SRG, but the refmap kept {@code f_20888_:L...Player;}, so the {@code @Accessor} found no
 * field. Forge refmaps store accessor, invoker, and shadow targets without an owner.
 */
class OwnerlessSrgRefmapTest {

    private static final String REFMAP = """
            {"mappings":{"MixinLivingEntityAccessor":{
            "lastHurtByPlayer":"f_20888_:Lnet/minecraft/world/entity/player/Player;"},
            "MixinFallingBlock":{"falling":"m_6788_(Lnet/minecraft/world/entity/item/FallingBlockEntity;)V"},
            "MixinBlockEntityType":{"validBlocks":"f_58915_:Ljava/util/Set;"}}}
            """;

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("owner-less SRG refmap targets become Mojang names on a Mojang-named host")
    void ownerlessTargetsBecomeMojang() {
        String out = MixinRefmapRemapper.remapForgeSelectors(
                REFMAP, new MixinCompatibilityTransformer(transformer));

        assertTrue(out.contains("\"lastHurtByPlayer:Lnet/minecraft/world/entity/player/Player;\""), out);
        assertTrue(out.contains("\"falling(Lnet/minecraft/world/entity/item/FallingBlockEntity;)V\""), out);
        assertTrue(out.contains("\"validBlocks:Ljava/util/Set;\""), out);
    }

    @Test
    @DisplayName("an SRG-named Forge host keeps owner-less SRG targets")
    void srgHostKeepsOwnerlessTargets() {
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);

        String out = MixinRefmapRemapper.remapForgeSelectors(
                REFMAP, new MixinCompatibilityTransformer(transformer));

        assertTrue(out.contains("f_20888_:"), "without an owner the host SRG name is unknown: " + out);
        assertTrue(out.contains("m_6788_("), out);
    }
}
