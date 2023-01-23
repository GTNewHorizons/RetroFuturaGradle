package com.gtnewhorizons.retrofuturagradle.minecraft;

import com.google.common.base.Strings;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import cpw.mods.fml.relauncher.Side;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.inject.Inject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Executes code for manual interaction")
public abstract class RunMinecraftTask extends JavaExec {
    public static final UUID DEFAULT_UUID = UUID.nameUUIDFromBytes(new byte[] {'d', 'e', 'v'});

    @Input
    public abstract Property<String> getUsername();

    @Input
    public abstract Property<UUID> getUserUUID();

    @Input
    public abstract Property<String> getAccessToken();

    @Input
    public abstract ListProperty<String> getTweakClasses();

    @Input
    public abstract ListProperty<String> getExtraJvmArgs();

    @Input
    public abstract ListProperty<String> getExtraArgs();

    private final Side side;

    @Inject
    public RunMinecraftTask(Side side) {
        this.side = side;
        getUsername().convention("Developer");
        getUserUUID().convention(DEFAULT_UUID);
        getAccessToken().convention("0");
        getExtraArgs().convention((side == Side.SERVER) ? Collections.singletonList("nogui") : Collections.emptyList());
        getExtraJvmArgs().convention(Collections.emptyList());

        // Forward stdio
        setStandardInput(System.in);
        setStandardOutput(System.out);
        setErrorOutput(System.err);
    }

    /**
     * Run this in the first configuration block of the task, constructors can't register actions so this is necessary
     */
    public void setup(Project project) {
        MinecraftExtension mcExt =
                Objects.requireNonNull(project.getExtensions().getByType(MinecraftExtension.class));
        MinecraftTasks mcTasks = Objects.requireNonNull(project.getExtensions().getByType(MinecraftTasks.class));
        getTweakClasses().convention(mcExt.getExtraTweakClasses());
        setWorkingDir(mcTasks.getRunDirectory());

        systemProperty("fml.ignoreInvalidMinecraftCertificates", true);
        getJavaLauncher().convention(mcExt.getToolchainLauncher());
        if (side == Side.CLIENT) {
            dependsOn(mcTasks.getTaskExtractNatives());
            final String libraryPath = mcTasks.getNativesDirectory().getAbsolutePath()
                    + File.pathSeparator
                    + System.getProperty("java.library.path");
            systemProperty("java.library.path", libraryPath);

            setMinHeapSize("1G");
            setMaxHeapSize("6G");
        } else {
            setMinHeapSize("1G");
            setMaxHeapSize("4G");
        }

        doFirst("setup late-binding arguments", this::setupLateArgs);
    }

    public List<String> calculateArgs(Project project) {
        ArrayList<String> args = new ArrayList<>();
        if (side == Side.CLIENT) {
            MinecraftExtension mcExt =
                    Objects.requireNonNull(project.getExtensions().getByType(MinecraftExtension.class));
            MinecraftTasks mcTasks =
                    Objects.requireNonNull(project.getExtensions().getByType(MinecraftTasks.class));
            args.addAll(Arrays.asList(
                    "--username",
                    getUsername().get(),
                    "--version",
                    mcExt.getMcVersion().get(),
                    "--gameDir",
                    getWorkingDir().getAbsolutePath(),
                    "--assetsDir",
                    mcTasks.getVanillaAssetsLocation().getAbsolutePath(),
                    "--assetIndex",
                    mcExt.getMcVersion().get(),
                    "--uuid",
                    getUserUUID().get().toString(),
                    "--userProperties",
                    "{}",
                    "--accessToken",
                    getAccessToken().get()));
        }
        for (String tweakClass : getTweakClasses().get()) {
            args.add("--tweakClass");
            args.add(tweakClass);
        }
        args.addAll(getExtraArgs().get());
        return args;
    }

    public List<String> calculateJvmArgs(Project project) {
        MinecraftExtension mcExt =
                Objects.requireNonNull(project.getExtensions().getByType(MinecraftExtension.class));
        ArrayList<String> args = new ArrayList<>();
        args.addAll(getExtraJvmArgs().get());
        args.addAll(mcExt.getExtraRunJvmArguments().get());
        return args;
    }

    private void setupLateArgs(Task task) {
        try {
            Project project = getProject();
            FileUtils.forceMkdir(getWorkingDir());
            args(calculateArgs(project));
            jvmArgs(calculateJvmArgs(project));
            if (side == Side.SERVER) {
                final File eula = new File(getWorkingDir(), "eula.txt");
                if (!eula.exists()) {
                    getLogger()
                            .warn(
                                    "Do you accept the minecraft EULA? Say 'y' if you accept the terms at https://account.mojang.com/documents/minecraft_eula");
                    final String userInput;
                    try (InputStreamReader isr = new InputStreamReader(CloseShieldInputStream.wrap(System.in));
                            BufferedReader reader = new BufferedReader(isr)) {
                        userInput = Strings.nullToEmpty(reader.readLine()).trim();
                    }
                    if (userInput.startsWith("y") || userInput.startsWith("Y")) {
                        FileUtils.write(eula, "eula=true", StandardCharsets.UTF_8);
                    } else {
                        getLogger().error("EULA not accepted!");
                        throw new RuntimeException("Minecraft EULA not accepted.");
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
