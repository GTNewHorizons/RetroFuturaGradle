
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.0"
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        vendor.set(JvmVendorSpec.AZUL)
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
        isAllowInsecureProtocol = true
        url = uri("http://jenkins.usrv.eu:8081/nexus/content/groups/public/")
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

tasks.shadowJar {
    relocate("org.objectweb.asm", "com.gtnewhorizons.asm503")
    relocate("net.md_5.specialsource", "com.gtnewhorizons.specialsource174")
    relocate("org.apache", "com.gtnewhorizons.oldasmwrapper.apache")
    relocate("com.google", "com.gtnewhorizons.oldasmwrapper.google")
}
