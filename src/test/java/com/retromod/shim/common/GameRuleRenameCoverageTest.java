/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The old {@code RULE_*} game rule names are pre-26.1 Mojang names, not a Fabric artifact.
 *
 * <p>The rename table ran for Fabric only, on the reasoning that a Mojang-named loader already
 * carries the current names. That holds for a mod built after 26.1 and for nothing older. A 1.20 or
 * 1.21 NeoForge or Forge mod writes {@code GameRules.RULE_MOBGRIEFING} and hit
 * {@code NoSuchFieldError: Class net.minecraft.world.level.gamerules.GameRules does not have member
 * field ... RULE_MOBGRIEFING} on 26.x. The test mod caught it in game.
 */
class GameRuleRenameCoverageTest {

    private static final String OWNER = "net/minecraft/world/level/gamerules/GameRules";
    private static final String OLD_OWNER = "net/minecraft/world/level/GameRules";
    private static final String RULE = "Lnet/minecraft/world/level/gamerules/GameRule;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    /** What a 1.21 mod emits for {@code GameRules.RULE_MOBGRIEFING}. */
    private static byte[] modReadingMobGriefing(String owner, String descriptor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/RuleUser", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "rule", "()" + descriptor, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "RULE_MOBGRIEFING", descriptor);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private String fieldNameAfterTransform(byte[] input) {
        String[] seen = {null};
        new ClassReader(transformer.transformClass(input, "probe/RuleUser"))
                .accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitFieldInsn(int op, String o, String nm, String de) {
                                if (op == Opcodes.GETSTATIC && o.endsWith("GameRules")) seen[0] = nm;
                            }
                        };
                    }
                }, 0);
        return seen[0];
    }

    @Test
    @DisplayName("every loader renames the constant, not just Fabric")
    void everyLoaderRenamesTheConstant() {
        record Case(String loader, Runnable register) {}
        Case[] cases = {
            new Case("NeoForge", () -> new NeoForge_1_21_11_to_26_1().registerRedirects(transformer)),
            new Case("Forge", () -> new Forge_1_21_11_to_26_1().registerRedirects(transformer)),
            new Case("Fabric", () -> new Fabric_1_21_11_to_26_1().registerRedirects(transformer)),
        };
        for (Case c : cases) {
            transformer.clearRedirectsForTesting();
            c.register().run();
            assertEquals("MOB_GRIEFING", fieldNameAfterTransform(modReadingMobGriefing(OWNER, RULE)),
                    "on " + c.loader() + " a mod reading RULE_MOBGRIEFING must land on the 26.1 "
                    + "name, or it fails with NoSuchFieldError the first time it reads a game rule");
        }
    }

    @Test
    @DisplayName("the rename still reaches a mod naming the pre-26.1 owner")
    void oldOwnerAlsoResolves() {
        new NeoForge_1_21_11_to_26_1().registerRedirects(transformer);
        // A 1.21 mod names world/level/GameRules; the class move carries it to gamerules/GameRules
        // and the rename then has to apply to the moved owner.
        assertEquals("MOB_GRIEFING",
                fieldNameAfterTransform(modReadingMobGriefing(OLD_OWNER,
                        "Lnet/minecraft/world/level/GameRules$Key;")),
                "the class move and the field rename have to compose, or one of them is wasted");
    }
}
