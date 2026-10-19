# Retromod Test Mod for 1.21

This is a second old Fabric test mod, built for Minecraft 1.21.1 with Fabric API 0.116. It covers Fabric API shapes from 1.20.5 to 1.21.11, which the 1.20.1 test mod in `../retromod-test-mod/` cannot compile against. Its cases continue that mod's numbering, starting at Test34.

Unlike the 1.20.1 mod, it uses a common entry point, so the suite also runs on a dedicated server.

## Build

```bash
cd test-mods/retromod-test-mod-fabric-1.21
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build
```

The jar is written to `build/libs/`. Gradle 8.10 needs Java 21 to run.

## Run

1. Put the built jar in `retromod-input/` on a Fabric 1.21.11 or 26.x instance or server.
2. Start it so Retromod translates the jar, then restart when asked.
3. Search `logs/latest.log` for `[Retromod-Test-1.21]`.

A healthy run ends with `SUMMARY: N/N passed`. Do not stop at the summary. Wait for the server to finish starting, or for the title screen, then check that `crash-reports/` has no new file.

## Cases

Init cases run at mod initialization. Server cases run once a dedicated or integrated server has started.

- `Test34LoaderApiRuntimeMarker` checks that the translated jar carries the `Retromod-Loader-Api-Runtime` value the running Retromod expects for its host. That value is empty before 26.1 and while the runtime is off. In the launch where the setting changed, the jar passes when its rebuilt copy waits in `retromod-input/pending/`.
- `Test39PayloadPacketShapes` (server) builds a packet with `ServerPlayNetworking.createS2CPacket(CustomPayload)`, which 26.1 renamed, and sends a payload and a packet through a `PacketSender` with old `PacketCallbacks`, which must still run.
- `Test40ClientPayloadPacket` (client only) builds a packet with `ClientPlayNetworking.createC2SPacket(CustomPayload)`.
- `Test42RenderLayerAndColorShapes` registers a block and its item, puts them through the old instance `BlockRenderLayerMap` and the static one Fabric API used from 1.21.6 to 1.21.11, and registers and reads back an old block color and an item color that carries alpha. The 1.21.6 calls compile against stand-ins in `src/main/java/net/`, which the jar task leaves out, so the built jar calls them as a 1.21.11 mod does. On a client they also pass `CUTOUT_MIPPED` and `TRIPWIRE`, which 26.x removed, and check that they record as `CUTOUT` and `TRANSLUCENT`. On a dedicated server it passes no render type or section layer, because none exists there.
- `Test46ScreenShapesFrom121` fires a 1.20.5 tooltip callback through the host's own tooltip event and the 1.21.5 debug text events through their old invokers. On a client it also fires a 1.21 HUD callback through the old invoker, adds layers through the 1.21.4 HUD layer API, which fires once the client has started, and attaches HUD elements next to the 1.21.6 `DEBUG` and `STATUS_EFFECTS` ids that 26.x removed and renamed. The 1.21.4 to 1.21.8 shapes compile against stand-ins in `src/main/java/net/`, which the jar task leaves out. A dedicated server has no GUI classes, so it builds no HUD lambda.
