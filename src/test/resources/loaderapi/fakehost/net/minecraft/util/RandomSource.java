package net.minecraft.util;

public interface RandomSource {
    static RandomSource create() { return new RandomSource() { }; }
}
