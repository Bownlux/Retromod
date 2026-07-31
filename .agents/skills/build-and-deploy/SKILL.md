---
name: build-and-deploy
description: Build Retromod and deploy the correct loader-specific jar for an in-game test. Use after code changes or before a release check.
argument-hint: "--skip-tests or --deploy"
---

# Build and Deploy

Build with the Java version configured by the project, then deploy the jar produced for the target loader and Minecraft version.

## Build

```bash
# Full build with tests
mvn package -Dexec.skip=true

# Quick build (skip tests)
mvn package -DskipTests -Dexec.skip=true -q
```

Development jars are written to `target/`. Loader-specific jars are written to `dist/` by `build-all.sh`.

## Deploy

```bash
MODS="$HOME/Library/Application Support/minecraft/mods"
cp dist/<Loader>/<MC-version>/retromod-*.jar "$MODS/"
```

Use the loader-specific distribution for runtime testing. The raw `-all.jar` bundles dependencies that can conflict with the loader.

## Run CLI Commands

Since the JAR doesn't include dependencies, use Maven exec:

```bash
mvn -f pom.xml exec:java \
  -Dexec.mainClass="com.retromod.cli.RetromodCli" \
  -Dexec.args="<command> [args]" -q
```

### Common CLI Commands
```bash
# Analyze a mod
-Dexec.args="analyze '/path/to/mod.jar'"

# Batch transform
-Dexec.args="batch '/path/to/mods-folder' --aot"

# List shims
-Dexec.args="shims"

# Show help
-Dexec.args="--help"
```

## Build Requirements
- Java 25 for compilation
- Maven 3.8+
- The build targets Java 17 bytecode so one Retromod jar can run across supported hosts

## Before Calling It Verified
- Pass `-Dexec.skip=true` so Maven does not run the CLI entry point during the build.
- Confirm the test-mod summary, then wait until the title screen or dedicated server is stable.
- Check `crash-reports/` for a report created by the current run.
- Test the mod or feature that originally failed, not only Retromod's own startup.
