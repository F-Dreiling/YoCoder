package dev.dreiling.YoCoder.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads file contents from the local filesystem.
 * Used by MainController to load the target file and context files
 * before sending their contents to the backend.
 */
public class FileReader {

    private static final long MAX_FILE_SIZE_BYTES = 500_000; // 500 KB per file

    /**
     * Reads a single file and returns its content as a String.
     */
    public static String read(String projectRoot, String relativePath) throws IOException {
        Path root = Path.of(projectRoot).normalize();
        Path target = safeResolve(root, relativePath);
        if (target == null || !Files.isReadable(target)) {
            throw new IOException("Cannot read file: " + relativePath);
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    /**
     * Reads multiple files and returns a path -> content map.
     * Files that cannot be read or exceed the size limit are silently skipped.
     */
    public static Map<String, String> readAll(String projectRoot, List<String> relativePaths) {
        Path root = Path.of(projectRoot).normalize();
        Map<String, String> result = new LinkedHashMap<>();

        for (String rel : relativePaths) {
            Path target = safeResolve(root, rel);
            if (target == null) continue;
            try {
                long size = Files.size(target);
                if (size > MAX_FILE_SIZE_BYTES) continue; // skip oversized files silently
                result.put(rel, Files.readString(target, StandardCharsets.UTF_8));
            } catch (IOException e) {
                // skip unreadable files
            }
        }

        return result;
    }

    private static Path safeResolve(Path root, String relativePath) {
        try {
            Path resolved = root.resolve(relativePath).normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (Exception e) {
            return null;
        }
    }
}