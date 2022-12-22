buildscript {
    repositories {
        repositories {
            maven {
                name = "forge"
                url = uri("https://maven.minecraftforge.net")
                mavenContent {
                    includeGroup("net.minecraftforge")
                    includeGroup("net.minecraftforge.srg2source")
                    includeGroup("de.oceanlabs.mcp")
                    includeGroup("cpw.mods")
                }
            }
            maven {
                // because Srg2Source needs an eclipse dependency.
                name = "eclipse"
                url = uri("https://repo.eclipse.org/content/groups/eclipse/")
                mavenContent { includeGroup("org.eclipse.jdt") }
            }
            maven {
                name = "mojang"
                url = uri("https://libraries.minecraft.net/")
                mavenContent {
                    includeGroup("com.ibm.icu")
                    includeGroup("com.mojang")
                    includeGroup("com.paulscode")
                    includeGroup("org.lwjgl.lwjgl")
                    includeGroup("tv.twitch")
                    includeGroup("net.minecraft")
                }
            }
            mavenCentral()
        }
    }
}

plugins {
    id("com.gtnewhorizons.retrofuturagradle")
}

// Always show stacktraces for exceptions
gradle.startParameter.showStacktrace = org.gradle.api.logging.configuration.ShowStacktrace.ALWAYS_FULL

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

minecraft {
    mcVersion.set("1.7.10")
    applyMcDependencies.set(true)
}
