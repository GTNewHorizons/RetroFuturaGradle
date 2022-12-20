package com.gtnewhorizons.retrofuturagradle.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AssetManifest {
    private final JsonObject root;

    public AssetManifest(final String assetManifestContents) {
        root = JsonParser.parseString(assetManifestContents).getAsJsonObject();
    }
}
