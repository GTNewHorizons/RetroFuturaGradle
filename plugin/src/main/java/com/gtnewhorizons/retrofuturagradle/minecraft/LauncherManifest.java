package com.gtnewhorizons.retrofuturagradle.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.stream.Stream;

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
                .map(JsonElement::getAsJsonObject)
                .filter(entry -> entry.get("id").getAsString().equals(mcVersion))
                .findAny()
                .orElseThrow(() -> new IllegalStateException("Could not find a minecraft version matching " + mcVersion + " in the launcher manifest"));
        return matchingVersion.get("url").getAsString();
    }
}
