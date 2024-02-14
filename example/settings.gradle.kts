rootProject.name = "RFGExampleMod"

pluginManagement {
  repositories {
    maven {
      // RetroFuturaGradle
      name = "GTNH Maven"
      url = uri("https://nexus.gtnewhorizons.com/repository/public/")
      mavenContent {
        includeGroupByRegex("com\\.gtnewhorizons\\..+")
        includeGroup("com.gtnewhorizons")
      }
    }
    gradlePluginPortal()
    mavenCentral()
    mavenLocal()
  }
}

plugins {
  // Automatic toolchain provisioning
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}
