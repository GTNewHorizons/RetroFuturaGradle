package com.gtnewhorizons.retrofuturagradle.modutils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.gradle.api.DefaultTask;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class UpdateDependenciesTask extends DefaultTask {

    private static final Pattern GTNH_DEPENDENCY = Pattern
            .compile("com\\.github\\.GTNewHorizons:([^:]+):([^:'\"]+)(:[^:'\"]+)?");

    @Inject
    public UpdateDependenciesTask() {
        this.getOutputs().upToDateWhen(Specs.SATISFIES_NONE);
    }

    @TaskAction
    public void updateDependencies() throws IOException {
        Path filePath = Paths.get(getProject().getProjectDir().getPath(), "dependencies.gradle");
        if (!filePath.toFile().isFile()) {
            getLogger().error("File does not exist: " + filePath);
            return;
        }

        boolean updated = false;
        List<String> lines = Files.readAllLines(filePath);
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            Matcher matcher = GTNH_DEPENDENCY.matcher(line);
            if (!matcher.find()) continue;

            String modName = matcher.group(1);
            String currentVersion = matcher.group(2);
            if (modName == null || currentVersion == null) continue;

            List<String> versions = fetchVersions(modName);
            if (versions == null) {
                // Something went wrong while fetch. Assume logging is already done
                continue;
            }
            if (versions.isEmpty()) {
                getLogger().warn(String.format("No releases found on %s", modName));
                continue;
            }
            int currentVersionIndex = -1;
            int latestVersionIndex = -1;
            // Assume last pushed version == latest version. Maybe we can actually parse version string,
            // but for now it works most of the time.
            for (int i = versions.size() - 1; i >= 0; i--) {
                String versionCandidate = versions.get(i);
                if (currentVersionIndex == -1 && versionCandidate.equals(currentVersion)) {
                    currentVersionIndex = i;
                }
                if (latestVersionIndex == -1 && !versionCandidate.endsWith("-pre")) {
                    latestVersionIndex = i;
                }
            }
            if (latestVersionIndex == -1) {
                getLogger().warn(String.format("Releases found on %s do not contain non-pre version", modName));
                continue;
            }
            // currentVersionIndex == -1 can happen when release is removed from maven
            if (latestVersionIndex > currentVersionIndex) {
                String newVersion = versions.get(latestVersionIndex);
                lines.set(lineIndex, line.replace(currentVersion, newVersion));
                getLogger().lifecycle(String.format("Updated %s: %s -> %s", modName, currentVersion, newVersion));
                updated = true;
            }
        }
        if (updated) {
            Files.write(filePath, lines);
        } else {
            getLogger().lifecycle("Dependencies are up-to-date!");
        }
    }

    private List<String> fetchVersions(String modName) {
        // Currently works only with GTNH repositories
        URL url;
        String urlString = String.format(
                "http://jenkins.usrv.eu:8081/nexus/content/groups/public/com/github/GTNewHorizons/%s/maven-metadata.xml",
                modName);
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            getLogger().error("Error generating URL: {}", urlString, e);
            return null;
        }
        URLConnection connection;
        try {
            connection = url.openConnection();
        } catch (IOException e) {
            getLogger().error("Failed to establish connection: {}", urlString, e);
            return null;
        }

        Document document;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            document = builder.parse(connection.getInputStream());
        } catch (Exception e) {
            getLogger().error("Could not fetch version: {}", urlString, e);
            return null;
        }
        NodeList versionElements = document.getElementsByTagName("version");
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < versionElements.getLength(); i++) {
            Node versionElement = versionElements.item(i);
            String version = versionElement.getFirstChild().getNodeValue();
            versions.add(version);
        }
        return versions;
    }
}
