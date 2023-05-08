# MCP&FML&Forge tasks

The tasks defined in the package `com.gtnewhorizons.retrofuturagradle.mcp` can be used to create a Forge/FML-enabled development environment for mods.

Defined Gradle configurations (dependency sets):
 - `mcpMappingData` - MCP mapping data (SRG and MCP name CSVs)
 - `forgeUserdev` - Forge/FML SDK distribution zip
 - `patchedMinecraft` (Source Set) - The generated patched decompiled sources of the game+Forge if enabled
 - `mcLauncher` (Source Set) - GradleStart and related files for setting up the deobfuscated FML runtime to run the dev client&server
 - `reobfJarConfiguration` - dependencies of the reobfuscated jar for publishing
 - `obfRuntimeClasses` - extends `reobfJarConfiguration`, used for running the obfuscated environment

In order of operations (some of them can execute in parallel):
 - `downloadFernflower` - downloads the Fernflower decompiler
 - `extractMcpData` - extracts `mcpMappingData` to `~/.gradle/caches/minecraft/de/oceanlabs/mcp/mcp_stable/12/` (example for stable-12)
 - `extractForgeUserdev` - extracts `fmlUserdev` to `~/.gradle/caches/minecraft/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/unpacked/` (example for 1.7.10)
 - `generateForgeSrgMappings` - generates remapping configuration files at either the forge userdev folder or mcp folder (depending on the source of primary mcp mappings, `minecraft.getUseForgeEmbeddedMappings()`)
 - `mergeVanillaSidedJars` - merges the client&server, adding appropriate `@SideOnly` annotations into `build/rfg/vanilla_merged_minecraft.jar`
 - `deobfuscateMergedJarToSrg` - deobfuscates the merged jar with the SRG naming scheme (`func_12345_a`) into `build/rfg/srg_merged_minecraft.jar`, it also applies forge&fml access transformers if forge/fml are enabled
 - `decompileSrgJar` and `cleanupDecompSrgJar` runs FernFlower on the SRG jar to generate a source jar at `build/tmp/decompileSrgJar/ff-out/mc.jar`
   - keeps a cache of `SHA256(fernflower.jar)-SHA256(srg_merged_minecraft.jar).jar` outputs at `~/.gradle/caches/retro_futura_gradle/fernflower-cache/`
   - saves the output at `build/rfg/srg_merged_minecraft-sources-rawff.jar`
 - `cleanupDecompSrgJar`:
   - applies post-FF cleanup regexes (in the `FFPatcher` class) from the MCP tree at `build/tmp/decompileSrgJar/ffpatcher.jar`
   - applies `.patch` files from MCP at `build/tmp/decompileSrgJar/mcppatched.jar`
   - runs final cleanup tasks (AStyle autoformat, GL constant fixer, comment cleanup) at `build/tmp/decompileSrgJar/mcpcleanup.jar`
   - saves the output at `build/rfg/srg_merged_minecraft-sources.jar`
 - `patchDecompiledJar` - patches the decompiled jar with Forge/FML patches (when enabled) at `build/rfg/srg_patched_minecraft-sources.jar`
 - `remapDecompiledJar` - finds all SRG names in the decompiled patched jar and replaces them with MCP names, also adds javadocs, output at `build/rfg/mcp_patched_minecraft-sources.jar`
   - as the last task in the jar-producing chain, it removes the jars made by previous tasks to save disk space
 - `decompressDecompiledSources` - decompresses the patched sources into `build/rfg/minecraft-src`
 - `compilePatchedMcJava` - compiles the decompressed sources to `build/rfg/minecraft-classes`
 - `packagePatchedMc` - packages the recompiled minecraft to `build/rfg/recompiled_minecraft.jar`
 - `createMcLauncherFiles` - creates GradleStart java sources at `build/rfg/launcher-src` from the templates in the plugin's resources folder
 - `compileMcLauncherJava` - compiles the launcher files to `build/classes/java/mcLauncher`
 - `packageMcLauncher` - creates a jar of the launcher classes at `build/rfg/mclauncher.jar`
- `runClient` - runs the deobfuscated client with the main jar, runtimeClasspath, patched mc&mcLauncher on the classpath
- `runServer` - runs the deobfuscated server with the main jar, runtimeClasspath, patched mc&mcLauncher on the classpath
- `jar` - modified packaging task to set the classifier to `dev`
- `reobf<JarTaskName>`, including `reobfJar` - reobfuscates the given jar into a jar with an identical name and no classifier set (custom instances of ReobfuscatedJar tasks can be made that don't require a Jar task input)
- `runObfClient` - runs the obfuscated client with the reobfed jar, obfuscatedRuntimeClasspath, forge-univeral and vanilla client on the classpath
- `runObfServer` - runs the obfuscated server with the reobfed jar, obfuscatedRuntimeClasspath, forge-univeral and vanilla server on the classpath
Currently unused tasks:
- `installBinaryPatchedVersion` - generates a patched jar using the binary patching method instead of the source patching method of installing Forge
- `srgifyBinpatchedJar` - deobfuscates the binary patched jar to SRG names

All of these tasks are registered in the `plugin/src/main/java/com/gtnewhorizons/retrofuturagradle/mcp/MCPTasks.java` constructor.
This class also provides getters for all of the tasks and the mentioned files/directories for ease of use.
