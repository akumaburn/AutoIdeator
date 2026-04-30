package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig;
import com.autoideator.config.AutoIdeatorConfig.IdeaQueueWeights;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("IdeaQueue (Weighted Round-Robin)")
class IdeaQueueTest {

    private DreamerAgent dreamer;
    private ArtistAgent artist;
    private RefinerAgent refiner;
    private HackerAgent hacker;
    private ObsessorAgent obsessor;
    private AdvancerAgent advancer;

    @BeforeEach
    void setUp() {
        dreamer  = mock(DreamerAgent.class);
        artist   = mock(ArtistAgent.class);
        refiner  = mock(RefinerAgent.class);
        hacker   = mock(HackerAgent.class);
        obsessor = mock(ObsessorAgent.class);
        advancer = mock(AdvancerAgent.class);

        when(dreamer.getName()).thenReturn("Dreamer");
        when(artist.getName()).thenReturn("Artist");
        when(refiner.getName()).thenReturn("Refiner");
        when(hacker.getName()).thenReturn("Hacker");
        when(obsessor.getName()).thenReturn("Obsessor");
        when(advancer.getName()).thenReturn("Advancer");
    }

    private IdeaQueue queue(IdeaQueueWeights weights) {
        return new IdeaQueue(dreamer, artist, refiner, hacker, obsessor, advancer, weights);
    }

    @Test
    @DisplayName("Default weights produce a 15-slot sequence")
    void defaultWeightsProduceFifteenSlots() {
        IdeaQueue q = queue(IdeaQueueWeights.DEFAULT);
        // D=1 A=2 R=1 H=1 O=5 Adv=5 → 15
        assertThat(q.sequenceLength()).isEqualTo(15);
    }

    @Test
    @DisplayName("Expanded sequence matches expected order")
    void expandedSequenceOrder() {
        IdeaQueueWeights weights = new IdeaQueueWeights(1, 2, 1, 1, 5, 5);
        List<IdeaQueue.Slot> seq = IdeaQueue.buildExpandedSequence(weights);

        assertThat(seq).containsExactly(
            IdeaQueue.Slot.DREAMER,
            IdeaQueue.Slot.ARTIST, IdeaQueue.Slot.ARTIST,
            IdeaQueue.Slot.REFINER,
            IdeaQueue.Slot.HACKER,
            IdeaQueue.Slot.OBSESSOR, IdeaQueue.Slot.OBSESSOR, IdeaQueue.Slot.OBSESSOR,
            IdeaQueue.Slot.OBSESSOR, IdeaQueue.Slot.OBSESSOR,
            IdeaQueue.Slot.ADVANCER, IdeaQueue.Slot.ADVANCER, IdeaQueue.Slot.ADVANCER,
            IdeaQueue.Slot.ADVANCER, IdeaQueue.Slot.ADVANCER
        );
    }

    @Test
    @DisplayName("Full rotation visits all agents in weighted order")
    void fullRotationVisitsAllAgents() {
        IdeaQueue q = queue(new IdeaQueueWeights(1, 1, 1, 1, 1, 1));
        // 6 agents, all weight 1 → 6 slots

        assertThat(q.consume(true, true, true)).isSameAs(dreamer);
        assertThat(q.consume(true, true, true)).isSameAs(artist);
        assertThat(q.consume(true, true, true)).isSameAs(refiner);
        assertThat(q.consume(true, true, true)).isSameAs(hacker);
        assertThat(q.consume(true, true, true)).isSameAs(obsessor);
        assertThat(q.consume(true, true, true)).isSameAs(advancer);
        // Wrap around
        assertThat(q.consume(true, true, true)).isSameAs(dreamer);
    }

    @Test
    @DisplayName("Artist skip advances past all consecutive Artist slots")
    void artistSkipAdvancesPastAllSlots() {
        // Weight artist=2 → [D, A, A, R, H, O, Adv]
        IdeaQueue q = queue(new IdeaQueueWeights(1, 2, 1, 1, 1, 1));

        assertThat(q.consume(true, true, true)).isSameAs(dreamer);   // pos 0 → D
        // Next is Artist (pos 1), but artist disabled
        Agent result = q.consume(false, true, true);
        // Should skip both A slots (1, 2) and land on Refiner (pos 3)
        assertThat(result).isSameAs(refiner);
        // Next should be Hacker (pos 4)
        assertThat(q.consume(true, true, true)).isSameAs(hacker);
    }

