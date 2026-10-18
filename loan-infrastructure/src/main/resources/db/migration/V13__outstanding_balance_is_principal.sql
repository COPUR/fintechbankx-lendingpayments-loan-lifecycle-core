-- Comment only, no data change. V3 described outstanding_balance as the schedule's total still due;
-- the balance is the principal still owed, as the monolith reports it (round 12, LP-01). The installment
-- rows record what each payment settled; the balance moves by the principal part of each payment, not by
-- the schedule's amounts.
COMMENT ON COLUMN loan.outstanding_balance IS
    'Principal still owed, as the monolith reports it: the loan amount less the principal part of each payment (migrated flat-rate loans: interest first, per installment; loans created here: the whole payment). 0 exactly when FULLY_PAID.';
