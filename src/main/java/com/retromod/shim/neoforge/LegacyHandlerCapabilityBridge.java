/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.neoforge.embedded.LegacyHandlerCapabilities;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps NeoForge 1.21.1's item, fluid, and energy capabilities working on hosts that replaced them, 1.21.11 included. */
public final class LegacyHandlerCapabilityBridge {

    static final String SUPPORT = "com/retromod/shim/neoforge/embedded/LegacyHandlerCapabilities";
    static final String ITEM_CAPS = "com/retromod/generated/LegacyItemHandlerCapabilities";
    static final String FLUID_CAPS = "com/retromod/generated/LegacyFluidHandlerCapabilities";
    static final String ENERGY_CAPS = "com/retromod/generated/LegacyEnergyStorageCapabilities";
    static final String ITEM_ADAPTER = "com/retromod/generated/LegacyItemResourceHandler";
    static final String FLUID_ADAPTER = "com/retromod/generated/LegacyFluidResourceHandler";
    static final String ENERGY_ADAPTER = "com/retromod/generated/LegacyEnergyHandler";
    static final String CALLS = "com/retromod/generated/LegacyHandlerCapabilityCalls";

    private static final String CAPS = "net/neoforged/neoforge/capabilities/";
    static final String OLD_ITEM_CAPS = CAPS + "Capabilities$ItemHandler";
    static final String OLD_FLUID_CAPS = CAPS + "Capabilities$FluidHandler";
    static final String OLD_ENERGY_CAPS = CAPS + "Capabilities$EnergyStorage";
    private static final String EVENT = CAPS + "RegisterCapabilitiesEvent";
    private static final String BLOCK_CAP = "L" + CAPS + "BlockCapability;";
    private static final String ENTITY_CAP = "L" + CAPS + "EntityCapability;";
    private static final String ITEM_CAP = "L" + CAPS + "ItemCapability;";
    private static final String PROVIDER = "L" + CAPS + "ICapabilityProvider;";
    private static final String BLOCK_PROVIDER = "L" + CAPS + "IBlockCapabilityProvider;";
    private static final String CACHE = CAPS + "BlockCapabilityCache";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String BLOCK_ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String JOURNAL = "net/neoforged/neoforge/transfer/transaction/SnapshotJournal";
    private static final String RESOURCE_HANDLER = "net/neoforged/neoforge/transfer/ResourceHandler";
    private static final String ENERGY_HANDLER = "net/neoforged/neoforge/transfer/energy/EnergyHandler";
    private static final String RESOURCE = "Lnet/neoforged/neoforge/transfer/resource/Resource;";
    private static final String TRANSACTION = "Lnet/neoforged/neoforge/transfer/transaction/TransactionContext;";
    private static final String OBJECT = "Ljava/lang/Object;";

    /** Every Minecraft class a mod calls the removed entity lookup on, beside its own entities. */
    private static final List<String> ENTITY_OWNERS = List.of("net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/Mob",
            "net/minecraft/world/entity/player/Player", "net/minecraft/server/level/ServerPlayer",
            "net/minecraft/client/player/LocalPlayer", "net/minecraft/client/player/AbstractClientPlayer");
    private static final List<String> LEVEL_OWNERS = List.of("net/minecraft/world/level/Level",
            "net/minecraft/server/level/ServerLevel", "net/minecraft/client/multiplayer/ClientLevel",
            "net/neoforged/neoforge/common/extensions/ILevelExtension");

    private LegacyHandlerCapabilityBridge() {}

    /** Registers on a host whose capabilities hold resource handlers, without the old handler ones. */
    public static void register(RetromodTransformer t) {
        // 26.3 removed the handler types as well; the rebuilt API brings them and the old capabilities back.
        if (LegacyTransferApi.hostRemovedHandlers()) {
            LegacyTransferApi.registerCapabilities(t);
            return;
        }
        register(t, ClassResourceInspector.exists(CAPS + "Capabilities$Item") && !ClassResourceInspector.exists(OLD_ITEM_CAPS));
    }

