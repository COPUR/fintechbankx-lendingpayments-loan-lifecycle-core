package com.bank.loan.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    /**
     * Takes the cluster-wide relay lock for the current transaction. Only one
     * replica relays at a time, which keeps each aggregate's events in order.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryRelayLock(@Param("key") long key);

    @Query(value = """
        select * from outbox_event
        where published_at is null and parked_at is null
        order by created_seq
        limit :batchSize
        """, nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    /** Backlog: rows still to be relayed (parked rows are counted separately). */
    long countByPublishedAtIsNullAndParkedAtIsNull();

    long countByParkedAtIsNotNull();

    @Query("select min(e.occurredAt) from OutboxEventJpaEntity e where e.publishedAt is null and e.parkedAt is null")
    Optional<Instant> oldestPendingOccurredAt();
}
