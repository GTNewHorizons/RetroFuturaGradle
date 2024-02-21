import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.*
import java.util.jar.JarFile

/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Gradle plugin project to get you started.
 * For more details take a look at the Writing Custom Plugins chapter in the Gradle
 * User Manual available at https://docs.gradle.org/7.6/userguide/custom_plugins.html
 */

plugins {
  // Apply the Java Gradle plugin development plugin to add support for developing Gradle plugins
  id("java-gradle-plugin")
  id("com.github.johnrengelman.shadow") version "8.1.1"
  id("com.palantir.git-version") version "3.0.0"
  id("maven-publish")
  id("com.diffplug.spotless") version "6.23.1"
  id("com.github.gmazzo.buildconfig") version "4.2.0"
}

evaluationDependsOnChildren()

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
    url = uri("https://nexus.gtnewhorizons.com/repository/public/")
  }
  maven {
    name = "paper"
    url = uri("https://papermc.io/repo/repository/maven-snapshots/")
    mavenContent {
      includeGroup("org.cadixdev")
    }
  }
  maven {
    name = "sonatype"
    url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenContent {
      includeGroup("org.cadixdev")
    }
  }
  mavenCentral {}
  gradlePluginPortal()
}

val gitVersion: groovy.lang.Closure<String> by extra

group = "com.gtnewhorizons"

val versionOverride = System.getenv("VERSION") ?: null
val identifiedVersion = versionOverride ?: gitVersion().removeSuffix(".dirty")
version = identifiedVersion

if (identifiedVersion == versionOverride) {
  println("Override version to $version!")
}

val runtimeOnlyNonPublishable by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = false
}
configurations.runtimeClasspath.configure { extendsFrom(runtimeOnlyNonPublishable) }

dependencies {
  compileOnly(localGroovy())
  compileOnly(gradleApi())

  annotationProcessor("com.github.bsideup.jabel:jabel-javac-plugin:1.0.1")
  // workaround for https://github.com/bsideup/jabel/issues/174
  annotationProcessor("net.java.dev.jna:jna-platform:5.13.0")
  testAnnotationProcessor("com.github.bsideup.jabel:jabel-javac-plugin:1.0.1")
  compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:1.0.1") { isTransitive = false }

  // Apache Commons utilities
  implementation("org.apache.commons:commons-lang3:3.14.0")
  implementation("org.apache.commons:commons-text:1.11.0")
  implementation("commons-io:commons-io:2.15.1")
  implementation("commons-codec:commons-codec:1.16.0")
  implementation("org.apache.commons:commons-compress:1.25.0")
  // Guava utilities
  implementation("com.google.guava:guava:33.0.0-jre")
  // CSV reader, also used by SpecialSource
  implementation("com.opencsv:opencsv:5.7.1")
  // Diffing&Patching
  implementation("org.ow2.asm:asm:9.6")
  implementation("com.cloudbees:diff4j:1.1")
  implementation("com.github.jponge:lzma-java:1.3")
  implementation("net.md-5:SpecialSource:1.11.3")
  // Java source manipulation
  implementation("com.github.javaparser:javaparser-core:3.25.8")
  implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.8")
  // "MCP stuff", shaded manually later
  compileOnly(project(":oldasmwrapper", "fullyShadedElements"))
  testImplementation(project(":oldasmwrapper", "fullyShadedElements"))
  runtimeOnlyNonPublishable(project(":oldasmwrapper", "fullyShadedElements"))
  // Startup classes
  compileOnly("com.mojang:authlib:1.5.16") { isTransitive = false }
  compileOnly("net.minecraft:launchwrapper:1.12") { isTransitive = false }
  // Provides a file-downloading task implementation for Gradle
  implementation(
      group = "de.undercouch.download",
      name = "de.undercouch.download.gradle.plugin",
      version = "5.5.0")
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.0")
  // JSON handling for Minecraft manifests etc.
  implementation("com.google.code.gson:gson:2.10.1")
  // Forge utilities (to be merged into the source tree in the future)

  // Source remapping
  // We use Paper's Mercury fork because it both supports Java 17 and is binary compatible with mercurymixin.
  implementation("org.cadixdev:mercury:0.1.2-paperweight-SNAPSHOT")
  implementation("org.cadixdev:mercurymixin:0.1.0-SNAPSHOT")
  implementation("net.fabricmc:mapping-io:0.5.1")
  // Use JUnit Jupiter for testing.
  testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")

  constraints {
    implementation("org.apache.logging.log4j:log4j-core") {
      version {
        strictly("[2.17, 3[")
        prefer("2.19.0")
      }
      because(
          "CVE-2021-44228, CVE-2021-45046, CVE-2021-45105: Log4j vulnerable to remote code execution and other critical security vulnerabilities")
    }
  }

  testImplementation(gradleApi())
}

