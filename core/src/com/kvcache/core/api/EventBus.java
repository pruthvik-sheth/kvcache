package com.kvcache.core.api;

import java.util.function.Consumer;

/**
 * Intra-process event bus for decoupled module communication.
 *
 * <p>Modules publish domain events here instead of holding direct references to
 * each other. This keeps the runtime call graph acyclic even when modules need
 * to react to each other's state changes — e.g. the gossip module publishes
 * {@code NodeStateChangedEvent} and the replication module reacts without
 * gossip knowing anything about replication.
 *
 * <p>Delivery is synchronous on the publishing thread. Listeners must not block
 * indefinitely; offload heavy work to a virtual thread if needed.
 *
 * @see com.kvcache.core.impl.InMemoryEventBus
 */
public interface EventBus {

    /**
     * Publishes an event to all listeners registered for its runtime type.
     *
     * <p>A listener that throws must not prevent other listeners from receiving
     * the event. Implementations are responsible for isolating listener failures.
     *
     * @param event the event to publish; must not be {@code null}
     */
    <T> void publish(T event);

    /**
     * Registers a listener to receive all future events of the given type.
     *
     * <p>Listeners are called in subscription order. Subscribing the same listener
     * twice will result in it being called twice per event.
     *
     * @param eventType the exact runtime class to match; subtypes are not matched
     * @param listener  the callback invoked for each matching event
     */
    <T> void subscribe(Class<T> eventType, Consumer<T> listener);
}
