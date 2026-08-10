/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.ClassResourceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-1.19 Text constructor bridge for Fabric on pre-26.1 hosts (intermediary names).
 *
 * <p>The 1.19 chat/Text rework removed the public constructors of
 * {@code TranslatableText} ({@code class_2588}) and {@code LiteralText} ({@code class_2585}):
 * the classes were repurposed as {@code *TextContent} and creation moved to the
 * {@code Text.translatable}/{@code Text.literal} factories
 * ({@code class_2561.method_43471}/{@code method_43470}, both returning
 * {@code class_5250 MutableText}). A pre-1.19 mod's {@code new TranslatableText("key")} then
 * dies {@code NoSuchMethodError} on a 1.19+ host. Found live in the snapshot.8 acceptance pass:
 * every Serilum 1.16.5 content mod (Double Doors, Mineral Chance, Stack Refill, via their
 * shared Collective library) failed its entrypoint on exactly this on a 1.20.1 server.
 *
 * <p>Constructor-to-factory redirects fix the creation site; the factory's
 * {@code MutableText} flows anywhere the old value was used as {@code Text}. But the mod's
 * DOWNSTREAM references stay typed on the old class: {@code new TranslatableText(k).formatted(f)}
 * compiles to {@code invokevirtual class_2588.method_27692}, and with a {@code class_5250} on
 * the stack that dies {@code VerifyError: Bad type on operand stack} (found live, round 4 of the
 * snapshot.8 acceptance pass). So the bridge ALSO class-redirects both old classes to
 * {@code class_5250}: every remaining reference (call owners, descriptors, casts, frames)
 * retypes to the factory's return type. The fluent surface a pre-1.19 mod uses
 * ({@code method_10862} setStyle, {@code method_27692} formatted, {@code method_27693}/
 * {@code method_10852} append) has lived on {@code class_5250} with the same IDs since 1.16,
 * verified against the 1.20.1 intermediary jar. Methods that existed only on the old classes
 * (e.g. {@code class_2588.method_11022} getKey) stay broken, but they were headed for a
 * {@code VerifyError} regardless.
 *
 * <p>Host-introspecting like the other Pre* bridges: registers only when the host actually
 * lost the constructor and has the factories, so 1.16.x-1.18.x hosts (constructor intact)
 * are untouched. It reads class resources without defining Minecraft classes during pre-launch.
 * Trade-off: a 1.19+ mod on this host that references {@code class_2588} as a
 * CONTENT class ({@code TranslatableTextContent}) would be mis-redirected, but such mods use
 * {@code Text.translatable} and never touch the content class directly in practice.
 */
public final class Pre1_19TextBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String TRANSLATABLE = "net/minecraft/class_2588";
    private static final String LITERAL = "net/minecraft/class_2585";
    private static final String TEXT = "net/minecraft/class_2561";
    private static final String MUTABLE = "net/minecraft/class_5250";

    private Pre1_19TextBridge() {}

    /** Register the constructor bridges when the host's Text shape needs them. */
    public static void register(RetromodTransformer transformer) {
        var translatable = ClassResourceInspector.read(TRANSLATABLE);
        if (translatable == null) return;
        boolean legacyCtorExists = translatable.methods.stream()
                .anyMatch(m -> m.name.equals("<init>")
                        && m.desc.equals("(Ljava/lang/String;)V"));
        if (legacyCtorExists) return;

        var text = ClassResourceInspector.read(TEXT);
        if (text == null) return;
        boolean hasTranslatable = text.methods.stream()
                .anyMatch(m -> m.name.equals("method_43471")
                        && m.desc.equals("(Ljava/lang/String;)L" + MUTABLE + ";"));
        boolean hasLiteral = text.methods.stream()
                .anyMatch(m -> m.name.equals("method_43470")
                        && m.desc.equals("(Ljava/lang/String;)L" + MUTABLE + ";"));
        if (!hasTranslatable || !hasLiteral) return;

        // Creation sites: constructor -> factory (isInterface=true: class_2561 Text is an
        // INTERFACE on 1.19+, so the static factory call needs an InterfaceMethodref).
        // CtorRedirectPrePass sees the PRE-remap owner names (the class redirects below run
        // downstream of it), so the two <init>s stay distinct and pick the right factory.
        transformer.registerConstructorRedirect(TRANSLATABLE, "(Ljava/lang/String;)V",
                TEXT, "method_43471", "(Ljava/lang/String;)L" + MUTABLE + ";", true);
        // the with-args overload: new TranslatableText(key, args) -> Text.translatable
        transformer.registerConstructorRedirect(TRANSLATABLE,
                "(Ljava/lang/String;[Ljava/lang/Object;)V",
                TEXT, "method_43469",
                "(Ljava/lang/String;[Ljava/lang/Object;)L" + MUTABLE + ";", true);
        transformer.registerConstructorRedirect(LITERAL, "(Ljava/lang/String;)V",
                TEXT, "method_43470", "(Ljava/lang/String;)L" + MUTABLE + ";", true);
        // Every OTHER reference (fluent-call owners, descriptors, casts, frames) retypes to
        // MutableText so the factory's return value verifies at its use sites.
        transformer.registerClassRedirect(TRANSLATABLE, MUTABLE);
        transformer.registerClassRedirect(LITERAL, MUTABLE);
        LOGGER.info("Registered pre-1.19 Text bridge "
                + "(TranslatableText/LiteralText -> Text factories + MutableText retype)");
    }
}
