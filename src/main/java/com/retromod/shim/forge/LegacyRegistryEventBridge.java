/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Fires a 1.17 or 1.18 Forge mod's {@code RegistryEvent.Register} handlers again, once per registry, from the host's {@code RegisterEvent}. */
public final class LegacyRegistryEventBridge {

    static final String OLD_EVENT = "net/minecraftforge/event/RegistryEvent$Register";
    static final String SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    /** Prefix of the event class a handler for one registry is retyped to; the registry path follows, with / as _. */
    static final String PER_REGISTRY = "com/retromod/generated/forge118/LegacyRegister_";
    /** The event a handler for a registry this bridge does not know is retyped to; nothing posts it. */
    static final String UNBRIDGED = PER_REGISTRY + "unbridged";
    static final String HOOK_KEY = "legacy-registry-events";
    private static final String EVENTS = Forge1122EventBridge.EVENTS;
    private static final String MOD_BUS_MARKER = "net/minecraftforge/fml/event/IModBusEvent";

    /** The registry each 1.18 Forge registry's entry type was posted for, by its path in the host's registry key. */
    static final Map<String, String> REGISTRY_PATHS = Map.ofEntries(
            Map.entry("net/minecraft/world/level/block/Block", "block"),
            Map.entry("net/minecraft/world/item/Item", "item"),
            Map.entry("net/minecraft/world/level/material/Fluid", "fluid"),
            Map.entry("net/minecraft/sounds/SoundEvent", "sound_event"),
            Map.entry("net/minecraft/world/effect/MobEffect", "mob_effect"),
            Map.entry("net/minecraft/world/item/alchemy/Potion", "potion"),
            Map.entry("net/minecraft/world/item/enchantment/Enchantment", "enchantment"),
            Map.entry("net/minecraft/world/entity/EntityType", "entity_type"),
            Map.entry("net/minecraft/world/level/block/entity/BlockEntityType", "block_entity_type"),
            Map.entry("net/minecraft/core/particles/ParticleType", "particle_type"),
            Map.entry("net/minecraft/world/inventory/MenuType", "menu"),
            Map.entry("net/minecraft/world/item/crafting/RecipeSerializer", "recipe_serializer"),
            Map.entry("net/minecraft/world/entity/ai/attributes/Attribute", "attribute"),
            Map.entry("net/minecraft/stats/StatType", "stat_type"),
            Map.entry("net/minecraft/world/entity/npc/VillagerProfession", "villager_profession"),
            Map.entry("net/minecraft/world/entity/ai/village/poi/PoiType", "point_of_interest_type"),
            Map.entry("net/minecraft/world/entity/ai/memory/MemoryModuleType", "memory_module_type"),
            Map.entry("net/minecraft/world/entity/ai/sensing/SensorType", "sensor_type"),
            Map.entry("net/minecraft/world/level/levelgen/feature/Feature", "worldgen/feature"),
            Map.entry("net/minecraft/world/level/levelgen/carver/WorldCarver", "worldgen/carver"));

    private LegacyRegistryEventBridge() {}

    /** Registers the bridge where the running game built a mod bus {@code Register} stand-in: Forge 1.20 to 1.21.5. */
    public static void register(RetromodTransformer t) {
        byte[] standIn = t.getSyntheticClasses().get(Forge1122EventBridge.REGISTER_EVENT);
        if (standIn == null) return;
        ClassReader reader = new ClassReader(standIn);
        if (reader.getSuperName().equals("java/lang/Object") || !List.of(reader.getInterfaces()).contains(MOD_BUS_MARKER)) {
            return;
        }
        for (String path : new LinkedHashSet<>(REGISTRY_PATHS.values())) {
            t.registerSyntheticClass(eventFor(path), generateEvent(eventFor(path)));
        }
        t.registerSyntheticClass(UNBRIDGED, generateEvent(UNBRIDGED));
        ModConstructorHooks.register(t, HOOK_KEY, LegacyRegistryEventBridge::plan);
    }

