package com.autoideator.llm;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mock LLM interface for testing purposes.
 * Returns pre-defined responses based on task type.
 */
public class MockLlmClient implements LlmInterface {

    private static final Logger LOG = LoggerFactory.getLogger(MockLlmClient.class);

    private static final String PLAN_RESPONSE = """
        ## Analysis

        This task requires implementing a Fibonacci number generator.
        Key considerations:
        - Handle edge cases (negative numbers, zero, one)
        - Choose between iterative and recursive approaches
        - Consider performance for large numbers

        ## Improvements

        1. Add input validation
        2. Use iterative approach for better performance
        3. Add unit tests
        4. Update documentation

        ## Updated Tasks
        - [CRITICAL] Analyze requirements and design API
        - [HIGH] Implement Fibonacci generator with iterative approach
        - [HIGH] Add input validation and error handling
        - [MEDIUM] Write comprehensive unit tests
        - [MEDIUM] Update README documentation
        - [LOW] Add performance benchmarks

        ## Summary
        Plan refined with proper task breakdown and priorities.
        """;

    private static final String CODE_RESPONSE = """
        ```java
        /**
         * Generate Fibonacci number at the given position.
         * Uses iterative approach for O(n) time complexity.
         *
         * @param n the position in the Fibonacci sequence (0-indexed)
         * @return the Fibonacci number at position n
         * @throws IllegalArgumentException if n is negative
         */
        public long fibonacci(int n) {
            if (n < 0) {
                throw new IllegalArgumentException("Position must be non-negative");
            }
            if (n <= 1) {
                return n;
            }

            long prev = 0, curr = 1;
            for (int i = 2; i <= n; i++) {
                long next = prev + curr;
                prev = curr;
                curr = next;
            }
            return curr;
        }
        ```
        """;

    private static final String TEST_RESPONSE = """
        ```java
        @Test
        void testFibonacci() {
            RandomNumberFun fun = new RandomNumberFun();

            // Base cases
            assertEquals(0, fun.fibonacci(0));
            assertEquals(1, fun.fibonacci(1));

            // Standard cases
            assertEquals(1, fun.fibonacci(2));
            assertEquals(2, fun.fibonacci(3));
            assertEquals(5, fun.fibonacci(5));
            assertEquals(55, fun.fibonacci(10));

            // Edge case
            assertThrows(IllegalArgumentException.class, () -> fun.fibonacci(-1));
        }
        ```
        """;

    private static final String REVIEW_RESPONSE = """
        ## Review Results

        **POSITIVE**: Clean iterative implementation, good variable naming

        **MEDIUM**: Consider adding memoization for repeated calls

        **LOW**: Could add BigInteger support for very large numbers

        **Summary**: Code is production-ready with minor improvement opportunities.
        """;

    private static final String IMPROVEMENT_RESPONSE = """
        ## Improvements Found

        1. [quality] [MEDIUM] Add memoization cache for Fibonacci numbers
           - Files: RandomNumberFun.java
           - Effort: small
           - Rationale: Improves performance for repeated calls

        2. [quality] [LOW] Add BigInteger overload method
           - Files: RandomNumberFun.java
           - Effort: medium
           - Rationale: Supports arbitrarily large Fibonacci numbers
        """;

    private final ExecutorService executor;

    public MockLlmClient(AutoIdeatorConfig config) {
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> 
            LOG.error("Uncaught exception in MockLlmClient virtual thread '{}': {}", 
                thread.getName(), throwable.getMessage(), throwable);
        this.executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual()
                .uncaughtExceptionHandler(handler)
                .factory());
        LOG.info("MockLlmClient initialized for testing");
    }

    @Override
    public CompletableFuture<AgentResponse> sendPrompt(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            // Simulate some processing time
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String response = determineResponse(userPrompt);
            LOG.debug("Mock response for prompt: {}", response.substring(0, Math.min(100, response.length())));
            return AgentResponse.success(response, 100, 100);
        }, executor);
    }

    @Override
    public CompletableFuture<AgentResponse> sendPromptWithHistory(
        String systemPrompt,
        Iterable<Message> messages,
        String userPrompt
    ) {
        return sendPrompt(systemPrompt, userPrompt);
    }

    private String determineResponse(String prompt) {
        String lowerPrompt = prompt.toLowerCase();

        if (lowerPrompt.contains("improvement") || lowerPrompt.contains("enhance")) {
            return IMPROVEMENT_RESPONSE;
        }
        if (lowerPrompt.contains("test")) {
            return TEST_RESPONSE;
        }
        if (lowerPrompt.contains("review")) {
            return REVIEW_RESPONSE;
        }
        if (lowerPrompt.contains("implement") || lowerPrompt.contains("code") || lowerPrompt.contains("fibonacci")) {
            return CODE_RESPONSE;
        }
        if (lowerPrompt.contains("plan") || lowerPrompt.contains("analyze") || lowerPrompt.contains("design")) {
            return PLAN_RESPONSE;
        }

        // Default response
        return PLAN_RESPONSE;
    }

    @Override
    public String getBackendName() {
        return "Mock LLM";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
