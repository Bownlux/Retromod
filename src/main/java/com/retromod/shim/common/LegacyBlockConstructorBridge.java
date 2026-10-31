/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyCauldronInteractions;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Block constructors that 1.20.3 reshaped for block codecs, for Mojang-named mods: Forge and
 * NeoForge mods after the SRG remap, and Fabric mods on 26.1 and later. Pre-26.1 Fabric keeps
 * intermediary names and has {@code Pre1_20_3BlockConstructorBridge} instead.
 *
 * <p>An older mod building these blocks dies with {@code NoSuchMethodError} in its block registry,
 * which stops every later block of the same mod:
 * <ul>
 *   <li>{@code DropExperienceBlock(Properties)} and {@code DropExperienceBlock(Properties,
 *       IntProvider)} became {@code DropExperienceBlock(IntProvider, Properties)}. The one-argument
 *       form gets {@code ConstantInt.of(0)}, which its old constructor passed.</li>
 *   <li>{@code AmethystClusterBlock(int, int, Properties)} became
 *       {@code AmethystClusterBlock(float, float, Properties)}. The sizes widen unchanged.</li>
 *   <li>{@code AbstractCauldronBlock(Properties, Map<Item, CauldronInteraction>)} took a named
 *       {@code InteractionMap} from 1.20.3 and a {@code Dispatcher} from 26.1. The mod's map is
 *       converted to whichever this host takes.</li>
 *   <li>The vanilla tables {@code CauldronInteraction.EMPTY}, {@code WATER}, {@code LAVA} and
 *       {@code POWDER_SNOW}, which mods add their buckets to, stopped being plain maps at the same
 *       time. A read of the old map field gets a map that adds to the host's table.</li>
 * </ul>
 *
 * <p>Each rewrite applies to {@code new X(...)} and to a subclass's {@code super(...)} alike, and
 * only on a host that has the new constructor and not the old one. A cauldron interaction compiled
 * against 1.20.1, which returned {@code InteractionResult}, is passed through unchanged.
 */
