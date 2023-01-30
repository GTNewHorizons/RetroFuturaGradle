package com.gtnewhorizons.retrofuturagradle.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import com.google.common.base.Joiner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.util.patching.ContextualPatch;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;

public final class Utilities {

    public static final Gson GSON;

    static {
        GsonBuilder builder = new GsonBuilder();
        builder.enableComplexMapKeySerialization();
        builder.setPrettyPrinting();
        GSON = builder.create();
    }

    public static File getRawCacheRoot(Project project) {
        return FileUtils.getFile(project.getGradle().getGradleUserHomeDir(), "caches");
    }

    public static File getCacheRoot(Project project) {
        return FileUtils.getFile(getRawCacheRoot(project), "retro_futura_gradle");
    }

    public static File getRawCacheDir(Project project, String... paths) {
        return FileUtils.getFile(getRawCacheRoot(project), paths);
    }

    public static File getCacheDir(Project project, String... paths) {
        return FileUtils.getFile(getCacheRoot(project), paths);
    }

    public static CSVReader createCsvReader(URL url) throws IOException {
        final CSVParser csvParser = new CSVParserBuilder().withEscapeChar(CSVParser.NULL_CHARACTER)
                .withStrictQuotes(false).build();
        final String content = IOUtils.toString(url, StandardCharsets.UTF_8);
        return new CSVReaderBuilder(new StringReader(content)).withSkipLines(1).withCSVParser(csvParser).build();
    }

    public static CSVReader createCsvReader(File file) throws IOException {
        final CSVParser csvParser = new CSVParserBuilder().withEscapeChar(CSVParser.NULL_CHARACTER)
                .withStrictQuotes(false).build();
        return new CSVReaderBuilder(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)).withSkipLines(1)
                .withCSVParser(csvParser).build();
    }

    public static byte[] readZipEntry(ZipFile jar, ZipEntry entry) throws IOException {
        try (InputStream zis = jar.getInputStream(entry)) {
            return IOUtils.toByteArray(zis);
        }
    }

    public static byte[] getClassBytes(Class<?> klass) {
        final String resourcePath = String.format("/%s.class", klass.getName().replace('.', '/'));
        try (InputStream cis = klass.getResourceAsStream(resourcePath)) {
            return IOUtils.toByteArray(cis);
        } catch (IOException exc) {
            throw new RuntimeException(
                    "IO Exception caught when trying to get the class bytes for " + klass.getName(),
                    exc);
        }
    }

    public static ClassNode parseClassBytes(byte[] bytes, String debugName) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            return classNode;
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse bytes of class " + debugName, e);
        }
    }

    public static byte[] emitClassBytes(ClassNode node, int writerFlags) {
        ClassWriter writer = new ClassWriter(writerFlags);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static String readEmbeddedResourceText(String path) {
        ClassLoader loader = Utilities.class.getClassLoader();
        assert loader != null;
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new FileNotFoundException("Resource not found: " + path);
            }
            return IOUtils.toString(stream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't read resource " + path, e);
        }
    }

    /**
     * Load a JAR file into in-memory hashmaps
     * 
     * @param jar             The JAR to load
     * @param loadedResources The map to populate with non-java file contents
     * @param loadedSources   The map to populate with java file contents
     * @throws IOException Forwarded IO errors from the JAR reading process
     */
    public static void loadMemoryJar(File jar, Map<String, byte[]> loadedResources, Map<String, String> loadedSources)
            throws IOException {
        try (final FileInputStream fis = new FileInputStream(jar);
                final BufferedInputStream bis = new BufferedInputStream(fis);
                final ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry entry = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().contains("META-INF")) {
                    continue;
                }
                if (entry.isDirectory() || !entry.getName().endsWith(".java")) {
                    loadedResources.put(entry.getName(), IOUtils.toByteArray(zis));
                } else {
                    final String src = IOUtils.toString(zis, StandardCharsets.UTF_8);
                    loadedSources.put(entry.getName(), src);
                }
            }
        }
    }

    public static File saveMemoryJar(Map<String, byte[]> loadedResources, Map<String, String> loadedSources,
            File target, boolean isTemporary) throws IOException {
        if (isTemporary && !Constants.DEBUG_NO_TMP_CLEANUP) {
            return null;
        }
        try (FileOutputStream fos = new FileOutputStream(target);
                BufferedOutputStream bos = new BufferedOutputStream(fos);
                ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> resource : loadedResources.entrySet()) {
                zos.putNextEntry(new ZipEntry(resource.getKey()));
                zos.write(resource.getValue());
                zos.closeEntry();
            }
            for (Map.Entry<String, String> srcFile : loadedSources.entrySet()) {
                zos.putNextEntry(new ZipEntry(srcFile.getKey()));
                zos.write(srcFile.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return target;
    }

    public static URL[] filesToURLArray(Collection<File> cpFiles) throws MalformedURLException {
        URL[] urls = new URL[cpFiles.size()];
        int idx = 0;
        for (File f : cpFiles) {
            urls[idx] = f.toURI().toURL();
            idx++;
        }
        if (idx != urls.length) {
            throw new IllegalStateException("Mismatched collection size on iteration and size() call");
        }
        return urls;
    }

    /**
     * Provides patching context from an in-memory jar
     */
    public static class InMemoryJarContextProvider implements ContextualPatch.IContextProvider {

        private Map<String, String> fileMap;

        private final int stripFrontComponents;

        public InMemoryJarContextProvider(Map<String, String> fileMap, int stripFrontComponents) {
            this.fileMap = fileMap;
            this.stripFrontComponents = stripFrontComponents;
        }

        public String strip(String target) {
            target = target.replace('\\', '/');
            int index = 0;
            for (int x = 0; x < stripFrontComponents; x++) {
                index = target.indexOf('/', index) + 1;
            }
            return target.substring(index);
        }

        @Override
        public List<String> getData(String target) {
            target = strip(target);

            if (fileMap.containsKey(target)) {
                String[] lines = fileMap.get(target).split("\r\n|\r|\n");
                return new ArrayList<>(Arrays.asList(lines));
            }

            return null;
        }

        @Override
        public void setData(String target, List<String> data) {
            fileMap.put(strip(target), Joiner.on(System.lineSeparator()).join(data));
        }
    }
}
