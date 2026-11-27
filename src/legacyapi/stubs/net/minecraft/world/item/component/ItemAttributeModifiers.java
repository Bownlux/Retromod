// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.item.component;

public record ItemAttributeModifiers(java.util.List<?> modifiers) {
    public static final ItemAttributeModifiers EMPTY = null;
    public void forEach(net.minecraft.world.entity.EquipmentSlot slot,
            java.util.function.BiConsumer<net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>, net.minecraft.world.entity.ai.attributes.AttributeModifier> action) {
        throw new UnsupportedOperationException();
    }
}
