package dev.dreiling.YoCoder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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
    //  Streaming Refactor
    //  File contents are read locally and sent in the request body.
    // ─────────────────────────────────────────────────────────────────────────

    public CompletableFuture<Void> streamRefactor(
            String targetFilePath,
            String targetFileContent,
            Map<String, String> contextFileContents, // path -> content, may be empty
            String prompt,
            String providerOverride,
            String modelOverride,
            Consumer<String> onChunk,
            Runnable onDone,
            Consumer<String> onError) {

        ObjectNode body = mapper.createObjectNode();
        body.put("targetFilePath", targetFilePath);
        body.put("targetFileContent", targetFileContent);
        body.put("prompt", prompt);

        if (contextFileContents != null && !contextFileContents.isEmpty()) {
            ObjectNode ctxNode = mapper.createObjectNode();
            contextFileContents.forEach(ctxNode::put);
            body.set("contextFileContents", ctxNode);
        }

        if (providerOverride != null) body.put("providerOverride", providerOverride);
        if (modelOverride != null)    body.put("modelOverride", modelOverride);

        String bodyStr;
        try { bodyStr = mapper.writeValueAsString(body); }
        catch (Exception e) { return CompletableFuture.failedFuture(e); }

        AtomicBoolean doneHandled = new AtomicBoolean(false);
        StringBuilder[] pendingChunk = {new StringBuilder()};

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/refactor"))
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
                                        if (line.startsWith("data:")) {
                                            String data = line.substring(5).trim();
                                            if (data.equals("[DONE]")) {
                                                if (doneHandled.compareAndSet(false, true)) onDone.run();
                                            } else if (data.startsWith("[ERROR]")) {
                                                onError.accept(data.substring(7).trim());
                                            } else if (!data.isEmpty()) {
                                                pendingChunk[0].append(data).append("\n");
                                            }
                                        } else if (line.isEmpty()) {
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
    //  HTTP Helpers
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
}