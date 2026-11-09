/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.HostLibraryNames;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.function.Function;

/** Points a mod's old {@code Component.Serializer} and {@code Style.Serializer} at Retromod's stand-ins, in Mojang or intermediary names. */
public final class LegacyComponentJsonBridge {

    static final String OLD = "net/minecraft/network/chat/Component$Serializer";
    static final String JSON = RebuiltApiClasses.ROOT + "minecraft/network/chat/LegacyComponentJson";
    static final String OLD_STYLE = "net/minecraft/network/chat/Style$Serializer";
    static final String STYLE = RebuiltApiClasses.ROOT + "minecraft/network/chat/LegacyStyleJson";
    static final String INTERMEDIARY_JSON = RebuiltApiClasses.ROOT + "intermediary/minecraft/network/chat/LegacyComponentJson";
    static final String INTERMEDIARY_STYLE = RebuiltApiClasses.ROOT + "intermediary/minecraft/network/chat/LegacyStyleJson";
    static final String NO_CONTEXT = RebuiltApiClasses.ROOT + "intermediary/minecraft/core/NoRegistryContext";
    private static final String CONTEXT = RebuiltApiClasses.ROOT + "minecraft/core/LegacyRegistryContext";
    private static final String SERIALIZATION = "net/minecraft/network/chat/ComponentSerialization";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String MUTABLE = "net/minecraft/network/chat/MutableComponent";
    private static final String STYLE_TYPE = "net/minecraft/network/chat/Style";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String OLD_FROM_JSON = "(Ljava/lang/String;)L" + MUTABLE + ";";

    /** What the host's {@code Component.Serializer} is: gone (1.21.6), registry-taking statics (1.20.5), the old statics without the adapter (1.20.3), or the old adapter. */
    enum Serializer { GONE, TAKES_REGISTRIES, STATICS, OLD }

    // A Fabric 1.20.3 to 1.21.11 host runs intermediary names, the same ones in each of those versions (checked
    // against the 1.21.1 and 1.21.11 jars). The stand-ins are copied into those names under their own.
    static final Remapper INTERMEDIARY = new IntermediaryNames();

    private LegacyComponentJsonBridge() {}

    /** Registers the stand-ins the host's serializers call for; offline, the target version decides. */
    public static void register(RetromodTransformer t) {
        register(t, serializer(ClassResourceInspector::read, RetromodVersion.TARGET_MC_VERSION),
                styleIsAdapter(ClassResourceInspector::read, RetromodVersion.TARGET_MC_VERSION));
    }

    /** The intermediary-named stand-ins a Fabric host before 26.1 calls for, read from its classes. */
    public static void registerIntermediary(RetromodTransformer t) {
        registerIntermediary(t, ClassResourceInspector::read, RetromodVersion.TARGET_MC_VERSION);
    }

    /** The same offline, where the Fabric host cannot be read and the target version decides. */
    public static void registerIntermediaryForTarget(RetromodTransformer t, String target) {
        registerIntermediary(t, name -> null, target);
    }

    /** Whether the intermediary stand-in answers the old serializer's calls, so the earlier registry-passing helper is not needed. */
    public static boolean answersIntermediaryCalls(RetromodTransformer t) {
        return INTERMEDIARY_JSON.equals(t.getClassRedirects().get(INTERMEDIARY.mapType(OLD)))
                && t.getSyntheticClasses().containsKey(INTERMEDIARY_JSON);
    }

    private static void registerIntermediary(RetromodTransformer t, Function<String, ClassNode> host, String target) {
        // From 26.1 a Fabric mod is remapped to Mojang names first, and the Mojang-named stand-ins answer it.
        if (RetromodVersion.isUnobfuscatedTarget(target)) return;
        register(t, serializer(host, target, INTERMEDIARY), styleIsAdapter(host, target, INTERMEDIARY), INTERMEDIARY);
    }

    /** Registers the stand-ins for these host shapes. */
    static void register(RetromodTransformer t, Serializer serializer, boolean styleIsAdapter) {
        register(t, serializer, styleIsAdapter, null);
    }