    static String eventFor(String path) {
        return PER_REGISTRY + path.replace('/', '_');
    }

    /** The stand-in event for one registry: the shared {@code Register} stand-in with nothing added. */
    static byte[] generateEvent(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, Forge1122EventBridge.REGISTER_EVENT, null);
        for (String desc : new String[]{"()V", "(Ljava/lang/Object;)V"}) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            if (desc.length() > 3) init.visitVarInsn(ALOAD, 1);
            init.visitMethodInsn(INVOKESPECIAL, Forge1122EventBridge.REGISTER_EVENT, "<init>", desc, false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Plans one jar: the registries its handlers listen to, forwarded from each {@code @Mod} constructor. */
    static ModConstructorHooks.JarPlan plan(Map<String, byte[]> classes, Map<String, String> modIds) {
        Set<String> paths = new LinkedHashSet<>();
        boolean handlers = false;
        for (byte[] bytes : classes.values()) {
            if (!com.retromod.shim.common.LegacyWidgetInputAdapter.mentions(bytes, OLD_EVENT)) continue;
            // A generic listener on the old event would receive the posted events and fail, so such a mod stays as it was.
            if (com.retromod.shim.common.LegacyWidgetInputAdapter.mentions(bytes, "addGenericListener")) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (!isHandler(method, OLD_EVENT)) continue;
                handlers = true;
                String path = handlerPath(method, OLD_EVENT);
                if (path != null) paths.add(path);
            }
        }
        // A jar with only unknown-registry handlers still needs them retyped away from the shared stand-in.
        if (!handlers) return null;
        return new ModConstructorHooks.JarPlan() {
            @Override
            public List<ModConstructorHooks.Call> callsFor(String modClass, String modId) {
                List<ModConstructorHooks.Call> calls = new ArrayList<>();
                for (String path : paths) {
                    calls.add(new ModConstructorHooks.Call(ModConstructorHooks.Position.HEAD, EVENTS, "forwardRegistry",
                            "(Ljava/lang/String;Ljava/lang/String;)V", List.of(modId, path)));
                }
                return calls;
            }

            @Override
            public byte[] adjust(String className, byte[] classBytes) {
                return retypeHandlers(classBytes);
            }
        };
    }

    /** A transformed class whose old registry handlers now take their registry's own stand-in event. */
    static byte[] retypeHandlers(byte[] classBytes) {
        if (!com.retromod.shim.common.LegacyWidgetInputAdapter.mentions(classBytes, Forge1122EventBridge.REGISTER_EVENT)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!isHandler(method, Forge1122EventBridge.REGISTER_EVENT)) continue;
            String path = handlerPath(method, Forge1122EventBridge.REGISTER_EVENT);
            // The host bus sends each handler only its own registry's event, as 1.18's generic filter did.
            // A handler left on the shared stand-in would get every registry's event, so unknown ones get none.
            method.desc = "(L" + (path == null ? UNBRIDGED : eventFor(path)) + ";)V";
            method.signature = null;
            changed = true;
        }
        if (!changed) return classBytes;
        // Only descriptors changed, so the class's frames stay valid as they are.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether the method is a {@code @SubscribeEvent} handler taking {@code eventType}, raw or generic. */
    static boolean isHandler(MethodNode method, String eventType) {
        return method.desc.equals("(L" + eventType + ";)V") && subscribed(method);
    }

    /** The registry path of a handler taking {@code eventType} for a known entry type, or null. */
    static String handlerPath(MethodNode method, String eventType) {
        if (!isHandler(method, eventType) || method.signature == null) return null;
        String marker = eventType + "<L";
        int start = method.signature.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = start;
        while (end < method.signature.length() && method.signature.charAt(end) != ';' && method.signature.charAt(end) != '<') {
            end++;
        }
        return REGISTRY_PATHS.get(method.signature.substring(start, end));
    }

    private static boolean subscribed(MethodNode method) {
        if (method.visibleAnnotations == null) return false;
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.equals(SUBSCRIBE)) return true;
        }
        return false;
    }
}
