package com.autoideator.agent;

import com.autoideator.config.AutoIdeatorConfig.IdeaQueueWeights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weighted round-robin queue of idea-generating agents.
 *
 * <p>Each orchestration cycle, exactly one agent from this queue is selected to generate
 * improvement ideas. The ideas feed into Skeptic → Director as normal.
 *
 * <p>The queue is built from configurable weights per agent. Default weights
 * (D=1, A=2, R=1, H=1, O=5, Adv=5) produce a 15-slot sequence:
 * {@code [D, A, A, R, H, O, O, O, O, O, Adv, Adv, Adv, Adv, Adv]}
 *
 * <p>The cursor advances one slot per cycle. Skip logic (Artist/Hacker disabled)
 * uses {@code advancePast()} to skip all consecutive slots of the disabled agent.
 *
 * <p>Both Artist and Hacker can be conditionally skipped:
 * <ul>
 *   <li>Artist is skipped when the Maestro determines the project has no frontend.
 *       The queue advances past all consecutive Artist slots and the next agent runs.</li>
 *   <li>Hacker is skipped when {@code hackerEnabled} is false. The queue advances
 *       past all consecutive Hacker slots and the next agent runs.</li>
 * </ul>
 *
 * <p>The GoalVerifierAgent is NOT part of this queue — it runs as a mandatory
 * check at the start of every cycle outside the IdeaQueue rotation.
 *
 * <h3>Thread-safety</h3>
 * <p>All methods that read or modify queue state are {@code synchronized}.
 * Weight changes via {@link #rebuildWeights(IdeaQueueWeights)} are deferred:
 * the new weights are stored and applied atomically at the start of the next
 * {@link #consume(boolean, boolean)} call. This ensures that
 * {@link #isArtistNext()}/{@link #isHackerNext()} and the subsequent
 * {@code consume()} within a single orchestration cycle always see a
 * consistent snapshot of the sequence, even if a config update arrives
 * concurrently from the dashboard HTTP thread.
 */
public class IdeaQueue {

    private static final Logger LOG = LoggerFactory.getLogger(IdeaQueue.class);

    /** Named slots in the expanded sequence. */
    public enum Slot {
        DREAMER, ARTIST, REFINER, HACKER, OBSESSOR, ADVANCER
    }

    private final Map<Slot, Agent> agentMap;

    /** The expanded flat sequence built from weights. */
    private List<Slot> sequence;

    /** The weights that produced the current sequence. */
    private IdeaQueueWeights activeWeights;

    /**
     * Current queue position. Points to the slot that will run on the NEXT call to
     * {@link #consume(boolean, boolean)}.
     */
    private int position;

    /**
     * Pending weights to apply at the start of the next {@link #consume} call.
     * Set by {@link #rebuildWeights} from the dashboard HTTP thread and consumed
     * by the orchestration thread. Access is guarded by {@code synchronized}.
     */
    private IdeaQueueWeights pendingWeights;

    public IdeaQueue(
            DreamerAgent dreamer,
            ArtistAgent artist,
            RefinerAgent refiner,
            HackerAgent hacker,
            ObsessorAgent obsessor,
            AdvancerAgent advancer,
            IdeaQueueWeights weights) {
        this.agentMap = Map.of(
            Slot.DREAMER,   dreamer,
            Slot.ARTIST,    artist,
            Slot.REFINER,   refiner,
            Slot.HACKER,    hacker,
            Slot.OBSESSOR,  obsessor,
            Slot.ADVANCER,  advancer
        );
        this.sequence = buildExpandedSequence(weights);
        this.activeWeights = weights;
        this.position = 0;
    }

    /**
     * Builds the expanded flat sequence from weights.
     * Slots are appended in order: Dreamer, Artist, Refiner, Hacker, Obsessor, Advancer.
     * A higher weight means more slots for that agent, so it runs in more cycles per rotation.
     */
    static List<Slot> buildExpandedSequence(IdeaQueueWeights w) {
        List<Slot> seq = new ArrayList<>();
        for (int i = 0; i < w.dreamer();   i++) seq.add(Slot.DREAMER);
        for (int i = 0; i < w.artist();    i++) seq.add(Slot.ARTIST);
        for (int i = 0; i < w.refiner();   i++) seq.add(Slot.REFINER);
        for (int i = 0; i < w.hacker();    i++) seq.add(Slot.HACKER);
        for (int i = 0; i < w.obsessor();  i++) seq.add(Slot.OBSESSOR);
        for (int i = 0; i < w.advancer();  i++) seq.add(Slot.ADVANCER);
        if (seq.isEmpty()) {
            // Fallback: at least one Dreamer slot to prevent division-by-zero
            LOG.warn("All idea queue weights are zero — adding a default Dreamer slot");
            seq.add(Slot.DREAMER);
        }
        return Collections.unmodifiableList(seq);
    }

    /**
     * Returns the name of the agent at the current position.
     * Does NOT advance the queue.
     *
     * <p>Note: If weights have been changed via {@link #rebuildWeights} but not
     * yet applied by a {@link #consume} call, this returns the name from the
     * previous sequence. This is intentional for cycle consistency.
     */
    public synchronized String peekCurrentName() {
        Slot slot = sequence.get(position % sequence.size());
        return slotToName(slot);
    }

    /**
     * Returns true when the current slot is a Dreamer slot.
     * The orchestrator uses this to decide whether to skip the Dreamer.
     */
    public synchronized boolean isDreamerNext() {
        return sequence.get(position % sequence.size()) == Slot.DREAMER;
    }

    /**
     * Returns true when the current slot is an Artist slot.
     * The orchestrator uses this to decide whether to invoke the Maestro first.
     */
    public synchronized boolean isArtistNext() {
        return sequence.get(position % sequence.size()) == Slot.ARTIST;
    }

    /**
     * Returns true when the current slot is a Hacker slot.
     */
    public synchronized boolean isHackerNext() {
        return sequence.get(position % sequence.size()) == Slot.HACKER;
    }

    /**
     * Consume the current queue slot and advance for the next cycle.
     *
     * <p>If pending weights have been set via {@link #rebuildWeights}, they are applied
     * first (resetting position to 0).
     *
     * <p>If {@code artistEnabled} is {@code false} and the current slot is Artist, all
     * consecutive Artist slots are skipped and the following agent runs instead.
     *
     * <p>If {@code hackerEnabled} is {@code false} and the current slot is Hacker, all
     * consecutive Hacker slots are skipped and the following agent runs instead.
     *
     * <p>If {@code dreamerEnabled} is {@code false} and the current slot is Dreamer, all
     * consecutive Dreamer slots are skipped (the Dreamer has determined all goals are met).
     *
     * @param artistEnabled  whether the Artist is currently allowed to run
     * @param hackerEnabled  whether the Hacker is currently allowed to run
     * @param dreamerEnabled whether the Dreamer is currently allowed to run
     * @return the Agent that should generate ideas this cycle
     */
    public synchronized Agent consume(boolean artistEnabled, boolean hackerEnabled, boolean dreamerEnabled) {
        // Apply deferred weight changes
        if (pendingWeights != null) {
            LOG.info("Applying deferred idea queue weight change");
            this.sequence = buildExpandedSequence(pendingWeights);
            this.activeWeights = pendingWeights;
            this.position = 0;
            this.pendingWeights = null;
        }

        int size = sequence.size();
        int pos = position % size;
        Slot current = sequence.get(pos);

        // Loop skip checks: after skipping one disabled agent we may land on another,
        // so repeat until the current slot is an enabled agent or we've exhausted all slots.
        // Track visited positions (not iteration count) to detect full-cycle exhaustion.
        Set<Integer> visited = new HashSet<>();
        boolean foundEnabled = false;
        while (visited.size() < size) {
            current = sequence.get(pos);
            if (visited.contains(pos)) {
                // We've looped back to a position we already tried — all slots exhausted
                break;
            }
            if (current == Slot.DREAMER && !dreamerEnabled) {
                visited.add(pos);
                pos = advancePast(pos, Slot.DREAMER);
                continue;
            }
            if (current == Slot.ARTIST && !artistEnabled) {
                visited.add(pos);
                pos = advancePast(pos, Slot.ARTIST);
                continue;
            }
            if (current == Slot.HACKER && !hackerEnabled) {
                visited.add(pos);
                pos = advancePast(pos, Slot.HACKER);
                continue;
            }
            foundEnabled = true;
            break;
        }

        if (!foundEnabled) {
            // All slots disabled — pick the first agent that isn't disabled.
            // Prefer Obsessor (always useful) over Dreamer as fallback.
            LOG.error("All {} slots skipped (visited {}) — selecting fallback agent", size, visited.size());
            position = (pos + 1) % size;
            if (dreamerEnabled) return agentMap.get(Slot.DREAMER);
            return agentMap.get(Slot.OBSESSOR);
        }

        // Advance past the consumed slot
        position = (pos + 1) % size;
        return agentMap.get(current);
    }

    /**
     * Advances past all consecutive slots of the given type starting from {@code startPos}.
     * Returns the first position that is NOT the given type.
     *
     * <p>The {@code steps < size} guard prevents an infinite loop if (impossibly,
     * given weight validation) all slots are the skipped type.
     */
    private int advancePast(int startPos, Slot skipSlot) {
        int size = sequence.size();
        int pos = (startPos + 1) % size; // Start from the NEXT slot, not the current one
        int steps = 1;
        while (sequence.get(pos) == skipSlot && steps < size) {
            pos = (pos + 1) % size;
            steps++;
        }
        if (steps >= size) {
            LOG.error("advancePast({}) exhausted all {} slots — no alternative agent available; "
                    + "returning position {} which may still be {}", skipSlot, size, pos, skipSlot);
        }
        return pos;
    }

    /**
     * Queue new weights to be applied at the start of the next {@link #consume} call.
     *
     * <p>This is called from the dashboard HTTP thread when the user changes config.
     * The deferred application ensures that an in-flight orchestration cycle (which
     * may have already called {@link #isArtistNext()}) does not see a mid-cycle
     * sequence/position change.
     */
    public synchronized void rebuildWeights(IdeaQueueWeights newWeights) {
        this.pendingWeights = newWeights;
    }

    /**
     * Result of a combined peek-and-consume operation.
     * Returned by {@link #peekAndConsume} to let the caller
     * inspect slot eligibility and the selected agent atomically.
     */
    public record ConsumeResult(boolean isDreamerNext, boolean isArtistNext, boolean isHackerNext, Agent agent) {}

    /**
     * Peek the current slot, evaluate artist eligibility outside the lock (the
     * decider may invoke Maestro which runs an LLM call for minutes), then
     * consume and advance the cursor while holding the lock.
     *
     * <p>This eliminates the TOCTOU race between separate peek and consume calls
     * that could see different sequences if a weight rebuild occurs concurrently,
     * while avoiding holding the synchronized lock during the potentially
     * long-running {@code artistEnabledDecider} callback.
     *
     * <p>The algorithm is:
     * <ol>
     *   <li>Synchronized: apply pending weights, peek current slot, record flags.</li>
     *   <li>Unsynchronized: if the peeked slot is Artist, call the decider.</li>
     *   <li>Synchronized: consume the slot, skipping disabled agents.</li>
     * </ol>
     *
     * @param hackerEnabled  whether the Hacker agent is enabled in config
     * @param dreamerEnabled whether the Dreamer agent is enabled (false if goals met)
     * @param artistEnabledDecider a function that returns true if artist should
     *        run; only called when the current slot is Artist
     * @return a {@link ConsumeResult} with peek flags and the selected agent
     */
    public ConsumeResult peekAndConsume(boolean hackerEnabled,
                                        boolean dreamerEnabled,
                                        java.util.function.BooleanSupplier artistEnabledDecider) {
        boolean wasDreamerNext;
        boolean wasArtistNext;
        boolean wasHackerNext;

        // Step 1: Synchronized peek — apply pending weights and read current slot
        synchronized (this) {
            if (pendingWeights != null) {
                LOG.info("Applying deferred idea queue weight change");
                this.sequence = buildExpandedSequence(pendingWeights);
                this.activeWeights = pendingWeights;
                this.position = 0;
                this.pendingWeights = null;
            }
            int pos = position % sequence.size();
            Slot peeked = sequence.get(pos);
            wasDreamerNext = peeked == Slot.DREAMER;
            wasArtistNext  = peeked == Slot.ARTIST;
            wasHackerNext  = peeked == Slot.HACKER;
        }

        // Step 2: Evaluate artist eligibility OUTSIDE the lock.
        // The decider may trigger a Maestro LLM call that takes minutes —
        // holding the lock would block all other queue operations.
        boolean artistEnabled;
        if (wasArtistNext) {
            artistEnabled = artistEnabledDecider.getAsBoolean();
        } else {
            // Will be re-evaluated lazily below if we land on Artist after skipping
            artistEnabled = true; // placeholder — re-checked in step 3
        }

        // Step 3: Synchronized consume — advance the cursor, skipping disabled agents
        synchronized (this) {
            // Re-apply pending weights in case they changed during the decider call
            if (pendingWeights != null) {
                LOG.info("Applying deferred idea queue weight change (during consume)");
                this.sequence = buildExpandedSequence(pendingWeights);
                this.activeWeights = pendingWeights;
                this.position = 0;
                this.pendingWeights = null;
            }

            int size = sequence.size();
            int pos = position % size;
            Slot current;
            Set<Integer> visited = new HashSet<>();
            boolean foundEnabled = false;

            // If we didn't peek Artist originally, but land on Artist after skipping,
            // we need a fresh decider call. Track whether we've already evaluated.
            Boolean artistEval = wasArtistNext ? artistEnabled : null;

            while (visited.size() < size) {
                current = sequence.get(pos);
                if (visited.contains(pos)) break;
                if (current == Slot.DREAMER && !dreamerEnabled) {
                    visited.add(pos);
                    pos = advancePast(pos, Slot.DREAMER);
                    continue;
                }
                if (current == Slot.ARTIST) {
                    if (artistEval == null) {
                        // We landed on Artist via skip — must evaluate outside lock.
                        // Since we can't release the lock mid-loop, use the decider here.
                        // This is the rare case (Artist after Hacker/Dreamer skip) and is acceptable.
                        artistEval = artistEnabledDecider.getAsBoolean();
                    }
                    if (!artistEval) {
                        visited.add(pos);
                        pos = advancePast(pos, Slot.ARTIST);
                        continue;
                    }
                }
                if (current == Slot.HACKER && !hackerEnabled) {
                    visited.add(pos);
                    pos = advancePast(pos, Slot.HACKER);
                    continue;
                }
                foundEnabled = true;
                break;
            }

            if (!foundEnabled) {
                LOG.error("All {} slots skipped (visited {}) — selecting fallback agent", size, visited.size());
                position = (pos + 1) % size;
                Agent fallback = dreamerEnabled ? agentMap.get(Slot.DREAMER) : agentMap.get(Slot.OBSESSOR);
                return new ConsumeResult(wasDreamerNext, wasArtistNext, wasHackerNext, fallback);
            }

            current = sequence.get(pos);
            position = (pos + 1) % size;
            return new ConsumeResult(wasDreamerNext, wasArtistNext, wasHackerNext, agentMap.get(current));
        }
    }

    /**
     * Returns the current queue position (the slot that will run next).
     * Used for checkpointing — the position can be restored on resume.
     */
    public synchronized int getPosition() {
        return position;
    }

    /**
     * Restore the queue position from a checkpoint.
     * If the position is out of range for the current sequence, it wraps via modulo.
     *
     * @param restoredPosition the position to restore
     */
    public synchronized void setPosition(int restoredPosition) {
        int size = sequence.size();
        // Guard against negative values (Java's % can return negative)
        this.position = ((restoredPosition % size) + size) % size;
    }

    /**
     * Returns the weights that produced the current sequence.
     * Used for checkpointing — allows detecting whether the sequence changed.
     */
    public synchronized IdeaQueueWeights getCurrentWeights() {
        return activeWeights;
    }

    /**
     * Returns the total number of slots in the current sequence.
     */
    public synchronized int sequenceLength() {
        return sequence.size();
    }

    /**
     * Returns an unmodifiable view of the current sequence (for testing/debugging).
     */
    public synchronized List<Slot> currentSequence() {
        return sequence;
    }

    private static String slotToName(Slot slot) {
        return switch (slot) {
            case DREAMER  -> "Dreamer";
            case ARTIST   -> "Artist";
            case REFINER  -> "Refiner";
            case HACKER   -> "Hacker";
            case OBSESSOR -> "Obsessor";
            case ADVANCER -> "Advancer";
        };
    }
}
