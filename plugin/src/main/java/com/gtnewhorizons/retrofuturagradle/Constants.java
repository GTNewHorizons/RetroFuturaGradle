package com.gtnewhorizons.retrofuturagradle;

import java.util.List;

import com.google.common.collect.ImmutableList;

public final class Constants {

    // URLs
    public static final String URL_MOJANG_LIBRARIES_MAVEN = "https://libraries.minecraft.net";
    public static final String URL_FORGE_MAVEN = "https://maven.minecraftforge.net";
    public static final String URL_SPONGEPOWERED_MAVEN = "https://repo.spongepowered.org/maven/";
    public static final String URL_LAUNCHER_VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    public static final String URL_ASSETS_ROOT = "https://resources.download.minecraft.net/";
    public static final String URL_FERNFLOWER_1 = "https://files.minecraftforge.net/fernflower-fix-1.0.zip";
    public static final String URL_PLAYER_TO_UUID = "https://api.mojang.com/users/profiles/minecraft/";

    // Well-known paths
    public static final String PATH_USERDEV_FML_ACCESS_TRANFORMER = "src/main/resources/fml_at.cfg";
    public static final String PATH_USERDEV_FORGE_ACCESS_TRANFORMER = "src/main/resources/forge_at.cfg";

    // Debug toggles for local development
    public static final boolean DEBUG_NO_TMP_CLEANUP = false;

    public static final List<String> FG23_MERGE_CONFIG = ImmutableList.of(
            "^org/bouncycastle",
            "^org/apache",
            "^com/google",
            "^com/mojang/authlib",
            "^com/mojang/util",
            "^gnu/trove",
            "^io/netty",
            "^javax/annotation",
            "^argo",
            "^it/");
}
