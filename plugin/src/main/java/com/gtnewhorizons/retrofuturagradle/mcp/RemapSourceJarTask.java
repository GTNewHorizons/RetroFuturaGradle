package com.gtnewhorizons.retrofuturagradle.mcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

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
import com.gtnewhorizons.retrofuturagradle.fgpatchers.JavadocAdder;
import com.gtnewhorizons.retrofuturagradle.util.HashUtils;
import com.gtnewhorizons.retrofuturagradle.util.IJarTransformTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;

@CacheableTask
public abstract class RemapSourceJarTask extends DefaultTask implements IJarTransformTask {

    private static final boolean DEBUG_PRINT_ALL_GENERICS = false;

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBinaryJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getFieldCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMethodCsv();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getParamCsv();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    @Optional
    public abstract ConfigurableFileCollection getExtraParamsCsvs();

    @Input
    @Optional
    public abstract Property<String> getGenericFieldsCsvName();

    @Input
    public abstract Property<Boolean> getAddJavadocs();

    @Input
    public abstract Property<Boolean> getAddDummyJavadocs();

    @Override
    public void hashInputs(MessageDigest digest) {
        HashUtils.addPropertyToHash(digest, getFieldCsv());
        HashUtils.addPropertyToHash(digest, getMethodCsv());
        HashUtils.addPropertyToHash(digest, getParamCsv());
        HashUtils.addPropertyToHash(digest, getExtraParamsCsvs());
        HashUtils.addPropertyToHash(digest, getGenericFieldsCsvName());
        if (getGenericFieldsCsvName().isPresent() && !getGenericFieldsCsvName().get().isEmpty()) {
            // [UPDATE] Generics version number
            HashUtils.addToHash(digest, 2);
        }
        HashUtils.addPropertyToHash(digest, getAddJavadocs());
        HashUtils.addPropertyToHash(digest, getAddDummyJavadocs());
    }

    public RemapSourceJarTask() {
        getAddDummyJavadocs().convention(false);
    }

    private final Map<String, byte[]> loadedResources = new HashMap<>();
    private final Map<String, String> loadedSources = new HashMap<>();

    private Utilities.MappingsSet mappings = new Utilities.MappingsSet();

