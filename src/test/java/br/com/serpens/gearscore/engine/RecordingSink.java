package br.com.serpens.gearscore.engine;

import br.com.serpens.gearscore.model.PlayerRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for the write queue: remembers what it accepted and can pretend to be full.
 */
public final class RecordingSink implements ScoreEngine.Sink {

    public final List<PlayerRecord> written = new ArrayList<>();
    public final List<Long> sequences = new ArrayList<>();
    /** Hand-overs that were not recoverable (player leaving, shutdown). */
    public final List<PlayerRecord> finalHandOvers = new ArrayList<>();
    public boolean full;

    @Override
    public boolean submit(PlayerRecord record, long sequence, boolean recoverable) {
        if (full && recoverable) {
            return false;
        }
        written.add(record);
        sequences.add(sequence);
        if (!recoverable) {
            finalHandOvers.add(record);
        }
        return true;
    }

    @Override
    public boolean hasRoom() {
        return !full;
    }

    public long lastSequence() {
        return sequences.get(sequences.size() - 1);
    }
}
