
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("java-library")
    id("com.palantir.git-version") version "0.15.0"
    id("maven-publish")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    withSourcesJar()
    withJavadocJar()
}

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
    mavenCentral {}
}

dependencies {
    implementation("org.ow2.asm:asm-debug-all") { version { strictly("5.0.3") } }
    implementation("com.github.abrarsyed.jastyle:jAstyle:1.2")
    implementation("com.nothome:javaxdelta:2.0.1")
    implementation("net.md-5:SpecialSource:1.7.4")
    implementation("de.oceanlabs.mcp:mcinjector:3.2-SNAPSHOT")
    implementation("net.minecraftforge.srg2source:Srg2Source:3.2-SNAPSHOT")
    implementation("org.eclipse.jdt:org.eclipse.jdt.core") { version { strictly("3.10.0") } }
}

group = "com.gtnewhorizons"
version = "1.0"

tasks.named<ShadowJar>("shadowJar") {
    relocate("org.objectweb.asm", "com.gtnewhorizons.asm503")
    relocate("net.md_5.specialsource", "com.gtnewhorizons.specialsource174")
    relocate("org.apache", "com.gtnewhorizons.oldasmwrapper.apache")
    relocate("com.google", "com.gtnewhorizons.oldasmwrapper.google")
}

publishing {
    publications {
        create<MavenPublication>("testdepmod") {
            shadow.component(this)
        }
    }
}
