import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("io.github.goooler.shadow") version "8.1.7"
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        vendor.set(JvmVendorSpec.AZUL)
    }
}

repositories {
    // For when the Forge maven goes down
    /*
    maven {
        name = "overmind-mirror-mvn"
        url = uri("https://gregtech.overminddl1.com/")
        content {
            includeGroup("net.minecraftforge")
            includeGroup("net.minecraftforge.srg2source")
            includeGroup("de.oceanlabs.mcp")
            includeGroup("cpw.mods")
        }
        metadataSources {
            artifact()
        }
    }
    */
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
        name = "gtnh"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    mavenCentral {}
}

val fg12Emulation: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val fg23Emulation: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    fg12Emulation("org.ow2.asm:asm-debug-all") { version { strictly("5.0.3") } }
    fg12Emulation("com.github.abrarsyed.jastyle:jAstyle:1.2")
    fg12Emulation("com.nothome:javaxdelta:2.0.1")
    fg12Emulation("net.md-5:SpecialSource:1.7.4")
    fg12Emulation("de.oceanlabs.mcp:mcinjector:3.2-SNAPSHOT")
    fg12Emulation("net.minecraftforge.srg2source:Srg2Source:3.2-SNAPSHOT")
    fg12Emulation("org.eclipse.jdt:org.eclipse.jdt.core") { version { strictly("3.10.0") } }

    fg23Emulation("org.ow2.asm:asm") { version { strictly("6.0") } }
    fg23Emulation("org.ow2.asm:asm-commons") { version { strictly("6.0") } }
    fg23Emulation("org.ow2.asm:asm-tree") { version { strictly("6.0") } }
    fg23Emulation("com.github.abrarsyed.jastyle:jAstyle:1.3")
    fg23Emulation("net.md-5:SpecialSource:1.8.2")
    fg23Emulation("de.oceanlabs.mcp:mcinjector:3.4-SNAPSHOT")
    fg23Emulation("net.minecraftforge:forgeflower:1.0.342-SNAPSHOT")
}

group = "com.gtnewhorizons"
version = "1.0"

val fg12EmuJar = tasks.register<ShadowJar>("fg12EmuJar") {
    archiveClassifier.set("fg12")
    isEnableRelocation = true
    relocationPrefix = "com.gtnewhorizons.retrofuturagradle.fg12shadow"
    configurations.add(fg12Emulation)
    exclude("META-INF/*.SF", "META-INF/*.RSA")
}

val fg23EmuJar = tasks.register<ShadowJar>("fg23EmuJar") {
    archiveClassifier.set("fg23")
    isEnableRelocation = true
    relocationPrefix = "com.gtnewhorizons.retrofuturagradle.fg23shadow"
    configurations.add(fg23Emulation)
    exclude("META-INF/*.SF", "META-INF/*.RSA")
}

tasks.shadowJar {
    enabled = false
}
tasks.jar {
    enabled = false
}

val allJar by tasks.registering(Jar::class) {
    dependsOn(fg12EmuJar, fg23EmuJar)
    // If the classifier is blank, IntelliJ fails to recognize the classes from this jar
    archiveClassifier.set("all")
    from(zipTree(fg12EmuJar.get().archiveFile.get()))
    from(zipTree(fg23EmuJar.get().archiveFile.get()))
    duplicatesStrategy = DuplicatesStrategy.WARN
    exclude("about_files", "about_files/**")
    exclude("ant_tasks", "ant_tasks/**")
    exclude("module-info.class")
    exclude("META-INF/LICENSE")
    exclude("META-INF/README")
    exclude("META-INF/maven/**")
}

tasks.assemble {
    dependsOn(allJar)
}

val fullyShadedElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(fullyShadedElements.name, allJar)
}
