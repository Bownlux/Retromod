/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyNumberProvider;
import com.retromod.shim.common.embedded.LegacyNumberProviders;
import com.retromod.shim.common.embedded.LegacyNumberValue;

/**
 * Keeps loot numbers built in code working on 26.3, which removed {@code NumberProvider}.
 *
 * <p>Through 26.2 one float-valued provider family served every consumer, and mods built trade
 * sets, loot pools, and item counts with {@code ConstantValue.exactly(2)} or
 * {@code UniformGenerator.between(1, 3)}. 26.3 split it into {@code ints} and {@code floats}
 * families and made the consumers take a {@code Holder} of one of them. A class that only named the
 * old interface in a descriptor then failed verification with {@code NoClassDefFoundError}, which
 * stopped the mod's entry point before it registered anything after that class.
 *
 * <p>The old types become one embedded stand-in, the old factories build values that remember what
 * the mod wrote, and the consumers convert them to the family their 26.3 signature names. A mod
 * that implements {@code NumberProvider} itself, or hands an old provider to a consumer not listed
 * here, still needs a port.
 */
public final class LegacyNumberProviderBridge {

    static final String NUMBER = "net/minecraft/world/level/storage/loot/providers/number/";
    static final String NUMBER_PROVIDER = NUMBER + "NumberProvider";
    static final String CONSTANT_VALUE = NUMBER + "ConstantValue";
    static final String UNIFORM_GENERATOR = NUMBER + "UniformGenerator";
    static final String BINOMIAL = NUMBER + "BinomialDistributionGenerator";
    static final String TRADE_SET = "net/minecraft/world/item/trading/TradeSet";
    static final String POOL_BUILDER = "net/minecraft/world/level/storage/loot/LootPool$Builder";
    static final String SET_COUNT = "net/minecraft/world/level/storage/loot/functions/SetItemCountFunction";

    static final String LEGACY = "com/retromod/shim/common/embedded/LegacyNumberProvider";
    static final String VALUE = "com/retromod/shim/common/embedded/LegacyNumberValue";
    static final String HELPER = "com/retromod/shim/common/embedded/LegacyNumberProviders";

    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String L_LEGACY = "L" + LEGACY + ";";
    private static final String L_NUMBER_PROVIDER = "L" + NUMBER_PROVIDER + ";";

    private LegacyNumberProviderBridge() {}

    /** Registers on a host from 26.3 on, through the 26.2 to 26.3 core moves. */
    public static void register(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, LEGACY, LegacyNumberProvider.class);
        SyntheticEmbedder.registerClassResource(t, VALUE, LegacyNumberValue.class);
        SyntheticEmbedder.registerClassResource(t, HELPER, LegacyNumberProviders.class);

        // ConstantValue and UniformGenerator exist in both 26.3 families, so the old names cannot
        // move to either one. They share the stand-in with the interface, which keeps a field,
        // local, or frame typed with the old class consistent with what the factories return.
        for (String removed : new String[] {NUMBER_PROVIDER, CONSTANT_VALUE, UNIFORM_GENERATOR}) {
            t.registerClassRedirect(removed, LEGACY);
        }
        registerFactories(t);
        registerConsumers(t);
    }

    private static void registerFactories(RetromodTransformer t) {
        t.registerMethodRedirect(CONSTANT_VALUE, "exactly", "(F)L" + CONSTANT_VALUE + ";",
                HELPER, "exactly", "(F)" + L_LEGACY);
        t.registerConstructorRedirect(CONSTANT_VALUE, "(F)V", HELPER, "exactly", "(F)" + L_LEGACY);
        t.registerMethodRedirect(UNIFORM_GENERATOR, "between", "(FF)L" + UNIFORM_GENERATOR + ";",
                HELPER, "between", "(FF)" + L_LEGACY);
        t.registerConstructorRedirect(UNIFORM_GENERATOR, "(" + L_NUMBER_PROVIDER + L_NUMBER_PROVIDER + ")V",
                HELPER, "uniform", "(" + OBJECT + OBJECT + ")" + L_LEGACY);
        // The core moves send this class to the int family, which has no binomial(int, float).
        t.registerMethodRedirect(BINOMIAL, "binomial", "(IF)L" + BINOMIAL + ";",
                HELPER, "binomial", "(IF)" + OBJECT);
    }

    private static void registerConsumers(RetromodTransformer t) {
        t.registerConstructorRedirect(TRADE_SET,
                "(Lnet/minecraft/core/HolderSet;" + L_NUMBER_PROVIDER + "ZLjava/util/Optional;)V",
                HELPER, "tradeSet", "(" + OBJECT + OBJECT + "ZLjava/util/Optional;)" + OBJECT);
        String builderReturn = "L" + POOL_BUILDER + ";";
        t.registerMethodRedirect(POOL_BUILDER, "setRolls", "(" + L_NUMBER_PROVIDER + ")" + builderReturn,
                HELPER, "setRolls", "(" + OBJECT + OBJECT + ")" + OBJECT, true);
        t.registerMethodRedirect(POOL_BUILDER, "setBonusRolls", "(" + L_NUMBER_PROVIDER + ")" + builderReturn,
                HELPER, "setBonusRolls", "(" + OBJECT + OBJECT + ")" + OBJECT, true);
        String functionBuilder = "Lnet/minecraft/world/level/storage/loot/functions/LootItemConditionalFunction$Builder;";
        t.registerMethodRedirect(SET_COUNT, "setCount", "(" + L_NUMBER_PROVIDER + ")" + functionBuilder,
                HELPER, "setCount", "(" + OBJECT + ")" + OBJECT);
        t.registerMethodRedirect(SET_COUNT, "setCount", "(" + L_NUMBER_PROVIDER + "Z)" + functionBuilder,
                HELPER, "setCount", "(" + OBJECT + "Z)" + OBJECT);
    }
}
