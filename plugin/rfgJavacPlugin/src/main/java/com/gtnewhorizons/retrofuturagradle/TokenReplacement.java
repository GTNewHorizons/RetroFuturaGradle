package com.gtnewhorizons.retrofuturagradle;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.function.IntConsumer;
import org.apache.commons.lang3.StringUtils;

public class TokenReplacement {
    private final Properties config = new Properties();

    private String[] srcReplacements, dstReplacements;
    private PathMatcher[] filesToReplace;

    public void loadConfig(String inputUrl) {
        final URL url;
        try {
            url = new URL(inputUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Malformed URL for RFG token replacement", e);
        }
        if (!"file".equals(url.getProtocol())) {
            throw new IllegalArgumentException("You can only use 'file' URIs as inputs to RFG token replacement");
        }
        config.clear();
        try (InputStream fis = url.openStream();
                BufferedInputStream bis = new BufferedInputStream(fis)) {
            config.load(bis);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        final ArrayList<String> srcReplacements = new ArrayList<>();
        final ArrayList<String> dstReplacements = new ArrayList<>();
        final ArrayList<PathMatcher> filesToReplace = new ArrayList<>();
        final FileSystem fs = FileSystems.getDefault();
        for (Map.Entry<Object, Object> entry : config.entrySet()) {
            final String key = entry.getKey().toString();
            final String value = entry.getValue().toString();
            final String REPLACE_KEY = "replacements.";
            if (key.startsWith("files.")) {
                filesToReplace.add(fs.getPathMatcher("glob:**/" + value));
            } else if (key.startsWith(REPLACE_KEY)) {
                srcReplacements.add(StringUtils.removeStart(key, REPLACE_KEY));
                dstReplacements.add(value);
            }
        }
        this.srcReplacements = srcReplacements.toArray(new String[0]);
        this.dstReplacements = dstReplacements.toArray(new String[0]);
        this.filesToReplace = filesToReplace.toArray(new PathMatcher[0]);
    }

    public boolean isEmpty() {
        return config.isEmpty() || srcReplacements == null || srcReplacements.length == 0;
    }

    public boolean shouldReplaceInFile(File srcFile) {
        return Arrays.stream(this.filesToReplace).anyMatch(matcher -> matcher.matches(srcFile.toPath()));
    }

    public CharSequence replaceIfNeeded(CharSequence input, IntConsumer consumeTotalReplaced) {
        int totalReplaced = 0;
        if (StringUtils.containsAny(input, srcReplacements)) {
            // count
            for (String pattern : srcReplacements) {
                totalReplaced += StringUtils.countMatches(input, pattern);
            }
            input = StringUtils.replaceEach(input.toString(), srcReplacements, dstReplacements);
        }
        if (consumeTotalReplaced != null) {
            consumeTotalReplaced.accept(totalReplaced);
        }
        return input;
    }
}
