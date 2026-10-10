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

    /**
     * Unpublished rows in insertion order, skipping every row of a loan that
     * has a parked row (relay payload park or operator park): with one topic
     * per aggregate the loan's sagas rely on order, so its later events wait
     * until the parked one is replayed (ADR-021 decision 4; V12 partial index
     * ix_outbox_parked_aggregate). Rows of other loans are not held back.
     */
    @Query(value = """
        select * from outbox_event o
        where o.published_at is null and o.parked_at is null
          and not exists (select 1 from outbox_event p
                          where p.parked_at is not null and p.aggregate_id = o.aggregate_id)
        order by o.created_seq
        limit :batchSize
        """, nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    /** Backlog: rows still to be relayed, including rows held back behind a parked row of their loan (parked rows are counted separately). */
    long countByPublishedAtIsNullAndParkedAtIsNull();

    long countByParkedAtIsNotNull();

    /** Parked rows not yet counted in outbox_parked_events_total: operator parks done in SQL. */
    @Query("select e from OutboxEventJpaEntity e where e.parkedAt is not null and e.parkCounted = false")
    List<OutboxEventJpaEntity> findUncountedParks();

    @Query("select min(e.occurredAt) from OutboxEventJpaEntity e where e.publishedAt is null and e.parkedAt is null")
    Optional<Instant> oldestPendingOccurredAt();
}
