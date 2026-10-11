package com.bank.loan.domain;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class LoanDomainArchitectureTest {

    @Test
    void domainDoesNotDependOnApplicationOrInfrastructure() {
        JavaClasses classes = new ClassFileImporter().importPackagesOf(Loan.class);

        noClasses()
            .that().resideInAPackage("com.bank.loan.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.bank.loan.application..", "com.bank.loan.infrastructure..")
            .allowEmptyShould(true)
            .check(classes);
    }

    @Test
    void domainIsFreeOfFrameworkAndPersistenceTypes() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bank.loan.domain");

        noClasses()
            .that().resideInAPackage("com.bank.loan.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..",
                "com.fasterxml.jackson..", "org.apache.kafka..")
            .check(classes);
    }
}