    /** Registers the stand-ins for these host shapes, renamed by {@code names} unless it is null. */
    static void register(RetromodTransformer t, Serializer serializer, boolean styleIsAdapter, Remapper names) {
        // Save and load code passes registries only on Forge and NeoForge, so a Fabric copy reads the world's.
        if (names != null && (serializer != Serializer.OLD || !styleIsAdapter)) registerNoContext(t, names);
        if (serializer != Serializer.OLD) {
            String old = name(names, OLD);
            RebuiltApiClasses.registerTree(t, JSON, names);
            // The stand-in declares every static the serializer had, under the same names and descriptors.
            t.registerClassRedirect(old, name(names, JSON));
            // A mod built for 1.20.3 or 1.20.4 calls their old statics, and one built for 1.20.5 to 1.21.5 the
            // registry-taking ones, as written.
            if (serializer == Serializer.STATICS) t.limitClassRedirectToModsBefore(old, "1.20.3");
            if (serializer == Serializer.TAKES_REGISTRIES) t.limitClassRedirectToModsBefore(old, "1.20.5");
        }
        // From 1.20.3 Style.Serializer holds codecs; mods built before registered it as a Gson type adapter.
        if (!styleIsAdapter) {
            RebuiltApiClasses.registerTree(t, STYLE, names);
            t.registerClassRedirect(name(names, OLD_STYLE), name(names, STYLE));
            t.limitClassRedirectToModsBefore(name(names, OLD_STYLE), "1.20.3");
        }
    }

    /** The host's serializer, read from its classes when they can be read, else from the target version. */
    static Serializer serializer(Function<String, ClassNode> host, String target) {
        return serializer(host, target, null);
    }

    static Serializer serializer(Function<String, ClassNode> host, String target, Remapper names) {
        if (host.apply(name(names, SERIALIZATION)) == null) {
            if (atLeast(target, "1.21.6")) return Serializer.GONE;
            if (atLeast(target, "1.20.5")) return Serializer.TAKES_REGISTRIES;
            return atLeast(target, "1.20.3") ? Serializer.STATICS : Serializer.OLD;
        }
        ClassNode serializer = host.apply(name(names, OLD));
        if (serializer == null) return Serializer.GONE;
        if (isAdapter(serializer)) return Serializer.OLD;
        String fromJson = names == null ? "fromJson" : names.mapMethodName(OLD, "fromJson", OLD_FROM_JSON);
        String desc = names == null ? OLD_FROM_JSON : names.mapMethodDesc(OLD_FROM_JSON);
        boolean oldStatic = serializer.methods.stream().anyMatch(m -> m.name.equals(fromJson) && m.desc.equals(desc));
        return oldStatic ? Serializer.STATICS : Serializer.TAKES_REGISTRIES;
    }

    /** Whether the host's {@code Style.Serializer} is still a Gson type adapter, as before 1.20.3. */
    static boolean styleIsAdapter(Function<String, ClassNode> host, String target) {
        return styleIsAdapter(host, target, null);
    }

    static boolean styleIsAdapter(Function<String, ClassNode> host, String target, Remapper names) {
        if (host.apply(name(names, SERIALIZATION)) == null) return !atLeast(target, "1.20.3");
        ClassNode style = host.apply(name(names, OLD_STYLE));
        return style != null && isAdapter(style);
    }

    private static boolean isAdapter(ClassNode serializer) {
        return serializer.interfaces.contains(HostLibraryNames.GSON_PACKAGE + "/JsonDeserializer");
    }

    private static String name(Remapper names, String type) {
        return names == null ? type : names.mapType(type);
    }

    private static boolean atLeast(String target, String version) {
        return target != null && RetromodVersion.compareMcVersions(target, version) >= 0;
    }

