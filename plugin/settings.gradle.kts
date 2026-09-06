rootProject.name = "retrofuturagradle"

// Add explicit path to libs.versions.toml for Windows users
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

include("oldasmwrapper")

include("rfgJavacPlugin")
