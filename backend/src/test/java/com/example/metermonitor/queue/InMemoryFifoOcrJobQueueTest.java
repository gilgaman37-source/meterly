package com.example.metermonitor.queue;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryFifoOcrJobQueueTest {
    @Test void preservesFifoOrderAndDeduplicatesScheduledIds() {
        var queue = new InMemoryFifoOcrJobQueue(); UUID first=UUID.randomUUID(), second=UUID.randomUUID();
        queue.enqueue(first); queue.enqueue(second); queue.enqueue(first);
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.poll()).contains(first); assertThat(queue.poll()).contains(second); assertThat(queue.poll()).isEmpty();
    }
}
