package com.gtnewhorizons.retrofuturagradle.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A model of the launcher manifest JSON of a Minecraft version
 */
public class LauncherManifest {

    /**
     * Parses the all-versions launcher manifest.
     *
     * @return The URL for the manifest of a specific MC version.
     */
    public static String getVersionManifestUrl(final String allVersionsManifestContents, final String mcVersion) {
        final JsonElement allVersionsRoot = JsonParser.parseString(allVersionsManifestContents);
        final JsonArray allVersionsList = allVersionsRoot.getAsJsonObject().getAsJsonArray("versions");
        final JsonObject matchingVersion = Stream.generate(allVersionsList.iterator()::next)
                .map(JsonElement::getAsJsonObject).filter(entry -> entry.get("id").getAsString().equals(mcVersion))
                .findAny().orElseThrow(
                        () -> new IllegalStateException(
                                "Could not find a minecraft version matching " + mcVersion
                                        + " in the launcher manifest"));
        return matchingVersion.get("url").getAsString();
    }

    private final JsonObject root;
    private final JsonObject assetIndex;

    public LauncherManifest(final String versionManifestContents) {
        root = JsonParser.parseString(versionManifestContents).getAsJsonObject();
        assetIndex = root.getAsJsonObject("assetIndex");
    }

    public LauncherManifest(final File versionManifestLocation) throws IOException {
        this(FileUtils.readFileToString(versionManifestLocation, StandardCharsets.UTF_8));
    }

    public static LauncherManifest read(final File versionManifestLocation) {
        try {
            return new LauncherManifest(versionManifestLocation);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public String getAssetIndexUrl() {
        return assetIndex.get("url").getAsString();
    }

    public String getAssetIndexSha1() {
        return assetIndex.get("sha1").getAsString();
    }

    public String getClientUrl() {
        return root.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
    }

    public String getClientSha1() {
        return root.getAsJsonObject("downloads").getAsJsonObject("client").get("sha1").getAsString();
    }

    public String getServerUrl() {
        return root.getAsJsonObject("downloads").getAsJsonObject("server").get("url").getAsString();
    }

    public String getServerSha1() {
        return root.getAsJsonObject("downloads").getAsJsonObject("server").get("sha1").getAsString();
    }
}
