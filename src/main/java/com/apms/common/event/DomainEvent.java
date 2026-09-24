package com.apms.common.event;

/**
 * Marker interface for all domain events in the system.
 * Domain events are published to decouple complex cross-database transactions.
 * For example, pushing a Candidate (MongoDB) to a Graph Node (Neo4j) after approval.
 */
public interface DomainEvent {
    // Shared event properties or methods can be added here
}
