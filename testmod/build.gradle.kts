
plugins {
    id ("com.gtnewhorizons.retrofuturagradle")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

minecraft {
    mcVersion.set("1.7.10")
    applyMcDependencies.set(true)
}
