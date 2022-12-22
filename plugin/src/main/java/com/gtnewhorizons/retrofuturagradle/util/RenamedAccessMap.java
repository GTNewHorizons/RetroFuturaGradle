package com.gtnewhorizons.retrofuturagradle.util;

import java.util.Map;
import net.md_5.specialsource.AccessMap;
import org.apache.commons.lang3.StringUtils;

public class RenamedAccessMap extends AccessMap {
    private final Map<String, String> symbolRenameMap;

    public RenamedAccessMap(Map<String, String> symbolRenameMap) {
        this.symbolRenameMap = symbolRenameMap;
    }

    @Override
    public void addAccessChange(String symbolString, String accessString) {
        // eg. "foo/bar ()desc"
        String[] parts = symbolString.split(" ");
        if (parts.length >= 2) {
            int parenIndex = parts[1].indexOf('(');
            String start = parts[1];
            String end = "";

            if (parenIndex != -1) {
                start = parts[1].substring(0, parenIndex);
                end = parts[1].substring(parenIndex);
            }

            parts[1] = symbolRenameMap.getOrDefault(start, start) + end;
        }
        super.addAccessChange(StringUtils.joinWith(".", (Object) parts), accessString);
    }
}
