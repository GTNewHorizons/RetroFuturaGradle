package com.gtnewhorizons.retrofuturagradle.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AssetManifest {

    private final JsonObject root;

    public AssetManifest(final String assetManifestContents) {
        root = JsonParser.parseString(assetManifestContents).getAsJsonObject().getAsJsonObject("objects");
    }

    public AssetManifest(final File assetManifestLocation) throws IOException {
        this(FileUtils.readFileToString(assetManifestLocation, StandardCharsets.UTF_8));
    }

    public static AssetManifest read(final File assetManifestLocation) {
        try {
            return new AssetManifest(assetManifestLocation);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<Asset> getAssets() {
        ArrayList<Asset> assets = new ArrayList<>(root.size());
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            final String path = entry.getKey();
            final JsonObject jobj = entry.getValue().getAsJsonObject();
            final long size = jobj.get("size").getAsLong();
            final String hash = jobj.get("hash").getAsString();
            assets.add(new Asset(path, size, hash));
        }
        return assets;
    }

    public static class Asset {

        public final String realName;
        public final long size;
        public final String hash;
        public final String path;

        public Asset(String realName, long size, String hash) {
            this.realName = realName;
            this.size = size;
            this.hash = hash;
            this.path = hash.substring(0, 2) + "/" + hash;
        }

        public File getObjectPath(File objectsDir) {
            return new File(objectsDir, path);
        }
    }
}
