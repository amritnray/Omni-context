package com.example.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool for reading source code files from the target application.
 * Allows LLM to access and analyze the source code structure and content.
 */
public class SourceCodeReaderTool {

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
        ".java", ".kt", ".groovy", ".scala",
        ".js", ".ts", ".jsx", ".tsx",
        ".py",
        ".cpp", ".h", ".c", ".hpp",
        ".cs",
        ".go",
        ".rs",
        ".php",
        ".rb",
        ".swift", ".m", ".mm"
    );

    private static final Set<String> EXCLUDE_DIRS = Set.of(
        "build", "target", "out", "dist", "node_modules",
        ".git", ".idea", ".vscode", ".gradle", ".mvn",
        "bin", "obj", "coverage", "tmp", ".tmp"
    );

    private final String projectRootPath;

    public SourceCodeReaderTool() {
        // Default to current working directory
        this.projectRootPath = System.getProperty("user.dir");
    }

    public SourceCodeReaderTool(String projectRootPath) {
        this.projectRootPath = projectRootPath;
    }

    /**
     * Lists all source code files in the project, excluding common build and IDE folders.
     */
    public Map<String, Object> listSourceFiles() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> sourceFiles = new ArrayList<>();

        try {
            Path rootPath = Paths.get(projectRootPath);

            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDE_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    String extension = getFileExtension(fileName);

                    if (SOURCE_EXTENSIONS.contains(extension)) {
                        Path relativePath = rootPath.relativize(file);
                        sourceFiles.add(relativePath.toString().replace("\\", "/"));
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            // Sort files for consistent output
            Collections.sort(sourceFiles);

            result.put("success", true);
            result.put("files", sourceFiles);
            result.put("count", sourceFiles.size());

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Reads the content of a specific source file.
     */
    public Map<String, Object> readSourceFile(String filePath) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Security check: ensure the file is within the project root
            Path rootPath = Paths.get(projectRootPath).toAbsolutePath().normalize();
            Path fullPath = rootPath.resolve(filePath).toAbsolutePath().normalize();

            if (!fullPath.startsWith(rootPath)) {
                throw new SecurityException("File access outside project root is not allowed: " + filePath);
            }

            if (!Files.exists(fullPath)) {
                throw new IOException("File not found: " + filePath);
            }

            if (Files.isDirectory(fullPath)) {
                throw new IOException("Path is a directory, not a file: " + filePath);
            }

            String content = Files.readString(fullPath, StandardCharsets.UTF_8);

            result.put("success", true);
            result.put("filePath", filePath);
            result.put("content", content);
            result.put("size", content.length());

        } catch (Exception e) {
            result.put("success", false);
            result.put("filePath", filePath);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Reads multiple source files and returns their content.
     */
    public Map<String, Object> readSourceFiles(List<String> filePaths) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> filesContent = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (String filePath : filePaths) {
            try {
                Map<String, Object> fileResult = readSourceFile(filePath);
                if ((Boolean) fileResult.get("success")) {
                    filesContent.put(filePath, fileResult);
                } else {
                    errors.add(filePath + ": " + fileResult.get("error"));
                }
            } catch (Exception e) {
                errors.add(filePath + ": " + e.getMessage());
            }
        }

        result.put("success", errors.isEmpty());
        result.put("files", filesContent);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        result.put("readCount", filesContent.size());
        result.put("errorCount", errors.size());

        return result;
    }

    /**
     * Searches for files containing specific text.
     */
    public Map<String, Object> searchFiles(String searchText, List<String> fileExtensions) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> matches = new ArrayList<>();

        try {
            Path rootPath = Paths.get(projectRootPath);

            // Default to common source extensions if none provided
            if (fileExtensions == null || fileExtensions.isEmpty()) {
                fileExtensions = Arrays.asList("java", "js", "ts", "py", "cpp", "c", "h", "cs", "go", "rs");
            }

            Set<String> extensions = fileExtensions.stream()
                .map(ext -> ext.startsWith(".") ? ext : "." + ext)
                .collect(Collectors.toSet());

            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDE_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    String extension = getFileExtension(fileName);

                    if (extensions.contains(extension)) {
                        try {
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            if (content.contains(searchText)) {
                                // Get relative path from project root
                                Path relativePath = rootPath.relativize(file);
                                String relativePathStr = relativePath.toString().replace("\\", "/");

                                Map<String, Object> match = new LinkedHashMap<>();
                                match.put("filePath", relativePathStr);
                                match.put("size", content.length());

                                // Find line numbers where the text appears
                                List<Integer> lineNumbers = new ArrayList<>();
                                String[] lines = content.split("\n");
                                for (int i = 0; i < lines.length; i++) {
                                    if (lines[i].contains(searchText)) {
                                        lineNumbers.add(i + 1); // 1-based line numbers
                                    }
                                }
                                match.put("lineNumbers", lineNumbers);

                                matches.add(match);
                            }
                        } catch (IOException e) {
                            // Skip files that can't be read
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }
            });

            result.put("success", true);
            result.put("matches", matches);
            result.put("count", matches.size());

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Gets file extension including the dot (e.g., ".java").
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex).toLowerCase();
        }
        return "";
    }
}