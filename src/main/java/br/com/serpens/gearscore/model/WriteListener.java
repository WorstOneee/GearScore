package br.com.serpens.gearscore.model;

import java.util.UUID;

/**
 * What the write queue tells the owner of the results. Methods are called from the queue's
 * own threads and must be cheap and thread-safe.
 */
public interface WriteListener {

    /** The transaction holding this player's result with this sequence number committed. */
    void confirmed(UUID id, long sequence);

    /** The queue gave up this player's pending result without writing it. */
    void dropped(UUID id, long sequence);

    /**
     * Whether the owner still holds this player's latest result and can submit it again.
     * The queue gives up such entries first, because nothing is lost by doing so.
     */
    boolean recoverable(UUID id);
}
