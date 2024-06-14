# RetroFuturaGradle

A Gradle plugin for development of Minecraft 1.7.10 modifications, aiming to replace use of the legacy ForgeGradle toolchain.
Large portions of the original ForgeGradle code are used, following the original LGPLv2 license.
Almost the entire interface with Gradle's task system is rewritten, but a lot of the core algorithms and transformations remain the same for compatibility with the old SDKs.

# Usage

See [the example directory](example/) for a simple example of RFG usage. For a much more complex buildscript with more features, check out the [GTNHGradle plugin](https://github.com/GTNewHorizons/GTNHGradle/).

For documentation, refer to the JavaDocs for RFG classes (viewable from IntelliJ and in the plugin sources) and the Gradle User Guide. An overview of the available tasks is also documented in [docs/minecraft.md](docs/minecraft.md) and [docs/mcp-forge.md](docs/mcp-forge.md).

# Features

- [x] A forge-enabled dev environment for mod building for mc 1.7.10
- [x] Generic injection: mappings for most public and protected APIs enhances type information (`List<String>` instead of `List`)
- [x] Fixes for some FG bugs and annoyances: build errors pointing to a copy of the sources instead of the original, unreliable reobfuscation, requiring to run a setup task without which there would be an obscure error
- [x] Dependency deobfuscation artifact transformer
- [x] A task to run the decompilation constant replacer on user code (change `3.1415...` into `Math.PI` and similar, especially useful for OpenGL calls)
- [x] Support for gradle 7.6-8.8 with full support of features such as Java toolchains, configuration cache, build cache and task configuration avoidance
- [ ] (not yet implemented) A MC dev environment for recompiling a forge-style mod using the patch workflow
