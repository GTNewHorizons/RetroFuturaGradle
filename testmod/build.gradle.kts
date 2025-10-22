import com.gtnewhorizons.retrofuturagradle.mcp.InjectTagsTask

buildscript {
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
        maven {
            name = "fabric"
            url = uri("https://maven.fabricmc.net/")
            mavenContent {
                includeGroup("net.fabricmc")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("com.gtnewhorizons.retrofuturagradle")
    id("maven-publish")
}

repositories {
    maven {
        // RetroFuturaGradle
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
        mavenContent {
            includeGroup("com.gtnewhorizons")
            includeGroupByRegex("com\\.gtnewhorizons\\..+")
        }
    }
    gradlePluginPortal()
    mavenCentral()
    mavenLocal()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        vendor.set(JvmVendorSpec.AZUL)
    }
    withSourcesJar()
    withJavadocJar()
}

group = "testmod"
version = "1.0"

minecraft {
    skipSlowTasks.set(true)
    injectedTags.put("TAG_VERSION", version)
    tagReplacementFiles.add("TestMod.java")
}

tasks.injectTags.configure {
    outputClassName.set("testmod.Tags")
}

tasks.applyJST.configure {
    // The path here can be anything, it doesn't need to be in injectedInterfaces
    // The contents of these files must match this:
    // https://github.com/neoforged/JavaSourceTransformer?tab=readme-ov-file#interface-injection
    // Interfaces should only be added to src/injectedInterfaces/java, if they are added to main, mixin, test, etc then MC will not compile
    interfaceInjectionConfigs.setFrom("src/injectedInterfaces/interfaces.json", "src/injectedInterfaces/interfaces2.json");
}

publishing {
    publications {
        create<MavenPublication>("testmod") {
            from(components["java"])
        }
    }
}
