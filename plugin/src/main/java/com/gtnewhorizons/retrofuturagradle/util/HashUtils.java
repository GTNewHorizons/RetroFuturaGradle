package com.gtnewhorizons.retrofuturagradle.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

public class HashUtils {

    private HashUtils() {}

    private static class FileHashCacheEntry {

        public long lastModified;
        public byte[] digest;

        public FileHashCacheEntry(long lastModified, byte[] digest) {
            this.lastModified = lastModified;
            this.digest = digest;
        }
    }

    public static final boolean DEBUG_LOG = false;

    private static DigestUtils utils = new DigestUtils(DigestUtils.getSha256Digest());
    private static Map<File, FileHashCacheEntry> fileHashCache = new HashMap<>();

    public static void addToHash(MessageDigest digest, String value) {
        if (DEBUG_LOG) {
            System.err.println("hash str {" + value + "}");
        }
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    public static void addToHash(MessageDigest digest, int value) {
        if (DEBUG_LOG) {
            System.err.println("hash int {" + value + "}");
        }
        digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    public static void addToHash(MessageDigest digest, long value) {
        if (DEBUG_LOG) {
            System.err.println("hash long {" + value + "}");
        }
        digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    public static void addFileContentsToHash(MessageDigest digest, File file) {
        if (DEBUG_LOG) {
            System.err.println("hash file {" + file + "}");
        }
        if (!file.exists()) {
            if (DEBUG_LOG) {
                System.err.println(" = not exists (0)");
            }
            addToHash(digest, 0);
            return;
        }
        file = file.getAbsoluteFile();
        final FileHashCacheEntry cacheEntry = fileHashCache.compute(file, (f, fhce) -> {
            if (fhce == null || fhce.lastModified < f.lastModified()) {
                try {
                    return new FileHashCacheEntry(f.lastModified(), utils.digest(f));
                } catch (IOException e) {
                    throw new RuntimeException("Could not hash file " + f, e);
                }
            } else {
                return fhce;
            }
        });
        digest.update(cacheEntry.digest);
        if (DEBUG_LOG) {
            System.err.println(" = " + Hex.encodeHexString(cacheEntry.digest));
        }
    }

    public static void addDirContentsToHash(MessageDigest digest, File dir) {
        if (DEBUG_LOG) {
            System.err.println("hash dir {" + dir + "}");
        }
        if (!dir.exists()) {
            addToHash(digest, 0);
            return;
        }
        List<File> files = new ArrayList<>();
        files.addAll(CollectionUtils.collect(FileUtils.iterateFiles(dir, null, true), o -> o));
        files.sort(Comparator.naturalOrder());
        files.forEach(f -> addFileContentsToHash(digest, f));
    }

    public static void addFileCollectionToHash(MessageDigest digest, FileCollection fc) {
        if (DEBUG_LOG) {
            System.err.println("hash fc");
        }
        List<File> files = new ArrayList<>();
        files.addAll(fc.getFiles());
        files.sort(Comparator.naturalOrder());
        files.forEach(f -> addFileContentsToHash(digest, f));
    }

    public static void addPropertyToHash(MessageDigest digest, RegularFileProperty prop) {
        if (DEBUG_LOG) {
            System.err.println("hash rfp");
        }
        prop.finalizeValue();
        if (prop.isPresent()) {
            addFileContentsToHash(digest, prop.get().getAsFile());
        } else {
            addToHash(digest, 0);
        }
    }

    public static void addPropertyToHash(MessageDigest digest, DirectoryProperty prop) {
        if (DEBUG_LOG) {
            System.err.println("hash dp");
        }
        prop.finalizeValue();
        if (prop.isPresent()) {
            addDirContentsToHash(digest, prop.get().getAsFile());
        } else {
            addToHash(digest, 0);
        }
    }

    public static void addPropertyToHash(MessageDigest digest, ConfigurableFileTree prop) {
        if (DEBUG_LOG) {
            System.err.println("hash cft");
        }
        if (!prop.isEmpty()) {
            addDirContentsToHash(digest, prop.getDir());
        } else {
            addToHash(digest, 0);
        }
    }

    public static void addPropertyToHash(MessageDigest digest, ConfigurableFileCollection prop) {
        if (DEBUG_LOG) {
            System.err.println("hash cfc");
        }
        prop.finalizeValue();
        if (!prop.isEmpty()) {
            addFileCollectionToHash(digest, prop);
        } else {
            addToHash(digest, 0);
        }
    }

    public static void addPropertyToHash(MessageDigest digest, Property<?> prop) {
        if (DEBUG_LOG) {
            System.err.println("hash prop " + prop.getOrNull());
        }
        prop.finalizeValue();
        if (prop.isPresent()) {
            addToHash(digest, prop.get().toString());
        } else {
            addToHash(digest, 0);
        }
    }

    public static void addPropertyToHash(MessageDigest digest, ListProperty<?> prop) {
        if (DEBUG_LOG) {
            System.err.println("hash list of " + prop.getOrElse(Collections.emptyList()).size());
        }
        prop.finalizeValue();
        if (prop.isPresent()) {
            for (Object elem : prop.get()) {
                addToHash(digest, elem.toString());
            }
        } else {
            addToHash(digest, 0);
        }
    }
}
