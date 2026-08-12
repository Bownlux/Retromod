/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinRefmapRepairIndexTest {

    private static final String MIXIN = "example/mixin/TargetMixin";
    private static final String SELECTOR = "oldYarnName(Ljava/lang/String;)V";

    @Test
    @DisplayName("Equal facts from multiple refmap sections merge into one repair")
    void equalFactsMerge() {
        MixinRefmapRepairIndex.Repair repair = repair("(Ljava/lang/String;I)V");
        MixinRefmapRepairIndex left = MixinRefmapRepairIndex.builder()
                .put(MIXIN, SELECTOR, repair).build();
        MixinRefmapRepairIndex right = MixinRefmapRepairIndex.builder()
                .put(MIXIN.replace('/', '.'), SELECTOR, repair).build();

        MixinRefmapRepairIndex merged = left.merge(right);

        assertEquals(repair, merged.find(MIXIN, SELECTOR).orElseThrow());
    }

    @Test
    @DisplayName("Conflicting facts make a refmap source selector unavailable")
    void conflictingFactsAreDiscarded() {
        MixinRefmapRepairIndex left = MixinRefmapRepairIndex.builder()
                .put(MIXIN, SELECTOR, repair("(Ljava/lang/String;I)V")).build();
        MixinRefmapRepairIndex right = MixinRefmapRepairIndex.builder()
                .put(MIXIN, SELECTOR, repair("(JLjava/lang/String;)V")).build();

        MixinRefmapRepairIndex merged = left.merge(right);

        assertTrue(merged.find(MIXIN, SELECTOR).isEmpty(),
                "an archive with two layouts must not choose either one");
    }

    @Test
    @DisplayName("A conflict remains unavailable after a later equal entry")
    void conflictCannotBeReintroduced() {
        MixinRefmapRepairIndex.Builder builder = MixinRefmapRepairIndex.builder();
        MixinRefmapRepairIndex.Repair first = repair("(Ljava/lang/String;I)V");
        builder.put(MIXIN, SELECTOR, first);
        builder.put(MIXIN, SELECTOR, repair("(JLjava/lang/String;)V"));
        builder.put(MIXIN, SELECTOR, first);

        assertTrue(builder.build().find(MIXIN, SELECTOR).isEmpty());
    }

    private static MixinRefmapRepairIndex.Repair repair(String newDescriptor) {
        return new MixinRefmapRepairIndex.Repair(
                "net/minecraft/test/Target", "(Ljava/lang/String;)V", newDescriptor,
                Opcodes.ACC_PUBLIC,
                List.of(new MixinHandlerResignature.ParamInsert(1, "I")));
    }
}
