/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.LegacyLootContextCreateBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.retromod.shim.forge.LoaderApiHostJars.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The 1.20 loot builder split renames only older mods, and 1.20.1's create(id) reaches the 1.20.2 form. */
class LootContextBuilderTest {

    private static final String BUILDER = "net/minecraft/world/level/storage/loot/LootContext$Builder";
    private static final String PARAMS = "net/minecraft/world/level/storage/loot/LootParams";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A 1.19 mod and one of unknown version take the rename; a 1.20.1 mod and host code keep LootContext.Builder")
    void renameIsLimitedToOlderMods() {
        new Forge_1_19_4_to_1_20().registerRedirects(transformer);
        for (String version : Arrays.asList("1.19.2", null)) {
            transformer.setModSourceVersion(version);
            assertEquals(List.of(PARAMS + "$Builder.<init>(L" + PARAMS + ";)V"), refs(buildContext()), "mod built for " + version);
        }
        transformer.setModSourceVersion("1.20.1");
        assertEquals(List.of(BUILDER + ".<init>(L" + PARAMS + ";)V"), refs(buildContext()));
        assertFalse(transformer.getClassRedirectsForHostCode().containsKey(BUILDER),
                "Retromod's own rebuilt classes are written for the host and keep the builder");
    }

    @Test
    @DisplayName("create(id) becomes create(Optional.ofNullable(id)) where the host has only the Optional form")
    void createWithIdReachesTheOptionalForm() {
        LegacyLootContextCreateBridge.register(transformer, name -> null);
        String context = "Lnet/minecraft/world/level/storage/loot/LootContext;";
        byte[] out = transformer.transformClass(mod("mod/Loot", "(L" + BUILDER + ";Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "create", "(Lnet/minecraft/resources/Identifier;)" + context, false);
            mv.visitInsn(ARETURN);
        }), "mod/Loot");
        String helper = LegacyLootContextCreateBridge.HELPER;
        assertEquals(List.of(helper + ".create(L" + BUILDER + ";Lnet/minecraft/resources/Identifier;)" + context), refs(out));
        assertEquals(List.of("java/util/Optional.ofNullable(Ljava/lang/Object;)Ljava/util/Optional;",
                BUILDER + ".create(Ljava/util/Optional;)" + context), refs(transformer.getSyntheticClasses().get(helper)));
    }

    /** A mod that builds a loot context from params, as a block's getDrops helper does. */
    private byte[] buildContext() {
        return transformer.transformClass(mod("mod/Drops", "(L" + PARAMS + ";)V", mv -> {
            mv.visitTypeInsn(NEW, BUILDER);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, BUILDER, "<init>", "(L" + PARAMS + ";)V", false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
        }), "mod/Drops");
    }
}
