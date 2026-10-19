package net.minecraft.world.item;

public interface TooltipFlag {
    TooltipFlag NORMAL = new TooltipFlag() {
        @Override public String toString() { return "normal"; }
    };
}
