/*
 * Retromod Test Library (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testlib.forge;

import net.minecraftforge.eventbus.api.Event;

/** An event this library defines on Forge 1.20.1's event base, which another mod's auto-subscribed class listens to. */
public class LibAutoSubscribedEvent extends Event {
    public LibAutoSubscribedEvent() {}
}
