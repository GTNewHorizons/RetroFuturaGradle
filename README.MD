# RetroFuturaGradle

A Gradle plugin for development of Minecraft 1.7.10 modifications, aiming to replace use of the legacy ForgeGradle toolchain.
Large portions of the original ForgeGradle code are used, following the original LGPLv2 license.
Almost the entire interface with Gradle's task system is rewritten, but a lot of the core algorithms and transformations remain the same for compatibility with the old SDKs.

# Usage

See [the example directory](example/) for a simple example of RFG usage. For a much more complex buildscript with more features, check out the [GTNH buildscript](https://github.com/GTNewHorizons/ExampleMod1.7.10/blob/master/build.gradle).

For documentation, refer to the JavaDocs for RFG classes (viewable from IntelliJ and in the plugin sources) and the Gradle User Guide.

## TODO

- [x] Set up a forge-enabled dev environment for mod building
  - [x] Download MC version json 
  - [x] Process MC version JSON
  - [x] Download MC jars&assets
  - [x] Decompile
  - [x] Deobfuscate
  - [x] Recompile
  - [x] Run
  - [x] Reobfuscate
- [ ] Set up a MC dev environment for recompiling a forge-style mod using the patch workflow