    static void register(RetromodTransformer t, boolean hostReplacedTheCapabilities) {
        if (!hostReplacedTheCapabilities) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyHandlerCapabilities.class);
        t.registerSyntheticClass(ITEM_CAPS, generateCapabilities(ITEM_CAPS, "item",
                List.of("BLOCK", "ENTITY", "ENTITY_AUTOMATION", "ITEM")));
        t.registerSyntheticClass(FLUID_CAPS, generateCapabilities(FLUID_CAPS, "fluid", List.of("BLOCK", "ENTITY", "ITEM")));
        t.registerSyntheticClass(ENERGY_CAPS, generateCapabilities(ENERGY_CAPS, "energy", List.of("BLOCK", "ENTITY", "ITEM")));
        t.registerSyntheticClass(ITEM_ADAPTER, generateAdapter(ITEM_ADAPTER, "item"));
        t.registerSyntheticClass(FLUID_ADAPTER, generateAdapter(FLUID_ADAPTER, "fluid"));
        t.registerSyntheticClass(ENERGY_ADAPTER, generateAdapter(ENERGY_ADAPTER, "energy"));
        t.registerSyntheticClass(CALLS, generateCalls());

        // The old capabilities are real ones under Retromod's ids, so old mods find each other.
        t.registerClassRedirect(OLD_ITEM_CAPS, ITEM_CAPS);
        t.registerClassRedirect(OLD_FLUID_CAPS, FLUID_CAPS);
        t.registerClassRedirect(OLD_ENERGY_CAPS, ENERGY_CAPS);
        registerCallRedirects(t, CALLS, false);
    }

    /**
     * Sends an old mod's capability registrations and lookups to {@code calls}. The rebuilt calls
     * of 26.3 also take the capabilities' own lookups and the cache with an invalidation listener.
     */
    static void registerCallRedirects(RetromodTransformer t, String calls, boolean rebuilt) {
        // A registration on an old capability is also offered on the current one.
        String event = "L" + EVENT + ";";
        registration(t, calls, "registerBlockEntity", "(" + BLOCK_CAP + "Lnet/minecraft/world/level/block/entity/BlockEntityType;" + PROVIDER + ")V", event);
        registration(t, calls, "registerBlock", "(" + BLOCK_CAP + BLOCK_PROVIDER + "[Lnet/minecraft/world/level/block/Block;)V", event);
        registration(t, calls, "registerEntity", "(" + ENTITY_CAP + "Lnet/minecraft/world/entity/EntityType;" + PROVIDER + ")V", event);
        registration(t, calls, "registerItem", "(" + ITEM_CAP + PROVIDER + "[Lnet/minecraft/world/level/ItemLike;)V", event);

        // A lookup on an old capability falls back to the current one.
        for (String owner : LEVEL_OWNERS) {
            for (String[] shape : LEVEL_SHAPES) {
                t.registerMethodRedirect(owner, "getCapability", shape[0], calls, shape[1], "(" + OBJECT + shape[0].substring(1), true);
            }
        }
        t.registerMethodRedirect("net/minecraft/world/item/ItemStack", "getCapability", "(" + ITEM_CAP + ")" + OBJECT,
                calls, "stackCapability", "(" + STACK + ITEM_CAP + ")" + OBJECT, true);
        t.registerMethodRedirect("net/minecraft/world/item/ItemStack", "getCapability", "(" + ITEM_CAP + OBJECT + ")" + OBJECT,
                calls, "stackCapability", "(" + STACK + ITEM_CAP + OBJECT + ")" + OBJECT, true);
        // 26.1.2 removed the entity lookups; the capability asks the entity instead.
        for (String desc : List.of("(" + ENTITY_CAP + ")" + OBJECT, "(" + ENTITY_CAP + OBJECT + ")" + OBJECT)) {
            String helperDesc = "(" + OBJECT + desc.substring(1);
            t.registerInheritedMethodRedirect(ENTITY_OWNERS.get(0), "getCapability", desc, calls, "entityCapability", helperDesc);
            for (String owner : ENTITY_OWNERS) {
                t.registerMethodRedirect(owner, "getCapability", desc, calls, "entityCapability", helperDesc, true);
            }
        }
        String level = "Lnet/minecraft/server/level/ServerLevel;";
        t.registerMethodRedirect(CACHE, "create", "(" + BLOCK_CAP + level + POS + OBJECT + ")L" + CACHE + ";",
                calls, "cache", "(" + BLOCK_CAP + level + POS + OBJECT + ")L" + CACHE + ";");
        t.registerMethodRedirect(CACHE, "getCapability", "()" + OBJECT, calls, "cached", "(L" + CACHE + ";)" + OBJECT, true);
        if (!rebuilt) return;
        String listened = "(" + BLOCK_CAP + level + POS + OBJECT + "Ljava/util/function/BooleanSupplier;Ljava/lang/Runnable;)L" + CACHE + ";";
        t.registerMethodRedirect(CACHE, "create", listened, calls, "cache", listened);
        // A capability asked directly, rather than through the level, stack, or entity.
        String blockLookup = "(Lnet/minecraft/world/level/Level;" + POS + STATE + BLOCK_ENTITY + OBJECT + ")" + OBJECT;
        t.registerMethodRedirect(CAPS + "BlockCapability", "getCapability", blockLookup,
                calls, "blockCapability", "(" + BLOCK_CAP + blockLookup.substring(1), true);
        String itemLookup = "(" + STACK + OBJECT + ")" + OBJECT;
        t.registerMethodRedirect(CAPS + "ItemCapability", "getCapability", itemLookup,
                calls, "itemCapability", "(" + ITEM_CAP + itemLookup.substring(1), true);
        String entityLookup = "(Lnet/minecraft/world/entity/Entity;" + OBJECT + ")" + OBJECT;
        t.registerMethodRedirect(CAPS + "EntityCapability", "getCapability", entityLookup,
                calls, "entityCapabilityOn", "(" + ENTITY_CAP + entityLookup.substring(1), true);
    }

    /** The four level lookups and the helper each goes to. */
    private static final String[][] LEVEL_SHAPES = {
        {"(" + BLOCK_CAP + POS + OBJECT + ")" + OBJECT, "levelCapability"},
        {"(" + BLOCK_CAP + POS + STATE + BLOCK_ENTITY + OBJECT + ")" + OBJECT, "levelCapabilityAt"},
        {"(" + BLOCK_CAP + POS + ")" + OBJECT, "levelCapability"},
        {"(" + BLOCK_CAP + POS + STATE + BLOCK_ENTITY + ")" + OBJECT, "levelCapabilityAt"},
    };

    private static void registration(RetromodTransformer t, String calls, String name, String desc, String event) {
        t.registerMethodRedirect(EVENT, name, desc, calls, name, "(" + event + desc.substring(1), true);
    }

    // ---- generated classes -----------------------------------------------------------------------

    /** {@code Capabilities.ItemHandler}, {@code FluidHandler}, or {@code EnergyStorage}: each field is the old capability under Retromod's id. */
    static byte[] generateCapabilities(String name, String kind, List<String> fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (String field : fields) {
            String desc = switch (field) {
                case "BLOCK" -> BLOCK_CAP;
                case "ITEM" -> ITEM_CAP;
                default -> ENTITY_CAP;
            };
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, field, desc, null, null).visitEnd();
            init.visitLdcInsn(kind);
            init.visitLdcInsn(field.toLowerCase(java.util.Locale.ROOT));
            init.visitMethodInsn(INVOKESTATIC, SUPPORT, "capability", "(Ljava/lang/String;Ljava/lang/String;)" + OBJECT, false);
            init.visitTypeInsn(CHECKCAST, Type.getType(desc).getInternalName());
            init.visitFieldInsn(PUTSTATIC, name, field, desc);
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * An old handler as a current {@code ResourceHandler}, or an old energy storage as an
     * {@code EnergyHandler}. It is a snapshot journal, so the transaction that changes it can undo
     * the change; the helper does the work.
     */
    static byte[] generateAdapter(String name, String kind) {
        boolean energy = kind.equals("energy");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, JOURNAL,
                new String[]{energy ? ENERGY_HANDLER : RESOURCE_HANDLER});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "handler", OBJECT, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + OBJECT + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, JOURNAL, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, name, "handler", OBJECT);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        if (energy) {
            // An energy handler is one amount, with no slots or resources.
            delegate(cw, name, kind, "getAmountAsLong", "()J", "Amount", false);
            delegate(cw, name, kind, "getCapacityAsLong", "()J", "Capacity", false);
            delegate(cw, name, kind, "insert", "(I" + TRANSACTION + ")I", "Insert", true);
            delegate(cw, name, kind, "extract", "(I" + TRANSACTION + ")I", "Extract", true);
        } else {
            delegate(cw, name, kind, "size", "()I", "Size", false);
            delegate(cw, name, kind, "getResource", "(I)" + RESOURCE, "Resource", false);
            delegate(cw, name, kind, "getAmountAsLong", "(I)J", "Amount", false);
            delegate(cw, name, kind, "getCapacityAsLong", "(I" + RESOURCE + ")J", "Capacity", false);
            delegate(cw, name, kind, "isValid", "(I" + RESOURCE + ")Z", "Valid", false);
            delegate(cw, name, kind, "insert", "(I" + RESOURCE + "I" + TRANSACTION + ")I", "Insert", true);
            delegate(cw, name, kind, "extract", "(I" + RESOURCE + "I" + TRANSACTION + ")I", "Extract", true);
        }
        delegate(cw, name, kind, "createSnapshot", "()" + OBJECT, "Snapshot", true);
        delegate(cw, name, kind, "revertToSnapshot", "(" + OBJECT + ")V", "Revert", true);
        delegate(cw, name, kind, "onRootCommit", "(" + OBJECT + ")V", "Commit", true);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code name(args)}: {@code SUPPORT.<kind><helper>([this,] handler, args)}, references passed as
     * objects. Only the transaction steps need the adapter itself. Commit takes no arguments past the handler.
     */
    private static void delegate(ClassWriter cw, String owner, String kind, String name, String desc, String helper,
            boolean passAdapter) {
        boolean commit = helper.equals("Commit");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        StringBuilder helperDesc = new StringBuilder("(");
        if (passAdapter) {
            mv.visitVarInsn(ALOAD, 0);
            helperDesc.append(OBJECT);
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, owner, "handler", OBJECT);
        helperDesc.append(OBJECT);
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(desc)) {
            if (!commit) {
                mv.visitVarInsn(argument.getOpcode(ILOAD), slot);
                helperDesc.append(argument.getSort() == Type.OBJECT ? OBJECT : argument.getDescriptor());
            }
            slot += argument.getSize();
        }
        Type result = Type.getReturnType(desc);
        boolean reference = result.getSort() == Type.OBJECT;
        helperDesc.append(")").append(reference ? OBJECT : result.getDescriptor());
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, kind + helper, helperDesc.toString(), false);
        if (reference && !result.getInternalName().equals("java/lang/Object")) mv.visitTypeInsn(CHECKCAST, result.getInternalName());
        mv.visitInsn(result.getOpcode(IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Exact-typed adapters for the redirected calls. */
    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        String event = "L" + EVENT + ";";
        registerCall(cw, "registerBlockEntity", "(" + BLOCK_CAP + "Lnet/minecraft/world/level/block/entity/BlockEntityType;" + PROVIDER + ")V", "mirrorBlockEntity", event);
        registerCall(cw, "registerBlock", "(" + BLOCK_CAP + BLOCK_PROVIDER + "[Lnet/minecraft/world/level/block/Block;)V", "mirrorBlock", event);
        registerCall(cw, "registerEntity", "(" + ENTITY_CAP + "Lnet/minecraft/world/entity/EntityType;" + PROVIDER + ")V", "mirrorEntity", event);
        registerCall(cw, "registerItem", "(" + ITEM_CAP + PROVIDER + "[Lnet/minecraft/world/level/ItemLike;)V", "mirrorItem", event);

        // level.getCapability(...): (level, cap, pos, [ctx]) and (level, cap, pos, state, be, [ctx]).
        lookup(cw, "levelCapability", "(" + OBJECT + BLOCK_CAP + POS + OBJECT + ")" + OBJECT, new int[]{0, 1, 2, -1, -1, 3}, false);
        lookup(cw, "levelCapability", "(" + OBJECT + BLOCK_CAP + POS + ")" + OBJECT, new int[]{0, 1, 2, -1, -1, -1}, false);
        lookup(cw, "levelCapabilityAt", "(" + OBJECT + BLOCK_CAP + POS + STATE + BLOCK_ENTITY + OBJECT + ")" + OBJECT, new int[]{0, 1, 2, 3, 4, 5}, true);
        lookup(cw, "levelCapabilityAt", "(" + OBJECT + BLOCK_CAP + POS + STATE + BLOCK_ENTITY + ")" + OBJECT, new int[]{0, 1, 2, 3, 4, -1}, true);
        forward(cw, "stackCapability", "(" + STACK + ITEM_CAP + ")" + OBJECT, "stack", new int[]{0, 1, -1});
        forward(cw, "stackCapability", "(" + STACK + ITEM_CAP + OBJECT + ")" + OBJECT, "stack", new int[]{0, 1, 2});
        forward(cw, "entityCapability", "(" + OBJECT + ENTITY_CAP + ")" + OBJECT, "entity", new int[]{0, 1, -1});
        forward(cw, "entityCapability", "(" + OBJECT + ENTITY_CAP + OBJECT + ")" + OBJECT, "entity", new int[]{0, 1, 2});
        forward(cw, "cache", "(" + BLOCK_CAP + "Lnet/minecraft/server/level/ServerLevel;" + POS + OBJECT + ")L" + CACHE + ";", "cache", new int[]{0, 1, 2, 3});
        forward(cw, "cached", "(L" + CACHE + ";)" + OBJECT, "cached", new int[]{0});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code event.name(args)} as before, then the helper offers it on the current capability. */
    private static void registerCall(ClassWriter cw, String name, String desc, String mirror, String event) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + event + desc.substring(1), null, null);
        mv.visitCode();
        for (int slot = 0; slot < 4; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKEVIRTUAL, EVENT, name, desc, false);
        for (int slot = 0; slot < 4; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitLdcInsn(Type.getObjectType(ITEM_ADAPTER));
        mv.visitLdcInsn(Type.getObjectType(FLUID_ADAPTER));
        mv.visitLdcInsn(Type.getObjectType(ENERGY_ADAPTER));
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, mirror,
                "(" + OBJECT.repeat(4) + "Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code SUPPORT.level(level, cap, pos, state, be, ctx, full)}, a missing argument passed as null. */
    private static void lookup(ClassWriter cw, String name, String desc, int[] slots, boolean full) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        for (int slot : slots) {
            if (slot < 0) mv.visitInsn(ACONST_NULL);
            else mv.visitVarInsn(ALOAD, slot);
        }
        mv.visitInsn(full ? ICONST_1 : ICONST_0);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "level", "(" + OBJECT.repeat(6) + "Z)" + OBJECT, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code SUPPORT.helper(args)}, a missing argument passed as null, the result cast back. */
    private static void forward(ClassWriter cw, String name, String desc, String helper, int[] slots) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        for (int slot : slots) {
            if (slot < 0) mv.visitInsn(ACONST_NULL);
            else mv.visitVarInsn(ALOAD, slot);
        }
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, helper, "(" + OBJECT.repeat(slots.length) + ")" + OBJECT, false);
        String result = Type.getReturnType(desc).getInternalName();
        if (!result.equals("java/lang/Object")) mv.visitTypeInsn(CHECKCAST, result);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
