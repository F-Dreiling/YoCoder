package dev.dreiling.YoCoder.utils;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.*;

public class EnvLoader {

    private static final Map<String, String> values = new HashMap<>();

    static {
        try {
            var input = EnvLoader.class.getResourceAsStream("/.env");

            if (input == null) {
                var localEnv = Path.of(System.getProperty("user.dir"), ".env");
                if (Files.exists(localEnv)) {
                    input = Files.newInputStream(localEnv);
                }
            }

            if (input != null) {
                new java.io.BufferedReader(new java.io.InputStreamReader(input))
                        .lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .forEach(line -> {
                            int eq = line.indexOf('=');
                            if (eq > 0) {
                                String key = line.substring(0, eq).trim();
                                String val = line.substring(eq + 1).trim()
                                        .replaceAll("^\"|\"$", "");
                                values.put(key, val);
                            }
                        });
            }
        } catch (Exception e) {
            System.err.println("Warning: could not read .env file: " + e.getMessage());
        }
    }

    public static String get(String key) {
        return values.getOrDefault(key, System.getenv(key));
    }
}