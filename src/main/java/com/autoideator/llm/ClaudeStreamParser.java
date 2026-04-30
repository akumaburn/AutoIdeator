package com.autoideator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parses Claude CLI stream-json events and emits human-readable streaming chunks.
 * Tracks tool call state across content blocks to show tool names, inputs, and results.
 *
 * <p>Create one instance per output stream — holds mutable accumulation state
 * and is NOT thread-safe. Since the output reader thread processes events
 * sequentially, this is safe for single-stream use.
 */
final class ClaudeStreamParser {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeStreamParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESULT_PREVIEW = 300;

    /** Tool name per content block index. */
    private final Map<Integer, String> toolNames = new HashMap<>();

    /** Accumulated input JSON per content block index. */
    private final Map<Integer, StringBuilder> toolInputs = new HashMap<>();

    /**
     * Process a single stream-json event and emit human-readable content.
     */
    void processEvent(JsonNode event, String eventType, Consumer<String> onChunk) {
        try {
            switch (eventType) {
                case "assistant" -> emitAssistantContent(event, onChunk);
                case "content_block_start" -> handleBlockStart(event, onChunk);
                case "content_block_delta" -> handleBlockDelta(event, onChunk);
                case "content_block_stop" -> handleBlockStop(event, onChunk);
                case "tool_result" -> handleToolResult(event, onChunk);
                // Skip system, init, result, message_start, message_stop, ping, etc.
                default -> {}
            }
        } catch (Exception e) {
            LOG.trace("Error extracting stream chunk: {}", e.getMessage());
        }
    }

    private void emitAssistantContent(JsonNode event, Consumer<String> onChunk) {
        // Claude CLI stream-json wraps content under "message"; handle both layouts
        JsonNode content = event.path("content");
        if (!content.isArray()) {
            content = event.path("message").path("content");
        }
        if (!content.isArray()) return;

        for (JsonNode block : content) {
            String blockType = block.path("type").asText();
            if ("text".equals(blockType)) {
                String text = block.path("text").asText(null);
                if (text != null && !text.isEmpty()) {
                    onChunk.accept(text);
                }
            } else if ("tool_use".equals(blockType)) {
                String toolName = block.path("name").asText(null);
                if (toolName != null) {
                    onChunk.accept("\n[tool: " + toolName + "]\n");
                    // If the full input is already present in the assistant event
                    JsonNode input = block.path("input");
                    if (input.isObject() && input.size() > 0) {
                        String summary = summarizeToolInput(toolName, input);
                        if (summary != null) {
                            onChunk.accept(summary + "\n");
                        }
                    }
                }
            }
        }
    }

    private void handleBlockStart(JsonNode event, Consumer<String> onChunk) {
        JsonNode block = event.path("content_block");
        if (!"tool_use".equals(block.path("type").asText())) return;

        String toolName = block.path("name").asText(null);
        if (toolName == null) return;

        int index = event.path("index").asInt(-1);
        toolNames.put(index, toolName);
        toolInputs.put(index, new StringBuilder());
        onChunk.accept("\n[tool: " + toolName + "] ");
    }

    private void handleBlockDelta(JsonNode event, Consumer<String> onChunk) {
        JsonNode delta = event.path("delta");
        String deltaType = delta.path("type").asText();

        if ("text_delta".equals(deltaType)) {
            String text = delta.path("text").asText(null);
            if (text != null && !text.isEmpty()) {
                onChunk.accept(text);
            }
        } else if ("input_json_delta".equals(deltaType)) {
            int index = event.path("index").asInt(-1);
            StringBuilder sb = toolInputs.get(index);
            String partial = delta.path("partial_json").asText(null);
            if (sb != null && partial != null) {
                sb.append(partial);
            }
        }
    }

    private void handleBlockStop(JsonNode event, Consumer<String> onChunk) {
        int index = event.path("index").asInt(-1);
        String toolName = toolNames.remove(index);
        StringBuilder sb = toolInputs.remove(index);

        if (toolName == null || sb == null || sb.isEmpty()) return;

        try {
            JsonNode input = JSON.readTree(sb.toString());
            String summary = summarizeToolInput(toolName, input);
            if (summary != null) {
                onChunk.accept(summary + "\n");
            }
        } catch (Exception e) {
            // Malformed JSON — show truncated raw input
            String raw = sb.toString();
            if (raw.length() > 200) {
                raw = raw.substring(0, 200) + "...";
            }
            onChunk.accept("  → " + raw + "\n");
        }
    }

    private void handleToolResult(JsonNode event, Consumer<String> onChunk) {
        JsonNode content = event.path("content");
        String text = null;

        if (content.isTextual()) {
            text = content.asText();
        } else if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText(""));
                }
            }
            text = sb.toString();
        }

        if (text == null || text.isBlank()) return;

        String preview = text.length() > MAX_RESULT_PREVIEW
            ? text.substring(0, MAX_RESULT_PREVIEW) + "..."
            : text;
        onChunk.accept("[result: " + preview.replace("\n", " ") + "]\n");
    }

    /**
     * Extract a concise human-readable summary from tool input JSON.
     */
    private String summarizeToolInput(String toolName, JsonNode input) {
        try {
            return switch (toolName) {
                case "Read" -> summarizePathInput(input);
                case "Edit" -> summarizePathInput(input);
                case "Write" -> summarizePathInput(input);
                case "Bash" -> {
                    String cmd = input.path("command").asText(null);
                    yield cmd != null
                        ? "  → " + (cmd.length() > 200 ? cmd.substring(0, 200) + "..." : cmd)
                        : null;
                }
                case "Glob" -> {
                    String pattern = input.path("pattern").asText(null);
                    yield pattern != null ? "  → " + pattern : null;
                }
                case "Grep" -> {
                    String pattern = input.path("pattern").asText(null);
                    String path = input.path("path").asText(null);
                    if (pattern == null) yield null;
                    yield path != null
                        ? "  → " + pattern + " in " + path
                        : "  → " + pattern;
                }
                case "Agent" -> {
                    String desc = input.path("description").asText(null);
                    yield desc != null ? "  → " + desc : null;
                }
                default -> {
                    String raw = input.toString();
                    yield "  → " + (raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
                }
            };
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizePathInput(JsonNode input) {
        String path = input.path("file_path").asText(null);
        return path != null ? "  → " + path : null;
    }
}
