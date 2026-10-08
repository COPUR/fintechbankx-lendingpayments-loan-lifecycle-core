package com.bank.loan.domain;

/**
 * Enumeration representing the lifecycle status of a loan
 */
public enum LoanStatus {
    CREATED("Loan Created", false),
    PENDING_APPROVAL("Pending Approval", false),
    APPROVED("Approved", false),
    ACTIVE("Active", true),
    DISBURSED("Disbursed", true),
    FULLY_PAID("Fully Paid", false),
    DEFAULTED("Defaulted", false),
    REJECTED("Rejected", false),
    CANCELLED("Cancelled", false),
    RESTRUCTURED("Restructured", true),
    WRITTEN_OFF("Written Off", false);
    
    private final String displayName;
    private final boolean activePaymentStatus;
    
    LoanStatus(String displayName, boolean activePaymentStatus) {
        this.displayName = displayName;
        this.activePaymentStatus = activePaymentStatus;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public boolean isActivePaymentStatus() {
        return activePaymentStatus;
    }
    
    public boolean isTerminalStatus() {
        return this == FULLY_PAID || this == DEFAULTED || 
               this == REJECTED || this == CANCELLED || this == WRITTEN_OFF;
    }
    
    public boolean canAcceptPayments() {
        return activePaymentStatus;
    }
    
    public boolean canBeApproved() {
        return this == CREATED || this == PENDING_APPROVAL;
    }
    
    public boolean canBeRejected() {
        return this == CREATED || this == PENDING_APPROVAL;
    }
    
    public boolean canBeDisbursed() {
        return this == APPROVED;
    }
    
    /**
     * True if the loan was never disbursed: CREATED, PENDING_APPROVAL,
     * APPROVED, or closed before a disbursement (REJECTED, CANCELLED). Credit
     * reserved for such a loan is not used by a disbursement.
     */
    public boolean isNeverDisbursed() {
        return this == CREATED || this == PENDING_APPROVAL || this == APPROVED
            || this == REJECTED || this == CANCELLED;
    }

    public boolean canBeCancelled() {
        return this == CREATED || this == PENDING_APPROVAL || this == APPROVED;
    }
}