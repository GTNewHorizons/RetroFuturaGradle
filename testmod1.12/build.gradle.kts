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
    mcpMappingChannel.set("stable")
    mcpMappingVersion.set("39")
    useForgeEmbeddedMappings.set(false)
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
