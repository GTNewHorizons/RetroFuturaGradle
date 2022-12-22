# MCP&FML&Forge tasks

The tasks defined in the package `com.gtnewhorizons.retrofuturagradle.mcp` can be used to create a Forge/FML-enabled development environment for mods.

Defined Gradle configurations (dependency sets):
 - `mcpMappingData` - MCP mapping data (SRG and MCP name CSVs)
 - `fmlUserdev` - Forge/FML SDK distribution zip

In order of operations (some of them can execute in parallel):
 - `downloadFernflower` - downloads the Fernflower decompiler
 - `extractMcpData` - extracts `mcpMappingData` to `build/mcp/data`
 - `extractForgeUserdev` - extracts `fmlUserdev` to `build/mcp/userdev`
 - `generateForgeSrgMappings` - generates remapping configuration files at `build/mcp/forge_srg`
 - `mergeVanillaSidedJars` - merges the client&server, adding appropriate `@SideOnly` annotations into `build/mcp/vanilla_merged_minecraft.jar`
 - `deobfuscateMergedJarToSrg` - deobfuscates the merged jar with the SRG naming scheme (`func_12345_a`) into `build/mcp/srg_merged_minecraft.jar`

All of these tasks are registered in the `plugin/src/main/java/com/gtnewhorizons/retrofuturagradle/mcp/MCPTasks.java` constructor.
This class also provides getters for all of the tasks and the mentioned files/directories for ease of use.
