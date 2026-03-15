package com.bank.shared.kernel.domain;

public interface ValueObject {
    default boolean isEmpty() {
        return false;
    }
}
