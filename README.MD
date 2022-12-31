# RetroFuturaGradle

A Gradle plugin for development of Minecraft 1.7.10 modifications, aiming to replace use of the legacy ForgeGradle toolchain.
Large portions of the original ForgeGradle code are used, following the original LGPLv2 license.
Almost the entire interface with Gradle's task system is rewritten, but a lot of the core algorithms and transformations remain the same for compatibility with the old SDKs.

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
