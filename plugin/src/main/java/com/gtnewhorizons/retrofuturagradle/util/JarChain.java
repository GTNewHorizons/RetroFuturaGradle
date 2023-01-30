package com.gtnewhorizons.retrofuturagradle.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

import com.gtnewhorizons.retrofuturagradle.Constants;

/**
 * A utility to remove outputs of intermediary tasks if the inputs don't change.
 */
public class JarChain {

    private List<TaskProvider<? extends IJarOutputTask>> taskChain = new ArrayList<>();

    private boolean wasUpToDate = false;
    private long lastUpToDateCheck = -1;

    public JarChain() {
        //
    }

    private TaskProvider<? extends IJarOutputTask> getLastTask() {
        return taskChain.isEmpty() ? null : taskChain.get(taskChain.size() - 1);
    }

    public void addTask(@Nonnull TaskProvider<? extends IJarOutputTask> newTask) {
        taskChain.add(newTask);
        newTask.configure(task -> {
            task.getOutputs().upToDateWhen(ignored -> this.isUpToDate());
            task.onlyIf(ignored -> !this.isUpToDate());
        });
    }

    public void finish() {
        taskChain = Collections.unmodifiableList(taskChain);
        getLastTask().configure(lastTask -> { lastTask.doLast("Jar Chain finalizer", new FinalizerAction()); });
    }

    private class FinalizerAction implements Action<Task> {

        @Override
        public void execute(Task ignored) {
            saveUpToDateDigest();

            for (int i = 0; i < taskChain.size() - 1; i++) {
                IJarOutputTask task = taskChain.get(i).get();
                File outJar = task.getOutputJar().get().getAsFile();
                if (!Constants.DEBUG_NO_TMP_CLEANUP) {
                    FileUtils.deleteQuietly(outJar);
                }
            }
        }
    }

    public boolean isUpToDate() {
        final long now = System.currentTimeMillis();
        if (now - lastUpToDateCheck < 10_000) {
            return wasUpToDate;
        }
        final TaskProvider<? extends IJarOutputTask> lastTask = getLastTask();
        if (lastTask == null) {
            return true;
        }
        final File outputFileLocation = lastTask.get().getOutputJar().getAsFile().get();
        if (!outputFileLocation.isFile()) {
            return false;
        }
        final File inputsKeyFile = new File(outputFileLocation.getPath() + ".inputs.sha256");
        if (!inputsKeyFile.isFile()) {
            return false;
        }
        try {
            final String savedInputsDigest = FileUtils.readFileToString(inputsKeyFile, StandardCharsets.UTF_8).trim();
            final String hexDigest = calculateInputsDigest();

            final boolean isUpToDate = savedInputsDigest.equals(hexDigest);
            lastUpToDateCheck = System.currentTimeMillis();
            wasUpToDate = isUpToDate;

            if (HashUtils.DEBUG_LOG) {
                System.err.println(
                        "Up to date: " + isUpToDate + " ; file,current:\n" + savedInputsDigest + "\n" + hexDigest);
            }

            return isUpToDate;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveUpToDateDigest() {
        wasUpToDate = true;
        lastUpToDateCheck = System.currentTimeMillis();
        final TaskProvider<? extends IJarOutputTask> lastTask = getLastTask();
        if (lastTask == null) {
            return;
        }
        final File outputFileLocation = lastTask.get().getOutputJar().getAsFile().get();
        if (!outputFileLocation.isFile()) {
            return;
        }
        final File inputsKeyFile = new File(outputFileLocation.getPath() + ".inputs.sha256");
        final String hexDigest = calculateInputsDigest();
        try {
            FileUtils.writeStringToFile(inputsKeyFile, hexDigest + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String calculateInputsDigest() {
        if (HashUtils.DEBUG_LOG) {
            System.err.println("*** Recalculating inputs digest");
            new Throwable().printStackTrace(System.err);
        }
        final MessageDigest inputsHasher = DigestUtils.getSha256Digest();
        for (TaskProvider<? extends IJarOutputTask> t : taskChain) {
            if (HashUtils.DEBUG_LOG) {
                System.err.println(" * task hash: " + t.getName());
            }
            t.get().hashInputs(inputsHasher);
        }
        taskChain.forEach(t -> t.get().hashInputs(inputsHasher));
        final byte[] digest = inputsHasher.digest();
        return Hex.encodeHexString(digest).trim();
    }
}
