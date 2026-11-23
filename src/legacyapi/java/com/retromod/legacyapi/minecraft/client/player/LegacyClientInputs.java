/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.player;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * The fields of Minecraft 1.21.1's movement input, read from and written to the client input that replaced it: the held
 * keys became one record, and the two impulses one move vector.
 */
public final class LegacyClientInputs {

    // The move vector is protected on the host, so an old write of an impulse reaches it by reflection.
    private static final Field MOVE_VECTOR = moveVectorField();

    private LegacyClientInputs() {}

    public static boolean up(Object input) {
        return keys(input).forward();
    }

    public static void setUp(Object input, boolean held) {
        Input k = keys(input);
        setKeys(input, new Input(held, k.backward(), k.left(), k.right(), k.jump(), k.shift(), k.sprint()));
    }

    public static boolean down(Object input) {
        return keys(input).backward();
    }

    public static void setDown(Object input, boolean held) {
        Input k = keys(input);
        setKeys(input, new Input(k.forward(), held, k.left(), k.right(), k.jump(), k.shift(), k.sprint()));
    }

    public static boolean left(Object input) {
        return keys(input).left();
    }

    public static void setLeft(Object input, boolean held) {
        Input k = keys(input);
        setKeys(input, new Input(k.forward(), k.backward(), held, k.right(), k.jump(), k.shift(), k.sprint()));
    }

    public static boolean right(Object input) {
        return keys(input).right();
    }

    public static void setRight(Object input, boolean held) {
        Input k = keys(input);
        setKeys(input, new Input(k.forward(), k.backward(), k.left(), held, k.jump(), k.shift(), k.sprint()));
    }

    public static boolean jumping(Object input) {
        return keys(input).jump();
    }

    public static void setJumping(Object input, boolean held) {
        Input k = keys(input);
        setKeys(input, new Input(k.forward(), k.backward(), k.left(), k.right(), held, k.shift(), k.sprint()));
    }

    public static boolean shiftKeyDown(Object input) {
        return keys(input).shift();
    }

    public static void setShiftKeyDown(Object input, boolean held) {
        Input k = keys(input);
        setKeys(input, new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), held, k.sprint()));
    }

    /** The sideways impulse, the move vector's x. */
    public static float leftImpulse(Object input) {
        return ((ClientInput) input).getMoveVector().x;
    }

    public static void setLeftImpulse(Object input, float impulse) {
        setMove(input, new Vec2(impulse, ((ClientInput) input).getMoveVector().y));
    }

    /** The forward impulse, the move vector's y. */
    public static float forwardImpulse(Object input) {
        return ((ClientInput) input).getMoveVector().y;
    }

    public static void setForwardImpulse(Object input, float impulse) {
        setMove(input, new Vec2(((ClientInput) input).getMoveVector().x, impulse));
    }

    /** {@code input.tick(sneaking, sneakSpeed)}: the client input reads the sneak state itself now. */
    public static void tick(Object input, boolean sneaking, float sneakSpeed) {
        ((ClientInput) input).tick();
    }

    private static Input keys(Object input) {
        return ((ClientInput) input).keyPresses;
    }

    private static void setKeys(Object input, Input keys) {
        ((ClientInput) input).keyPresses = keys;
    }

    private static void setMove(Object input, Vec2 move) {
        try {
            if (MOVE_VECTOR != null) {
                MOVE_VECTOR.set(input, move);
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException refused) {
            // Reported below.
        }
        LegacyLog.warnOnce("legacy input impulse " + LegacyClientInputs.class.getName(), "An old mod set a movement impulse"
                + " that Minecraft no longer lets other code change, so the player moves as the keys say.");
    }

    private static Field moveVectorField() {
        try {
            Field field = ClientInput.class.getDeclaredField("moveVector");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }
}
