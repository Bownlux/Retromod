/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retromod.gui.RetromodGui;
import com.retromod.gui.TitleScreenButtonInjector;
import com.retromod.util.McReflect;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Fabric client setup for the title-screen tools and crash handler. */
@Environment(EnvType.CLIENT)
public class RetromodClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Starting Retromod on the client.");

        EnvironmentDetector.setEnvironment(true, false);

        Path gameDir = Paths.get(".").toAbsolutePath().normalize();

        // Keep setup usable even when the pre-launch entry point did not run.
        ModHealthChecker.ensureFoldersExist(gameDir);

        initializeGui();
        registerClientCrashHandler();
        registerRetromodPresenceChannel();

        LOGGER.info("Retromod client setup is ready.");
    }
    
    /** Adds the title screen button (loader auto-detected); needs Fabric API on Fabric. */
    private void initializeGui() {
        try {
            TitleScreenButtonInjector.register();
            LOGGER.info("Retromod is available from the title screen.");
        } catch (Exception e) {
            LOGGER.warn("Could not register title screen button: {}", e.getMessage());
            LOGGER.info("Use the CLI instead: retromod <command>");
        }
    }
    
    /** Hands the MC client instance to the crash handler for world saving. */
    private void registerClientCrashHandler() {
        try {
            SafeCrashHandler crashHandler = SafeCrashHandler.getInstance();

            Class<?> mcClass = McReflect.findClass(
                "net.minecraft.client.MinecraftClient",  // yarn
                "net.minecraft.client.Minecraft"         // mojang
            );

            if (mcClass != null) {
                Method getInstance = McReflect.findMethod(mcClass, "getInstance");
                if (getInstance != null) {
                    Object mcInstance = getInstance.invoke(null);
                    crashHandler.registerClient(mcInstance);
                    LOGGER.debug("The crash handler is attached to the Minecraft client.");
                }
            } else {
                LOGGER.debug("Could not find Minecraft client class for crash handler");
            }
        } catch (Exception e) {
            LOGGER.debug("Crash handler not available: {}", e.getMessage());
        }
    }

    /**
     * Registers a JOIN listener that signals Retromod presence so a server can unlock cosmetics.
     * Reflection-only: Fabric API networking classes aren't on the Maven classpath.
     */
    private void registerRetromodPresenceChannel() {
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents");
            Object joinEvent = eventsClass.getField("JOIN").get(null);

            // proxy the JOIN callback functional interface
            Class<?> joinCallbackClass = Class.forName(
                "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents$Join");

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                joinCallbackClass.getClassLoader(),
                new Class<?>[]{joinCallbackClass},
                (proxyObj, method, args) -> {
                    if ("onPlayReady".equals(method.getName())) {
                        sendPresencePacket(args[1]); // PacketSender
                    }
                    return null;
                }
            );

            Method registerMethod = joinEvent.getClass().getMethod("register", Object.class);
            registerMethod.invoke(joinEvent, proxy);

            LOGGER.info("Retromod presence channel registered");
        } catch (Exception e) {
            LOGGER.debug("Could not register presence channel (Fabric API may not be available): {}", e.getMessage());
        }
    }

    private void sendPresencePacket(Object packetSender) {
        try {
            Class<?> packetByteBufsClass = Class.forName("net.fabricmc.fabric.api.networking.v1.PacketByteBufs");
            Object buf = packetByteBufsClass.getMethod("create").invoke(null);

            Method writeString = buf.getClass().getMethod("writeString", String.class);
            writeString.invoke(buf, "retromod");
            writeString.invoke(buf, "1.3.0-snapshot.4");

            Class<?> identifierClass = McReflect.findClass(
                "net.minecraft.util.Identifier",              // yarn
                "net.minecraft.resources.ResourceLocation"    // mojang
            );
            if (identifierClass == null) {
                LOGGER.debug("Could not find Identifier/ResourceLocation class");
                return;
            }
            Object identifier;
            // 1.21+ uses the static factory; older versions use the constructor
            Method idFactory = McReflect.findMethod(identifierClass,
                new Class[]{String.class, String.class},
                "of", "fromNamespaceAndPath");
            if (idFactory != null) {
                identifier = idFactory.invoke(null, "retromod", "presence");
            } else {
                identifier = identifierClass.getConstructor(String.class, String.class)
                    .newInstance("retromod", "presence");
            }

            Method sendPacket = packetSender.getClass().getMethod("sendPacket",
                identifierClass, buf.getClass().getSuperclass());
            sendPacket.invoke(packetSender, identifier, buf);

            LOGGER.debug("Sent Retromod presence signal to server");
        } catch (Exception e) {
            LOGGER.debug("Could not send Retromod presence signal: {}", e.getMessage());
        }
    }
}
