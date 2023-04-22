package net.minecraftforge.gradle;

import java.lang.reflect.Type;

import com.google.gson.*;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

public class OldPropertyMapSerializer implements JsonSerializer<PropertyMap> {

    @Override
    public JsonElement serialize(PropertyMap src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject out = new JsonObject();
        for (String key : src.keySet()) {
            JsonArray jsa = new JsonArray();
            for (Property p : src.get(key)) {
                jsa.add(new JsonPrimitive(p.getValue()));
            }
            out.add(key, jsa);
        }
        return out;
    }
}
