import com.gtnewhorizons.retrofuturagradle.mcp.InjectTagsTask

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
    // skipSlowTasks.set(true)
    mcVersion.set("1.12.2")
    injectedTags.put("TAG_VERSION", version)
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
