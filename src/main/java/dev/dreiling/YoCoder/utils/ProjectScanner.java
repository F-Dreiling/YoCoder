package dev.dreiling.YoCoder.utils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Scans a project directory locally and returns relative file paths.
 * Mirrors the filtering logic previously handled by the backend FileService.
 */
public class ProjectScanner {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "vendor", "target", "build", "dist", "out",
            ".git", ".idea", ".vscode", "__pycache__", ".gradle", "coverage"
    );

    private static final Set<String> INCLUDED_EXTENSIONS = Set.of(
            ".java", ".php", ".js", ".jsx", ".ts", ".tsx", ".vue",
            ".py", ".go", ".kt", ".rb", ".rs", ".cs",
            ".html", ".css", ".scss", ".xml", ".json",
            ".yaml", ".yml", ".sql", ".md", ".env"
    );

    /**
     * Scans the given root directory and returns sorted relative file paths.
     * Returns an error message string on failure (avoids checked exceptions
     * crossing the CompletableFuture boundary cleanly via ScanResult).
     */
    public static ScanResult scan(String projectRootPath) {
        Path root;
        try {
            root = Path.of(projectRootPath).toRealPath();
        } catch (IOException e) {
            return ScanResult.failure("Cannot access path: " + projectRootPath + " — " + e.getMessage());
        }

        if (!Files.isDirectory(root)) {
            return ScanResult.failure("Path is not a directory: " + projectRootPath);
        }

        List<String> relPaths = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String dirName = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String ext = getExtension(file.getFileName().toString());
                    if (INCLUDED_EXTENSIONS.contains(ext)) {
                        relPaths.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ScanResult.failure("Error scanning project: " + e.getMessage());
        }

        Collections.sort(relPaths);
        return ScanResult.success(relPaths);
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public static class ScanResult {
        private final List<String> files;
        private final String error;

        private ScanResult(List<String> files, String error) {
            this.files = files;
            this.error = error;
        }

        public static ScanResult success(List<String> files) { return new ScanResult(files, null); }
        public static ScanResult failure(String error)       { return new ScanResult(null, error); }

        public boolean isSuccess()    { return error == null; }
        public List<String> files()   { return files; }
        public String error()         { return error; }
    }
}