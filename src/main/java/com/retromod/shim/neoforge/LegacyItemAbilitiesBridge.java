/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps mods that name tool abilities linking on 26.3, on NeoForge and on Forge.
 *
 * <p>26.3 made tools data-driven, and both loaders removed the axe, hoe, pickaxe, shovel, sword,
 * and shield constants, together with the default sets built from them: NeoForge from
 * {@code ItemAbilities}, Forge 66 from {@code ToolActions}. A mod that read one failed with
 * {@code NoSuchFieldError}. Both APIs still intern an ability by name ({@code ItemAbility.get},
 * {@code ToolAction.get}), so each read now returns the same ability the constant held. Whether a
 * vanilla tool still reports that ability is decided by the host's tool data.
 */
public final class LegacyItemAbilitiesBridge {

    /** One loader's spelling of the ability API. */
    record Flavor(String helper, String holder, String abilityType, List<String> extraOwners) {
        String lAbility() {
            return "L" + abilityType + ";";
        }
    }

    static final Flavor NEOFORGE = new Flavor("com/retromod/generated/LegacyItemAbilities",
            "net/neoforged/neoforge/common/ItemAbilities", "net/neoforged/neoforge/common/ItemAbility",
            // A Forge mod on NeoForge may still name ToolActions when this redirect is consulted.
            List.of("net/minecraftforge/common/ToolActions"));
    static final Flavor FORGE = new Flavor("com/retromod/generated/LegacyToolActions",
            "net/minecraftforge/common/ToolActions", "net/minecraftforge/common/ToolAction", List.of());

    static final String HELPER = NEOFORGE.helper();
    private static final String SET = "java/util/Set";

    /** Constant name, then the ability name it held. {@code HOE_TILL} was always "till". */
    private static final String[][] ABILITY_NAMES = {
        {"AXE_DIG", "axe_dig"}, {"PICKAXE_DIG", "pickaxe_dig"}, {"SHOVEL_DIG", "shovel_dig"},
        {"HOE_DIG", "hoe_dig"}, {"SWORD_DIG", "sword_dig"}, {"AXE_STRIP", "axe_strip"},
        {"AXE_SCRAPE", "axe_scrape"}, {"AXE_WAX_OFF", "axe_wax_off"},
        {"SHOVEL_FLATTEN", "shovel_flatten"}, {"HOE_TILL", "till"}, {"SHIELD_BLOCK", "shield_block"},
    };

    /** Set name, then the abilities it held, as both loaders built them before 26.3. */
    private static final String[][] DEFAULT_SETS = {
        {"DEFAULT_AXE_ACTIONS", "axe_dig", "axe_strip", "axe_scrape", "axe_wax_off"},
        {"DEFAULT_HOE_ACTIONS", "hoe_dig", "till"},
        {"DEFAULT_SHOVEL_ACTIONS", "shovel_dig", "shovel_flatten", "shovel_douse"},
        {"DEFAULT_PICKAXE_ACTIONS", "pickaxe_dig"},
        {"DEFAULT_SWORD_ACTIONS", "sword_dig", "sword_sweep"},
        {"DEFAULT_SHIELD_ACTIONS", "shield_block"},
    };

    private LegacyItemAbilitiesBridge() {}

    /** NeoForge hosts. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, NEOFORGE, hostFields(NEOFORGE));
    }

    /** Forge hosts. */
    public static void registerForge(RetromodTransformer transformer) {
        register(transformer, FORGE, hostFields(FORGE));
    }

    /** {@code hostFields} is null when there is no host to inspect. */
    static void register(RetromodTransformer transformer, Set<String> hostFields) {
        register(transformer, NEOFORGE, hostFields);
    }

    static void register(RetromodTransformer transformer, Flavor flavor, Set<String> hostFields) {
        List<String[]> abilities = new ArrayList<>();
        for (String[] ability : ABILITY_NAMES) {
            if (removedFromHost(hostFields, ability[0])) abilities.add(ability);
        }
        List<String[]> sets = new ArrayList<>();
        for (String[] set : DEFAULT_SETS) {
            if (removedFromHost(hostFields, set[0])) sets.add(set);
        }
        if (abilities.isEmpty() && sets.isEmpty()) return;

        transformer.registerSyntheticClass(flavor.helper(), generateHelper(flavor, abilities, sets));
        for (String[] ability : abilities) {
            redirect(transformer, flavor, ability[0], "()" + flavor.lAbility());
        }
        for (String[] set : sets) {
            redirect(transformer, flavor, set[0], "()L" + SET + ";");
        }
    }

    private static void redirect(RetromodTransformer transformer, Flavor flavor, String constant,
                                 String helperDesc) {
        List<String> owners = new ArrayList<>(flavor.extraOwners());
        owners.add(0, flavor.holder());
        for (String owner : owners) {
            transformer.registerFieldRedirect(owner, constant, null, flavor.helper(), constant, helperDesc);
        }
    }

    private static Set<String> hostFields(Flavor flavor) {
        ClassNode host = ClassResourceInspector.read(flavor.holder());
        return host == null ? null : host.fields.stream().map(f -> f.name).collect(Collectors.toSet());
    }

    /** The host's own constants class decides; offline, the target version does. */
    private static boolean removedFromHost(Set<String> hostFields, String constant) {
        if (hostFields != null) return !hostFields.contains(constant);
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }

    static byte[] generateHelper(List<String[]> abilities, List<String[]> sets) {
        return generateHelper(NEOFORGE, abilities, sets);
    }

    /** One static method per bridged constant, named like the constant it replaces. */
    static byte[] generateHelper(Flavor flavor, List<String[]> abilities, List<String[]> sets) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, flavor.helper(), null, "java/lang/Object", null);
        for (String[] ability : abilities) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, ability[0],
                    "()" + flavor.lAbility(), null, null);
            mv.visitCode();
            pushAbility(mv, flavor, ability[1]);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        for (String[] set : sets) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, set[0],
                    "()L" + SET + ";", null, null);
            mv.visitCode();
            int size = set.length - 1;
            mv.visitLdcInsn(size);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < size; i++) {
                mv.visitInsn(DUP);
                mv.visitLdcInsn(i);
                pushAbility(mv, flavor, set[i + 1]);
                mv.visitInsn(AASTORE);
            }
            mv.visitMethodInsn(INVOKESTATIC, SET, "of", "([Ljava/lang/Object;)L" + SET + ";", true);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void pushAbility(MethodVisitor mv, Flavor flavor, String name) {
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESTATIC, flavor.abilityType(), "get",
                "(Ljava/lang/String;)" + flavor.lAbility(), false);
    }
}
