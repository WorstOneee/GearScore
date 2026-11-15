package br.com.serpens.gearscore.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Debounce queue: at most one pending evaluation per player, ordered by due tick.
 *
 * Every accepted request gets its own token. A heap entry only fires while its token is
 * still the player's current one, so an entry left behind by {@link #remove(UUID)} can
 * neither run nor consume a request made afterwards.
 * Not thread-safe; owned by the server thread.
 */
public final class EvaluationQueue {

    private record Entry(UUID id, long due, long token) {
    }

    private final PriorityQueue<Entry> entries = new PriorityQueue<>((a, b) -> {
        int byDue = Long.compare(a.due(), b.due());
        return byDue != 0 ? byDue : Long.compare(a.token(), b.token());
    });
    private final Map<UUID, Long> pending = new HashMap<>();
    private long nextToken;

    /** @return false when an evaluation for this player was already pending */
    public boolean request(UUID id, long now, int delay) {
        if (pending.containsKey(id)) {
            return false;
        }
        long token = ++nextToken;
        pending.put(id, token);
        entries.add(new Entry(id, now + Math.max(0, delay), token));
        return true;
    }

    public boolean isPending(UUID id) {
        return pending.containsKey(id);
    }

    public void remove(UUID id) {
        if (pending.remove(id) != null) {
            compact();
        }
    }

    /** Hands at most {@code max} due players to the consumer, earliest due first. */
    public int drain(long now, int max, Consumer<UUID> consumer) {
        int processed = 0;
        while (processed < max) {
            Entry head = entries.peek();
            if (head == null || head.due() > now) {
                break;
            }
            entries.poll();
            Long current = pending.get(head.id());
            if (current == null || current != head.token()) {
                continue;
            }
            pending.remove(head.id());
            consumer.accept(head.id());
            processed++;
        }
        compact();
        return processed;
    }

    // Cancelled entries normally leave when they reach the head; this keeps the heap from
    // growing when players are removed faster than their entries come due.
    private void compact() {
        if (pending.isEmpty()) {
            entries.clear();
        } else if (entries.size() > pending.size() * 2 + 32) {
            entries.removeIf(entry -> {
                Long current = pending.get(entry.id());
                return current == null || current != entry.token();
            });
        }
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }

    /** Heap entries, including cancelled ones not yet discarded. */
    int backlog() {
        return entries.size();
    }
}
