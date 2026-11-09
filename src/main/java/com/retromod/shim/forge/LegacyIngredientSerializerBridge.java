/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Keeps Forge 1.20.1 ingredient serializers and custom ingredients working on Forge 26.2 and newer. */
public final class LegacyIngredientSerializerBridge {

    public static final String FLAG = "forge-legacy-ingredient-serializer";

    static final String OLD_SERIALIZER = "net/minecraftforge/common/crafting/IIngredientSerializer";
    static final String OLD_ABSTRACT = "net/minecraftforge/common/crafting/AbstractIngredient";
    static final String OLD_VALUE = "net/minecraft/world/item/crafting/Ingredient$Value";
    static final String HOST_SERIALIZER = "net/minecraftforge/common/crafting/ingredients/IIngredientSerializer";
    static final String HOST_ABSTRACT = "net/minecraftforge/common/crafting/ingredients/AbstractIngredient";
    // EventBus 7, whose mod buses the registration listens on. Forge 1.21.11 has it too, so it decides no version.
    static final String BUS_GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";

    private static final String ROOT = RebuiltApiClasses.ROOT + "forge/crafting/";
    /** The rebuilt serializer the old interface becomes. */
    public static final String SERIALIZER = ROOT + "LegacyIngredientSerializer";
    /** The rebuilt registration CraftingHelper's stand-in calls. */
    public static final String SERIALIZERS = ROOT + "LegacyIngredientSerializers";
    static final String VALUE = ROOT + "LegacyIngredientValue";
    /** The generated base an old ingredient extends in place of the removed AbstractIngredient. */
    public static final String BASE = "com/retromod/generated/LegacyForgeAbstractIngredient";

    static final String GET_SERIALIZER = "getSerializer";
    static final String GET_SERIALIZER_DESC = "()L" + SERIALIZER + ";";
    static final String HOST_GETTER = "serializer";
    static final String HOST_GETTER_DESC = "()L" + HOST_SERIALIZER + ";";

    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');
    private static final ModConstructorHooks.Call WATCH =
            new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, SERIALIZERS, "watch", "()V", List.of());
    private static final byte[] OLD_SERIALIZER_NAME = OLD_SERIALIZER.getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] GETTER_DESC_BYTES = GET_SERIALIZER_DESC.getBytes(StandardCharsets.ISO_8859_1);

    private LegacyIngredientSerializerBridge() {}

    /** Whether the target is a Forge 26.2 or newer host with codec ingredient serializers on EventBus 7. */
    public static boolean applies() {
        return applies(ClassResourceInspector::read);
    }

    static boolean applies(Function<String, ClassNode> host) {
        // Forge 1.21.11 and 26.1 have the same classes, and the bridge is built and checked for 26.2 and newer only.
        String target = RetromodVersion.TARGET_MC_VERSION;
        return RetromodVersion.compareMcVersions(target, ForgeHostShape.FIRST_CHECKED_VERSION) >= 0
                && ForgeHostShape.matches(List.of(HOST_SERIALIZER, HOST_ABSTRACT, BUS_GROUP), List.of(OLD_SERIALIZER),
                        ForgeHostShape.FIRST_CHECKED_VERSION, host);
    }

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!applies(host)) return;
        registerHelpers(t);
        // No native mod names the old interface, which the host moved to the ingredients package with codecs.
        t.registerClassRedirect(OLD_SERIALIZER, SERIALIZER);
        // An old ingredient's base keeps its constructors; the old values they took only fed the removed item list.
        t.registerGeneratedLegacyBase(OLD_ABSTRACT, BASE, HOST_ABSTRACT, "()V");
        // Old ingredients hand that constructor an empty array of the removed Ingredient.Value.
        if (ForgeHostShape.matches(List.of(), List.of(OLD_VALUE), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            RebuiltApiClasses.registerTree(t, VALUE);
            t.registerClassRedirect(OLD_VALUE, VALUE);
        }
        // CraftingHelper's stand-in takes the serializers; each old @Mod constructor starts its jar's listener.
        ForgeNeoForgeSynthetics.registerForgeConditionStandIns(t, host);
        ModConstructorHooks.register(t, "legacy-ingredient-serializers", LegacyIngredientSerializerBridge::plan);
        t.enablePostProcess(FLAG);
    }

    /** The rebuilt classes CraftingHelper's stand-in calls, and the log they write to. */
    static void registerHelpers(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, SERIALIZERS);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
    }

    /** The registration start in each {@code @Mod} constructor of a jar that names the old serializer, or null. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        for (byte[] original : classes.values()) {
            if (contains(original, OLD_SERIALIZER_NAME)) return (modClass, modId) -> List.of(WATCH);
        }
        return null;
    }

    /** Post-remap pass: a class with an old {@code getSerializer()} gets the {@code serializer()} Forge asks for. */
    public static byte[] apply(byte[] classBytes) {
        // Each class gets its own, so one that extends another jar's ingredient still answers.
        if (classBytes == null || !contains(classBytes, GETTER_DESC_BYTES)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        // A mixin's members are merged into its target, which is the host's own Ingredient at worst.
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || LegacyNbtSerializableBridge.isMixin(node)
                || LegacyNbtSerializableBridge.declares(node, HOST_GETTER, HOST_GETTER_DESC)
                || !declaresOldGetter(node)) {
            return classBytes;
        }
        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE,
                HOST_GETTER, HOST_GETTER_DESC, null, null);
        bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, GET_SERIALIZER,
                GET_SERIALIZER_DESC, false));
        bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(bridge);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean declaresOldGetter(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(GET_SERIALIZER) && method.desc.equals(GET_SERIALIZER_DESC)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