    // Stands in for LegacyRegistryContext, whose Forge-side lookups a Fabric jar must not carry: no current registries.
    private static void registerNoContext(RetromodTransformer t, Remapper names) {
        if (t.getSyntheticClasses().containsKey(NO_CONTEXT)) return;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, NO_CONTEXT, null,
                "java/lang/Object", null);
        MethodVisitor current = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current",
                names.mapMethodDesc("()L" + PROVIDER + ";"), null, null);
        current.visitCode();
        current.visitInsn(Opcodes.ACONST_NULL);
        current.visitInsn(Opcodes.ARETURN);
        current.visitMaxs(0, 0);
        current.visitEnd();
        cw.visitEnd();
        // Registered first, so the stand-in's tree finds the name taken and leaves LegacyRegistryContext out.
        t.registerSyntheticClass(NO_CONTEXT, cw.toByteArray());
    }

    /** Mojang names to a Fabric host's intermediary ones, for the stand-ins, what they call, and the members they stand in for. */
    private static final class IntermediaryNames extends SimpleRemapper {

        // The one-argument constructor: NeoForge 1.21.11 ships ASM 9.8, which has no api-level one.
        IntermediaryNames() {
            super(Map.ofEntries(
                    Map.entry(JSON, INTERMEDIARY_JSON),
                    Map.entry(STYLE, INTERMEDIARY_STYLE),
                    Map.entry(CONTEXT, NO_CONTEXT),
                    Map.entry(OLD, "net/minecraft/class_2561$class_2562"),
                    Map.entry(OLD_STYLE, "net/minecraft/class_2583$class_2584"),
                    Map.entry(SERIALIZATION, "net/minecraft/class_8824"),
                    Map.entry(COMPONENT, "net/minecraft/class_2561"),
                    Map.entry(MUTABLE, "net/minecraft/class_5250"),
                    Map.entry(STYLE_TYPE, "net/minecraft/class_2583"),
                    // The outer class is named too, by the Provider's InnerClasses entry.
                    Map.entry("net/minecraft/core/HolderLookup", "net/minecraft/class_7225"),
                    Map.entry(PROVIDER, "net/minecraft/class_7225$class_7874"),
                    Map.entry("net/minecraft/resources/RegistryOps", "net/minecraft/class_6903"),
                    Map.entry("net/minecraft/SharedConstants", "net/minecraft/class_155"),
                    Map.entry("net/minecraft/util/datafix/DataFixers", "net/minecraft/class_3551"),
                    Map.entry("net/minecraft/util/datafix/fixes/References", "net/minecraft/class_1208"),
                    // What the stand-ins call on the host.
                    Map.entry(SERIALIZATION + ".CODEC", "field_46597"),
                    Map.entry(OLD_STYLE + ".CODEC", "field_46613"),
                    Map.entry(OLD_STYLE + ".MAP_CODEC", "field_46612"),
                    Map.entry(COMPONENT + ".copy()L" + MUTABLE + ";", "method_27661"),
                    Map.entry(COMPONENT + ".empty()L" + MUTABLE + ";", "method_43473"),
                    Map.entry(MUTABLE + ".getStyle()L" + STYLE_TYPE + ";", "method_10866"),
                    Map.entry(MUTABLE + ".withStyle(L" + STYLE_TYPE + ";)L" + MUTABLE + ";", "method_27696"),
                    Map.entry(STYLE_TYPE + ".isEmpty()Z", "method_10967"),
                    Map.entry(PROVIDER + ".createSerializationContext"
                            + "(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/resources/RegistryOps;", "method_57093"),
                    Map.entry("net/minecraft/util/datafix/DataFixers.getDataFixer()Lcom/mojang/datafixers/DataFixer;",
                            "method_15450"),
                    // The style stand-in's codecs keep the ids a mod reads them by.
                    Map.entry(STYLE + ".CODEC", "field_46613"),
                    Map.entry(STYLE + ".MAP_CODEC", "field_46612")));
        }

        // The stand-ins' members take the ids of the serializer members they stand in for. Each overload keeps its
        // descriptor, so the id follows the name and the first parameter, and Gson's package is never spelled out.
        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (owner.equals(JSON) || owner.equals(OLD)) return componentMember(name, descriptor);
            if (owner.equals(STYLE) || owner.equals(OLD_STYLE)) return styleMember(name, descriptor);
            return super.mapMethodName(owner, name, descriptor);
        }

        // The text fixer reads two host fields by reflection, whose names reach the copy as plain strings.
        @Override
        public Object mapValue(Object value) {
            if ("WORLD_VERSION".equals(value)) return "field_29732";
            if ("TEXT_COMPONENT".equals(value)) return "field_55639";
            return super.mapValue(value);
        }

        private static String componentMember(String name, String descriptor) {
            return switch (name) {
                case "fromJson" -> descriptor.startsWith("(Ljava/lang/String;") ? "method_10877" : "method_10872";
                case "fromJsonLenient" -> "method_10873";
                case "toJson" -> "method_10867";
                case "toJsonTree" -> "method_10868";
                case "toStableJson" -> "method_43858";
                // The adapter's typed read and write, which a mod's subclass overrides; Gson's bridges keep Gson's names.
                case "deserialize" -> gsonBridge(descriptor) ? name : "method_10871";
                case "serialize" -> gsonBridge(descriptor) ? name : "method_10874";
                default -> name;
            };
        }

        private static String styleMember(String name, String descriptor) {
            return switch (name) {
                case "deserialize" -> gsonBridge(descriptor) ? name : "method_10991";
                case "serialize" -> gsonBridge(descriptor) ? name : "method_10990";
                default -> name;
            };
        }

        // javac's bridge for the Gson interface returns or takes Object where the typed method names the text type.
        private static boolean gsonBridge(String descriptor) {
            return descriptor.endsWith(")Ljava/lang/Object;") || descriptor.startsWith("(Ljava/lang/Object;");
        }
    }
}
