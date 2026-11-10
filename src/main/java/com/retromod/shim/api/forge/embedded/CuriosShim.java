package com.retromod.shim.api.forge.embedded;
import com.retromod.shim.api.common.embedded.ApiShimWarnings;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
public class CuriosShim {
    public static void onEquip(Object curio, String slot, Object entity) {
        try {
            Object context = createSlotContext(slot, entity);
            Method m = LegacyPublicMethods.method(curio.getClass(), "onEquip", Class.forName("top.theillusivec4.curios.api.SlotContext"), Class.forName("net.minecraft.world.item.ItemStack"));
            m.invoke(curio, context, null);
        } catch (Exception e) { warn("onEquip", curio, "it was skipped", e); }
    }
    public static void onUnequip(Object curio, String slot, Object entity) {
        try {
            Object context = createSlotContext(slot, entity);
            Method m = LegacyPublicMethods.method(curio.getClass(), "onUnequip", Class.forName("top.theillusivec4.curios.api.SlotContext"), Class.forName("net.minecraft.world.item.ItemStack"));
            m.invoke(curio, context, null);
        } catch (Exception e) { warn("onUnequip", curio, "it was skipped", e); }
    }
    public static void curioTick(Object curio, String slot, Object entity) {
        try {
            Object context = createSlotContext(slot, entity);
            LegacyPublicMethods.method(curio.getClass(), "curioTick", Class.forName("top.theillusivec4.curios.api.SlotContext")).invoke(curio, context);
        } catch (Exception e) { warn("curioTick", curio, "it was skipped", e); }
    }
    private static Object createSlotContext(String slot, Object entity) {
        try {
            return Class.forName("top.theillusivec4.curios.api.SlotContext")
                .getConstructor(String.class, Class.forName("net.minecraft.world.entity.LivingEntity"), int.class, boolean.class, boolean.class)
                .newInstance(slot, entity, 0, false, true);
        } catch (Exception e) {
            warn("createSlotContext", entity, "the curio gets no slot context", e);
            return null;
        }
    }
    public static Object getCuriosHelper() {
        try { return Class.forName("top.theillusivec4.curios.api.CuriosApi").getMethod("getCuriosHelper").invoke(null);
        } catch (Exception e) {
            warn("getCuriosHelper", null, "it returned null", e);
            return null;
        }
    }
    public static Optional<?> getCuriosHandler(Object entity) {
        try {
            Object lazyOpt = Class.forName("top.theillusivec4.curios.api.CuriosApi")
                .getMethod("getCuriosInventory", Class.forName("net.minecraft.world.entity.LivingEntity")).invoke(null, entity);
            return (Optional<?>) LegacyPublicMethods.method(lazyOpt.getClass(), "resolve").invoke(lazyOpt);
        } catch (Exception e) {
            warn("getCuriosHandler", entity, "it returned an empty Optional", e);
            return Optional.empty();
        }
    }
    public static String getSlotIdentifier(Object preset) {
        try { return (String) LegacyPublicMethods.method(preset.getClass(), "getIdentifier").invoke(preset);
        } catch (Exception e) {
            warn("getSlotIdentifier", preset, "it returned \"unknown\"", e);
            return "unknown";
        }
    }
    public static void registerSlotType(String modId, Object slotType) {
        try {
            for (Method m : Class.forName("top.theillusivec4.curios.api.CuriosApi").getMethods()) {
                if (m.getName().contains("registerSlot") || m.getName().contains("enqueueSlotType")) {
                    m.invoke(null, modId, slotType); return;
                }
            }
            warn("registerSlotType", slotType, "the slot type was not registered", null);
        } catch (Exception e) { warn("registerSlotType", slotType, "the slot type was not registered", e); }
    }
    public static Object getAttributeModifiers(Object curio, String slot) {
        try {
            Object context = createSlotContext(slot, null);
            return LegacyPublicMethods.method(curio.getClass(), "getAttributeModifiers", Class.forName("top.theillusivec4.curios.api.SlotContext"), UUID.class)
                .invoke(curio, context, UUID.randomUUID());
        } catch (Exception e) {
            warn("getAttributeModifiers", curio, "it returned no attribute modifiers", e);
            try {
                Class<?> multimapClass = Class.forName("com.google.common.collect.HashMultimap");
                return multimapClass.getMethod("create").invoke(null);
            } catch (Exception ex) {
                return new HashMap<>();
            }
        }
    }
    public static boolean canRender(Object curio, String slot, Object entity) {
        try {
            Object context = createSlotContext(slot, entity);
            return (Boolean) LegacyPublicMethods.method(curio.getClass(), "canRender", Class.forName("top.theillusivec4.curios.api.SlotContext")).invoke(curio, context);
        } catch (Exception e) {
            warn("canRender", curio, "it returned true", e);
            return true;
        }
    }
    public static void render(Object curio, String slot, Object poseStack, Object bufferSource, int light, Object entity, float a, float b, float c, float d, float e, float f) {
        try {
            Object context = createSlotContext(slot, entity);
            for (Method m : LegacyPublicMethods.methods(curio.getClass())) {
                if (m.getName().equals("render") || m.getName().equals("curioRender")) {
                    m.invoke(curio, context, poseStack, bufferSource, light, a, b, c, d, e, f); return;
                }
            }
            warn("render", curio, "it was not drawn", null);
        } catch (Exception ex) { warn("render", curio, "it was not drawn", ex); }
    }
    private static void warn(String call, Object receiver, String effect, Throwable failure) {
        ApiShimWarnings.warnOnce("Curios", call, receiver, effect, failure);
    }
}
