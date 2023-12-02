package com.gtnewhorizons.retrofuturagradle.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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

    public static MessageDigestConsumer addToHash(String value) {
        if (DEBUG_LOG) {
            System.err.println("hash str {" + value + "}");
        }
        return digest -> digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    public static MessageDigestConsumer addToHash(int value) {
        if (DEBUG_LOG) {
            System.err.println("hash int {" + value + "}");
        }
        return digest -> digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    public static MessageDigestConsumer addToHash(long value) {
        if (DEBUG_LOG) {
            System.err.println("hash long {" + value + "}");
        }
        return digest -> digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    public static MessageDigestConsumer addFileContentsToHash(File file) {
        if (DEBUG_LOG) {
            System.err.println("hash file {" + file + "}");
        }
        return digest -> {
            if (!file.exists()) {
                if (DEBUG_LOG) {
                    System.err.println(" = not exists (0)");
                }
                addToHash(0).accept(digest);
                return;
            }
            final File absoluteFile = file.getAbsoluteFile();
            final FileHashCacheEntry cacheEntry = fileHashCache.compute(absoluteFile, (f, fhce) -> {
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
        };
    }

    public static MessageDigestConsumer addDirContentsToHash(File dir) {
        if (DEBUG_LOG) {
            System.err.println("hash dir {" + dir + "}");
        }
        return digest -> {
            if (!dir.exists()) {
                addToHash(0).accept(digest);
                return;
            }
            List<File> files = new ArrayList<>();
            files.addAll(CollectionUtils.collect(FileUtils.iterateFiles(dir, null, true), o -> o));
            files.sort(Comparator.naturalOrder());
            files.forEach(f -> addFileContentsToHash(f).accept(digest));
        };
    }

    public static MessageDigestConsumer addFileCollectionToHash(FileCollection fc) {
        if (DEBUG_LOG) {
            System.err.println("hash fc");
        }
        return digest -> {
            List<File> files = new ArrayList<>();
            files.addAll(fc.getFiles());
            files.sort(Comparator.naturalOrder());
            files.forEach(f -> addFileContentsToHash(f).accept(digest));
        };
    }

    public static MessageDigestConsumer addPropertyToHash(RegularFileProperty prop) {
        if (DEBUG_LOG) {
            System.err.println("hash rfp");
        }
        return digest -> {
            prop.finalizeValue();
            if (prop.isPresent()) {
                addFileContentsToHash(prop.get().getAsFile()).accept(digest);
            } else {
                addToHash(0).accept(digest);
            }
        };
    }

    public static MessageDigestConsumer addPropertyToHash(DirectoryProperty prop) {
        if (DEBUG_LOG) {
            System.err.println("hash dp");
        }
        return digest -> {
            prop.finalizeValue();
            if (prop.isPresent()) {
                addDirContentsToHash(prop.get().getAsFile()).accept(digest);
            } else {
                addToHash(0).accept(digest);
            }
        };
    }

    public static MessageDigestConsumer addPropertyToHash(ConfigurableFileTree prop) {
        if (DEBUG_LOG) {
            System.err.println("hash cft");
        }
        return digest -> {
            if (!prop.isEmpty()) {
                addDirContentsToHash(prop.getDir()).accept(digest);
            } else {
                addToHash(0).accept(digest);
            }
        };
    }

    public static MessageDigestConsumer addPropertyToHash(ConfigurableFileCollection prop) {
        if (DEBUG_LOG) {
            System.err.println("hash cfc");
        }
        return digest -> {
            prop.finalizeValue();
            if (!prop.isEmpty()) {
                addFileCollectionToHash(prop).accept(digest);
            } else {
                addToHash(0).accept(digest);
            }
        };
    }

    public static MessageDigestConsumer addPropertyToHash(Property<?> prop) {
        if (DEBUG_LOG) {
            System.err.println("hash prop " + prop.getOrNull());
        }
        return digest -> {
            prop.finalizeValue();
            if (prop.isPresent()) {
                addToHash(prop.get().toString()).accept(digest);
            } else {
                addToHash(0).accept(digest);
            }
        };
    }

    public static MessageDigestConsumer addPropertyToHash(ListProperty<?> prop) {
        if (DEBUG_LOG) {
            System.err.println("hash list of " + prop.getOrElse(Collections.emptyList()).size());
        }
        return digest -> {
            prop.finalizeValue();
            if (prop.isPresent()) {
                for (Object elem : prop.get()) {
                    addToHash(elem.toString()).accept(digest);
                }
            } else {
                addToHash(0).accept(digest);
            }
        };
    }
}
