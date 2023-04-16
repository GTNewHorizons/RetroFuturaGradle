import org.jetbrains.gradle.ext.Application
import org.jetbrains.gradle.ext.Gradle
import org.jetbrains.gradle.ext.RunConfigurationContainer

plugins {
  id("java-library")
  id("maven-publish")
  id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.7"
  id("eclipse")
  id("com.gtnewhorizons.retrofuturagradle") version "1.2.5"
}

// Project properties
group = "rfg.examplemod"
version = "1.0.0"

// Set the toolchain version to decouple the Java we run Gradle with from the Java used to compile and run the mod
java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(8))
    // Azul covers the most platforms for Java 8 toolchains, crucially including MacOS arm64
    vendor.set(org.gradle.jvm.toolchain.JvmVendorSpec.AZUL)
  }
  // Generate sources and javadocs jars when building and publishing
  withSourcesJar()
  withJavadocJar()
}

// Most RFG configuration lives here, see the JavaDoc for com.gtnewhorizons.retrofuturagradle.MinecraftExtension
minecraft {
  mcVersion.set("1.7.10")

  // Username for client run configurations
  username.set("Developer")

  // Generate a field named VERSION with the mod version in the injected Tags class
  injectedTags.put("VERSION", project.version)

  // If you need the old replaceIn mechanism, prefer the injectTags task because it doesn't inject a javac plugin.
  // tagReplacementFiles.add("RfgExampleMod.java")

  // Enable assertions in the mod's package when running the client or server
  extraRunJvmArguments.add("-ea:${project.group}")

  // If needed, add extra tweaker classes like for mixins.
  // extraTweakClasses.add("org.spongepowered.asm.launch.MixinTweaker")

  // Exclude some Maven dependency groups from being automatically included in the reobfuscated runs
  groupsToExcludeFromAutoReobfMapping.addAll("com.diffplug", "com.diffplug.durian", "net.industrial-craft")
}

// Generates a class named rfg.examplemod.Tags with the mod version in it, you can find it at
tasks.injectTags.configure {
  outputClassName.set("${project.group}.Tags")
}

// Put the version from gradle into mcmod.info
tasks.processResources.configure {
  inputs.property("version", project.version)

  filesMatching("mcmod.info") {
    expand(mapOf("modVersion" to project.version))
  }
}

// Create a new dependency type for runtime-only dependencies that don't get included in the maven publication
val runtimeOnlyNonPublishable: Configuration by configurations.creating {
  description = "Runtime only dependencies that are not published alongside the jar"
  isCanBeConsumed = false
  isCanBeResolved = false
}
listOf(configurations.runtimeClasspath, configurations.testRuntimeClasspath).forEach {
  it.configure {
    extendsFrom(
      runtimeOnlyNonPublishable
    )
  }
}

// Add an access tranformer
// tasks.deobfuscateMergedJarToSrg.configure {accessTransformerFiles.from("src/main/resources/META-INF/mymod_at.cfg")}

// Dependencies
repositories {
  maven {
    name = "OvermindDL1 Maven"
    url = uri("https://gregtech.overminddl1.com/")
    mavenContent {
      excludeGroup("net.minecraftforge") // missing the `universal` artefact
    }
  }
  maven {
    name = "GTNH Maven"
    url = uri("http://jenkins.usrv.eu:8081/nexus/content/groups/public/")
    isAllowInsecureProtocol = true
  }
}

dependencies {
  // Adds NotEnoughItems and its dependencies (CCL&CCC) to runClient/runServer
  runtimeOnlyNonPublishable("com.github.GTNewHorizons:NotEnoughItems:2.3.39-GTNH:dev")
  // Example: grab the ic2 jar from curse maven and deobfuscate
  // api(rfg.deobf("curse.maven:ic2-242638:2353971"))
  // Example: grab the ic2 jar from libs/ in the workspace and deobfuscate
  // api(rfg.deobf(project.files("libs/ic2.jar")))
}

// Publishing to a Maven repository
publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
    }
  }
  repositories {
    // Example: publishing to the GTNH Maven repository
    maven {
      url = uri("http://jenkins.usrv.eu:8081/nexus/content/repositories/releases")
      isAllowInsecureProtocol = true
      credentials {
        username = System.getenv("MAVEN_USER") ?: "NONE"
        password = System.getenv("MAVEN_PASSWORD") ?: "NONE"
      }
    }
  }
}

// IDE Settings
eclipse {
  classpath {
    isDownloadSources = true
    isDownloadJavadoc = true
  }
}

idea {
  module {
    isDownloadJavadoc = true
    isDownloadSources = true
    inheritOutputDirs = true // Fix resources in IJ-Native runs
  }
  project {
    this.withGroovyBuilder {
      "settings" {
        "runConfigurations" {
          val self = this.delegate as RunConfigurationContainer
          self.add(Gradle("1. Run Client").apply {
            setProperty("taskNames", listOf("runClient"))
          })
          self.add(Gradle("2. Run Server").apply {
            setProperty("taskNames", listOf("runServer"))
          })
          self.add(Gradle("3. Run Obfuscated Client").apply {
            setProperty("taskNames", listOf("runObfClient"))
          })
          self.add(Gradle("4. Run Obfuscated Server").apply {
            setProperty("taskNames", listOf("runObfServer"))
          })
          /*
          These require extra configuration in IntelliJ, so are not enabled by default
          self.add(Application("Run Client (IJ Native, Deprecated)", project).apply {
            mainClass = "GradleStart"
            moduleName = project.name + ".ideVirtualMain"
            afterEvaluate {
              val runClient = tasks.runClient.get()
              workingDirectory = runClient.workingDir.absolutePath
              programParameters = runClient.calculateArgs(project).map { '"' + it + '"' }.joinToString(" ")
              jvmArgs = runClient.calculateJvmArgs(project).map { '"' + it + '"' }.joinToString(" ") +
                ' ' + runClient.systemProperties.map { "\"-D" + it.key + '=' + it.value.toString() + '"' }
                .joinToString(" ")
            }
          })
          self.add(Application("Run Server (IJ Native, Deprecated)", project).apply {
            mainClass = "GradleStartServer"
            moduleName = project.name + ".ideVirtualMain"
            afterEvaluate {
              val runServer = tasks.runServer.get()
              workingDirectory = runServer.workingDir.absolutePath
              programParameters = runServer.calculateArgs(project).map { '"' + it + '"' }.joinToString(" ")
              jvmArgs = runServer.calculateJvmArgs(project).map { '"' + it + '"' }.joinToString(" ") +
                ' ' + runServer.systemProperties.map { "\"-D" + it.key + '=' + it.value.toString() + '"' }
                .joinToString(" ")
            }
          })
          */
        }
        "compiler" {
          val self = this.delegate as org.jetbrains.gradle.ext.IdeaCompilerConfiguration
          afterEvaluate {
            self.javac.moduleJavacAdditionalOptions = mapOf(
              (project.name + ".main") to
                tasks.compileJava.get().options.compilerArgs.map { '"' + it + '"' }.joinToString(" ")
            )
          }
        }
      }
    }
  }
}

tasks.processIdeaSettings.configure {
  dependsOn(tasks.injectTags)
}
