package com.gtnewhorizons.retrofuturagradle.java8;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import org.gradle.workers.WorkAction;

import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.DecompilerContext;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import com.gtnewhorizons.retrofuturagradle.fg23shadow.org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import com.gtnewhorizons.retrofuturagradle.java8.fg23.AdvancedJadRenamer;
import com.gtnewhorizons.retrofuturagradle.java8.fg23.ArtifactSaver;
import com.gtnewhorizons.retrofuturagradle.java8.fg23.ByteCodeProvider;

public abstract class Fg23DecompTask implements WorkAction<Fg23DecompArgs> {

    @Override
    public void execute() {
        try {
            Fg23DecompArgs settings = getParameters();

            Map<String, Object> mapOptions = new HashMap<>();
            // "-din=1", "-rbr=1", "-dgs=1", "-asc=1", "-rsy=1", "-iec=1", "-jvn=1", "-log=TRACE", "-cfg",
            // "{libraries}", "{input}", "{output}"
            mapOptions.put(IFernflowerPreferences.DECOMPILE_INNER, "1");
            mapOptions.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
            mapOptions.put(IFernflowerPreferences.ASCII_STRING_CHARACTERS, "1");
            mapOptions.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "1");
            mapOptions.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
            mapOptions.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
            mapOptions.put(IFernflowerPreferences.LITERALS_AS_IS, "0");
            mapOptions.put(IFernflowerPreferences.UNIT_TEST_MODE, "0");
            mapOptions.put(IFernflowerPreferences.MAX_PROCESSING_METHOD, "0");
            mapOptions.put(DecompilerContext.RENAMER_FACTORY, AdvancedJadRenamer.Factory.class.getName());

            // FernFlowerSettings settings = new FernFlowerSettings(tempDir, in, tempJar,
            // Constants.getTaskLogFile(getProject(), getName() + ".log"), classpath.getFiles(), mapOptions);

            PrintStreamLogger logger = new PrintStreamLogger(new PrintStream(settings.getLogFile().getAsFile().get()));
            BaseDecompiler decompiler = new BaseDecompiler(
                    new ByteCodeProvider(),
                    new ArtifactSaver(settings.getOutputDir().getAsFile().get()),
                    mapOptions,
                    logger);

            decompiler.addSpace(settings.getInputJar().getAsFile().get(), true);
            for (File library : settings.getClasspath()) {
                decompiler.addSpace(library, false);
            }

            decompiler.decompileContext();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
