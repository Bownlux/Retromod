/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Keeps {@code Registry.getTagNames()}, {@code asLookup()} and the pairs {@code getTags()} streamed working where 1.21.2 made the registry its own lookup. */
public final class LegacyRegistryLookupBridge {

    // getTagNames() and listTagIds() both return a Stream of the registry's TagKeys.
    static final String STREAM = "()Ljava/util/stream/Stream;";

    /** One naming of the registry types: the base, its lookup interface, the old and new member names, its vanilla subtypes, and the helper. */
    record Names(String registry, String lookup, String tagNames, String listTagIds, String asLookup,
                 List<String> subtypes, String helper) {
        String asLookupDesc() {
            return "()L" + lookup + ";";
        }

        /** The helper's form of an old member: the registry, then nothing else. */
        String helperDesc(String oldDesc) {
            return "(L" + registry + ";" + oldDesc.substring(1);
        }
    }

    static final Names MOJANG = new Names("net/minecraft/core/Registry", "net/minecraft/core/HolderLookup$RegistryLookup",
            "getTagNames", "listTagIds", "asLookup",
            List.of("net/minecraft/core/DefaultedRegistry", "net/minecraft/core/WritableRegistry",
                    "net/minecraft/core/MappedRegistry", "net/minecraft/core/DefaultedMappedRegistry"),
            "com/retromod/generated/LegacyRegistryLookups");
    // The ids a Fabric host before 26.1 runs. 26.x Fabric hosts take the Mojang names after the remap.
    static final Names INTERMEDIARY = new Names("net/minecraft/class_2378", "net/minecraft/class_7225$class_7226",
            "method_40273", "method_46755", "method_46771",
            List.of("net/minecraft/class_7922", "net/minecraft/class_2385", "net/minecraft/class_2370",
                    "net/minecraft/class_2348"),
            "com/retromod/generated/LegacyRegistryLookupsIntermediary");

    /** One naming of {@code getTags()} and the named holder set it streams since 1.21.2, with the helper that pairs them again. */
    record TagPairNames(Names names, String getTags, String named, String key, String tagKey, String helper) {}

    static final TagPairNames MOJANG_TAGS = new TagPairNames(MOJANG, "getTags", "net/minecraft/core/HolderSet$Named", "key",
            "net/minecraft/tags/TagKey", "com/retromod/generated/LegacyRegistryTagPairs");
    // Checked against the 1.21.1 and 1.21.11 intermediary tables: the id stayed method_40272 when its elements changed.
    static final TagPairNames INTERMEDIARY_TAGS = new TagPairNames(INTERMEDIARY, "method_40272",
            "net/minecraft/class_6885$class_6888", "method_40251", "net/minecraft/class_6862",
            "com/retromod/generated/LegacyRegistryTagPairsIntermediary");
    /** The first version whose getTags() streams named holder sets; a mod built for it or later expects them. */
    static final String NAMED_TAGS_VERSION = "1.21.2";
    static final String PAIR = "com/mojang/datafixers/util/Pair";

    /** The members a host dropped: getTagNames(), asLookup(), or both. */
    record Dropped(boolean tagNames, boolean asLookup) {
        boolean any() {
            return tagNames || asLookup;
        }
    }

    private LegacyRegistryLookupBridge() {}

    /** Registers the bridge in Mojang names, for the members the host's Registry dropped. */
    public static void register(RetromodTransformer transformer) {
        registerRedirects(transformer, MOJANG, hostDropped(MOJANG));
        if (hostStreamsNamedTags(MOJANG_TAGS)) registerTagPairRedirects(transformer, MOJANG_TAGS);
    }

    /** Registers the bridge in intermediary names, for Fabric hosts 1.21.2 to 1.21.11. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        registerRedirects(transformer, INTERMEDIARY, hostDropped(INTERMEDIARY));
        if (hostStreamsNamedTags(INTERMEDIARY_TAGS)) registerTagPairRedirects(transformer, INTERMEDIARY_TAGS);
    }

    /**
     * Sends an old mod's {@code getTags()} to a helper that streams the (key, set) pairs it was written for. The name and
     * erased type did not change in 1.21.2, so the old call links and only fails when it reads an element as a pair.
     */
    static void registerTagPairRedirects(RetromodTransformer transformer, TagPairNames tags) {
        transformer.registerSyntheticClass(tags.helper(), generateTagPairHelper(tags));
        List<String> owners = new java.util.ArrayList<>(List.of(tags.names().registry()));
        owners.addAll(tags.names().subtypes());
        for (String owner : owners) {
            transformer.registerMethodRedirect(owner, tags.getTags(), STREAM, tags.helper(), "getTags",
                    tags.names().helperDesc(STREAM), true);
            // A mod built for 1.21.2 or later makes the same call and expects the named sets.
            transformer.limitRedirectToModsBefore(owner, tags.getTags(), STREAM, NAMED_TAGS_VERSION);
        }
    }

