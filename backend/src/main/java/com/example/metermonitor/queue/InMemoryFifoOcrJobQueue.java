package com.example.metermonitor.queue;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class InMemoryFifoOcrJobQueue implements OcrJobQueue {
    private final BlockingQueue<UUID> queue = new LinkedBlockingQueue<>();
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();
    @Override public void enqueue(UUID id) { if (scheduled.add(id)) queue.offer(id); }
    @Override public Optional<UUID> poll() { UUID id = queue.poll(); if (id != null) scheduled.remove(id); return Optional.ofNullable(id); }
    @Override public int size() { return queue.size(); }
}
