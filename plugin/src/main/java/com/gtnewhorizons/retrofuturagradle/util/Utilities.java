package com.gtnewhorizons.retrofuturagradle.util;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;

public final class Utilities {
    public static File getCacheRoot(Project project) {
        return FileUtils.getFile(project.getGradle().getGradleUserHomeDir(), "caches", "retro_futura_gradle");
    }

    public static File getCacheDir(Project project, String... paths) {
        return FileUtils.getFile(getCacheRoot(project), paths);
    }

    public static CSVReader createCsvReader(File file) throws IOException {
        final CSVParser csvParser = new CSVParserBuilder()
                .withEscapeChar(CSVParser.NULL_CHARACTER)
                .withStrictQuotes(false)
                .build();
        return new CSVReaderBuilder(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
                .withSkipLines(1)
                .withCSVParser(csvParser)
                .build();
    }
}