buildConfig {
  buildConfigField("String", "PLUGIN_NAME", "\"${project.name}\"")
  buildConfigField("String", "PLUGIN_GROUP", "\"${project.group}\"")
  buildConfigField("String", "PLUGIN_VERSION", provider { "\"${project.version}\"" })
  className("BuildConfig")
  packageName("com.gtnewhorizons.retrofuturagradle")
  useJavaOutput()
}

val depGradleApi = dependencies.gradleApi()

configurations.api.configure { dependencies.remove(depGradleApi) }

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(8))
    vendor.set(JvmVendorSpec.AZUL)
  }
}

// Add a source set for the functional test suite
val functionalTestSourceSet = sourceSets.create("functionalTest") {}

tasks.withType<JavaCompile> {
  sourceCompatibility = "17" // for the IDE support
  options.release.set(8)

  javaCompiler.set(javaToolchains.compilerFor {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.AZUL)
  })
}

tasks.withType<Javadoc>().configureEach {
  this.javadocTool.set(javaToolchains.javadocToolFor {
    languageVersion.set(JavaLanguageVersion.of(17))
    vendor.set(JvmVendorSpec.AZUL)
  })
  with(options as StandardJavadocDocletOptions) {
    links(
      "https://docs.gradle.org/${gradle.gradleVersion}/javadoc/",
      "https://docs.oracle.com/en/java/javase/17/docs/api/"
    )
  }
}

spotless {
  encoding("UTF-8")

  java {
    target("src/**/*.java")
    toggleOffOn()
    importOrderFile("../spotless.importorder")
    removeUnusedImports()
    eclipse("4.19.0").configFile("../spotless.eclipseformat.xml")
  }
}

gradlePlugin {
  // Define the plugin
  plugins {
    website.set("https://github.com/GTNewHorizons/RetroFuturaGradle")
    vcsUrl.set("https://github.com/GTNewHorizons/RetroFuturaGradle.git")
    isAutomatedPublishing = false
    create("userDev") {
      id = "com.gtnewhorizons.retrofuturagradle"
      implementationClass = "com.gtnewhorizons.retrofuturagradle.UserDevPlugin"
      displayName = "RetroFuturaGradle"
      description = "Provides a Minecraft 1.7.10 and Forge modding toolchain"
      tags.set(listOf("minecraft", "modding"))
    }
    create("patchDev") {
      id = "com.gtnewhorizons.retrofuturagradle.patchdev"
      implementationClass = "com.gtnewhorizons.retrofuturagradle.PatchDevPlugin"
      displayName = "RetroFuturaGradle-PatchDev"
      description = "Provides a Minecraft 1.7.10 and Forge modding toolchain"
      tags.set(listOf("minecraft", "modding"))
    }
  }
}

java {
  sourceSets {
    // Add the GradleStart tree as sources for IDE support
    create("gradleStart") {
      compileClasspath = configurations.compileClasspath.get()
      java {
        setSrcDirs(Collections.emptyList<String>())
        source(sourceSets.main.get().resources)
      }
    }
  }

  withSourcesJar()
  withJavadocJar()
}

if(project.properties["rfg.skipJavadoc"].toString().toBoolean()) {
  tasks.named("javadoc").configure { enabled = false }
}

val depsShadowJar = tasks.register<ShadowJar>("depsShadowJar") {
  archiveClassifier.set("deps")
  archiveVersion.set("0.0") // constant version to prevent task from rerunning when project version changes
  isEnableRelocation = true
  relocationPrefix = "com.gtnewhorizons.retrofuturagradle.shadow"
  configurations.add(project.configurations.runtimeClasspath.get())
  dependencies {
    // we're already shading this in combinedShadowJar
    exclude(project(":oldasmwrapper"))
  }
  mergeServiceFiles()
}

val mainShadowJar = tasks.register<ShadowJar>("mainShadowJar") {
  archiveClassifier.set("mainShadow")

  from(sourceSets.main.get().output)

  doFirst {
    // Adapted from Shadow's RelocationUtil.groovy
    // We want to relocate references to dependencies but not include the dependencies themselves in this jar.
    // We also don't want to double-shade RFG classes
    val prefix = "com.gtnewhorizons.retrofuturagradle.shadow"
    val configurations = listOf(project.configurations.runtimeClasspath.get())

    val packages = mutableSetOf<String>()

    configurations.iterator().forEach { configuration ->
      configuration.files.filter {
        // we're already shading this in combinedShadowJar
        f ->
        f != project(":oldasmwrapper").tasks.named<Jar>("allJar").get().archiveFile.get().asFile
      }.forEach { jar ->
        JarFile(jar).use {
          it.entries().iterator().forEach { entry ->
            if (entry.name.endsWith(".class") && entry.name != "module-info.class") {
              val pkg = entry.name.substring(0, entry.name.lastIndexOf('/') - 1).replace('/', '.')
              if (!pkg.startsWith("com.gtnewhorizons.retrofuturagradle")) {
                packages.add(pkg)
              }
            }
          }
        }
      }
    }
    packages.iterator().forEach {
      relocate(it, "${prefix}.${it}")
    }
  }
}

