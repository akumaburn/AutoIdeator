package com.autoideator.llm;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.model.AgentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * LLM interface using the OpenRouter API.
 */
public class OpenRouterClient implements LlmInterface {

    private static final Logger LOG = LoggerFactory.getLogger(OpenRouterClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AutoIdeatorConfig config;
    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private final ExecutorService dispatcherExecutor;

    public OpenRouterClient(AutoIdeatorConfig config) {
        this.config = config;
        long readTimeoutSeconds = config.llm().timeout() != null
            ? config.llm().timeout().getSeconds()
            : TimeUnit.MINUTES.toSeconds(5);
        // Use a dedicated dispatcher so close() doesn't shut down OkHttp's shared default pool
        this.dispatcherExecutor = Executors.newCachedThreadPool();
        Dispatcher dispatcher = new Dispatcher(this.dispatcherExecutor);
        this.httpClient = new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> 
            LOG.error("Uncaught exception in OpenRouterClient virtual thread '{}': {}", 
                thread.getName(), throwable.getMessage(), throwable);
        this.executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                .uncaughtExceptionHandler(handler)
                .factory());
    }

    @Override
    public CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> callOpenRouter(systemPrompt, userPrompt), executor);
    }

    @Override
    public CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        if (onChunk == null) {
            return sendPrompt(systemPrompt, userPrompt);
        }
        return CompletableFuture.supplyAsync(
            () -> callOpenRouterStreaming(systemPrompt, userPrompt, onChunk), executor);
    }

    @Override
    public CompletableFuture<AgentResponse> sendPromptWithHistory(
        String systemPrompt,
        Iterable<Message> messages,
        String userPrompt
    ) {
        return CompletableFuture.supplyAsync(
            () -> callOpenRouterWithHistory(systemPrompt, messages, userPrompt),
            executor
        );
    }

    private AgentResponse callOpenRouter(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        messages.add(Message.user(userPrompt));
        return callOpenRouterWithMessages(messages);
    }

    private AgentResponse callOpenRouterWithHistory(
        String systemPrompt,
        Iterable<Message> history,
        String userPrompt
    ) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        history.forEach(messages::add);
        messages.add(Message.user(userPrompt));
        return callOpenRouterWithMessages(messages);
    }

    private AgentResponse callOpenRouterWithMessages(List<Message> messages) {
        long startTime = System.currentTimeMillis();

        String apiKey = resolveApiKey();
        if (apiKey == null) {
            return AgentResponse.failure("OpenRouter API key not configured. Set OPENROUTER_API_KEY environment variable or use --api-key");
        }

        String model = config.llm().openRouter().model();
        if (model == null || model.isBlank()) {
            return AgentResponse.failure("OpenRouter model not configured. Specify a model in configuration or use --model");
        }

        try {
            String requestBody = buildRequestBody(messages);
            // Log request body but redact any potential sensitive content
            if (LOG.isDebugEnabled()) {
                LOG.debug("OpenRouter request: model={}", config.llm().openRouter().model());
            }

            Request request = new Request.Builder()
                .url(config.llm().openRouter().baseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://github.com/autoideator")
                .addHeader("X-Title", "AutoIdeator")
                .post(RequestBody.create(requestBody, JSON))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error body";
                    LOG.error("OpenRouter API error: {} - {}", response.code(), errorBody);
                    return AgentResponse.failure("OpenRouter API error: " + response.code() + " - " + errorBody);
                }

                if (response.body() == null) {
                    return AgentResponse.failure("OpenRouter returned empty response body");
                }
                String responseBody = response.body().string();
                return parseResponse(responseBody, duration);
            }
        } catch (IOException e) {
            LOG.error("OpenRouter API call failed", e);
            return AgentResponse.failure("OpenRouter API call failed: " + e.getMessage());
        }
    }

    private String buildRequestBody(List<Message> messages) throws IOException {
        return buildRequestBody(messages, false);
    }

    private String buildRequestBody(List<Message> messages, boolean stream) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", config.llm().openRouter().model());
        if (stream) {
            root.put("stream", true);
        }

        ArrayNode messagesArray = root.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
        }

        return MAPPER.writeValueAsString(root);
    }

    private AgentResponse callOpenRouterStreaming(String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        long startTime = System.currentTimeMillis();

        String apiKey = resolveApiKey();
        if (apiKey == null) {
            return AgentResponse.failure(
                "OpenRouter API key not configured. Set OPENROUTER_API_KEY environment variable or use --api-key");
        }

        String model = config.llm().openRouter().model();
        if (model == null || model.isBlank()) {
            return AgentResponse.failure("OpenRouter model not configured. Specify a model in configuration or use --model");
        }

        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        messages.add(Message.user(userPrompt));

        try {
            String requestBody = buildRequestBody(messages, true);
            if (LOG.isDebugEnabled()) {
                LOG.debug("OpenRouter streaming request: model={}", model);
            }

            Request request = new Request.Builder()
                .url(config.llm().openRouter().baseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://github.com/autoideator")
                .addHeader("X-Title", "AutoIdeator")
                .post(RequestBody.create(requestBody, JSON))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No error body";
                    LOG.error("OpenRouter streaming API error: {} - {}", response.code(), errorBody);
                    return AgentResponse.failure("OpenRouter API error: " + response.code() + " - " + errorBody);
                }

                if (response.body() == null) {
                    return AgentResponse.failure("OpenRouter returned empty response body");
                }

                StringBuilder contentBuilder = new StringBuilder();
                int totalTokens = 0;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;

                        try {
                            JsonNode chunk = MAPPER.readTree(data);
                            String content = chunk.path("choices").path(0)
                                .path("delta").path("content").asText(null);
                            if (content != null) {
                                contentBuilder.append(content);
                                try {
                                    onChunk.accept(content);
                                } catch (Exception e) {
                                    LOG.trace("Error in streaming callback: {}", e.getMessage());
                                }
                            }
                            // Capture usage from the final chunk if present
                            JsonNode usage = chunk.path("usage");
                            if (!usage.isMissingNode() && usage.has("total_tokens")) {
                                totalTokens = usage.path("total_tokens").asInt(0);
                            }
                        } catch (Exception e) {
                            LOG.trace("Error parsing SSE chunk: {}", e.getMessage());
                        }
                    }
                }

                long duration = System.currentTimeMillis() - startTime;
                String content = contentBuilder.toString();

                if (content.isBlank()) {
                    LOG.error("OpenRouter streaming produced no content");
                    return AgentResponse.failure("OpenRouter streaming produced no content");
                }

                LOG.debug("OpenRouter streaming response: {} chars, {} tokens in {}ms",
                    content.length(), totalTokens, duration);
                return AgentResponse.success(content, totalTokens, duration);
            }
        } catch (IOException e) {
            LOG.error("OpenRouter streaming API call failed", e);
            return AgentResponse.failure("OpenRouter streaming API call failed: " + e.getMessage());
        }
    }

    private String resolveApiKey() {
        String apiKey = config.llm().openRouter().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENROUTER_API_KEY");
        }
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : null;
    }

    private AgentResponse parseResponse(String responseBody, long durationMs) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");

            if (choices.isEmpty()) {
                return AgentResponse.failure("No choices in OpenRouter response");
            }

            String content = choices.get(0).path("message").path("content").asText();
            int tokens = root.path("usage").path("total_tokens").asInt(0);

            if (content == null || content.isBlank()) {
                return AgentResponse.failure("OpenRouter returned empty content");
            }

            LOG.debug("OpenRouter response received: {} tokens in {}ms", tokens, durationMs);
            return AgentResponse.success(content, tokens, durationMs);
        } catch (IOException e) {
            LOG.error("Failed to parse OpenRouter response", e);
            return AgentResponse.failure("Failed to parse OpenRouter response: " + e.getMessage());
        }
    }

    @Override
    public String getBackendName() {
        return "OpenRouter";
    }

    @Override
    public boolean isAvailable() {
        return resolveApiKey() != null;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        this.dispatcherExecutor.shutdown();
        try {
            if (!this.dispatcherExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.dispatcherExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.dispatcherExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        httpClient.connectionPool().evictAll();
    }
}
