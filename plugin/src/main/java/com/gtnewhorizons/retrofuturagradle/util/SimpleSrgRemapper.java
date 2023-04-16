package com.gtnewhorizons.retrofuturagradle.util;

import java.util.Map;

import org.objectweb.asm.commons.Remapper;

/**
 * A {@link Remapper} using a {@link Map} to define its mapping, using simple SRG-style mappings.
 */
public class SimpleSrgRemapper extends Remapper {

    private final Map<String, String> mapping;

    public SimpleSrgRemapper(final Map<String, String> mapping) {
        this.mapping = mapping;
    }

    @Override
    public String mapMethodName(final String owner, final String name, final String descriptor) {
        String remappedName = map(name);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapInvokeDynamicMethodName(final String name, final String descriptor) {
        String remappedName = map(name);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapAnnotationAttributeName(final String descriptor, final String name) {
        String remappedName = map(name);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapFieldName(final String owner, final String name, final String descriptor) {
        String remappedName = map(name);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String map(final String key) {
        return mapping.get(key);
    }
}
