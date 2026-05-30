package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Robust tool to find and read the project's README.md from the root directory.
 */
public class ReadmeTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadmeTool.class);

    /**
     * Attempts to find and read the README.md content.
     * Searches recursively upwards from the current directory.
     */
    public String getReadmeContent() {
        logger.info("Fetching README.md content...");
        long startTime = System.currentTimeMillis();
        
        File searchDir = new File(".").getAbsoluteFile();
        File readmeFile = findReadme(searchDir);

        if (readmeFile == null || !readmeFile.exists()) {
            logger.warn("README.md not found in project");
            return "README.md not found in the project root directory. Ensure a README.md file exists in your project root.";
        }

        try {
            String content = Files.readString(readmeFile.toPath());
            long duration = System.currentTimeMillis() - startTime;
            logger.info("README.md fetched from {} in {}ms ({} chars)", readmeFile.getAbsolutePath(), duration, content.length());
            return content;
        } catch (IOException e) {
            logger.error("Error reading README.md: {}", e.getMessage());
            return "Error reading README.md: " + e.getMessage();
        }
    }

    private File findReadme(File directory) {
        if (directory == null) {
            return null;
        }

        File possibleReadme = new File(directory, "README.md");
        if (possibleReadme.exists() && possibleReadme.isFile()) {
            return possibleReadme;
        }

        // Try case-insensitive matching
        File[] files = directory.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().equalsIgnoreCase("readme.md") && f.isFile()) {
                    return f;
                }
            }
        }

        // Search in parent directory
        File parent = directory.getParentFile();
        if (parent != null && !parent.equals(directory)) {
            return findReadme(parent);
        }

        return null;
    }
}
