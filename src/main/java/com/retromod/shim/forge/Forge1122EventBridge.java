/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyEventBus1122;
import com.retromod.shim.forge.embedded.LegacyEventBusSubscriber;
import com.retromod.shim.forge.embedded.LegacyForgeEvents;
import com.retromod.shim.forge.embedded.LegacyHandlers;
import com.retromod.shim.forge.embedded.LegacyModEventHandler;
import com.retromod.shim.forge.embedded.LegacyModInstance;
import com.retromod.shim.forge.embedded.LegacySidedProxy;
import com.retromod.shim.forge.embedded.LegacySubscribeEvent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * The 1.12.2 Forge event bus and registry idiom on a modern Forge or NeoForge host.
 *
 * <p>A 1.12.2 mod registers content from {@code @SubscribeEvent} handlers taking
 * {@code RegistryEvent.Register<T>}, posted on {@code MinecraftForge.EVENT_BUS}, and names each
 * entry with {@code setRegistryName}. None of that survives: the annotation and bus types are
 * gone, the modern registry event is the mod-bus {@code RegisterEvent}, and names moved out of
 * the entries. The pieces here map each part onto {@link LegacyForgeEvents}, which runs inside
 * the mod:
 * <ul>
 *   <li>The 1.12 annotations ({@code @SubscribeEvent}, {@code @Mod.EventHandler},
 *       {@code @SidedProxy}, {@code @Mod.Instance}) become Retromod annotations the host ignores
 *       and the bridge reads.</li>
 *   <li>The old {@code EventBus} type becomes {@link LegacyEventBus1122}; reads of
 *       {@code EVENT_BUS} and {@code bus()} typed with it are retargeted by
 *       {@link #rewriteLegacyCalls}.</li>
 *   <li>{@code RegistryEvent$Register} gains a constructor carrying a registrar and a working
 *       {@code getRegistry()}; {@code IForgeRegistry.register/registerAll} on it call the
 *       bridge.</li>
 *   <li>{@code setRegistryName} and {@code getRegistryName} calls, on any owner, call the bridge.
 *       No supported host declares them with these shapes.</li>
 * </ul>
 */
public final class Forge1122EventBridge {

    private Forge1122EventBridge() {}

    static final String EVENTS = "com/retromod/shim/forge/embedded/LegacyForgeEvents";
    static final String BUS = "com/retromod/shim/forge/embedded/LegacyEventBus1122";
    static final String SUBSCRIBE = "com/retromod/shim/forge/embedded/LegacySubscribeEvent";
    static final String SUBSCRIBER = "com/retromod/shim/forge/embedded/LegacyEventBusSubscriber";
    static final String REGISTER_EVENT = "com/retromod/generated/forge1122/RegistryEvent$Register";
    static final String HANDLERS = "com/retromod/shim/forge/embedded/LegacyHandlers";
    static final String INSTANCE = "com/retromod/shim/forge/embedded/LegacyModInstance";
    static final String SIDED_PROXY = "com/retromod/shim/forge/embedded/LegacySidedProxy";

    private static final String OLD_BUS = "net/minecraftforge/fml/common/eventhandler/EventBus";
    private static final String OLD_SUBSCRIBER_DESC = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
    /**
     * The same annotation after the Forge-on-NeoForge bridge renamed it. That class redirect runs
     * before this pass, and left in place NeoForge loads the 1.12 class itself, client-only
     * references included, which fails the mod on a dedicated server.
     */
    private static final String NEO_SUBSCRIBER_DESC = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    private static final String IFORGE_REGISTRY = "net/minecraftforge/registries/IForgeRegistry";
    private static final String OLD_ENTRY = "Lnet/minecraftforge/registries/IForgeRegistryEntry;";
    private static final String L_BUS = "L" + BUS + ";";
    private static final String L_OBJ = "Ljava/lang/Object;";

    /**
     * Forge registries that declare their own {@code getRegistryName()}, the name of the registry
     * itself. Forge 1.20.1 still has it, and every transformed mod reaches this pass, so a newer
     * mod's call on a registry must stay a direct call to the host.
     */
    private static final Set<String> FORGE_REGISTRIES = Set.of(IFORGE_REGISTRY,
            "net/minecraftforge/registries/ForgeRegistry");

    /** Return types {@code setRegistryName} has after the class remap, depending on the host. */
    private static final Set<String> SET_NAME_RETURNS = Set.of(
            L_OBJ, OLD_ENTRY, "Lcom/retromod/generated/forge1122/IForgeRegistryEntry;");

    private static final Set<String> NAME_TYPES = Set.of("()" + L_OBJ,
            "()Lnet/minecraft/resources/ResourceLocation;", "()Lnet/minecraft/resources/Identifier;");

    /** Set once the 1.12.2 chain registered; gates {@link #rewriteLegacyCalls}. */
    private static volatile boolean active;

    static void resetForTesting() {
        active = false;
    }

    /** Register the runtime classes and every redirect that routes the 1.12 idiom to them. */
    public static void register(RetromodTransformer t) {
        active = true;
        t.onRegistrationReset(() -> active = false);
        SyntheticEmbedder.registerClassResource(t, EVENTS, LegacyForgeEvents.class);
        SyntheticEmbedder.registerClassResource(t, BUS, LegacyEventBus1122.class);
        SyntheticEmbedder.registerClassResource(t, SUBSCRIBE, LegacySubscribeEvent.class);
        SyntheticEmbedder.registerClassResource(t, SUBSCRIBER, LegacyEventBusSubscriber.class);
        SyntheticEmbedder.registerClassResource(t, HANDLERS, LegacyHandlers.class);
        String handler = "com/retromod/shim/forge/embedded/LegacyModEventHandler";
        String sidedProxy = SIDED_PROXY;
        String instance = INSTANCE;
        SyntheticEmbedder.registerClassResource(t, handler, LegacyModEventHandler.class);
        SyntheticEmbedder.registerClassResource(t, sidedProxy, LegacySidedProxy.class);
        SyntheticEmbedder.registerClassResource(t, instance, LegacyModInstance.class);

        // All four names were deleted in Forge 1.13, so no later mod carries them.
        t.registerClassRedirect("net/minecraftforge/fml/common/eventhandler/SubscribeEvent", SUBSCRIBE);
        t.registerClassRedirect("net/minecraftforge/fml/common/Mod$EventHandler", handler);
        t.registerClassRedirect("net/minecraftforge/fml/common/SidedProxy", sidedProxy);
        t.registerClassRedirect("net/minecraftforge/fml/common/Mod$Instance", instance);
        t.registerClassRedirect(OLD_BUS, BUS);

        // new ItemBlock(block): 1.13 added the Item.Properties argument.
        t.registerSuperConstructorRedirect("net/minecraft/world/item/BlockItem",
                "(Lnet/minecraft/world/level/block/Block;)V",
                "(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/item/Item$Properties;)V");
        // new ItemStack(item[, count[, meta]]): the host takes an ItemLike and has no metadata.
        t.registerSyntheticClass(STACKS, itemStackFactoryBytes());
        for (String type : new String[]{"net/minecraft/world/item/Item",
                "net/minecraft/world/level/block/Block"}) {
            for (String tail : new String[]{"", "I", "II"}) {
                t.registerConstructorRedirect("net/minecraft/world/item/ItemStack",
                        "(L" + type + ";" + tail + ")V", STACKS, "of",
                        "(L" + ITEM_LIKE + ";" + tail + ")L" + ITEM_STACK + ";");
            }
        }

        // new SoundEvent(name): private since 1.19.3, built through a static factory instead.
        t.registerConstructorRedirect(SOUND_EVENT, "(L" + RESOURCE_LOCATION + ";)V", STACKS, "sound",
                "(L" + RESOURCE_LOCATION + ";)L" + SOUND_EVENT + ";");

        // getRegistry() on the bridged Register event hands back a registrar, not a host registry.
        t.registerMethodRedirect(IFORGE_REGISTRY, "register", "(" + OLD_ENTRY + ")V",
                EVENTS, "registryRegister", "(" + L_OBJ + L_OBJ + ")V", true);
        t.registerMethodRedirect(IFORGE_REGISTRY, "registerAll", "([" + OLD_ENTRY + ")V",
                EVENTS, "registryRegisterAll", "(" + L_OBJ + "[" + L_OBJ + ")V", true);
    }

    /**
     * Replace the inert {@code RegistryEvent$Register} stub with one the bridge can construct
     * around a registrar. It keeps the host event base, so a handler still registers on a host bus
     * without throwing, and declares one type parameter: reflection rejects a parameterized
     * {@code Register<Item>} whose raw class has none.
     */
    public static void registerRegisterEvent(RetromodTransformer t, String eventBase,
                                             boolean baseIsInterface, String modBusMarker) {
        t.registerSyntheticClass(REGISTER_EVENT,
                registerEventBytes(eventBase, baseIsInterface, modBusMarker));
        t.registerClassRedirect("net/minecraftforge/event/RegistryEvent$Register", REGISTER_EVENT);
    }

    static final String STACKS = "com/retromod/generated/forge1122/LegacyFactories";
    private static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String SOUND_EVENT = "net/minecraft/sounds/SoundEvent";
    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";

    /**
     * Factories for constructors the host removed: the 1.12 {@code ItemStack} argument lists over
     * {@code ItemStack(ItemLike, int)}, and {@code SoundEvent(name)}.
     */
    static byte[] itemStackFactoryBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, STACKS, null, "java/lang/Object", null);
        for (String tail : new String[]{"", "I", "II"}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of",
                    "(L" + ITEM_LIKE + ";" + tail + ")L" + ITEM_STACK + ";", null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, ITEM_STACK);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            if (tail.isEmpty()) {
                mv.visitInsn(ICONST_1);
            } else {
                mv.visitVarInsn(ILOAD, 1); // the count; 1.12 metadata (arg 2) has no host equivalent
            }
            mv.visitMethodInsn(INVOKESPECIAL, ITEM_STACK, "<init>", "(L" + ITEM_LIKE + ";I)V", false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        // The embedder renames this call to the host's SRG name on Forge 1.20.1.
        MethodVisitor sound = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sound",
                "(L" + RESOURCE_LOCATION + ";)L" + SOUND_EVENT + ";", null, null);
        sound.visitCode();
        sound.visitVarInsn(ALOAD, 0);
        sound.visitMethodInsn(INVOKESTATIC, SOUND_EVENT, "createVariableRangeEvent",
                "(L" + RESOURCE_LOCATION + ";)L" + SOUND_EVENT + ";", false);
        sound.visitInsn(ARETURN);
        sound.visitMaxs(0, 0);
        sound.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] registerEventBytes(String eventBase, boolean baseIsInterface) {
        return registerEventBytes(eventBase, baseIsInterface, null);
    }

    /**
     * As above, also implementing {@code modBusMarker} when it is non-null. A 1.13 to 1.18.2 mod
     * subscribes to this event from a MOD-bus subscriber, and Forge's mod bus rejects a parameter
     * that is not an {@code IModBusEvent}, which fails the whole mod's construction.
     */
    static byte[] registerEventBytes(String eventBase, boolean baseIsInterface,
                                     String modBusMarker) {
        String superName = baseIsInterface ? "java/lang/Object" : eventBase;
        List<String> interfaceList = new java.util.ArrayList<>();
        if (baseIsInterface) interfaceList.add(eventBase);
        if (modBusMarker != null) interfaceList.add(modBusMarker);
        String[] interfaces = interfaceList.isEmpty() ? null : interfaceList.toArray(new String[0]);
        StringBuilder signature = new StringBuilder("<T:Ljava/lang/Object;>L" + superName + ";");
        for (String iface : interfaceList) signature.append('L').append(iface).append(';');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, REGISTER_EVENT, signature.toString(), superName,
                interfaces);
        cw.visitField(ACC_PUBLIC, "registry", L_OBJ, null, null).visitEnd();

        MethodVisitor plain = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        plain.visitCode();
        plain.visitVarInsn(ALOAD, 0);
        plain.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        plain.visitInsn(RETURN);
        plain.visitMaxs(0, 0);
        plain.visitEnd();

        MethodVisitor withRegistrar = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + L_OBJ + ")V", null, null);
        withRegistrar.visitCode();
        withRegistrar.visitVarInsn(ALOAD, 0);
        withRegistrar.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        withRegistrar.visitVarInsn(ALOAD, 0);
        withRegistrar.visitVarInsn(ALOAD, 1);
        withRegistrar.visitFieldInsn(PUTFIELD, REGISTER_EVENT, "registry", L_OBJ);
        withRegistrar.visitInsn(RETURN);
        withRegistrar.visitMaxs(0, 0);
        withRegistrar.visitEnd();

        // The verifier treats interface types as Object, so the registrar returns unchecked.
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getRegistry", "()L" + IFORGE_REGISTRY + ";",
                null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, REGISTER_EVENT, "registry", L_OBJ);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Post-remap pass over every class of a transformed mod. Retargets the 1.12 call shapes the
     * redirect tables cannot key on, because their owner is arbitrary or their descriptor depends
     * on the host. Returns the input array when nothing matched.
     */
    public static byte[] rewriteLegacyCalls(byte[] classBytes) {
        if (!active || classBytes == null) return classBytes;
        String pool = new String(classBytes, StandardCharsets.ISO_8859_1);
        boolean names = pool.contains("RegistryName");
        boolean bus = pool.contains(BUS);
        boolean handlers = pool.contains(SUBSCRIBE) || pool.contains(LIFECYCLE_PACKAGE)
                || pool.contains(INSTANCE) || pool.contains(SIDED_PROXY);
        boolean subscriber = (pool.contains(OLD_SUBSCRIBER_DESC) || pool.contains(NEO_SUBSCRIBER_DESC))
                && pool.contains(SUBSCRIBE);
        boolean builders = pool.contains("func_77") || pool.contains("func_149")
                || pool.contains(MATERIAL) || pool.contains(CREATIVE_TAB)
                || pool.contains("com/retromod/shim/forge/embedded/Legacy")
                || pool.contains(Forge1122FluidBridge.FLUID_BLOCK)
                || pool.contains(MOB_EFFECT)
                || pool.contains(STATE_DEFINITION)
                || pool.contains(Forge1122ServiceStubs.LOOT_TABLE_LIST)
                || pool.contains(Forge1122ServiceStubs.GAME_REGISTRY)
                || pool.contains(Forge1122ServiceStubs.ORE_DICTIONARY)
                || pool.contains(Forge1122WorldgenBridge.ENUM_HELPER)
                || (pool.contains(ITEM) && (pool.contains("(IFZ)V") || pool.contains("(IZ)V")));
        boolean optional = pool.contains(Forge1122OptionalStripper.MARKER);
        if (!names && !bus && !handlers && !builders && !optional) return classBytes;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(classBytes).accept(cn, 0);
            boolean changed = subscriber && renameLegacySubscriber(cn);
            changed |= optional && Forge1122OptionalStripper.strip(cn);
            changed |= handlers && recordHandlers(cn);
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (in instanceof MethodInsnNode mi) {
                        changed |= rewriteCall(cn, m, mi);
                    } else if (in instanceof FieldInsnNode fi) {
                        changed |= rewriteBusField(fi) || nullLegacyCreativeTab(m, fi);
                    }
                }
            }
            if (!changed) return classBytes;
            // Each rewrite keeps the stack shape at its own instruction, so the existing frames
            // stay valid; only the stack depth can change.
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            return classBytes;
        }
    }

    /**
     * Element type of a {@code RegistryEvent.Register<T>} to the registry it fills. Data-driven
     * registries (recipes, biomes) are absent: the host never offers them in {@code RegisterEvent}.
     */
    private static final java.util.Map<String, String> REGISTRY_PATHS = java.util.Map.ofEntries(
            java.util.Map.entry("net/minecraft/world/level/block/Block", "block"),
            java.util.Map.entry("net/minecraft/world/item/Item", "item"),
            java.util.Map.entry("net/minecraft/sounds/SoundEvent", "sound_event"),
            java.util.Map.entry("net/minecraft/world/effect/MobEffect", "mob_effect"),
            java.util.Map.entry("net/minecraft/world/item/alchemy/Potion", "potion"),
            java.util.Map.entry("net/minecraft/world/item/enchantment/Enchantment", "enchantment"),
            java.util.Map.entry("net/minecraft/world/entity/EntityType", "entity_type"),
            java.util.Map.entry("net/minecraft/world/level/block/entity/BlockEntityType",
                    "block_entity_type"),
            java.util.Map.entry("net/minecraft/core/particles/ParticleType", "particle_type"),
            java.util.Map.entry("net/minecraft/world/entity/npc/VillagerProfession",
                    "villager_profession"),
            java.util.Map.entry("net/minecraft/world/inventory/MenuType", "menu"),
            java.util.Map.entry("net/minecraft/world/item/crafting/RecipeSerializer",
                    "recipe_serializer"));

    private static final String LIFECYCLE_PACKAGE = "com/retromod/shim/forge/embedded/FML";
    private static final Set<String> LIFECYCLE_EVENTS = Set.of(
            Forge1122LifecycleSynthetics.PRE_EVENT, Forge1122LifecycleSynthetics.INIT_EVENT,
            Forge1122LifecycleSynthetics.POST_EVENT);

    /**
     * Record this class's 1.12 handlers in a {@code LegacyHandlers} annotation, so the runtime
     * can look each one up alone (see that annotation for why and for the entry format).
     */
    static boolean recordHandlers(ClassNode cn) {
        List<String> entries = new java.util.ArrayList<>();
        for (MethodNode m : cn.methods) {
            Type[] args = Type.getArgumentTypes(m.desc);
            if (args.length != 1 || args[0].getSort() != Type.OBJECT) continue;
            String kind = (m.access & ACC_STATIC) != 0 ? "s" : "i";
            AnnotationNode subscribe = annotation(m, "L" + SUBSCRIBE + ";");
            if (subscribe != null) {
                String path = args[0].getInternalName().equals(REGISTER_EVENT)
                        ? registryPathOf(m.signature) : "";
                if (path == null) continue; // a registry this bridge cannot fill
                boolean receiveCanceled = Boolean.TRUE.equals(value(subscribe, "receiveCanceled"));
                entries.add(String.join("|", "E", kind, m.name, m.desc, path,
                        receiveCanceled ? "1" : "0"));
            } else if (LIFECYCLE_EVENTS.contains(args[0].getInternalName())) {
                entries.add(String.join("|", "L", kind, m.name, m.desc, "", "0"));
            }
        }
        for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
            if ((f.access & ACC_STATIC) == 0 || f.visibleAnnotations == null) continue;
            for (AnnotationNode a : f.visibleAnnotations) {
                if (a.desc.equals("L" + INSTANCE + ";")) {
                    Object id = value(a, "value");
                    entries.add(String.join("|", "I", "s", f.name, f.desc, id == null ? "" : id.toString(), "0"));
                } else if (a.desc.equals("L" + SIDED_PROXY + ";")) {
                    Object client = value(a, "clientSide");
                    Object server = value(a, "serverSide");
                    entries.add(String.join("|", "P", "s", f.name, f.desc,
                            client == null ? "" : client.toString(), server == null ? "" : server.toString()));
                }
            }
        }
        if (entries.isEmpty()) return false;
        AnnotationNode table = new AnnotationNode("L" + HANDLERS + ";");
        table.values = new java.util.ArrayList<>(List.of("value", entries));
        if (cn.visibleAnnotations == null) cn.visibleAnnotations = new java.util.ArrayList<>();
        cn.visibleAnnotations.removeIf(a -> a.desc.equals(table.desc));
        cn.visibleAnnotations.add(table);
        return true;
    }

    /** {@code (L.../RegistryEvent$Register<Lnet/minecraft/world/item/Item;>;)V} to {@code item}. */
    static String registryPathOf(String signature) {
        if (signature == null) return null;
        String marker = "RegistryEvent$Register<L";
        int start = signature.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = start;
        while (end < signature.length() && signature.charAt(end) != ';'
                && signature.charAt(end) != '<') end++;
        return REGISTRY_PATHS.get(signature.substring(start, end));
    }

    private static AnnotationNode annotation(MethodNode m, String desc) {
        if (m.visibleAnnotations == null) return null;
        for (AnnotationNode a : m.visibleAnnotations) {
            if (a.desc.equals(desc)) return a;
        }
        return null;
    }

    private static Object value(AnnotationNode a, String name) {
        if (a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (name.equals(a.values.get(i))) return a.values.get(i + 1);
        }
        return null;
    }

    private static boolean rewriteCall(ClassNode cn, MethodNode m, MethodInsnNode mi) {
        int op = mi.getOpcode();
        boolean instanceCall = op == INVOKEVIRTUAL || op == INVOKEINTERFACE;
        String ret = Type.getReturnType(mi.desc).getDescriptor();

        if (Forge1122ServiceStubs.rewriteCall(m, mi)) return true;
        if (op == INVOKEVIRTUAL && mi.name.equals(ADD_ATTRIBUTE_MODIFIER) && bridgeEffectAttribute(m, mi)) {
            return true;
        }
        if (op == INVOKEVIRTUAL && BUILDER_SETTERS.contains(mi.name)) {
            dropSetter(m, mi);
            return true;
        }
        if (op == INVOKESPECIAL && mi.name.equals("<init>") && MATERIAL_CTORS.contains(mi.desc)
                && mi.owner.startsWith("net/minecraft/world/level/block/")) {
            replaceMaterialCtor(m, mi);
            return true;
        }
        if (op == INVOKEVIRTUAL && mi.owner.equals(STATE_DEFINITION) && ANY_STATE.contains(mi.name)
                && mi.desc.equals("()L" + BLOCK_STATE + ";")) {
            MethodInsnNode read = new MethodInsnNode(INVOKESTATIC, EVENTS, "baseState",
                    "(" + L_OBJ + ")" + L_OBJ, false);
            m.instructions.set(mi, read);
            m.instructions.insert(read, new TypeInsnNode(CHECKCAST, BLOCK_STATE));
            return true;
        }
        if (op == INVOKESPECIAL && mi.name.equals("<init>") && mi.owner.equals(ITEM)
                && FOOD_CTORS.contains(mi.desc)) {
            replaceFoodCtor(m, mi);
            return true;
        }
        if (op == INVOKESPECIAL && mi.name.equals("<init>") && mi.owner.equals(MOB_EFFECT)
                && mi.desc.equals("(ZI)V")) {
            replacePotionCtor(m, mi);
            return true;
        }

        if (instanceCall && mi.name.equals("setRegistryName") && SET_NAME_RETURNS.contains(ret)) {
            String args = mi.desc.substring(1, mi.desc.indexOf(')'));
            String newDesc = switch (args) {
                case "Ljava/lang/String;" -> "(" + L_OBJ + "Ljava/lang/String;)" + L_OBJ;
                case "Ljava/lang/String;Ljava/lang/String;" ->
                        "(" + L_OBJ + "Ljava/lang/String;Ljava/lang/String;)" + L_OBJ;
                default -> args.startsWith("L") && Type.getArgumentTypes(mi.desc).length == 1
                        ? "(" + L_OBJ + L_OBJ + ")" + L_OBJ : null;
            };
            if (newDesc == null) return false;
            retarget(mi, EVENTS, "setRegistryName", newDesc);
            return true;
        }
        if (instanceCall && mi.name.equals("getRegistryName") && NAME_TYPES.contains(mi.desc)
                && !FORGE_REGISTRIES.contains(mi.owner) && !declaresOwn(cn, mi)) {
            String nameType = Type.getReturnType(mi.desc).getInternalName();
            retarget(mi, EVENTS, "getRegistryName", "(" + L_OBJ + ")" + L_OBJ);
            if (!ret.equals(L_OBJ)) m.instructions.insert(mi, new TypeInsnNode(CHECKCAST, nameType));
            return true;
        }
        if (mi.owner.equals(BUS) && mi.name.equals("post") && !mi.desc.equals("(" + L_OBJ + ")Z")) {
            mi.desc = "(" + L_OBJ + ")Z";
            return true;
        }
        // FMLCommonHandler.instance().bus() and similar accessors of the single 1.12 bus.
        if (ret.equals(L_BUS) && !mi.owner.equals(BUS) && Type.getArgumentTypes(mi.desc).length == 0) {
            FieldInsnNode game = new FieldInsnNode(GETSTATIC, BUS, "GAME", L_BUS);
            if (op == INVOKESTATIC) {
                m.instructions.set(mi, game);
            } else {
                m.instructions.insertBefore(mi, new InsnNode(POP));
                m.instructions.set(mi, game);
            }
            return true;
        }
        return false;
    }

    /**
     * 1.12.2 (SRG) builder setters on {@code Item} and {@code Block}, plus the void
     * {@code addPropertyOverride}. 1.13 moved these settings
     * into the constructor-time {@code Properties}, and the already-built properties cannot be
     * changed afterwards, so each call is dropped and the receiver is kept as the result. Names,
     * tabs, hardness, sounds and light keep their defaults. The names are unique SRG ids, so
     * they cannot collide with a newer method.
     */
    static final Set<String> BUILDER_SETTERS = Set.of(
            "func_77655_b", // Item.setTranslationKey
            "func_77637_a", // Item.setCreativeTab
            "func_77625_d", // Item.setMaxStackSize
            "func_77656_e", // Item.setMaxDamage
            "func_77627_a", // Item.setHasSubtypes
            "func_77642_a", // Item.setContainerItem
            "func_77664_n", // Item.setFull3D
            "func_185043_a", // Item.addPropertyOverride (client model predicate, void)
            "func_149663_c", // Block.setTranslationKey
            "func_149647_a", // Block.setCreativeTab
            "func_149711_c", // Block.setHardness
            "func_149752_b", // Block.setResistance
            "func_149672_a", // Block.setSoundType
            "func_149715_a", // Block.setLightLevel
            "func_149713_g", // Block.setLightOpacity
            "func_149722_s", // Block.setBlockUnbreakable
            "func_149675_a", // Block.setTickRandomly
            "func_149649_H", // Block.disableStats
            "func_76390_b", // Potion.setPotionName
            "func_76399_b", // Potion.setIconIndex (inventory icon sheet, replaced by sprites)
            "func_188413_j", // Potion.setBeneficial
            "func_111184_a" // Potion.registerPotionAttributeModifier
    );

    private static final String MATERIAL = "Lnet/minecraft/block/material/Material;";
    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final Set<String> MATERIAL_CTORS = Set.of("(" + MATERIAL + ")V",
            "(" + MATERIAL + "Lnet/minecraft/world/level/material/MapColor;)V");

    /**
     * Drop the setter's arguments and keep the receiver as its result (or drop it for void). An
     * argument pushed by one constant, local or static read is removed rather than popped: the
     * 1.12 {@code registerPotionAttributeModifier} reads a {@code SharedMonsterAttributes} field,
     * which became a {@code Holder<Attribute>} in 1.20.5, so the read alone fails to link there.
     */
    private static void dropSetter(MethodNode m, MethodInsnNode mi) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        int remaining = args.length;
        while (remaining > 0 && isPlainPush(mi.getPrevious())) {
            m.instructions.remove(mi.getPrevious());
            remaining--;
        }
        for (int i = remaining - 1; i >= 0; i--) {
            m.instructions.insertBefore(mi, new InsnNode(args[i].getSize() == 2 ? POP2 : POP));
        }
        if (Type.getReturnType(mi.desc).getSort() == Type.VOID) {
            m.instructions.set(mi, new InsnNode(POP));
        } else {
            m.instructions.remove(mi);
        }
    }

    /** {@code Potion.registerPotionAttributeModifier}, by its SRG id. */
    private static final String ADD_ATTRIBUTE_MODIFIER = "func_111184_a";
    private static final String ATTRIBUTE = "net/minecraft/world/entity/ai/attributes/Attribute";
    private static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";

    /**
     * {@code registerPotionAttributeModifier(attribute, uuid, amount, operation)} becomes the
     * host's {@code addAttributeModifier} through a reflective helper, so a potion keeps its speed
     * or damage effect. On 1.20.5 and newer the {@code Attributes} constants are holders, so the
     * attribute read is retyped. Only those constants change type: a mod's own attribute field
     * stays a plain {@code Attribute}, and retyping its read would fail with
     * {@code NoSuchFieldError}. When the attribute is anything but a plain read of an
     * {@code Attributes} constant there, the call is dropped as before instead.
     */
    private static boolean bridgeEffectAttribute(MethodNode m, MethodInsnNode mi) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        if (args.length != 4 || !args[1].getDescriptor().equals("Ljava/lang/String;")
                || args[2] != Type.DOUBLE_TYPE || args[3] != Type.INT_TYPE) return false;
        if (attributesAreHolders()) {
            AbstractInsnNode producer = mi.getPrevious();
            for (int i = 0; i < 3; i++) {
                if (!isPlainPush(producer)) return false;
                producer = producer.getPrevious();
            }
            if (!(producer instanceof FieldInsnNode read) || read.getOpcode() != GETSTATIC
                    || !read.owner.equals(ATTRIBUTES) || !read.desc.equals("L" + ATTRIBUTE + ";")) {
                return false;
            }
            read.desc = "Lnet/minecraft/core/Holder;";
        }
        Type result = Type.getReturnType(mi.desc);
        retarget(mi, EVENTS, "effectAttribute", "(" + L_OBJ + L_OBJ + "Ljava/lang/String;DI)" + L_OBJ);
        if (result.getSort() == Type.OBJECT && !result.getDescriptor().equals(L_OBJ)) {
            m.instructions.insert(mi, new TypeInsnNode(CHECKCAST, result.getInternalName()));
        } else if (result.getSort() == Type.VOID) {
            m.instructions.insert(mi, new InsnNode(POP));
        }
        return true;
    }

    /** 1.20.5 turned the {@code Attributes} constants into {@code Holder<Attribute>}. */
    private static boolean attributesAreHolders() {
        return com.retromod.core.RetromodVersion.compareMcVersions(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
    }

    /** One instruction that pushes one value and reads nothing from the stack. */
    private static boolean isPlainPush(AbstractInsnNode in) {
        if (in == null) return false;
        int op = in.getOpcode();
        return (op >= ACONST_NULL && op <= LDC) || (op >= ILOAD && op <= ALOAD) || op == GETSTATIC;
    }

    /**
     * {@code super(Material)} on any vanilla block base becomes {@code super(Properties)} with
     * default properties. The transformer bridges {@code Block} itself; this covers the other
     * bases a 1.12 block extends ({@code BlockContainer}, {@code BlockBush}, ...). The default
     * comes from a reflective helper because this pass runs after the remap, too late for the
     * Forge 1.20.1 SRG rename of {@code Properties.of()}.
     */
    private static void replaceMaterialCtor(MethodNode m, MethodInsnNode mi) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        for (int i = args.length - 1; i >= 0; i--) m.instructions.insertBefore(mi, new InsnNode(POP));
        m.instructions.insertBefore(mi, new MethodInsnNode(INVOKESTATIC, EVENTS,
                "defaultBlockProperties", "()" + L_OBJ, false));
        m.instructions.insertBefore(mi, new TypeInsnNode(CHECKCAST, PROPERTIES));
        mi.desc = "(L" + PROPERTIES + ";)V";
    }

    private static final String MOB_EFFECT = "net/minecraft/world/effect/MobEffect";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    /** {@code ItemFood(amount, saturation, isWolfFood)} and {@code ItemFood(amount, isWolfFood)}. */
    private static final Set<String> FOOD_CTORS = Set.of("(IFZ)V", "(IZ)V");

    /**
     * 1.12 {@code ItemFood} maps to {@code Item}, which takes properties since 1.13. The food
     * values on the stack become those properties, so both {@code new ItemFood(..)} and a
     * subclass's {@code super(..)} build a host food item. 1.12 gave the two-argument form a
     * saturation of 0.6.
     */
    private static void replaceFoodCtor(MethodNode m, MethodInsnNode mi) {
        if (mi.desc.equals("(IZ)V")) {
            m.instructions.insertBefore(mi, new org.objectweb.asm.tree.LdcInsnNode(0.6F));
            m.instructions.insertBefore(mi, new InsnNode(SWAP));
        }
        m.instructions.insertBefore(mi, new MethodInsnNode(INVOKESTATIC, EVENTS, "foodItemProperties",
                "(IFZ)" + L_OBJ, false));
        m.instructions.insertBefore(mi, new TypeInsnNode(CHECKCAST, ITEM_PROPERTIES));
        mi.desc = "(L" + ITEM_PROPERTIES + ";)V";
    }

    /**
     * 1.12 {@code BlockStateContainer.getBaseState()} returned {@code IBlockState}, so the remap
     * calls the host's {@code StateDefinition.any()} with a {@code BlockState} result. The host
     * method is generic and erases to {@code StateHolder}, and the remap misses it for the same
     * reason, so on Forge 1.20.1 the call also keeps the Mojang name instead of the SRG id the
     * host runs. A reflective read works on both, and the result is cast back.
     */
    private static final String STATE_DEFINITION = "net/minecraft/world/level/block/state/StateDefinition";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final Set<String> ANY_STATE = Set.of("any", "m_61090_");
    private static final String EFFECT_CATEGORY = "net/minecraft/world/effect/MobEffectCategory";

    /**
     * {@code super(isBadEffect, color)} in a 1.12 {@code Potion} subclass becomes the host's
     * {@code super(category, color)}. The flag sits under the color on the stack, so it is swapped
     * up, turned into a category, and swapped back.
     */
    private static void replacePotionCtor(MethodNode m, MethodInsnNode mi) {
        m.instructions.insertBefore(mi, new InsnNode(SWAP));
        m.instructions.insertBefore(mi, new MethodInsnNode(INVOKESTATIC, EVENTS, "effectCategory",
                "(Z)" + L_OBJ, false));
        m.instructions.insertBefore(mi, new TypeInsnNode(CHECKCAST, EFFECT_CATEGORY));
        m.instructions.insertBefore(mi, new InsnNode(SWAP));
        mi.desc = "(L" + EFFECT_CATEGORY + ";I)V";
    }

    private static final String CREATIVE_TAB = "net/minecraft/world/item/CreativeModeTab";

    /**
     * 1.12 creative tab constants ({@code CreativeTabs.MISC}, remapped to {@code TAB_MISC}) are
     * gone since 1.19.3, and their only 1.12 use, {@code setCreativeTab}, is dropped above. Read
     * them as null instead of failing the item constructor halfway, which on NeoForge leaves an
     * unregistered item holder that aborts the registry freeze.
     */
    private static boolean nullLegacyCreativeTab(MethodNode m, FieldInsnNode fi) {
        if (fi.getOpcode() != GETSTATIC || !fi.owner.equals(CREATIVE_TAB)
                || !fi.desc.equals("L" + CREATIVE_TAB + ";")
                || !(fi.name.startsWith("TAB_") || fi.name.startsWith("field_"))) return false;
        m.instructions.set(fi, new InsnNode(ACONST_NULL));
        return true;
    }

    /** {@code MinecraftForge.EVENT_BUS} (and the terrain and ore buses) all read the one bus. */
    private static boolean rewriteBusField(FieldInsnNode fi) {
        if (fi.getOpcode() != GETSTATIC || !fi.desc.equals(L_BUS) || fi.owner.equals(BUS)) return false;
        fi.owner = BUS;
        fi.name = "GAME";
        return true;
    }

    private static void retarget(MethodInsnNode mi, String owner, String name, String desc) {
        mi.setOpcode(INVOKESTATIC);
        mi.owner = owner;
        mi.name = name;
        mi.desc = desc;
        mi.itf = false;
    }

    /** {@code @Mod.EventBusSubscriber(Side.CLIENT)}: its handlers name client-only classes. */
    private static boolean isClientOnly(AnnotationNode subscriber) {
        if (subscriber.values == null) return false;
        for (int i = 0; i + 1 < subscriber.values.size(); i += 2) {
            if (!"value".equals(subscriber.values.get(i))) continue;
            if (!(subscriber.values.get(i + 1) instanceof List<?> sides) || sides.isEmpty()) return false;
            for (Object side : sides) {
                if (!(side instanceof String[] e) || e.length != 2 || !"CLIENT".equals(e[1])) return false;
            }
            return true;
        }
        return false;
    }

    /** A class calling its own {@code getRegistryName} keeps that call. */
    private static boolean declaresOwn(ClassNode cn, MethodInsnNode mi) {
        if (!mi.owner.equals(cn.name)) return false;
        for (MethodNode m : cn.methods) {
            if (m.name.equals(mi.name) && m.desc.equals(mi.desc)) return true;
        }
        return false;
    }

    /**
     * Swap a class's {@code @Mod.EventBusSubscriber} for the Retromod marker when every handler in
     * it is a 1.12 one. A class with a host {@code @SubscribeEvent} is a newer mod's and keeps
     * the host annotation.
     */
    private static boolean renameLegacySubscriber(ClassNode cn) {
        if (cn.visibleAnnotations == null) return false;
        boolean legacyHandlers = false;
        for (MethodNode m : cn.methods) {
            if (m.visibleAnnotations == null) continue;
            for (AnnotationNode a : m.visibleAnnotations) {
                if (a.desc.equals("L" + SUBSCRIBE + ";")) legacyHandlers = true;
                else if (a.desc.endsWith("/SubscribeEvent;")) return false;
            }
        }
        if (!legacyHandlers) return false;
        List<AnnotationNode> anns = cn.visibleAnnotations;
        for (int i = 0; i < anns.size(); i++) {
            String desc = anns.get(i).desc;
            if (desc.equals(OLD_SUBSCRIBER_DESC) || desc.equals(NEO_SUBSCRIBER_DESC)) {
                AnnotationNode marker = new AnnotationNode("L" + SUBSCRIBER + ";");
                if (isClientOnly(anns.get(i))) marker.visit("clientOnly", Boolean.TRUE);
                anns.set(i, marker);
                return true;
            }
        }
        return false;
    }
}
