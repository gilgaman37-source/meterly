package com.example.metermonitor.queue;

import java.util.*;

public interface OcrJobQueue {
    void enqueue(UUID requestId);
    Optional<UUID> poll();
    int size();
}
