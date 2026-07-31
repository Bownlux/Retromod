/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the removed Fabric item-group events v1 API
 * ({@code net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents}) onto the surviving
 * {@code creativetab/v1}.
 *
 * <p>A class redirect alone can't do it: the SAM method renamed ({@code modifyEntries} ->
 * {@code modifyOutput}), so redirecting the inner interface crashes {@code LambdaMetafactory}
 * (the mod's lambda hard-codes {@code modifyEntries}), and the registration entrypoint renamed
 * too ({@code modifyEntriesEvent} -> {@code modifyOutputEvent}). So we keep the holder and the
 * SAMs alive under the old names:
 * <ol>
 *   <li>Synthetic {@code ItemGroupEvents} holder + {@code ModifyEntries}/{@code ModifyEntriesAll}
 *       SAM interfaces, routed through
 *       {@link com.retromod.shim.api.fabric.embedded.ItemGroupEventsBridge}, which wires
 *       each v1 event to its v2 counterpart by reflection.</li>
 *   <li>{@code addAfter}/{@code addBefore} on {@code FabricCreativeModeTabOutput} renamed to
 *       {@code insertAfter}/{@code insertBefore}, all 12 overloads each.</li>
 * </ol>
 *
 * <p>{@code FabricItemGroupEntries -> FabricCreativeModeTabOutput} is the version shim's job
 * ({@code Fabric_1_21_11_to_26_1}); this shim assumes it and only renames the methods on the
 * new owner.
 *
 * <p>Not yet runtime-verified: needs a 26.1 launch adding an item to a creative tab via a v1 mod.
 */
public class FabricItemGroupEventsShim implements VersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String GEN = "com/retromod/generated/legacyitemgroup/";
    private static final String HOLDER            = GEN + "ItemGroupEvents";
    private static final String MODIFY_ENTRIES    = GEN + "ModifyEntries";
    private static final String MODIFY_ENTRIES_ALL = GEN + "ModifyEntriesAll";
    private static final String BRIDGE = "com/retromod/shim/api/fabric/embedded/ItemGroupEventsBridge";

    private static final String OLD_OUTER       = "net/fabricmc/fabric/api/itemgroup/v1/ItemGroupEvents";
    private static final String OLD_OUTER_CLIENT = "net/fabricmc/fabric/api/client/itemgroup/v1/ItemGroupEvents";

    private static final String EVENT   = "net/fabricmc/fabric/api/event/Event";
    private static final String L_EVENT = "L" + EVENT + ";";
    private static final String OUTPUT  = "net/fabricmc/fabric/api/creativetab/v1/FabricCreativeModeTabOutput";
    private static final String CREATIVE_TAB = "net/minecraft/world/item/CreativeModeTab";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";

    private static final String MODIFY_ENTRIES_DESC     = "(L" + OUTPUT + ";)V";
    private static final String MODIFY_ENTRIES_ALL_DESC = "(L" + CREATIVE_TAB + ";L" + OUTPUT + ";)V";
    private static final String MODIFY_ENTRIES_EVENT_DESC = "(L" + RESOURCE_KEY + ";)" + L_EVENT;

    /** The 12 entry-insertion overload descriptors (Mojang form), shared by add and insert (fabric-api-0.145.4). */
    private static final String[] INSERT_DESCS = {
        "(Ljava/util/function/Predicate;Ljava/util/Collection;Ljava/util/List;)V",
        "(Ljava/util/function/Predicate;Ljava/util/Collection;Lnet/minecraft/world/item/CreativeModeTab$TabVisibility;)V",
        "(Lnet/minecraft/world/item/ItemStack;Ljava/util/Collection;)V",
        "(Lnet/minecraft/world/item/ItemStack;Ljava/util/Collection;Ljava/util/List;)V",
        "(Lnet/minecraft/world/item/ItemStack;Ljava/util/Collection;Lnet/minecraft/world/item/CreativeModeTab$TabVisibility;)V",
        "(Lnet/minecraft/world/item/ItemStack;[Lnet/minecraft/world/item/ItemStack;)V",
        "(Lnet/minecraft/world/item/ItemStack;[Lnet/minecraft/world/level/ItemLike;)V",
        "(Lnet/minecraft/world/level/ItemLike;Ljava/util/Collection;)V",
        "(Lnet/minecraft/world/level/ItemLike;Ljava/util/Collection;Ljava/util/List;)V",
        "(Lnet/minecraft/world/level/ItemLike;Ljava/util/Collection;Lnet/minecraft/world/item/CreativeModeTab$TabVisibility;)V",
        "(Lnet/minecraft/world/level/ItemLike;[Lnet/minecraft/world/item/ItemStack;)V",
        "(Lnet/minecraft/world/level/ItemLike;[Lnet/minecraft/world/level/ItemLike;)V",
    };

    @Override public String getShimName() { return "Fabric item-group events v1 (ItemGroupEvents) bridge"; }
    @Override public String getSourceVersion() { return "0.40.0"; }
    @Override public String getTargetVersion() { return "0.145.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 26.1+ hosts only (#9): pre-26.1 still ships the v1 API, so the synthetics would
        // shadow a working API and reference types that don't resolve.
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            LOGGER.debug("[Retromod] item-group events v1 bridge skipped (host {} < 26.1 - old API still present)",
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
            return;
        }

        transformer.registerEmbeddedShim(BRIDGE.replace('/', '.'));

        transformer.registerSyntheticClass(HOLDER, generateHolder());
        transformer.registerSyntheticClass(MODIFY_ENTRIES, generateModifyEntries());
        transformer.registerSyntheticClass(MODIFY_ENTRIES_ALL, generateModifyEntriesAll());

        for (String outer : new String[]{OLD_OUTER, OLD_OUTER_CLIENT}) {
            transformer.registerClassRedirect(outer, HOLDER);
            transformer.registerClassRedirect(outer + "$ModifyEntries", MODIFY_ENTRIES);
            transformer.registerClassRedirect(outer + "$ModifyEntriesAll", MODIFY_ENTRIES_ALL);
        }

        // Register each overload after the output class has been redirected.
        for (String desc : INSERT_DESCS) {
            transformer.registerMethodRedirect(OUTPUT, "addAfter", desc, OUTPUT, "insertAfter", desc);
            transformer.registerMethodRedirect(OUTPUT, "addBefore", desc, OUTPUT, "insertBefore", desc);
        }

        LOGGER.debug("Installed the Fabric item group events v1 bridge. "
                + "An in-game verification run is still pending.");
    }

    /** Synthetic holder: {@code modifyEntriesEvent} forwards to the bridge, {@code MODIFY_ENTRIES_ALL} set in {@code <clinit>}. */
    static byte[] generateHolder() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HOLDER, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "MODIFY_ENTRIES_ALL", L_EVENT, null, null).visitEnd();

        // public static Event modifyEntriesEvent(ResourceKey key) {
        //     return (Event) ItemGroupEventsBridge.modifyEntriesEvent(key);
        // }
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "modifyEntriesEvent", MODIFY_ENTRIES_EVENT_DESC, null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "modifyEntriesEvent",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        m.visitTypeInsn(Opcodes.CHECKCAST, EVENT);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        // static { MODIFY_ENTRIES_ALL = (Event) ItemGroupEventsBridge.installModifyAll(); }
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "installModifyAll",
                "()Ljava/lang/Object;", false);
        clinit.visitTypeInsn(Opcodes.CHECKCAST, EVENT);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, HOLDER, "MODIFY_ENTRIES_ALL", L_EVENT);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Synthetic {@code ModifyEntries}: SAM {@code modifyEntries(FabricCreativeModeTabOutput)}. */
    static byte[] generateModifyEntries() {
        return samInterface(MODIFY_ENTRIES, "modifyEntries", MODIFY_ENTRIES_DESC);
    }

    /** Synthetic {@code ModifyEntriesAll}: SAM {@code modifyEntries(CreativeModeTab, FabricCreativeModeTabOutput)}. */
    static byte[] generateModifyEntriesAll() {
        return samInterface(MODIFY_ENTRIES_ALL, "modifyEntries", MODIFY_ENTRIES_ALL_DESC);
    }

    private static byte[] samInterface(String internalName, String samName, String samDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, samName, samDesc, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
