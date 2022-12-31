package com.gtnewhorizons.retrofuturagradle.mcp;

import com.google.common.base.Strings;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.JavadocAdder;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.opencsv.CSVReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class RemapSourceJarTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFieldCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMethodCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getParamCsv();

    @Input
    public abstract Property<Boolean> getAddJavadocs();

    @Input
    public abstract Property<Boolean> getAddDummyJavadocs();

    public RemapSourceJarTask() {
        getAddDummyJavadocs().convention(false);
    }

    private final Map<String, byte[]> loadedResources = new HashMap<>();
    private final Map<String, String> loadedSources = new HashMap<>();

    private static final class Mapping {
        public final String name;
        public final String javadoc;

        public Mapping(String name, String javadoc) {
            if (name == null) {
                throw new IllegalArgumentException("Null mapping name passed");
            }
            if (javadoc == null) {
                javadoc = "";
            }
            this.name = name;
            this.javadoc = javadoc;
        }
    }

    private final Map<String, Mapping> methodMappings = new HashMap<>();
    private final Map<String, Mapping> fieldMappings = new HashMap<>();
    private final Map<String, String> paramMappings = new HashMap<>();

    // Matches SRG-style names (func_123_g/field_1_p/p_123_1_)
    private static final Pattern SRG_FINDER =
            Pattern.compile("(func_\\d+_[a-zA-Z_]+|field_\\d+_[a-zA-Z_]+|p_\\w+_\\d+_)([^\\w$])");
    private static final Pattern METHOD_DEFINITION =
            Pattern.compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )+(func_[0-9]+_[a-zA-Z_]+)\\(");
    private static final Pattern FIELD_DEFINITION =
            Pattern.compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )+(field_[0-9]+_[a-zA-Z_]+) *(?:=|;)");

    @TaskAction
    public void remapSources() throws IOException {
        loadedResources.clear();
        loadedSources.clear();
        Utilities.loadMemoryJar(getInputJar().get().getAsFile(), loadedResources, loadedSources);

        loadMappingCsvs();

        final Matcher mSrg = SRG_FINDER.matcher("");
        final Matcher mMethod = METHOD_DEFINITION.matcher("");
        final Matcher mField = FIELD_DEFINITION.matcher("");

        final boolean addJavadocs = getAddJavadocs().get();
        final boolean addDummyJavadocs = getAddDummyJavadocs().get();

        for (Map.Entry<String, String> srcEntry : loadedSources.entrySet()) {
            final String originalSrc = srcEntry.getValue();
            final String[] originalLines = originalSrc.split("(\r\n)|\n|\r");
            final ArrayList<String> newLines = new ArrayList<>(originalLines.length);

            for (final String originalLine : originalLines) {
                String newLine = originalLine;
                mSrg.reset(originalLine);
                if (addJavadocs || addDummyJavadocs) {
                    mMethod.reset(originalLine);
                    mField.reset(originalLine);
                    if (mMethod.find()) {
                        final String methodName = mMethod.group(2);
                        final Mapping methodMapping = methodMappings.get(methodName);
                        if (methodMapping != null && !methodMapping.javadoc.isEmpty()) {
                            addBeforeAnnotations(
                                    newLines,
                                    addDummyJavadocs
                                            ? (mMethod.group(1) + "// JAVADOC METHOD $$ " + methodName)
                                            : JavadocAdder.buildJavadoc(mMethod.group(1), methodMapping.javadoc, true));
                        }
                    } else if (originalLine.trim().startsWith("// JAVADOC ")) {
                        if (mSrg.find()) {
                            final String indent = originalLine.substring(0, originalLine.indexOf("// JAVADOC"));
                            final String entityName = mSrg.group();
                            if (entityName.startsWith("func_")) {
                                final Mapping methodMapping = methodMappings.get(entityName);
                                if (methodMapping != null && !Strings.isNullOrEmpty(methodMapping.javadoc)) {
                                    newLine = JavadocAdder.buildJavadoc(indent, methodMapping.javadoc, true);
                                }
                            } else if (entityName.startsWith("field_")) {
                                final Mapping fieldMapping = fieldMappings.get(entityName);
                                if (fieldMapping != null && !Strings.isNullOrEmpty(fieldMapping.javadoc)) {
                                    newLine = JavadocAdder.buildJavadoc(indent, fieldMapping.javadoc, true);
                                }
                            }

                            if (newLine.endsWith(System.lineSeparator())) {
                                newLine = newLine.substring(
                                        0,
                                        newLine.length()
                                                - System.lineSeparator().length());
                            }
                        }
                    } else if (mField.find()) {
                        final String fieldName = mField.group(2);
                        final Mapping fieldMapping = fieldMappings.get(fieldName);
                        if (fieldMapping != null && !fieldMapping.javadoc.isEmpty()) {
                            addBeforeAnnotations(
                                    newLines,
                                    addDummyJavadocs
                                            ? (mField.group(1) + "// JAVADOC FIELD $$ " + fieldName)
                                            : JavadocAdder.buildJavadoc(mField.group(1), fieldMapping.javadoc, false));
                        }
                    }
                }
                final StringBuffer mappedLine = new StringBuffer();
                mSrg.reset(newLine);
                while (mSrg.find()) {
                    final String found = mSrg.group(1);
                    final String mapped;
                    if (found.startsWith("p_")) {
                        mapped = paramMappings.getOrDefault(found, found);
                    } else if (found.startsWith("func_")) {
                        final Mapping mapping = methodMappings.get(found);
                        mapped = (mapping != null) ? mapping.name : found;
                    } else if (found.startsWith("field_")) {
                        final Mapping mapping = fieldMappings.get(found);
                        mapped = (mapping != null) ? mapping.name : found;
                    } else {
                        mapped = found;
                    }
                    mSrg.appendReplacement(mappedLine, mapped);
                    mappedLine.append(mSrg.group(2));
                }
                mSrg.appendTail(mappedLine);
                newLines.add(mappedLine.toString());
            }

            srcEntry.setValue(StringUtils.join(newLines, System.lineSeparator()));
        }

        Utilities.saveMemoryJar(
                loadedResources, loadedSources, getOutputJar().get().getAsFile());
    }

    private void loadMappingCsvs() throws IOException {
        try (CSVReader methodReader =
                Utilities.createCsvReader(getMethodCsv().get().getAsFile())) {
            methodMappings.clear();
            for (String[] csvLine : methodReader) {
                // func_100012_b,setPotionDurationMax,0,Toggle the isPotionDurationMax field.
                methodMappings.put(csvLine[0], new Mapping(csvLine[1], csvLine[3]));
            }
        }
        try (CSVReader fieldReader =
                Utilities.createCsvReader(getFieldCsv().get().getAsFile())) {
            fieldMappings.clear();
            for (String[] csvLine : fieldReader) {
                // field_100013_f,isPotionDurationMax,0,"True if potion effect duration is at maximum, false otherwise."
                fieldMappings.put(csvLine[0], new Mapping(csvLine[1], csvLine[3]));
            }
        }
        try (CSVReader paramReader =
                Utilities.createCsvReader(getParamCsv().get().getAsFile())) {
            paramMappings.clear();
            for (String[] csvLine : paramReader) {
                // p_104055_1_,force,1
                paramMappings.put(csvLine[0], csvLine[1]);
            }
        }
    }

    private static void addBeforeAnnotations(ArrayList<String> lineBuffer, String text) {
        int pos = lineBuffer.size() - 1;
        while (pos > 0 && lineBuffer.get(pos).trim().startsWith("@")) {
            pos--;
        }
        lineBuffer.add(pos + 1, text);
    }
}
