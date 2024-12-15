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
        mavenCentral()
    }
}

plugins {
    id("com.gtnewhorizons.retrofuturagradle")
    id("maven-publish")
}

repositories {
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

publishing {
    publications {
        create<MavenPublication>("testmod") {
            from(components["java"])
        }
    }
}
