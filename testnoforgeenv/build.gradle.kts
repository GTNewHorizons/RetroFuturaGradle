plugins {
    id("com.gtnewhorizons.retrofuturagradle")
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

minecraft {
    mcVersion.set("1.12.2")
    usesForge.set(false)
}

group = "testnoforgeenv"
version = "1.0"