    // Matches SRG-style names (func_123_g/field_1_p/p_123_1_)
    private static final Pattern SRG_FINDER = Pattern
            .compile("(func_\\d+_[a-zA-Z_]+|field_\\d+_[a-zA-Z_]+|p_\\w+_\\d+_)([^\\w$]|$)");
    private static final Pattern METHOD_DEFINITION = Pattern
            .compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )+([0-9a-zA-Z_]+)\\(");

    private static final Pattern CONSTRUCTOR_DEFINITION = Pattern
            .compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )*([a-zA-Z0-9_]+)\\(");
    private static final Pattern FIELD_DEFINITION = Pattern
            .compile("^((?: {4})+|\\t+)(?:[\\w$.\\[\\]]+ )+(field_[0-9]+_[a-zA-Z_]+) *(?:=|;)");

    @TaskAction
    public void remapSources() throws IOException {
        loadedResources.clear();
        loadedSources.clear();
        Utilities.loadMemoryJar(getInputJar().get().getAsFile(), loadedResources, loadedSources);

        mappings = Utilities.loadMappingCsvs(
                getMethodCsv().get().getAsFile(),
                getFieldCsv().get().getAsFile(),
                getParamCsv().getAsFile().getOrNull(),
                getExtraParamsCsvs().getFiles(),
                getGenericFieldsCsvName().getOrNull());

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
            combinedTypeSolver.add(new JarTypeSolver(getBinaryJar().get().getAsFile()));
            int deps = 0;
            for (File depJar : getProject().getConfigurations().getByName(MCPTasks.PATCHED_MINECRAFT_CONFIGURATION_NAME)
                    .resolve()) {
                if (depJar.isFile() && depJar.getName().endsWith(".jar")) {
                    deps++;
                    combinedTypeSolver.add(new JarTypeSolver(depJar));
                }
            }
            getLogger().lifecycle("Injected {} jars into the parser solver", deps + 1);
            combinedTypeSolver.add(new ReflectionTypeSolver(true));
            final File genericLogFile = new File(getTemporaryDir(), "genericlog.csv");
            FileOutputStream fos = FileUtils.openOutputStream(genericLogFile);
            genLog = new PrintWriter(fos);

            final JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
            javaParser.getParserConfiguration().setSymbolResolver(symbolSolver);

            genLog.println("zipEntry,className,srg,mcp,param,type,genericSuffix");
        }

        if (DEBUG_PRINT_ALL_GENERICS) {
            mappings.genericMappings.clear();
            mappings.genericPatches.clear();
        }

        final Set<String> paramsApplied = new HashSet<>(16);
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
                paramsApplied.clear();
                if (!newLine.trim().startsWith("return ")) {
                    if (mMethod.find() && !Character.isUpperCase(mMethod.group(2).charAt(0))) {
                        final String methodName = mMethod.group(2);
                        final Utilities.Mapping methodMapping = mappings.methodMappings.get(methodName);
                        if ((addJavadocs || addDummyJavadocs) && methodMapping != null
                                && !methodMapping.javadoc.isEmpty()) {
                            addBeforeAnnotations(
                                    newLines,
                                    addDummyJavadocs ? (mMethod.group(1) + "// JAVADOC METHOD $$ " + methodName)
                                            : JavadocAdder.buildJavadoc(mMethod.group(1), methodMapping.javadoc, true));
                        }
                        final List<Utilities.GenericMapping> genMaps = mappings.genericMappings.get(methodName);
                        for (Utilities.GenericMapping genMap : genMaps) {
                            if (!genMap.zipEntry.equals(srcEntry.getKey())) {
                                continue;
                            }
                            if (paramsApplied.contains(genMap.param)) {
                                continue;
                            }
                            final String[] typeComps = genMap.type.split("\\.");
                            if (!newLine.contains(typeComps[typeComps.length - 1])) {
                                continue;
                            }
                            genMap.uses++;
                            paramsApplied.add(genMap.param);
                            try {
                                if (genMap.param.equals("@return")) {
                                    final int parenIdx = newLine.indexOf('(');
                                    final int nameIdx = newLine.substring(0, parenIdx).lastIndexOf(' ');
                                    newLine = newLine.substring(0, nameIdx) + genMap.suffix
                                            + newLine.substring(nameIdx);
                                } else {
                                    final int whichParam = Integer.parseInt(genMap.param);
                                    final int paramsOffset = newLine.indexOf('(');
                                    int paramStart = (whichParam == 0) ? (paramsOffset + 1)
                                            : (StringUtils.ordinalIndexOf(newLine, ",", whichParam) + 1);
                                    while (Character.isWhitespace(newLine.charAt(paramStart))) {
                                        paramStart++;
                                    }
                                    int paramSplit = newLine.indexOf(' ', paramStart);
                                    while (newLine.substring(0, paramSplit).trim().endsWith("final")) {
                                        paramSplit = newLine.indexOf(' ', paramSplit + 1);
                                    }
                                    if (paramSplit == -1) {
                                        throw new IllegalStateException(
                                                "Could not find param " + whichParam
                                                        + " in line: |"
                                                        + newLine
                                                        + "| file: "
                                                        + srcEntry.getKey()
                                                        + ":"
                                                        + (newLines.size() + 1));
                                    }
                                    newLine = newLine.substring(0, paramSplit) + genMap.suffix
                                            + newLine.substring(paramSplit);
                                }
                            } catch (Exception e) {
                                throw new IllegalStateException(
                                        "Error applying generic mapping " + genMap
                                                + " to line |"
                                                + newLine
                                                + "| file: "
                                                + srcEntry.getKey()
                                                + ":"
                                                + (newLines.size() + 1));
                            }
                        }
                    } else if ((addJavadocs || addDummyJavadocs) && originalLine.trim().startsWith("// JAVADOC ")) {
                        if (mSrg.find()) {
                            final String indent = originalLine.substring(0, originalLine.indexOf("// JAVADOC"));
                            final String entityName = mSrg.group();
                            if (entityName.startsWith("func_")) {
                                final Utilities.Mapping methodMapping = mappings.methodMappings.get(entityName);
                                if (methodMapping != null && !Strings.isNullOrEmpty(methodMapping.javadoc)) {
                                    newLine = JavadocAdder.buildJavadoc(indent, methodMapping.javadoc, true);
                                }
                            } else if (entityName.startsWith("field_")) {
                                final Utilities.Mapping fieldMapping = mappings.fieldMappings.get(entityName);
                                if (fieldMapping != null && !Strings.isNullOrEmpty(fieldMapping.javadoc)) {
                                    newLine = JavadocAdder.buildJavadoc(indent, fieldMapping.javadoc, true);
                                }
                            }

                            if (newLine.endsWith(System.lineSeparator())) {
                                newLine = newLine.substring(0, newLine.length() - System.lineSeparator().length());
                            }
                        }
                    } else if (mField.find()) {
                        final String fieldName = mField.group(2);
                        final Utilities.Mapping fieldMapping = mappings.fieldMappings.get(fieldName);
                        if ((addJavadocs || addDummyJavadocs) && fieldMapping != null
                                && !fieldMapping.javadoc.isEmpty()) {
                            addBeforeAnnotations(
                                    newLines,
                                    addDummyJavadocs ? (mField.group(1) + "// JAVADOC FIELD $$ " + fieldName)
                                            : JavadocAdder.buildJavadoc(mField.group(1), fieldMapping.javadoc, false));
                        }
                        final List<Utilities.GenericMapping> genMaps = mappings.genericMappings.get(fieldName);
                        for (Utilities.GenericMapping genMap : genMaps) {
                            if (!genMap.zipEntry.equals(srcEntry.getKey())) {
                                continue;
                            }
                            genMap.uses++;
                            final int splitIdx = newLine.indexOf(" field_");
                            newLine = newLine.substring(0, splitIdx) + genMap.suffix + newLine.substring(splitIdx);
                        }
                    } else if (mCtor.find()) {
                        final String key = srcEntry.getKey() + "@init:" + extractCtorSig(newLine, newLines.size() + 1);
                        final List<Utilities.GenericMapping> genMaps = mappings.genericMappings.get(key);
                        for (Utilities.GenericMapping genMap : genMaps) {
                            if (!genMap.zipEntry.equals(srcEntry.getKey())) {
                                continue;
                            }
                            if (paramsApplied.contains(genMap.param)) {
                                continue;
                            }
                            final String[] typeComps = genMap.type.split("\\.");
                            if (!newLine.contains(typeComps[typeComps.length - 1])) {
                                continue;
                            }
                            genMap.uses++;
                            paramsApplied.add(genMap.param);
                            final int whichParam = Integer.parseInt(genMap.param);
                            final int paramsOffset = newLine.indexOf('(');
                            int paramStart = (whichParam == 0) ? (paramsOffset + 1)
                                    : (StringUtils.ordinalIndexOf(newLine, ",", whichParam) + 1);
                            while (Character.isWhitespace(newLine.charAt(paramStart))) {
                                paramStart++;
                            }
                            int paramSplit = newLine.indexOf(' ', paramStart);
                            while (newLine.substring(0, paramSplit).trim().endsWith("final")) {
                                paramSplit = newLine.indexOf(' ', paramSplit + 1);
                            }
                            if (paramSplit == -1) {
                                throw new IllegalStateException(
                                        "Could not find param " + whichParam
                                                + " in line: |"
                                                + newLine
                                                + "| file: "
                                                + srcEntry.getKey()
                                                + ":"
                                                + (newLines.size() + 1));
                            }
                            newLine = newLine.substring(0, paramSplit) + genMap.suffix + newLine.substring(paramSplit);
                        }
                    }
                }
                if (!mappings.genericMappings.isEmpty()) {
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
                            mapped = mappings.paramMappings.getOrDefault(found, found);
                        } else if (found.startsWith("func_")) {
                            final Utilities.Mapping mapping = mappings.methodMappings.get(found);
                            mapped = (mapping != null) ? mapping.name : found;
                        } else if (found.startsWith("field_")) {
                            final Utilities.Mapping mapping = mappings.fieldMappings.get(found);
                            mapped = (mapping != null) ? mapping.name : found;
                        } else {
                            mapped = found;
                        }
                        mSrg.appendReplacement(mappedLine, mapped);
                        mappedLine.append(mSrg.group(2));
                    }
                    mSrg.appendTail(mappedLine);
                    newLine = mappedLine.toString();

                    final List<Utilities.GenericPatch> patches = mappings.genericPatches.get(srcEntry.getKey());
                    for (Utilities.GenericPatch patch : patches) {
                        if (newLine.contains(patch.containsFilter)) {
                            newLine = newLine.replace(patch.toReplace, patch.replaceWith);
                        }
                    }
                }
                newLines.add(newLine);
            }

            srcEntry.setValue(StringUtils.join(newLines, System.lineSeparator()));

            if (DEBUG_PRINT_ALL_GENERICS) {
                if (!srcEntry.getKey().startsWith("net/minecraft") && !srcEntry.getKey().startsWith("/net/minecraft")) {
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
        for (Map.Entry<String, Utilities.GenericMapping> entry : mappings.genericMappings.entries()) {
            totalGenericsApplied += entry.getValue().uses;
        }
        getLogger().lifecycle("Applied {} missing generics", totalGenericsApplied);

        if (genLog != null) {
            genLog.close();
        }

        Utilities.saveMemoryJar(loadedResources, loadedSources, getOutputJar().get().getAsFile(), false);
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
                    + Arrays.stream(argStrs).map(String::trim).map(arg -> arg.substring(0, arg.lastIndexOf(' ')).trim())
                            .collect(Collectors.joining(","));
        } catch (Exception e) {
            return "???" + lineNo;
        }
    }

    private static String extractCtorSig(ConstructorDeclaration ctor, int lineNo) {
        return extractCtorSig(ctor.getDeclarationAsString(true, false, true), lineNo);
    }

    private void printRawGenericFile(PrintWriter genLog, Map.Entry<String, String> srcEntry, List<String> srcLines,
            CompilationUnit cu) {
        cu.accept(new ModifierVisitor<Void>() {

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
                        final Utilities.Mapping map = mappings.fieldMappings.get(srgName);
                        analyzeType(
                                rfd.declaringType(),
                                rDecl,
                                srgName,
                                map == null ? (srgName + "?") : map.name,
                                "@field");
                    }
                } catch (Exception e) {}
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
                } catch (Exception e) {}
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
                    final Utilities.Mapping map = mappings.methodMappings.get(srgName);
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
                } catch (Exception e) {}
                return super.visit(decl, arg);
            }

            private void analyzeType(ResolvedTypeDeclaration declaringType, ResolvedType resolvedType, String srgName,
                    String mcpName, String paramNr) {
                if (resolvedType.isReferenceType()) {
                    final ResolvedReferenceType refType = resolvedType.asReferenceType();
                    if (!refType.isRawType()) {
                        return;
                    }
                    genLog.println(
                            StringUtils.join(
                                    new String[] { srcEntry.getKey(), declaringType.getQualifiedName(),
                                            '"' + srgName + '"', '"' + mcpName + '"', paramNr,
                                            refType.getQualifiedName(), "" },
                                    ","));
                }
            }
        }, null);
    }

    private static void addBeforeAnnotations(ArrayList<String> lineBuffer, String text) {
        int pos = lineBuffer.size() - 1;
        while (pos > 0 && lineBuffer.get(pos).trim().startsWith("@")) {
            pos--;
        }
        lineBuffer.add(pos + 1, text);
    }
}