public final class LegacyBlockConstructorBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String DROP_EXPERIENCE = "net/minecraft/world/level/block/DropExperienceBlock";
    static final String AMETHYST_CLUSTER = "net/minecraft/world/level/block/AmethystClusterBlock";
    static final String CAULDRON = "net/minecraft/world/level/block/AbstractCauldronBlock";
    static final String HELPER = "com/retromod/shim/common/embedded/LegacyCauldronInteractions";

    private static final String L_PROPERTIES = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String INT_PROVIDER = "net/minecraft/util/valueproviders/IntProvider";
    private static final String CONSTANT_INT = "net/minecraft/util/valueproviders/ConstantInt";

    static final String DROP_OLD_PROPERTIES = "(" + L_PROPERTIES + ")V";
    static final String DROP_OLD_PROPERTIES_PROVIDER = "(" + L_PROPERTIES + "L" + INT_PROVIDER + ";)V";
    static final String DROP_MODERN = "(L" + INT_PROVIDER + ";" + L_PROPERTIES + ")V";
    static final String CLUSTER_OLD = "(II" + L_PROPERTIES + ")V";
    static final String CLUSTER_MODERN = "(FF" + L_PROPERTIES + ")V";
    static final String CAULDRON_OLD = "(" + L_PROPERTIES + "Ljava/util/Map;)V";
    private static final String BLOCKS = "net/minecraft/world/level/block/";
    private static final String L_SET_TYPE = "Lnet/minecraft/world/level/block/state/properties/BlockSetType;";
    private static final String L_WOOD_TYPE = "Lnet/minecraft/world/level/block/state/properties/WoodType;";
    private static final String L_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String SIMPLE_PARTICLE = "net/minecraft/core/particles/SimpleParticleType";
    /** The signs, whose wood type moved ahead of the properties. */
    static final List<String> SIGNS = List.of("SignBlock", "StandingSignBlock", "WallSignBlock",
            "CeilingHangingSignBlock", "WallHangingSignBlock");
    static final String INTERACTION = "net/minecraft/core/cauldron/CauldronInteraction";
    /** Each vanilla table and the helper method that answers the old map read. */
    static final String[][] VANILLA_TABLES = {
            {"EMPTY", "empty"}, {"WATER", "water"}, {"LAVA", "lava"}, {"POWDER_SNOW", "powderSnow"}};

    private LegacyBlockConstructorBridge() {}

    public static void register(RetromodTransformer transformer) {
        int bridged = register(transformer, ClassResourceInspector::read);
        if (bridged > 0) LOGGER.debug("Bridged {} block constructors that 1.20.3 reshaped", bridged);
    }

    /** Registers each rewrite the host needs, reading host classes through {@code host}. */
    static int register(RetromodTransformer transformer, Function<String, ClassNode> host) {
        int bridged = 0;
        if (replaced(host.apply(DROP_EXPERIENCE), DROP_OLD_PROPERTIES, DROP_MODERN)) {
            // properties -> ConstantInt.of(0), properties
            transformer.registerSuperConstructorAdapter(DROP_EXPERIENCE, DROP_OLD_PROPERTIES, DROP_MODERN, mv -> {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CONSTANT_INT, "of", "(I)L" + CONSTANT_INT + ";", false);
                mv.visitInsn(Opcodes.SWAP);
            });
            // properties, provider -> provider, properties
            transformer.registerSuperConstructorAdapter(DROP_EXPERIENCE, DROP_OLD_PROPERTIES_PROVIDER, DROP_MODERN,
                    mv -> mv.visitInsn(Opcodes.SWAP));
            bridged += 2;
        }
        if (replaced(host.apply(AMETHYST_CLUSTER), CLUSTER_OLD, CLUSTER_MODERN)) {
            // height, offset, properties -> (float) height, (float) offset, properties
            transformer.registerSuperConstructorAdapter(AMETHYST_CLUSTER, CLUSTER_OLD, CLUSTER_MODERN, mv -> {
                mv.visitInsn(Opcodes.DUP_X2);
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.I2F);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitInsn(Opcodes.I2F);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitInsn(Opcodes.DUP2_X1);
                mv.visitInsn(Opcodes.POP2);
            });
            bridged++;
        }
        bridged += registerTypeFirstConstructors(transformer, host);
        ClassNode cauldron = host.apply(CAULDRON);
        String interactionsType = hostInteractionsType(cauldron);
        if (interactionsType != null && !hasConstructor(cauldron, CAULDRON_OLD)) {
            SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyCauldronInteractions.class);
            String modern = "(" + L_PROPERTIES + "L" + interactionsType + ";)V";
            // properties, map -> properties, the host's interactions built from the map
            transformer.registerSuperConstructorAdapter(CAULDRON, CAULDRON_OLD, modern, mv -> {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "interactions",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, interactionsType);
            });
            bridged++;
        }
        if (interactionsType != null && !declaresMapTable(host.apply(INTERACTION))) {
            SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyCauldronInteractions.class);
            for (String[] table : VANILLA_TABLES) {
                transformer.registerExactFieldRedirect(INTERACTION, table[0], "Ljava/util/Map;",
                        HELPER, table[1], "()Ljava/util/Map;");
            }
            bridged += VANILLA_TABLES.length;
        }
        return bridged;
    }

    /** The 1.20.3 constructors that put a block's type ahead of its properties. */
    private static int registerTypeFirstConstructors(RetromodTransformer transformer, Function<String, ClassNode> host) {
        int bridged = 0;
        // properties, type -> type, properties
        for (String sign : SIGNS) {
            bridged += reorder(transformer, host, BLOCKS + sign, "(" + L_PROPERTIES + L_WOOD_TYPE + ")V",
                    "(" + L_WOOD_TYPE + L_PROPERTIES + ")V", mv -> mv.visitInsn(Opcodes.SWAP));
        }
        bridged += reorder(transformer, host, BLOCKS + "MushroomBlock", "(" + L_PROPERTIES + L_KEY + ")V",
                "(" + L_KEY + L_PROPERTIES + ")V", mv -> mv.visitInsn(Opcodes.SWAP));
        // ticks, properties, type -> ticks, type, properties
        bridged += reorder(transformer, host, BLOCKS + "WeightedPressurePlateBlock", "(I" + L_PROPERTIES + L_SET_TYPE + ")V",
                "(I" + L_SET_TYPE + L_PROPERTIES + ")V", mv -> mv.visitInsn(Opcodes.SWAP));
        // properties, type, ticks, arrows -> type, ticks, properties. Arrows moved into the BlockSetType.
        bridged += reorder(transformer, host, BLOCKS + "ButtonBlock", "(" + L_PROPERTIES + L_SET_TYPE + "IZ)V",
                "(" + L_SET_TYPE + "I" + L_PROPERTIES + ")V", mv -> {
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.DUP2_X1);
                    mv.visitInsn(Opcodes.POP2);
                });
        // properties, particle -> particle, properties. The torch particle became a simple type.
        for (String torch : List.of("TorchBlock", "WallTorchBlock")) {
            bridged += reorder(transformer, host, BLOCKS + torch,
                    "(" + L_PROPERTIES + "Lnet/minecraft/core/particles/ParticleOptions;)V",
                    "(L" + SIMPLE_PARTICLE + ";" + L_PROPERTIES + ")V", mv -> {
                        mv.visitTypeInsn(Opcodes.CHECKCAST, SIMPLE_PARTICLE);
                        mv.visitInsn(Opcodes.SWAP);
                    });
        }
        return bridged;
    }

    private static int reorder(RetromodTransformer transformer, Function<String, ClassNode> host, String block,
            String oldDesc, String modernDesc, Consumer<MethodVisitor> adapter) {
        if (!replaced(host.apply(block), oldDesc, modernDesc)) return 0;
        transformer.registerSuperConstructorAdapter(block, oldDesc, modernDesc, adapter);
        return 1;
    }

    /** Whether the host still declares {@code CauldronInteraction.EMPTY} as a plain map. */
    private static boolean declaresMapTable(ClassNode interaction) {
        return interaction != null && interaction.fields.stream()
                .anyMatch(field -> field.name.equals("EMPTY") && field.desc.equals("Ljava/util/Map;"));
    }

    /** The type after {@code Properties} in the host cauldron's two-argument constructor. */
    static String hostInteractionsType(ClassNode cauldron) {
        if (cauldron == null) return null;
        for (MethodNode method : cauldron.methods) {
            if (!method.name.equals("<init>")) continue;
            Type[] params = Type.getArgumentTypes(method.desc);
            if (params.length == 2 && params[0].getDescriptor().equals(L_PROPERTIES)
                    && params[1].getSort() == Type.OBJECT
                    && !params[1].getInternalName().equals("java/util/Map")) {
                return params[1].getInternalName();
            }
        }
        return null;
    }

    private static boolean replaced(ClassNode owner, String oldDesc, String modernDesc) {
        return owner != null && hasConstructor(owner, modernDesc) && !hasConstructor(owner, oldDesc);
    }

    private static boolean hasConstructor(ClassNode owner, String desc) {
        return owner.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(desc));
    }
}
