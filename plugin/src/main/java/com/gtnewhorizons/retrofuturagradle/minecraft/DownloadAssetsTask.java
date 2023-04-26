package com.gtnewhorizons.retrofuturagradle.minecraft;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import com.gtnewhorizons.retrofuturagradle.Constants;

/**
 * Downloads vanilla game assets based on a JSON manifest and puts them in a readable location.
 */
public abstract class DownloadAssetsTask extends DefaultTask {

    /**
     * Asset download root path
     */
    @OutputDirectory
    public abstract RegularFileProperty getObjectsDir();

    /**
     * A parset asset manifest JSON object.
     */
    @InputFile
    public abstract RegularFileProperty getManifest();

    @Inject
    public abstract WorkerExecutor getWorkerExecutor();

    public static final AtomicInteger toDownload = new AtomicInteger(), downloaded = new AtomicInteger();

    @TaskAction
    public void downloadAssets() {
        final File objectsDir = getObjectsDir().get().getAsFile();
        final AssetManifest manifest = AssetManifest.read(getManifest().get().getAsFile());

        if (!objectsDir.exists()) {
            objectsDir.mkdirs();
        }
        for (int i = 0x00; i <= 0xFF; i++) {
            final File objDir = new File(objectsDir, String.format("%02x", i));
            if (!objDir.exists()) {
                objDir.mkdir();
            }
        }

        WorkerExecutor executor = getWorkerExecutor();
        WorkQueue queue = executor.noIsolation();
        List<AssetManifest.Asset> assets = manifest.getAssets();
        toDownload.set(assets.size());
        downloaded.set(0);
        for (AssetManifest.Asset asset : assets) {
            queue.submit(AssetAction.class, action -> {
                try {
                    action.getSourceUrl().set(new URL(Constants.URL_ASSETS_ROOT + asset.path));
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
                action.getSha1().set(asset.hash);
                action.getTargetFile().set(asset.getObjectPath(objectsDir));
            });
        }
    }

    public interface AssetParameters extends WorkParameters {

        Property<URL> getSourceUrl();

        Property<String> getSha1();

        Property<File> getTargetFile();
    }

    public abstract static class AssetAction implements WorkAction<AssetParameters> {

        @Inject
        public AssetAction() {}

        @Override
        public void execute() {
            AssetParameters params = getParameters();
            if (params.getTargetFile().get().exists()) {
                downloaded.incrementAndGet();
                return;
            }

            for (int retry = 0; retry < 5; retry++) {
                try (BufferedInputStream bis = new BufferedInputStream(params.getSourceUrl().get().openStream());
                        FileOutputStream fos = new FileOutputStream(params.getTargetFile().get());
                        BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                    IOUtils.copy(bis, bos);
                    bos.flush();

                    final String realSha1;
                    try {
                        realSha1 = new DigestUtils(DigestUtils.getSha1Digest())
                                .digestAsHex(params.getTargetFile().get());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (!realSha1.equals(params.getSha1().get())) {
                        bos.close();
                        fos.close();
                        FileUtils.deleteQuietly(params.getTargetFile().get());
                        throw new RuntimeException(
                                String.format(
                                        "Asset %s sha1sum doesn't match! Downloaded: %s Expected: %s",
                                        params.getTargetFile().get().getAbsolutePath(),
                                        realSha1,
                                        params.getSha1().get()));
                    }

                    System.out.printf(
                            "Downloaded asset (%3d/%3d) %s\n",
                            downloaded.incrementAndGet(),
                            toDownload.get(),
                            params.getSha1().get());
                    break;
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                    e.printStackTrace();
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // no-op
                }
            }
        }
    }
}
