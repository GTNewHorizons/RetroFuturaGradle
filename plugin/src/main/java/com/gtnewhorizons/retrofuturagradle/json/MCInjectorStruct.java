package com.gtnewhorizons.retrofuturagradle.json;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

public class MCInjectorStruct {

    public EnclosingMethod enclosingMethod = null;
    public ArrayList<InnerClass> innerClasses = null;

    public static class EnclosingMethod {

        public final String desc;
        public final String name;
        public final String owner;

        EnclosingMethod(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }
    }

    public static class InnerClass {

        public String access;
        public final String inner_class;
        public final String inner_name;
        public final String outer_class;
        public final String start;

        InnerClass(String inner_class, String outer_class, String inner_name, String access, String start) {
            this.inner_class = inner_class;
            this.outer_class = outer_class;
            this.inner_name = inner_name;
            this.access = access;
            this.start = start;
        }

        public int getAccess() {
            return access == null ? 0 : Integer.parseInt(access, 16);
        }

        public int getStart() {
            return start == null ? 0 : Integer.parseInt(start, 10);
        }
    }

    public static Map<String, MCInjectorStruct> loadMCIJson(File jsonFile) throws IOException {
        Map<String, MCInjectorStruct> ret = new HashMap<>();
        JsonObject root = JsonParser.parseString(FileUtils.readFileToString(jsonFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            ret.put(entry.getKey(), Utilities.GSON.fromJson(entry.getValue(), MCInjectorStruct.class));
        }
        return ret;
    }
}
