package br.com.serpens.gearscore.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationQueueTest {

    private final EvaluationQueue queue = new EvaluationQueue();
    private final List<UUID> fired = new ArrayList<>();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private int drain(long now) {
        return queue.drain(now, 20, fired::add);
    }

    @Test
    void repeatedRequestsAreMergedIntoThePendingOne() {
        assertTrue(queue.request(a, 0, 10));
        assertFalse(queue.request(a, 3, 10));
        assertFalse(queue.request(a, 9, 10));
        assertEquals(0, drain(9));
        assertEquals(1, drain(10));
        assertEquals(List.of(a), fired);
        assertTrue(queue.isEmpty());
    }

    @Test
    void staleEntryDoesNotFireTheNewRequestEarly() {
        queue.request(a, 0, 10);
        queue.remove(a);
        // Same player queued again later: it must wait its own full delay.
        assertTrue(queue.request(a, 5, 10));
        assertEquals(0, drain(10), "the cancelled entry came due and must be ignored");
        assertTrue(queue.isPending(a), "the cancelled entry must not consume the new request");
        assertEquals(0, drain(14));
        assertEquals(1, drain(15));
        assertEquals(List.of(a), fired);
    }

    @Test
    void staleEntryDoesNotCancelOrDuplicateTheNewRequest() {
        queue.request(a, 0, 10);
        queue.remove(a);
        queue.request(a, 1, 10);
        queue.remove(a);
        queue.request(a, 2, 10);
        assertEquals(0, drain(11));
        assertEquals(1, drain(12));
        assertEquals(0, drain(1_000));
        assertEquals(List.of(a), fired, "exactly one evaluation, from the latest request");
    }

    @Test
    void removedPlayerNeverFires() {
        queue.request(a, 0, 10);
        queue.request(b, 0, 10);
        queue.remove(a);
        assertEquals(1, drain(100));
        assertEquals(List.of(b), fired);
        assertTrue(queue.isEmpty());
    }

    @Test
    void entriesFireInDueOrderEvenWhenTheDelayChanges() {
        // a was queued under a long debounce; a reload then shortened it for b and c.
        queue.request(a, 0, 40);
        queue.request(b, 1, 5);
        queue.request(c, 2, 5);
        assertEquals(1, drain(6));
        assertEquals(1, drain(7));
        assertEquals(0, drain(39));
        assertEquals(1, drain(40));
        assertEquals(List.of(b, c, a), fired);
    }

    @Test
    void perCallLimitIsRespectedAndCancelledEntriesDoNotUseIt() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            queue.request(id, 0, 1);
        }
        for (int i = 0; i < 10; i++) {
            queue.remove(ids.get(i));
        }
        assertEquals(20, queue.drain(1, 20, fired::add));
        assertEquals(20, queue.drain(1, 20, fired::add));
        assertEquals(0, queue.drain(1, 20, fired::add));
        assertEquals(ids.subList(10, 50), fired, "first come, first served among equal due ticks");
    }

    @Test
    void cancelledEntriesDoNotAccumulate() {
        queue.request(a, 0, 1_000_000);
        for (int i = 0; i < 10_000; i++) {
            UUID id = UUID.randomUUID();
            queue.request(id, i, 1_000_000);
            queue.remove(id);
        }
        assertEquals(1, queue.size());
        assertTrue(queue.backlog() < 100, "backlog=" + queue.backlog());
        queue.remove(a);
        assertEquals(0, queue.backlog());
    }
}
