package com.kvcache.core.impl;

import com.kvcache.core.api.EventBus;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe in-process event bus.
 *
 * <p>Listeners are stored in a {@link ConcurrentHashMap} keyed by event class.
 * Each value is a {@link CopyOnWriteArrayList} so that iteration in
 * {@link #publish} is always lock-free and never races with concurrent
 * {@link #subscribe} calls.
 *
 * <p>Events are dispatched synchronously on the publishing thread in
 * subscription order. A listener that throws is isolated: its exception is
 * logged to stderr and the remaining listeners still receive the event.
 * This matches the semantics callers expect from an event bus — one bad
 * subscriber must not silently drop events for others.
 *
 * <p>This implementation is used in production. Tests that need to assert on
 * published events should subscribe before the code under test runs.
 */
public final class InMemoryEventBus implements EventBus {

    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners =
            new ConcurrentHashMap<>();

    @Override
    public <T> void publish(T event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }

        @SuppressWarnings("unchecked")
        List<Consumer<T>> eventListeners =
                (List<Consumer<T>>) (List<?>) listeners.getOrDefault(
                        event.getClass(), new CopyOnWriteArrayList<>());

        for (Consumer<T> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Isolate the failure so remaining listeners still receive the event.
                System.err.printf("[EventBus] listener threw for %s: %s%n",
                        event.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public <T> void subscribe(Class<T> eventType, Consumer<T> listener) {
        if (eventType == null) throw new IllegalArgumentException("eventType must not be null");
        if (listener == null) throw new IllegalArgumentException("listener must not be null");

        @SuppressWarnings("unchecked")
        CopyOnWriteArrayList<Consumer<T>> list =
                (CopyOnWriteArrayList<Consumer<T>>) (CopyOnWriteArrayList<?>)
                        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());

        list.add(listener);
    }
}
