/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLootSerializers;

/** Keeps a mod's single loot entries, built on the {@code LootPoolSingletonContainer} 26.3 removed, working on 26.3. */
public final class LegacyLootSingletonBridge {

    static final String ENTRIES = "net/minecraft/world/level/storage/loot/entries/";
    static final String SINGLETON = ENTRIES + "LootPoolSingletonContainer";
    static final String UNIFORM = ENTRIES + "UniformContainerBase";
    static final String CONTAINER = ENTRIES + "LootPoolEntryContainer";
    private static final String LIST = "Ljava/util/List;";
    static final String BASE = RebuiltApiClasses.ROOT + "minecraft/world/level/storage/loot/entries/LegacyLootPoolSingletonContainer";
    static final String FIRST_HOST_WITHOUT = "26.3";

    private static final String INSTANCE = "Lcom/mojang/serialization/codecs/RecordCodecBuilder$Instance;";
    private static final String P4 = "Lcom/mojang/datafixers/Products$P4;";
    static final String FIELDS_DESC = "(" + INSTANCE + ")" + P4;
    static final String OLD_BUILDER_DESC = "(L" + SINGLETON + "$EntryConstructor;)L" + SINGLETON + "$Builder;";
    static final String BUILDER_DESC = "(L" + BASE + "$EntryConstructor;)L" + UNIFORM + "$Builder;";

    private LegacyLootSingletonBridge() {}

    /** Registers on a host without the old base, through the 26.2 to 26.3 core moves, for mods built before 26.3. */
    public static void register(RetromodTransformer t) {
        if (!hostRemovedSingletons()) return;
        SyntheticEmbedder.registerClassResource(t, LegacyLootSerializers.class.getName().replace('.', '/'),
                LegacyLootSerializers.class);
        RebuiltApiClasses.registerTree(t, BASE);

        // Elsewhere the old base is a type: an instanceof, a cast, a field read of weight or quality,
        // an accessor mixin. 26.3's uniform base answers those for every vanilla and old entry alike.
        moveForOldMods(t, SINGLETON, UNIFORM);
        moveForOldMods(t, SINGLETON + "$EntryBase", UNIFORM + "$EntryBase");
        moveForOldMods(t, SINGLETON + "$EntryConstructor", BASE + "$EntryConstructor");
        // LegacyLootBuilderBridge moves the old builder for every caller of a vanilla factory.
        t.limitClassRedirectToModsBefore(SINGLETON + "$Builder", FIRST_HOST_WITHOUT);

        // A subclass extends the rebuilt base, which keeps the old constructor, codec fields and builder.
        t.registerRedirectedSubclassRebase(SINGLETON, UNIFORM, BASE);
        t.registerRedirectedSubclassRebase(SINGLETON + "$Builder", UNIFORM + "$Builder", BASE + "$Builder");

        // javac names the subclass as the owner of its inherited helpers, which the rebase answers.
        // A call written on the old base itself needs its own redirect.
        oldBaseCall(t, "singletonFields", FIELDS_DESC, FIELDS_DESC);
        oldBaseCall(t, "simpleBuilder", OLD_BUILDER_DESC, BUILDER_DESC);

        // Code outside an entry, such as a loot viewer, read its condition and function lists, which
        // 26.3 holds as one optional holder each. An entry's own reads name its class and keep the rebuilt fields.
        String support = LegacyLootSerializers.class.getName().replace('.', '/');
        String read = "(Ljava/lang/Object;)Ljava/util/List;";
        t.registerExactTypedFieldStaticBridge(UNIFORM, "functions", LIST, support, "entryFunctions", read, null, null);
        t.registerExactTypedFieldStaticBridge(UNIFORM, "conditions", LIST, support, "entryConditions", read, null, null);
        t.registerExactTypedFieldStaticBridge(CONTAINER, "conditions", LIST, support, "entryConditions", read, null, null);
    }

    /** Read from the host: 26.3 has the uniform base and not the old one. Offline, the target version decides. */
    static boolean hostRemovedSingletons() {
        if (ClassResourceInspector.read(UNIFORM) == null && ClassResourceInspector.read(SINGLETON) == null) {
            String host = RetromodVersion.TARGET_MC_VERSION;
            return host != null && RetromodVersion.compareMcVersions(host, FIRST_HOST_WITHOUT) >= 0;
        }
        return ClassResourceInspector.exists(UNIFORM) && !ClassResourceInspector.exists(SINGLETON);
    }

    private static void moveForOldMods(RetromodTransformer t, String removed, String destination) {
        t.registerClassRedirect(removed, destination);
        t.limitClassRedirectToModsBefore(removed, FIRST_HOST_WITHOUT);
    }

    private static void oldBaseCall(RetromodTransformer t, String name, String oldDesc, String newDesc) {
        t.registerMethodRedirect(SINGLETON, name, oldDesc, BASE, name, newDesc);
        t.limitRedirectToModsBefore(SINGLETON, name, oldDesc, FIRST_HOST_WITHOUT);
    }
}
