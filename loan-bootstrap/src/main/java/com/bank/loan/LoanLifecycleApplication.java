package com.bank.loan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-ln-loan-lifecycle: the Loan bounded context extracted from
 * enterprise-loan-management-system.
 */
@SpringBootApplication
public class LoanLifecycleApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanLifecycleApplication.class, args);
    }
}
