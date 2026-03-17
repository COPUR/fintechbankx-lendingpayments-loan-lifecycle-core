package com.bank.shared.kernel.domain;

public interface Entity<ID> {
    ID getId();

    default boolean sameIdentityAs(Entity<ID> other) {
        return other != null && getId() != null && getId().equals(other.getId());
    }
}