val combinedShadowJar = tasks.register<Jar>("combinedShadowJar") {
  from("LICENSE", "docs", tasks.named("classes"))
  val oldAsmJarTask = project(":oldasmwrapper").tasks.named<Jar>("allJar")
  dependsOn(oldAsmJarTask)
  from(zipTree(oldAsmJarTask.get().archiveFile)) // cheaper shadow of an already shaded jar

  // dependencies are relocated in a separate task so the results can be reused
  dependsOn(depsShadowJar)
  from(zipTree(depsShadowJar.get().archiveFile))

  dependsOn(mainShadowJar)
  from(zipTree(mainShadowJar.get().archiveFile))

  exclude("META-INF/gradle-plugins/de.*")
  exclude("META-INF/versions/9/module-info.class")
  exclude("META-INF/LICENSE")
  exclude("META-INF/LICENSE*")
  exclude("META-INF/NOTICE")
  exclude("META-INF/NOTICE*")
  exclude("META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class") // shadowJar defaults
  exclude("LICENSE*")
  exclude(".*", "*.html", "*.profile", "*.jar", "*.properties", "*.xml", "*.list", "META-INF/eclipse.inf") // eclipse stuff
}

tasks.jar.configure {
  enabled = false
  dependsOn(combinedShadowJar)
}

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
configurations["functionalTestAnnotationProcessor"].extendsFrom(configurations["testAnnotationProcessor"])

// Add a task to run the functional tests
val functionalTest by
    tasks.registering(Test::class) {
      testClassesDirs = functionalTestSourceSet.output.classesDirs
      classpath = functionalTestSourceSet.runtimeClasspath
      useJUnitPlatform()
    }

listOf(configurations.runtimeClasspath, configurations.compileClasspath,
  configurations.testRuntimeClasspath, configurations.testCompileClasspath,
  configurations.named("functionalTestRuntimeClasspath"), configurations.named("functionalTestCompileClasspath"),
).forEach {
  it.configure {
    // Make sure we resolve the jar and not the empty classes of :oldasmwrapper
    attributes.attribute(
      LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
      objects.named(LibraryElements::class, LibraryElements.JAR)
    )
  }
}

gradlePlugin.testSourceSets(functionalTestSourceSet)

tasks.check {
  // Run the functional tests as part of `check`
  dependsOn(functionalTest)
}

tasks.test {
  // Use JUnit Jupiter for unit tests.
  useJUnitPlatform()
}

tasks.named<Jar>("javadocJar").configure { from(fileTree("..").include("docs/*")) }

publishing {
  publications {
    create<MavenPublication>("retrofuturagradle") {
      artifact(combinedShadowJar)
      artifact(tasks.named("sourcesJar"))
      artifact(tasks.named("javadocJar"))
    }
    // From org.gradle.plugin.devel.plugins.MavenPluginPublishPlugin.createMavenMarkerPublication
    for (declaration in gradlePlugin.plugins) {
      create<MavenPublication>(declaration.name + "PluginMarkerMaven") {
        artifactId = declaration.id + ".gradle.plugin"
        groupId = declaration.id
        pom {
          name.set(declaration.displayName)
          description.set(declaration.description)
          withXml {
            val root = asElement()
            val document = root.ownerDocument
            val dependencies = root.appendChild(document.createElement("dependencies"))
            val dependency = dependencies.appendChild(document.createElement("dependency"))
            val groupId = dependency.appendChild(document.createElement("groupId"))
            groupId.textContent = project.group.toString()
            val artifactId = dependency.appendChild(document.createElement("artifactId"))
            artifactId.textContent = project.name
            val version = dependency.appendChild(document.createElement("version"))
            version.textContent = project.version.toString()
          }
        }
      }
    }
  }

  repositories {
    maven {
      url = uri("https://nexus.gtnewhorizons.com/repository/releases/")
      credentials {
        username = System.getenv("MAVEN_USER") ?: "NONE"
        password = System.getenv("MAVEN_PASSWORD") ?: "NONE"
      }
    }
  }
}
