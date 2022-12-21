# Minecraft tasks & configuration

The tasks defined in the package `com.gtnewhorizons.retrofuturagradle.minecraft` can fetch and run jars of the vanilla client&server.

In order of operations (some of them can execute in parallel):
 - The Mojang (`mojang`), Forge (`forge`) and MavenCentral maven repositories are added to the project
 - On plugin application, after buildscript evaluation:
   - If `minecraft { applyMcDependencies }` is set to true, all the dependencies needed to run the game are added to the project into a gradle configuration named `vanilla_minecraft`, accessible from the `MinecraftTasks` class.
 - `downloadLauncherAllVersionsManifest` - downloads the `version_manifest.json` file from the Mojang launcher servers to `build/vanilla-mc/all_versions_manifest.json`
 - `downloadLauncherVersionManifest` - downloads the `1.7.10.json` version manifest which specifies asset locations, client/server jar download urls and more, downloaded to `build/vanilla-mc/mc_version_manifest.json`
 - `downloadAssetManifest` - downloads the `1.7.10.json` asset manifest into `GRADLE_USER_HOME/cache/retro_futura_gradle/assets/indexes/1.7.10.json` (on Linux user home is at `~/.gradle`)
 - `downloadVanillaJars` - downloads `client.jar` and `server.jar` into `GRADLE_USER_HOME/cache/retro_futura_gradle/mc-vanilla/1.7.10/*.jar`
 - `downloadVanillaAssets` - parses the asset manifest and downloads assets into `GRADLE_USER_HOME/cache/retro_futura_gradle/assets/objects/XX/SHA1`
 - **`cleanVanillaAssets`** - can be executed to manually clear the asset cache from the previous task
 - `extractNatives` - extracts the natives from the dependencies in the `vanilla_minecraft` configuration into `PROJECT_DIR/run/natives`
 - `runVanillaClient` - runs the vanilla client jar at `PROJECT_DIR/run` with a dummy Developer account
 - `runVanillaServer` - runs the vanilla server jar at `PROJECT_DIR/run`

All of these tasks are registered in the `plugin/src/main/java/com/gtnewhorizons/retrofuturagradle/minecraft/MinecraftTasks.java` constructor.
This class also provides getters for all of the tasks and the mentioned files/directories for ease of use.
