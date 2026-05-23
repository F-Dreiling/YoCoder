package dev.dreiling.YoCoder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class BackendClient {

    private static final String API_HEADER = "X-YoCoder-Key";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public BackendClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Health Check
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<Boolean> checkHealth() {
        return getAsync("/api/health")
                .thenApply(json -> "UP".equals(json.path("status").asText()))
                .exceptionally(e -> false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Project Scanning
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<ScanResult> scanProject(String projectRoot) {
        ObjectNode body = mapper.createObjectNode();
        body.put("projectRoot", projectRoot);

        return postAsync("/api/project/scan", body)
                .thenApply(json -> {
                    if (!json.path("success").asBoolean()) {
                        return new ScanResult(false, json.path("errorMessage").asText(), List.of());
                    }
                    List<String> files = new ArrayList<>();
                    json.path("files").forEach(f -> files.add(f.asText()));
                    return new ScanResult(true, null, files);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  File Reading
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<String> readFile(String projectRoot, String filePath) {
        String url = "/api/project/file?projectRoot="
                + encode(projectRoot) + "&filePath=" + encode(filePath);
        return getAsync(url).thenApply(json -> json.path("content").asText(""));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Streaming Refactor
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<Void> streamRefactor(
            String projectRoot, String targetFile, String prompt,
            String providerOverride, String modelOverride,
            Consumer<String> onChunk, Runnable onDone, Consumer<String> onError) {

        ObjectNode body = mapper.createObjectNode();
        body.put("projectRoot", projectRoot);
        body.put("targetFile", targetFile);
        body.put("prompt", prompt);
        if (providerOverride != null) body.put("providerOverride", providerOverride);
        if (modelOverride != null)    body.put("modelOverride", modelOverride);

        String bodyStr;
        try { bodyStr = mapper.writeValueAsString(body); }
        catch (Exception e) { return CompletableFuture.failedFuture(e); }

        AtomicBoolean doneHandled = new AtomicBoolean(false);
        StringBuilder[] pendingChunk = {new StringBuilder()};

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/refactor/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header(API_HEADER, apiKey)
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        return http.sendAsync(request,
                        HttpResponse.BodyHandlers.fromLineSubscriber(
                                new java.util.concurrent.Flow.Subscriber<>() {
                                    private java.util.concurrent.Flow.Subscription subscription;

                                    public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                        this.subscription = s;
                                        s.request(Long.MAX_VALUE);
                                    }

                                    public void onNext(String line) {
                                        if (line.startsWith("event:")) {
                                            // SSE event type — ignore, we use data content instead
                                        } else if (line.startsWith("data:")) {
                                            String data = line.substring(5).trim();
                                            if (data.equals("[DONE]")) {
                                                if (doneHandled.compareAndSet(false, true)) onDone.run();
                                            } else if (data.startsWith("[ERROR]")) {
                                                onError.accept(data.substring(7).trim());
                                            } else if (!data.isEmpty()) {
                                                // Spring splits multi-line data into multiple data: lines
                                                pendingChunk[0].append(data).append("\n");
                                            }
                                        } else if (line.isEmpty()) {
                                            // Blank line = SSE event boundary
                                            if (pendingChunk[0].length() > 0) {
                                                onChunk.accept(pendingChunk[0].toString());
                                                pendingChunk[0].setLength(0);
                                            }
                                        }
                                    }

                                    public void onError(Throwable t) {
                                        onError.accept("Stream error: " + t.getMessage());
                                    }

                                    public void onComplete() {
                                        // Flush any remaining buffered content
                                        if (pendingChunk[0].length() > 0) {
                                            onChunk.accept(pendingChunk[0].toString());
                                            pendingChunk[0].setLength(0);
                                        }
                                        if (doneHandled.compareAndSet(false, true)) onDone.run();
                                    }
                                }
                        )
                ).thenApply(r -> (Void) null)
                .exceptionally(e -> { onError.accept("Connection error: " + e.getMessage()); return null; });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Save
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<Boolean> saveFile(String projectRoot, String filePath, String content) {
        ObjectNode body = mapper.createObjectNode();
        body.put("projectRoot", projectRoot);
        body.put("filePath", filePath);
        body.put("content", content);

        return postAsync("/api/refactor/save", body)
                .thenApply(json -> json.path("success").asBoolean())
                .exceptionally(e -> false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HTTP Helpers — every request includes the API key header
    // ─────────────────────────────────────────────────────────────────────────

    private CompletableFuture<JsonNode> getAsync(String path) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header(API_HEADER, apiKey)
                .GET()
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    try { return mapper.readTree(r.body()); }
                    catch (Exception e) { throw new RuntimeException("Parse error", e); }
                });
    }

    private CompletableFuture<JsonNode> postAsync(String path, Object bodyObj) {
        String bodyStr;
        try { bodyStr = mapper.writeValueAsString(bodyObj); }
        catch (Exception e) { throw new RuntimeException(e); }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofMinutes(6))
                .header("Content-Type", "application/json")
                .header(API_HEADER, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    try { return mapper.readTree(r.body()); }
                    catch (Exception e) { throw new RuntimeException("Parse error", e); }
                });
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Result types
    // ─────────────────────────────────────────────────────────────────────────

    public record ScanResult(boolean success, String error, List<String> files) {}
}