    @Test
    @DisplayName("Hacker skip advances past all consecutive Hacker slots")
    void hackerSkipAdvancesPastAllSlots() {
        // Weight hacker=3 → [D, A, R, H, H, H, O, Adv]
        IdeaQueue q = queue(new IdeaQueueWeights(1, 1, 1, 3, 1, 1));

        assertThat(q.consume(true, true, true)).isSameAs(dreamer);
        assertThat(q.consume(true, true, true)).isSameAs(artist);
        assertThat(q.consume(true, true, true)).isSameAs(refiner);
        // Next is Hacker but disabled → skip all 3 H slots
        Agent result = q.consume(true, false, true);
        assertThat(result).isSameAs(obsessor);
        // Next should be Advancer
        assertThat(q.consume(true, true, true)).isSameAs(advancer);
    }

    @Test
    @DisplayName("Peek does not advance the queue")
    void peekDoesNotAdvance() {
        IdeaQueue q = queue(IdeaQueueWeights.DEFAULT);

        String first = q.peekCurrentName();
        String second = q.peekCurrentName();

        assertThat(first).isEqualTo("Dreamer");
        assertThat(second).isEqualTo("Dreamer");
    }

    @Test
    @DisplayName("isArtistNext returns true only on Artist slots")
    void isArtistNextOnlyOnArtistSlots() {
        IdeaQueue q = queue(new IdeaQueueWeights(1, 1, 1, 1, 1, 1));

        assertThat(q.isArtistNext()).isFalse(); // Dreamer
        q.consume(true, true, true);
        assertThat(q.isArtistNext()).isTrue();   // Artist
        q.consume(true, true, true);
        assertThat(q.isArtistNext()).isFalse(); // Refiner
    }

    @Test
    @DisplayName("isHackerNext returns true only on Hacker slots")
    void isHackerNextOnlyOnHackerSlots() {
        IdeaQueue q = queue(new IdeaQueueWeights(1, 1, 1, 1, 1, 1));

        q.consume(true, true, true); // Dreamer
        q.consume(true, true, true); // Artist
        q.consume(true, true, true); // Refiner
        assertThat(q.isHackerNext()).isTrue();
        q.consume(true, true, true); // Hacker
        assertThat(q.isHackerNext()).isFalse();
    }

    @Test
    @DisplayName("rebuildWeights is deferred until next consume()")
    void rebuildWeightsDeferredUntilConsume() {
        IdeaQueue q = queue(new IdeaQueueWeights(1, 1, 1, 1, 1, 1));

        q.consume(true, true, true); // advance past Dreamer
        q.consume(true, true, true); // advance past Artist

        assertThat(q.peekCurrentName()).isEqualTo("Refiner");

        // Rebuild is deferred — peek still sees old sequence
        q.rebuildWeights(new IdeaQueueWeights(2, 1, 1, 1, 1, 1));
        assertThat(q.peekCurrentName()).isEqualTo("Refiner"); // old sequence still active
        assertThat(q.sequenceLength()).isEqualTo(6);           // old length

        // consume() applies the pending weights, resets position to 0, returns Dreamer
        Agent first = q.consume(true, true, true);
        assertThat(first).isSameAs(dreamer);
        assertThat(q.sequenceLength()).isEqualTo(7); // 2+1+1+1+1+1
        // After consuming Dreamer (pos 0), position advances to 1 → still Dreamer (weight=2)
        assertThat(q.peekCurrentName()).isEqualTo("Dreamer");
    }

    @Test
    @DisplayName("Wrap-around works correctly with default weights")
    void wrapAroundWithDefaultWeights() {
        IdeaQueue q = queue(IdeaQueueWeights.DEFAULT);
        // 15 slots total (D=1 A=2 R=1 H=1 O=5 Adv=5), consume all of them
        for (int i = 0; i < 15; i++) {
            q.consume(true, true, true);
        }
        // Should be back at Dreamer
        assertThat(q.peekCurrentName()).isEqualTo("Dreamer");
    }

    @Test
    @DisplayName("Custom weights produce correct sequence length")
    void customWeightsSequenceLength() {
        IdeaQueueWeights weights = new IdeaQueueWeights(3, 1, 2, 1, 4, 2);
        IdeaQueue q = queue(weights);
        assertThat(q.sequenceLength()).isEqualTo(3 + 1 + 2 + 1 + 4 + 2);
    }
}
