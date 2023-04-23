package com.gtnewhorizons.retrofuturagradle.util;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.gtnewhorizons.retrofuturagradle.fg12shadow.net.md_5.specialsource.AccessMap;

public class RenamedAccessMapFG12 extends AccessMap {

    private final Map<String, String> symbolRenameMap;
    private int renameCount = 0;

    public RenamedAccessMapFG12(Map<String, String> symbolRenameMap) {
        this.symbolRenameMap = symbolRenameMap;
    }

    @Override
    public void addAccessChange(String symbolString, String accessString) {
        // eg. "public net.minecraft.entity.passive.EntityVillager
        // func_146091_a(Lnet/minecraft/village/MerchantRecipeList;Lnet/minecraft/item/Item;Ljava/util/Random;F)V"
        String[] parts = symbolString.split(" ");
        if (parts.length >= 2) {
            int parenIndex = parts[1].indexOf('(');
            String start = parts[1];
            String end = "";

            if (parenIndex != -1) {
                start = parts[1].substring(0, parenIndex);
                end = parts[1].substring(parenIndex);
            }

            String renamed = symbolRenameMap.get(start);
            if (renamed != null) {
                parts[1] = renamed + end;
                renameCount++;
            }
        }
        super.addAccessChange(StringUtils.join(parts, "."), accessString);
    }

    public int getRenameCount() {
        return renameCount;
    }
}
