/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Subscribes an old mod's {@code @Mod.EventBusSubscriber} classes that FML 26.2 cannot from the end of its {@code @Mod} constructor. */
public final class LegacyAutoSubscribeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String SUBSCRIBER = RebuiltApiClasses.ROOT + "forge/eventbus/LegacyAutoSubscriber";
    static final String SUBSCRIBE_DESC = "(Ljava/lang/Object;Ljava/lang/String;)V";
    static final String AUTO_SUBSCRIBER = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
    private static final String LEGACY_SUBSCRIBE = "L" + LegacyEventBaseBridge.LEGACY_SUBSCRIBE + ";";
    private static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');
    private static final byte[] AUTO_SUBSCRIBER_NAME = "Mod$EventBusSubscriber".getBytes(StandardCharsets.ISO_8859_1);
    private static final List<String> BOTH_SIDES = List.of("CLIENT", "DEDICATED_SERVER");
    // A string constant holds at most 65535 bytes, up to three per character, so a long list is split over several calls.
    private static final int MAX_LIST_LENGTH = 20_000;

    /** A subscriber class: its internal name, its Forge 1.20.1 bus, the sides it loads on, and the mod id it names. */
    record Subscriber(String name, String bus, List<String> sides, String modId) {
        String line() {
            return name.replace('/', '.') + " " + bus + " " + String.join(",", sides);
        }
    }

    private LegacyAutoSubscribeBridge() {}

    /** Registers the runtime helper and the constructor call where the rebuilt event base is in use. */
    public static void register(RetromodTransformer t) {
        // FML 26.2 skips legacy listeners and refuses a moved event on a mod's group, so such classes are subscribed here.
        if (!LegacyEventBaseBridge.hostLacksEventBase()) return;
        RebuiltApiClasses.registerTree(t, SUBSCRIBER);
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        ModConstructorHooks.register(t, "legacy-auto-subscribe",
                (classes, modIds) -> plan(classes, modIds, event -> LegacyModBusEvents.leftModBus(t, event)));
    }

    /** The plan for one jar: each subscriber class FML cannot subscribe on this host, or null when it has none. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds,
            Predicate<String> leftModBus) {
        List<Subscriber> subscribers = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Subscriber subscriber = subscriber(entry.getKey(), entry.getValue(), leftModBus);
            if (subscriber != null) subscribers.add(subscriber);
        }
        // By name, so every transform path writes the same list whatever order it read the jar in.
        subscribers.sort(Comparator.comparing(Subscriber::name));
        return subscribers.isEmpty() ? null : new Plan(subscribers, modIds);
    }

    /**
     * The class as a subscriber when the event-base pass gives one of its static listeners {@code @LegacySubscribe},
     * or when it is a mod-bus class with an old listener for an event that left the mod bus; otherwise null.
     */
    static Subscriber subscriber(String name, byte[] original, Predicate<String> leftModBus) {
        if (original == null || !contains(original, AUTO_SUBSCRIBER_NAME)) return null;
        ClassNode node;
        AnnotationNode annotation;
        try {
            node = read(original);
            annotation = autoSubscriber(node);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (annotation == null) return null;
        // Forge 1.20.1 had only these buses and defaulted to the game bus; any other value is a newer mod's.
        String bus = enumValue(annotation, "bus", "FORGE");
        if (!bus.equals("FORGE") && !bus.equals("MOD")) return null;
        List<String> sides = sides(annotation);
        if (sides.isEmpty()) return null;
        // FML 26.2 puts every listener of a mod-bus class on the mod's group, which refuses an event that left it.
        boolean movedEvent = bus.equals("MOD") && hasMovedModBusListener(node, leftModBus);
        // The listeners as the class pass will leave them: its pre-remap step marks the legacy ones.
        try {
            if (!movedEvent && !hasLegacyStaticListener(read(LegacyEventBaseAdapter.apply(original)))) return null;
        } catch (RuntimeException refused) {
            return null;
        }
        Object modId = value(annotation, "modid");
        return new Subscriber(name, bus, sides, modId instanceof String id ? id : null);
    }

    /** One jar's subscribers, given to the constructor of the mod each belongs to and taken away from FML. */
    private static final class Plan implements ModConstructorHooks.JarPlan {
        private final List<Subscriber> subscribers;
        private final Map<String, String> modIds;
        private final Set<String> subscribed = new LinkedHashSet<>();

        Plan(List<Subscriber> subscribers, Map<String, String> modIds) {
            this.subscribers = List.copyOf(subscribers);
            this.modIds = modIds;
            // A class whose mod is not in this jar was never subscribed, so it is left as it is.
            for (Subscriber subscriber : subscribers) {
                for (String modId : modIds.values()) {
                    if (owner(subscriber, modId).equals(modId)) subscribed.add(subscriber.name());
                }
            }
        }

        @Override
        public List<ModConstructorHooks.Call> callsFor(String modClass, String modId) {
            List<String> lines = new ArrayList<>();
            for (Subscriber subscriber : subscribers) {
                if (owner(subscriber, modId).equals(modId)) lines.add(subscriber.line());
            }
            if (lines.isEmpty()) return List.of();
            LOGGER.debug(RetromodLog.DETAIL, "Subscribing {} old event subscriber class(es) of mod {} when its constructor finishes",
                    lines.size(), modId);
            List<ModConstructorHooks.Call> calls = new ArrayList<>();
            StringBuilder list = new StringBuilder();
            for (String line : lines) {
                if (list.length() > 0 && list.length() + line.length() >= MAX_LIST_LENGTH) {
                    calls.add(call(list.toString()));
                    list.setLength(0);
                }
                if (list.length() > 0) list.append('\n');
                list.append(line);
            }
            calls.add(call(list.toString()));
            return calls;
        }

        // FML would subscribe the class too, so its EventBus 7 listeners would run twice.
        @Override
        public byte[] adjust(String className, byte[] classBytes) {
            return subscribed.contains(className) ? withoutAutoSubscriber(classBytes) : classBytes;
        }

        /** The mod a subscriber joins while {@code constructing} constructs: the id it names, its own @Mod id, or that mod. */
        private String owner(Subscriber subscriber, String constructing) {
            if (subscriber.modId() != null) return subscriber.modId();
            return modIds.getOrDefault(subscriber.name(), constructing);
        }
    }

    private static ModConstructorHooks.Call call(String list) {
        return new ModConstructorHooks.Call(ModConstructorHooks.Position.TAIL, SUBSCRIBER, "subscribe", SUBSCRIBE_DESC,
                List.of(ModConstructorHooks.Instance.MOD, list));
    }

    /** The class without its {@code @Mod.EventBusSubscriber}, or the same array when it cannot be read. */
    static byte[] withoutAutoSubscriber(byte[] classBytes) {
        try {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return desc.equals(AUTO_SUBSCRIBER) ? null : super.visitAnnotation(desc, visible);
                }
            }, 0);
            return writer.toByteArray();
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
    }

    /** Whether a static EventBus 6 listener of the class takes an event this host no longer carries on the mod bus. */
    private static boolean hasMovedModBusListener(ClassNode node, Predicate<String> leftModBus) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || method.visibleAnnotations == null) continue;
            // A newer mod's EventBus 7 listener keeps its own annotation and is left to the mod.
            if (method.visibleAnnotations.stream().noneMatch(a -> a.desc.equals(OLD_SUBSCRIBE))) continue;
            Type[] parameters = Type.getArgumentTypes(method.desc);
            if (parameters.length == 1 && parameters[0].getSort() == Type.OBJECT
                    && leftModBus.test(parameters[0].getInternalName())) return true;
        }
        return false;
    }

    private static boolean hasLegacyStaticListener(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || method.visibleAnnotations == null) continue;
            for (AnnotationNode annotation : method.visibleAnnotations) {
                if (annotation.desc.equals(LEGACY_SUBSCRIBE)) return true;
            }
        }
        return false;
    }

    private static AnnotationNode autoSubscriber(ClassNode node) {
        if (node.visibleAnnotations == null) return null;
        for (AnnotationNode annotation : node.visibleAnnotations) {
            if (annotation.desc.equals(AUTO_SUBSCRIBER)) return annotation;
        }
        return null;
    }

    /** The sides the subscriber names, both by default as in Forge 1.20.1. */
    private static List<String> sides(AnnotationNode annotation) {
        if (!(value(annotation, "value") instanceof List<?> declared)) return BOTH_SIDES;
        List<String> sides = new ArrayList<>();
        for (Object side : declared) {
            if (side instanceof String[] constant && constant.length == 2) sides.add(constant[1]);
        }
        return sides;
    }

    private static String enumValue(AnnotationNode annotation, String name, String fallback) {
        return value(annotation, name) instanceof String[] constant && constant.length == 2 ? constant[1] : fallback;
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
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
