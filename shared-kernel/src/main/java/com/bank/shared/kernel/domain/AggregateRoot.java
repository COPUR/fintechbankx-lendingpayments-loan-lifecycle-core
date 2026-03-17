package com.bank.shared.kernel.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class AggregateRoot<ID> implements Entity<ID> {
    private final List<DomainEvent> domainEvents = new ArrayList<>();
    private Long version = 0L;

    protected void addDomainEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    public List<DomainEvent> getDomainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    public boolean hasUnpublishedEvents() {
        return !domainEvents.isEmpty();
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    protected void markModified() {
        this.version++;
    }
}
