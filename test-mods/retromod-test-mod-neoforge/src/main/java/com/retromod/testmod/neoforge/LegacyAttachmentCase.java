/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.serialization.Codec;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * #366: data attachments that serialize with a Codec. NeoForge 21.6 and newer take a MapCodec
 * there, so ProjectE stopped at registration with NoSuchMethodError on serialize(Codec).
 */
final class LegacyAttachmentCase {

    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "retromod_test_mod_neoforge");
    // Building each type runs serialize(Codec), so a missing bridge fails during registration.
    static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> COUNT = TYPES.register("legacy_count",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
    static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> NONZERO = TYPES.register("legacy_nonzero",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT, value -> value != 0).build());

    private LegacyAttachmentCase() {}

    static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /** The first step that went wrong, or success. */
    static String run(ServerLevel level) {
        level.setData(COUNT, 7);
        if (level.getData(COUNT) != 7) return "fail: the attachment read back " + level.getData(COUNT);
        level.setData(NONZERO, 3);
        if (!level.hasData(NONZERO) || level.getData(NONZERO) != 3) return "fail: the filtered attachment was lost";
        return "success";
    }
}
