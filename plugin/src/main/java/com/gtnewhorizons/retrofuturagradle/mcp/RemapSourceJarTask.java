package com.gtnewhorizons.retrofuturagradle.mcp;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.common.base.Strings;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.gtnewhorizons.retrofuturagradle.fgpatchers.JavadocAdder;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import com.opencsv.CSVReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class RemapSourceJarTask extends DefaultTask {

    private static final boolean DEBUG_PRINT_ALL_GENERICS = false;

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBinaryJar();

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
    @Optional
    public abstract Property<String> getGenericFieldsCsvName();

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

    private static final class GenericMapping {
        public final String zipEntry;
        public final String param;
        public final String suffix;
        public final String type;
        public int uses = 0;

        private GenericMapping(String zipEntry, String param, String suffix, String type) {
            this.zipEntry = zipEntry;
            this.param = Objects.requireNonNull(param);
            this.suffix = Objects.requireNonNull(suffix);
            this.type = type;
        }

        @Override
        public String toString() {
            return "GenericMapping{" + "zipEntry='"
                    + zipEntry + '\'' + ", param='"
                    + param + '\'' + ", suffix='"
                    + suffix + '\'' + ", type='"
                    + type + '\'' + '}';
        }
    }

    private static final class GenericPatch {
        public final String zipEntry;
        public final String containsFilter;
        public final String toReplace;
        public final String replaceWith;

        private GenericPatch(String zipEntry, String containsFilter, String toReplace, String replaceWith) {
            this.zipEntry = zipEntry;
            this.containsFilter = containsFilter;
            this.toReplace = toReplace;
            this.replaceWith = replaceWith;
        }

        @Override
        public String toString() {
            return "GenericPatch{" + "zipEntry='"
                    + zipEntry + '\'' + ", containsFilter='"
                    + containsFilter + '\'' + ", toReplace='"
                    + toReplace + '\'' + ", replaceWith='"
                    + replaceWith + '\'' + '}';
        }
    }

    private final Map<String, Mapping> methodMappings = new HashMap<>();
    private final Map<String, Mapping> fieldMappings = new HashMap<>();
    private final Map<String, String> paramMappings = new HashMap<>();
    // srg name -> mapping
    private final ListMultimap<String, GenericMapping> genericMappings =
            MultimapBuilder.hashKeys().arrayListValues().build();
    // zip entry -> patch list
    private final ListMultimap<String, GenericPatch> genericPatches =
            MultimapBuilder.hashKeys().arrayListValues().build();

    // Matches SRG-style names (func_123_g/field_1_p/p_123_1_)
    private static final Pattern SRG_FINDER =
            Pattern.compile("(func_\\d+_[a-zA-Z_]+|field_\\d+_[a-zA-Z_]+|p_\\w+_\\d+_)([^\\w$])");
    private static final Pattern METHOD_DEFINITION =
            Pattern.compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )+([0-9a-zA-Z_]+)\\(");

    private static final Pattern CONSTRUCTOR_DEFINITION =
            Pattern.compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )*([a-zA-Z0-9_]+)\\(");
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
        final Matcher mCtor = CONSTRUCTOR_DEFINITION.matcher("");

        final boolean addJavadocs = getAddJavadocs().get();
        final boolean addDummyJavadocs = getAddDummyJavadocs().get();

        JavaParser javaParser = null;
        PrintWriter genLog = null;
        if (DEBUG_PRINT_ALL_GENERICS) {
            final ParserConfiguration parserCfg = new ParserConfiguration();
            parserCfg.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
            parserCfg.setTabSize(4);
            javaParser = new JavaParser(parserCfg);
            final CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
            combinedTypeSolver.add(new JarTypeSolver(getInputJar().get().getAsFile()));
            combinedTypeSolver.add(new ReflectionTypeSolver(true));
            final File genericLogFile = new File(getTemporaryDir(), "genericlog.csv");
            FileOutputStream fos = FileUtils.openOutputStream(genericLogFile);
            genLog = new PrintWriter(fos);

            final JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
            javaParser.getParserConfiguration().setSymbolResolver(symbolSolver);

            genLog.println("zipEntry,className,srg,mcp,param,type,genericSuffix");
        }

        if (DEBUG_PRINT_ALL_GENERICS) {
            genericMappings.clear();
            genericPatches.clear();
        }

        for (Map.Entry<String, String> srcEntry : loadedSources.entrySet()) {
            final String originalSrc = srcEntry.getValue();
            final String[] originalLines = originalSrc.split("(\r\n)|\n|\r");
            final ArrayList<String> newLines = new ArrayList<>(originalLines.length);

            for (final String originalLine : originalLines) {
                String newLine = originalLine;
                mSrg.reset(originalLine);
                mMethod.reset(originalLine);
                mField.reset(originalLine);
                mCtor.reset(originalLine);
                if (!newLine.trim().startsWith("return ")) {
                    if (mMethod.find()
                            && !Character.isUpperCase(mMethod.group(2).charAt(0))) {
                        final String methodName = mMethod.group(2);
                        final Mapping methodMapping = methodMappings.get(methodName);
                        if ((addJavadocs || addDummyJavadocs)
                                && methodMapping != null
                                && !methodMapping.javadoc.isEmpty()) {
                            addBeforeAnnotations(
                                    newLines,
                                    addDummyJavadocs
                                            ? (mMethod.group(1) + "// JAVADOC METHOD $$ " + methodName)
                                            : JavadocAdder.buildJavadoc(mMethod.group(1), methodMapping.javadoc, true));
                        }
                        final List<GenericMapping> genMaps = genericMappings.get(methodName);
                        for (GenericMapping genMap : genMaps) {
                            if (!genMap.zipEntry.equals(srcEntry.getKey())) {
                                continue;
                            }
                            final String[] typeComps = genMap.type.split("\\.");
                            if (!newLine.contains(typeComps[typeComps.length - 1])) {
                                continue;
                            }
                            genMap.uses++;
                            try {
                                if (genMap.param.equals("@return")) {
                                    final int parenIdx = newLine.indexOf('(');
                                    final int nameIdx =
                                            newLine.substring(0, parenIdx).lastIndexOf(' ');
                                    newLine =
                                            newLine.substring(0, nameIdx) + genMap.suffix + newLine.substring(nameIdx);
                                } else {
                                    final int whichParam = Integer.parseInt(genMap.param);
                                    final int paramsOffset = newLine.indexOf('(');
                                    int paramStart = (whichParam == 0)
                                            ? (paramsOffset + 1)
                                            : (StringUtils.ordinalIndexOf(newLine, ",", whichParam) + 1);
                                    while (Character.isWhitespace(newLine.charAt(paramStart))) {
                                        paramStart++;
                                    }
                                    int paramSplit = newLine.indexOf(' ', paramStart);
                                    while (newLine.substring(0, paramSplit)
                                            .trim()
                                            .endsWith("final")) {
                                        paramSplit = newLine.indexOf(' ', paramSplit + 1);
                                    }
                                    if (paramSplit == -1) {
                                        throw new IllegalStateException(
                                                "Could not find param " + whichParam + " in line: |" + newLine
                                                        + "| file: " + srcEntry.getKey() + ":" + (newLines.size() + 1));
                                    }
                                    newLine = newLine.substring(0, paramSplit)
                                            + genMap.suffix
                                            + newLine.substring(paramSplit);
                                }
                            } catch (Exception e) {
                                throw new IllegalStateException(
                                        "Error applying generic mapping " + genMap + " to line |" + newLine + "| file: "
                                                + srcEntry.getKey() + ":" + (newLines.size() + 1));
                            }
                        }
                    } else if ((addJavadocs || addDummyJavadocs)
                            && originalLine.trim().startsWith("// JAVADOC ")) {
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
                        if ((addJavadocs || addDummyJavadocs)
                                && fieldMapping != null
                                && !fieldMapping.javadoc.isEmpty()) {
                            addBeforeAnnotations(
                                    newLines,
                                    addDummyJavadocs
                                            ? (mField.group(1) + "// JAVADOC FIELD $$ " + fieldName)
                                            : JavadocAdder.buildJavadoc(mField.group(1), fieldMapping.javadoc, false));
                        }
                        final List<GenericMapping> genMaps = genericMappings.get(fieldName);
                        for (GenericMapping genMap : genMaps) {
                            if (!genMap.zipEntry.equals(srcEntry.getKey())) {
                                continue;
                            }
                            genMap.uses++;
                            final int splitIdx = newLine.indexOf(" field_");
                            newLine = newLine.substring(0, splitIdx) + genMap.suffix + newLine.substring(splitIdx);
                        }
                    } else if (mCtor.find()) {
                        final String key = srcEntry.getKey() + "@init:" + extractCtorSig(newLine, newLines.size() + 1);
                        final List<GenericMapping> genMaps = genericMappings.get(key);
                        for (GenericMapping genMap : genMaps) {
                            if (!genMap.zipEntry.equals(srcEntry.getKey())) {
                                continue;
                            }
                            genMap.uses++;
                            final int whichParam = Integer.parseInt(genMap.param);
                            final int paramsOffset = newLine.indexOf('(');
                            int paramStart = (whichParam == 0)
                                    ? (paramsOffset + 1)
                                    : (StringUtils.ordinalIndexOf(newLine, ",", whichParam) + 1);
                            while (Character.isWhitespace(newLine.charAt(paramStart))) {
                                paramStart++;
                            }
                            final int paramSplit = newLine.indexOf(' ', paramStart);
                            if (paramSplit == -1) {
                                throw new IllegalStateException("Could not find param " + whichParam + " in line: |"
                                        + newLine + "| file: " + srcEntry.getKey() + ":" + (newLines.size() + 1));
                            }
                            newLine = newLine.substring(0, paramSplit) + genMap.suffix + newLine.substring(paramSplit);
                        }
                    }
                }
                if (!genericMappings.isEmpty()) {
                    // Extra patches
                    newLine = newLine.replace("(Object)null", "null");
                }
                if (!DEBUG_PRINT_ALL_GENERICS) {
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
                    newLine = mappedLine.toString();

                    final List<GenericPatch> patches = genericPatches.get(srcEntry.getKey());
                    for (GenericPatch patch : patches) {
                        if (newLine.contains(patch.containsFilter)) {
                            newLine = newLine.replace(patch.toReplace, patch.replaceWith);
                        }
                    }
                }
                newLines.add(newLine);
            }

            srcEntry.setValue(StringUtils.join(newLines, System.lineSeparator()));

            if (DEBUG_PRINT_ALL_GENERICS) {
                if (!srcEntry.getKey().startsWith("net/minecraft")
                        && !srcEntry.getKey().startsWith("/net/minecraft")) {
                    continue;
                }

                ParseResult<CompilationUnit> result = javaParser.parse(srcEntry.getValue());
                CompilationUnit cu = result.getResult().orElse(null);
                if (cu == null) {
                    getLogger().error("{}: {}", srcEntry.getKey(), result.toString());
                }
                printRawGenericFile(genLog, srcEntry, newLines, cu);
            }
        }

        int totalGenericsApplied = 0;
        for (Map.Entry<String, GenericMapping> entry : genericMappings.entries()) {
            if (entry.getValue().uses <= 0) {
                getLogger().error("Did not use genmap entry " + entry.getKey() + " : " + entry.getValue());
            }
            totalGenericsApplied += entry.getValue().uses;
        }
        getLogger().lifecycle("Applied {} missing generics", totalGenericsApplied);

        if (genLog != null) {
            genLog.close();
        }

        Utilities.saveMemoryJar(
                loadedResources, loadedSources, getOutputJar().get().getAsFile());
    }

    private static String extractCtorSig(String line, int lineNo) {
        try {
            final int lparen = line.indexOf('(');
            final int rparen = line.indexOf(')');
            final String preParen = line.substring(0, lparen).trim();
            final int ppNameIdx = preParen.lastIndexOf(' ');
            final String cname = preParen.substring(ppNameIdx + 1);
            final String argStr = line.substring(lparen + 1, rparen);
            final String[] argStrs = argStr.split(",");
            return cname + ":"
                    + Arrays.stream(argStrs)
                            .map(String::trim)
                            .map(arg -> arg.substring(0, arg.lastIndexOf(' ')).trim())
                            .collect(Collectors.joining(","));
        } catch (Exception e) {
            return "???" + lineNo;
        }
    }

    private static String extractCtorSig(ConstructorDeclaration ctor, int lineNo) {
        return extractCtorSig(ctor.getDeclarationAsString(true, false, true), lineNo);
    }

    private void printRawGenericFile(
            PrintWriter genLog, Map.Entry<String, String> srcEntry, List<String> srcLines, CompilationUnit cu) {
        cu.accept(
                new ModifierVisitor<Void>() {

                    @Override
                    public Visitable visit(FieldDeclaration decl, Void arg) {
                        if (decl.isPrivate()) {
                            return super.visit(decl, arg);
                        }
                        try {
                            final ResolvedFieldDeclaration rfd = decl.resolve();
                            final ResolvedType rDecl = rfd.getType();
                            for (VariableDeclarator var0 : decl.getVariables()) {
                                final String srgName = var0.getNameAsString();
                                final Mapping map = fieldMappings.get(srgName);
                                analyzeType(
                                        rfd.declaringType(),
                                        rDecl,
                                        srgName,
                                        map == null ? (srgName + "?") : map.name,
                                        "@field");
                            }
                        } catch (Exception e) {
                        }
                        return super.visit(decl, arg);
                    }

                    @Override
                    public Visitable visit(ConstructorDeclaration n, Void arg) {
                        if (n.isPrivate()) {
                            return super.visit(n, arg);
                        }
                        final int lineNumber = n.getName().getBegin().orElse(Position.HOME).line;
                        try {
                            final ResolvedConstructorDeclaration resolved = n.resolve();
                            for (int param = 0; param < resolved.getNumberOfParams(); param++) {
                                analyzeType(
                                        resolved.declaringType(),
                                        resolved.getParam(param).getType(),
                                        "@init:" + extractCtorSig(n, lineNumber),
                                        "@init:" + extractCtorSig(n, lineNumber),
                                        Integer.toString(param));
                            }
                        } catch (Exception e) {
                        }
                        return super.visit(n, arg);
                    }

                    @Override
                    public Visitable visit(MethodDeclaration decl, Void arg) {
                        if (decl.isPrivate()) {
                            return super.visit(decl, arg);
                        }
                        try {
                            final ResolvedMethodDeclaration resolved = decl.resolve();
                            final int declLineIndex = decl.getName().getBegin().orElse(Position.HOME).line;
                            final String srgName = decl.getNameAsString().trim();
                            final Mapping map = methodMappings.get(srgName);
                            final String mcpName = (map == null ? (srgName + "?") : map.name) + ":" + declLineIndex;
                            analyzeType(
                                    resolved.declaringType(),
                                    resolved.getReturnType(),
                                    srgName + ":" + declLineIndex,
                                    mcpName,
                                    "@return");
                            for (int param = 0; param < resolved.getNumberOfParams(); param++) {
                                analyzeType(
                                        resolved.declaringType(),
                                        resolved.getParam(param).getType(),
                                        srgName + ":" + declLineIndex,
                                        mcpName,
                                        Integer.toString(param));
                            }
                        } catch (Exception e) {
                        }
                        return super.visit(decl, arg);
                    }

                    private void analyzeType(
                            ResolvedTypeDeclaration declaringType,
                            ResolvedType resolvedType,
                            String srgName,
                            String mcpName,
                            String paramNr) {
                        if (resolvedType.isReferenceType()) {
                            final ResolvedReferenceType refType = resolvedType.asReferenceType();
                            if (!refType.isRawType()) {
                                return;
                            }
                            genLog.println(StringUtils.join(
                                    new String[] {
                                        srcEntry.getKey(),
                                        declaringType.getQualifiedName(),
                                        '"' + srgName + '"',
                                        '"' + mcpName + '"',
                                        paramNr,
                                        refType.getQualifiedName(),
                                        ""
                                    },
                                    ","));
                        }
                    }
                },
                null);
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
        final String genericsFilename = getGenericFieldsCsvName().getOrNull();
        genericMappings.clear();
        genericPatches.clear();
        if (StringUtils.isNotBlank(genericsFilename)) {
            URL genericsUrl = getClass().getResource(genericsFilename);
            URL genericPatchesUrl = getClass().getResource(genericsFilename.replace("Fields", "Patches"));
            try (CSVReader genReader = Utilities.createCsvReader(genericsUrl)) {
                for (String[] genLine : genReader) {
                    // zipEntry, className, srg, mcp, param, type, suffix, comment
                    String srg = genLine[2];
                    int colon = srg.indexOf(':');
                    if (colon >= 0) {
                        srg = srg.substring(0, colon);
                    }
                    final String zipEntry = genLine[0];
                    final String param = genLine[4];
                    final String type = genLine[5];
                    final String suffix = genLine[6];
                    final String key = srg.equals("@init") ? (zipEntry + genLine[2]) : srg;
                    genericMappings.put(key, new GenericMapping(zipEntry, param, suffix, type));
                }
            }
            try (CSVReader genReader = Utilities.createCsvReader(genericPatchesUrl)) {
                for (String[] genLine : genReader) {
                    // zipEntry, className, containsFilter, toReplace, replaceWith, reason
                    genericPatches.put(genLine[0], new GenericPatch(genLine[0], genLine[2], genLine[3], genLine[4]));
                }
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
