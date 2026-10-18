package com.bank.loan.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four rules of the FinTechBankX service guardrails (ADR-028), run on
 * {@code ./gradlew check} over the main classes of every module.
 */
class HexagonalArchitectureTest {

    private static final String ROOT = "com.bank.loan";
    private static JavaClasses main;

    @BeforeAll
    static void importMainClasses() {
        main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    }

    /** Rule 1: the domain depends on no layer above it and no framework. */
    @Test
    void domainIsFreeOfOuterLayersAndFrameworks() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                ROOT + ".application..", ROOT + ".infrastructure..",
                "org.springframework..", "jakarta.persistence..", "org.hibernate..",
                "org.apache.kafka..", "com.mongodb..", "org.bson..", "com.fasterxml.jackson..")
            .check(main);
    }

    /** Rule 2: application code never reaches into adapters. */
    @Test
    void applicationDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..")
            .check(main);
    }

    /**
     * Rule 2b (ADR-019 section 4): a consumed record's correlation reaches the
     * use cases as plain ids (EventCausation); no messaging or JSON types in
     * the application layer.
     */
    @Test
    void applicationIsFreeOfMessagingTypes() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.apache.kafka..", "org.springframework.kafka..", "com.fasterxml.jackson..")
            .check(main);
    }

    /** Rule 3: controllers and Kafka listeners drive the use cases through domain.port.in. */
    @Test
    void inboundAdaptersDependOnUseCaseInterfacesNotOnTheirImplementations() {
        classes().that(areInboundAdapters())
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.port.in..")
            .check(main);
        noClasses().that(areInboundAdapters())
            .should().dependOnClassesThat(implementUseCases())
            .check(main);
    }

    /** Rule 4: whatever implements an out-port lives in infrastructure. */
    @Test
    void outPortImplementationsLiveInInfrastructure() {
        classes().that(implementOutPorts())
            .should().resideInAPackage(ROOT + ".infrastructure..")
            .check(main);
    }

    private static DescribedPredicate<JavaClass> areInboundAdapters() {
        return DescribedPredicate.describe("are controllers or Kafka listeners", javaClass ->
            javaClass.isAnnotatedWith(RestController.class)
                || javaClass.isAnnotatedWith(Controller.class)
                || javaClass.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(KafkaListener.class)));
    }

    private static DescribedPredicate<JavaClass> implementUseCases() {
        return DescribedPredicate.describe("implement a domain.port.in use case", javaClass ->
            !javaClass.isInterface() && javaClass.getAllRawInterfaces().stream()
                .anyMatch(type -> type.getPackageName().startsWith(ROOT + ".domain.port.in")));
    }

    private static DescribedPredicate<JavaClass> implementOutPorts() {
        return DescribedPredicate.describe("implement a domain.port.out interface", javaClass ->
            !javaClass.isInterface() && javaClass.getAllRawInterfaces().stream()
                .anyMatch(type -> type.getPackageName().startsWith(ROOT + ".domain.port.out")));
    }
}
