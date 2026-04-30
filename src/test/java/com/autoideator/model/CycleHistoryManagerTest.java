package com.autoideator.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("CycleHistoryManager Tests")
class CycleHistoryManagerTest {

    @Test
    @DisplayName("Should not throw when filtering common keywords")
    void shouldNotThrowWhenFilteringCommonKeywords() {
        CycleHistoryManager history = new CycleHistoryManager();
        history.addCycle(CycleOutcome.failed(1, "obsessor", "this that with from", "failed"));

        assertThatCode(() -> history.checkSimilarity("this feature should improve correctness"))
            .doesNotThrowAnyException();
    }
}
