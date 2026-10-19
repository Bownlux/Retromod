# Retromod Test Mod

This is a deliberately old Fabric mod used to check Retromod in a real game. It calls APIs from several parts of Minecraft and prints one result for each behavior it tests.

Because the source is small and controlled, a failure points to a specific transform instead of leaving us to guess which part of a third-party mod broke.

## Build

```bash
cd test-mods/retromod-test-mod
./gradlew build
```

The jar is written to `build/libs/`.

The test mod uses Gradle and Fabric Loom because it compiles against Minecraft. The main Retromod project uses Maven because its transformer works on bytecode without a Minecraft compile dependency.

## Run

1. Put the built test-mod jar in `retromod-input/` on the Fabric instance you want to test.
2. Launch Minecraft so Retromod can transform it.
3. Restart when prompted.
4. Search `logs/latest.log` for `[Retromod-Test]`.

The mod loads on a dedicated server too. There the `common-init` and `server-started` phases run. `Test41LegacyRenderLayersAndColors` (`common-init`, both sides) registers a block and its item, puts them through every old `BlockRenderLayerMap` call, and registers and reads back an old block color and item color; on a dedicated server it passes no render type, because none exists there. On a client, `Test43ClientHostTints` (`client-started`) reads the host's tint source for that block, which should ask the old block color. `Test44LegacyTooltipCallbacks` (`common-init`, both sides) registers a three-argument tooltip lambda and class and a v0 tooltip lambda, fires the event through the old three-argument invoker, and checks that each line reached the host's tooltip event. On a client, `Test45ClientHudAndScreenEvents` (`client-started`) fires a 1.20 HUD lambda and class through the old invoker, then watches the open screen and every later one with old before and after render callbacks, and logs one `Test45:` line the first time the HUD callback and each screen callback runs. In the `server-started` phase, `Test35ServerRawChannels`, `Test36ServerTypedPackets` and `Test37PacketSenderListeners` drive the old raw channels, typed packets and `PacketSender` sends through the host's own receivers and codecs, with no client. Test35 and Test36 make a fake player's connection claim a channel through Fabric API's networking internals, so a send to that player runs the host's own send; the fake connection then drops the packet. On a client, `Test38ClientChannelEcho` sends at each join and logs a `client-echo` result once the server's answers arrive, so it needs a server that runs this mod, such as the integrated one.

A healthy run ends with a summary like this:

```text
[Retromod-Test] 1 (mod loaded): success
[Retromod-Test] 2 (Text.literal): success
[Retromod-Test] SUMMARY: all tests passed
```

Do not stop at the summary. Wait for the title screen, then check that the run did not create a new file in `crash-reports/`.

## Add a Test

Most cases are a `SimpleTest` entry in one of the classes under:

```text
src/main/java/com/retromod/testmod/tests/
```

Use a standalone `Test` implementation when the case needs its own class shape, such as a subclass or a special invocation. Register new suites in `TestRunner`.

Test descriptions should say what behavior is being exercised. A failure message should include enough detail to identify the value or call that failed.

For a reported compatibility bug, keep the unit test in the main Maven suite too. The unit test checks the exact rewrite, while this mod proves that the result survives a real launch.
