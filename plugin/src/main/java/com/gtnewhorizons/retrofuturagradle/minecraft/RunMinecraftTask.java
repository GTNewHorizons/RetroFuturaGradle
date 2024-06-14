package com.gtnewhorizons.retrofuturagradle.minecraft;

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
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

import com.google.common.base.Strings;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.util.Distribution;
import com.gtnewhorizons.retrofuturagradle.util.ProviderToStringWrapper;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

@DisableCachingByDefault(because = "Executes code for manual interaction")
public abstract class RunMinecraftTask extends JavaExec {

    public static final UUID DEFAULT_UUID = UUID.nameUUIDFromBytes(new byte[] { 'd', 'e', 'v' });

    @Input
    @Option(option = "username", description = "Minecraft Username to use")
    public abstract Property<String> getUsername();

    @Input
    @Option(option = "uuid", description = "Minecraft user UUID to use")
    public abstract Property<String> getUserUUID();

    @Input
    public abstract Property<String> getAccessToken();

    @Input
    public abstract ListProperty<String> getTweakClasses();

    @Input
    public abstract ListProperty<String> getExtraJvmArgs();

    @Input
    public abstract ListProperty<String> getExtraArgs();

    @Input
    @Optional
    @Option(option = "mcArgs", description = "Arguments to pass to Minecraft on startup")
    public abstract ListProperty<String> getCmdlineArgs();

    @Input
    @Optional
    @Option(option = "mcJvmArgs", description = "Arguments to pass to Minecraft's JVM on startup")
    public abstract ListProperty<String> getCmdlineJvmArgs();

    @Input
    public abstract Property<Integer> getLwjglVersion();

    @Internal
    public abstract DirectoryProperty getAssetsDirectory();

    @Input
    public abstract Property<String> getMcVersion();

    @Input
    public abstract ListProperty<String> getMcExtExtraRunJvmArguments();

    private final Distribution side;

    @Inject
    public RunMinecraftTask(Distribution side, Gradle gradle) {
        this.side = side;
        getUsername().convention("Developer");
        getUserUUID().convention(getUsername().map(name -> Utilities.resolveUUID(name, gradle).toString()));
        getAccessToken().convention("0");
        getExtraArgs().convention(
                (side == Distribution.DEDICATED_SERVER) ? Collections.singletonList("nogui") : Collections.emptyList());
        getExtraJvmArgs().convention(Collections.emptyList());
        getLwjglVersion().convention(2);

        // Forward stdio
        setStandardInput(System.in);
        setStandardOutput(System.out);
        setErrorOutput(System.err);
    }

    /**
     * Run this in the first configuration block of the task, constructors can't register actions so this is necessary
     */
    public void setup(Project project) {
        MinecraftExtension mcExt = Objects.requireNonNull(project.getExtensions().getByType(MinecraftExtension.class));
        MinecraftTasks mcTasks = Objects.requireNonNull(project.getExtensions().getByType(MinecraftTasks.class));
        getTweakClasses().convention(mcExt.getExtraTweakClasses());
        setWorkingDir(mcTasks.getRunDirectory());
        getLwjglVersion().convention(mcExt.getMainLwjglVersion());
        getAssetsDirectory().set(mcTasks.getVanillaAssetsLocation());
        getMcVersion().set(mcExt.getMcVersion());
        getMcExtExtraRunJvmArguments().set(mcExt.getExtraRunJvmArguments());

        systemProperty("fml.ignoreInvalidMinecraftCertificates", true);
        getJavaLauncher().convention(mcExt.getToolchainLauncher(project));
        if (side == Distribution.CLIENT) {
            dependsOn(mcTasks.getTaskExtractNatives(getLwjglVersion()));

            final String JAVA_LIB_PATH = "java.library.path";
            final Provider<String> libraryPath = getLwjglVersion().map(ver -> {
                if (ver == 2) {
                    return mcTasks.getLwjgl2NativesDirectory();
                } else if (ver == 3) {
                    return mcTasks.getLwjgl3NativesDirectory();
                } else {
                    throw new IllegalArgumentException("Lwjgl major version " + ver + " not supported");
                }
            }).map(
                    lwjglNatives -> Utilities.fixWindowsProcessCmdline(
                            lwjglNatives.getAbsolutePath() + File.pathSeparator + System.getProperty(JAVA_LIB_PATH)));
            systemProperty(JAVA_LIB_PATH, new ProviderToStringWrapper(libraryPath));

            classpath(mcTasks.getLwjglConfiguration(getLwjglVersion()));

            setMinHeapSize("1G");
            setMaxHeapSize("6G");
        } else {
            setMinHeapSize("1G");
            setMaxHeapSize("4G");
        }

        doFirst("setup late-binding arguments", this::setupLateArgs);
    }

    public List<String> calculateArgs() {
        ArrayList<String> args = new ArrayList<>();
        if (side == Distribution.CLIENT) {
            final String mcVer = getMcVersion().get();
            final String argVer = switch (mcVer) {
                case "1.12.2" -> "FML_DEV";
                default -> mcVer;
            };
            args.addAll(
                    Arrays.asList(
                            "--username",
                            getUsername().get(),
                            "--version",
                            argVer,
                            "--gameDir",
                            getWorkingDir().getAbsolutePath(),
                            "--assetsDir",
                            getAssetsDirectory().get().getAsFile().getAbsolutePath(),
                            "--assetIndex",
                            getMcVersion().get(),
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
        if (getCmdlineArgs().isPresent()) {
            args.addAll(getCmdlineArgs().get());
        }
        return args;
    }

    public List<String> calculateJvmArgs() {
        ArrayList<String> args = new ArrayList<>();
        args.addAll(getExtraJvmArgs().get());
        args.addAll(getMcExtExtraRunJvmArguments().get());
        if (getCmdlineJvmArgs().isPresent()) {
            args.addAll(getCmdlineJvmArgs().get());
        }
        return args;
    }

    private void setupLateArgs(Task task) {
        try {
            FileUtils.forceMkdir(getWorkingDir());
            // make args modifiable again if reloaded from configuration cache
            if (getArgs() != null) {
                setArgs(new ArrayList<>(getArgs()));
            }
            if (getJvmArgs() != null) {
                setJvmArgs(new ArrayList<>(getJvmArgs()));
            }
            args(calculateArgs());
            jvmArgs(calculateJvmArgs());
            if (side == Distribution.DEDICATED_SERVER) {
                final File properties = new File(getWorkingDir(), "server.properties");
                if (!properties.exists()) {
                    final String data = "online-mode="
                            + consolePrompt("Do you want to start the server in online-mode? Type 'y' to do so");
                    FileUtils.write(properties, data, StandardCharsets.UTF_8);
                }

                final File eula = new File(getWorkingDir(), "eula.txt");
                if (!eula.exists()) {
                    if (consolePrompt(
                            "Do you accept the minecraft EULA? Type 'y' if you accept the terms at https://account.mojang.com/documents/minecraft_eula")) {
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

    private boolean consolePrompt(String message) throws IOException {
        getLogger().warn(message);
        final String userInput;
        try (InputStreamReader isr = new InputStreamReader(CloseShieldInputStream.wrap(System.in));
                BufferedReader reader = new BufferedReader(isr)) {
            userInput = Strings.nullToEmpty(reader.readLine()).trim();
            return userInput.startsWith("y") || userInput.startsWith("Y");
        }
    }

}
