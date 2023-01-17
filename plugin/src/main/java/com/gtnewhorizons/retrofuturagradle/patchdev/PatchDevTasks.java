package com.gtnewhorizons.retrofuturagradle.patchdev;

import com.gtnewhorizons.retrofuturagradle.RfgPatchdevExtension;
import com.gtnewhorizons.retrofuturagradle.mcp.SharedMCPTasks;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import org.gradle.api.Project;

public class PatchDevTasks extends SharedMCPTasks<RfgPatchdevExtension> {

    public PatchDevTasks(Project project, RfgPatchdevExtension mcExt, MinecraftTasks mcTasks) {
        super(project, mcExt, mcTasks);
    }
}
