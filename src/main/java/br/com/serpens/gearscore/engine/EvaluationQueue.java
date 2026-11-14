package br.com.serpens.gearscore.engine;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Debounce queue: at most one pending evaluation per player. Entries become due a fixed
 * delay after the first request, so due times are monotonic and a plain FIFO is enough.
 * Not thread-safe; owned by the server thread.
 */
public final class EvaluationQueue {

    private record Entry(UUID id, long due) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final Set<UUID> pending = new HashSet<>();

    /** @return false when an evaluation for this player was already pending */
    public boolean request(UUID id, long now, int delay) {
        if (!pending.add(id)) {
            return false;
        }
        entries.addLast(new Entry(id, now + Math.max(0, delay)));
        return true;
    }

    public boolean isPending(UUID id) {
        return pending.contains(id);
    }

    public void remove(UUID id) {
        // The deque entry is dropped lazily when it reaches the head.
        pending.remove(id);
    }

    /** Hands at most {@code max} due players to the consumer. */
    public int drain(long now, int max, Consumer<UUID> consumer) {
        int processed = 0;
        while (processed < max) {
            Entry head = entries.peekFirst();
            if (head == null || head.due() > now) {
                break;
            }
            entries.pollFirst();
            if (pending.remove(head.id())) {
                consumer.accept(head.id());
                processed++;
            }
        }
        if (pending.isEmpty() && !entries.isEmpty()) {
            entries.clear();
        }
        return processed;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }
}