    /** Whether the host's getTags() streams named holder sets; offline, a target from 1.21.2. */
    static boolean hostStreamsNamedTags(TagPairNames tags) {
        ClassNode registry = ClassResourceInspector.read(tags.names().registry());
        if (registry == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, NAMED_TAGS_VERSION) >= 0;
        }
        return streamsNamedTags(registry, tags);
    }

    static boolean streamsNamedTags(ClassNode registry, TagPairNames tags) {
        // The descriptor is the same on both sides of the change; only the generic signature names the element.
        for (MethodNode method : registry.methods) {
            if (method.name.equals(tags.getTags()) && method.desc.equals(STREAM)) {
                return method.signature != null
                        && method.signature.startsWith("()Ljava/util/stream/Stream<L" + tags.named() + "<");
            }
        }
        return false;
    }

    /** Registers the helper and a redirect for each dropped member. */
    static void registerRedirects(RetromodTransformer transformer, Names names, Dropped dropped) {
        if (!dropped.any()) return;
        transformer.registerSyntheticClass(names.helper(), generateHelper(names, dropped));
        if (dropped.tagNames()) redirect(transformer, names, names.tagNames(), STREAM);
        if (dropped.asLookup()) redirect(transformer, names, names.asLookup(), names.asLookupDesc());
    }

    /** Sends the old member, called on Registry or a vanilla subtype, to the helper method of the same name. */
    private static void redirect(RetromodTransformer transformer, Names names, String name, String desc) {
        // A call names whichever registry type the mod held, often BuiltInRegistries' DefaultedRegistry.
        // Registry and any type proven to inherit it pass themselves to the helper.
        transformer.registerInheritedMethodRedirect(names.registry(), name, desc, names.helper(), name,
                names.helperDesc(desc));
        // The vanilla subtypes are named outright, so an offline run with no indexed jar covers them too.
        for (String subtype : names.subtypes()) {
            transformer.registerMethodRedirect(subtype, name, desc, names.helper(), name, names.helperDesc(desc), true);
        }
    }

    /** What the host Registry dropped once it became its own lookup; offline, both from 1.21.2. */
    static Dropped hostDropped(Names names) {
        ClassNode registry = ClassResourceInspector.read(names.registry());
        if (registry == null) {
            boolean after = RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0;
            return new Dropped(after, after);
        }
        return droppedIn(registry, names);
    }

    static Dropped droppedIn(ClassNode registry, Names names) {
        // Without the lookup interface there is nothing to call instead.
        if (registry.interfaces == null || !registry.interfaces.contains(names.lookup())) return new Dropped(false, false);
        return new Dropped(!declares(registry, names.tagNames(), STREAM), !declares(registry, names.asLookup(), names.asLookupDesc()));
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** Writes the helper with a static method for each dropped member. */
    static byte[] generateHelper(Names names, Dropped dropped) {
        ClassWriter cw = writer(names.helper());
        if (dropped.tagNames()) emitTagNames(cw, names);
        if (dropped.asLookup()) emitAsLookup(cw, names);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter writer(String helper) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, helper, null, "java/lang/Object", null);
        return cw;
    }

    /** Writes {@code getTags(registry)}: the host's named sets, each paired with its key as before 1.21.2, or none before tags bind. */
    static byte[] generateTagPairHelper(TagPairNames tags) {
        ClassWriter cw = writer(tags.helper());
        String object = "(Ljava/lang/Object;)Ljava/lang/Object;";
        MethodVisitor stream = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getTags",
                tags.names().helperDesc(STREAM), null, null);
        stream.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label unbound = new Label();
        stream.visitTryCatchBlock(start, end, unbound, "java/lang/IllegalStateException");
        stream.visitLabel(start);
        stream.visitVarInsn(Opcodes.ALOAD, 0);
        stream.visitMethodInsn(Opcodes.INVOKEINTERFACE, tags.names().registry(), tags.getTags(), STREAM, true);
        stream.visitLabel(end);
        // map(named -> Pair.of(named.key(), named)), with the function made by LambdaMetafactory as javac would.
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        stream.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory, Type.getMethodType(object),
                new Handle(Opcodes.H_INVOKESTATIC, tags.helper(), "pairOf", object, false), Type.getMethodType(object));
        stream.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "map",
                "(Ljava/util/function/Function;)Ljava/util/stream/Stream;", true);
        stream.visitInsn(Opcodes.ARETURN);
        // 1.21.2 and later refuse to list tags before a world binds them, where 1.21.1 listed the ones it had made.
        stream.visitLabel(unbound);
        stream.visitInsn(Opcodes.POP);
        stream.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "empty", STREAM, true);
        stream.visitInsn(Opcodes.ARETURN);
        stream.visitMaxs(0, 0);
        stream.visitEnd();

        MethodVisitor pair = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "pairOf",
                object, null, null);
        pair.visitCode();
        pair.visitVarInsn(Opcodes.ALOAD, 0);
        pair.visitTypeInsn(Opcodes.CHECKCAST, tags.named());
        pair.visitMethodInsn(Opcodes.INVOKEVIRTUAL, tags.named(), tags.key(), "()L" + tags.tagKey() + ";", false);
        pair.visitVarInsn(Opcodes.ALOAD, 0);
        pair.visitMethodInsn(Opcodes.INVOKESTATIC, PAIR, "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)L" + PAIR + ";", false);
        pair.visitInsn(Opcodes.ARETURN);
        pair.visitMaxs(0, 0);
        pair.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code getTagNames(registry)}: the registry's tag ids, or none before its tags are bound. */
    private static void emitTagNames(ClassWriter cw, Names names) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names.tagNames(),
                names.helperDesc(STREAM), null, null);
        mv.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label unbound = new Label();
        mv.visitTryCatchBlock(start, end, unbound, "java/lang/IllegalStateException");
        mv.visitLabel(start);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, names.registry(), names.listTagIds(), STREAM, true);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.ARETURN);
        // 26.x refuses to list tags before a world binds them, as at config load, where 1.21.1 listed the few it had made.
        mv.visitLabel(unbound);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "empty", STREAM, true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code asLookup(registry)}: the registry itself, which is its own lookup since 1.21.2. */
    private static void emitAsLookup(ClassWriter cw, Names names) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names.asLookup(),
                names.helperDesc(names.asLookupDesc()), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, names.lookup());
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
