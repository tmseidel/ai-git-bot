package org.remus.giteabot;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/**
 * ArchUnit rules that protect the package structure and the Spring/Lombok
 * conventions of the codebase:
 * <ul>
 *   <li>Spring instantiates beans via constructor injection — field injection
 *       is forbidden.</li>
 *   <li>Web entry points ({@code webhook} package, MVC controllers) are a
 *       top layer that no other code depends on.</li>
 *   <li>Shared low-level packages ({@code config}, {@code session}) must not
 *       reach up into feature packages, keeping package scopes intact.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "org.remus.giteabot", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "org.remus.giteabot";

    @ArchTest
    static final ArchRule no_field_injection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /** The {@code webhook} package is an entry-point layer: nothing may depend on it. */
    @ArchTest
    static final ArchRule webhook_package_is_top_layer =
            noClasses().that().resideOutsideOfPackage(BASE + ".webhook")
                    .should().dependOnClassesThat().resideInAPackage(BASE + ".webhook");

    /** MVC controllers are entry points and must not be used by other classes.
     * Classes nested inside a controller (e.g. lambdas capturing {@code this},
     * compiled to synthetic inner classes whose constructor takes the enclosing
     * controller) are exempt — that dependency is compiler-generated. */
    @ArchTest
    static final ArchRule controllers_are_not_depended_upon =
            noClasses()
                    .that(DescribedPredicate.describe("are not nested inside a controller",
                            origin -> !isNestedInsideController(origin)))
                    .should().dependOnClassesThat(
                            DescribedPredicate.describe("are controllers", ArchitectureTest::isController));

    private static boolean isController(com.tngtech.archunit.core.domain.JavaClass javaClass) {
        return !javaClass.isAnnotation()
                && (javaClass.isAnnotatedWith(org.springframework.stereotype.Controller.class)
                || javaClass.isAnnotatedWith(org.springframework.web.bind.annotation.RestController.class));
    }

    private static boolean isNestedInsideController(com.tngtech.archunit.core.domain.JavaClass javaClass) {
        var enclosing = javaClass.getEnclosingClass();
        while (enclosing.isPresent()) {
            if (isController(enclosing.get())) {
                return true;
            }
            enclosing = enclosing.get().getEnclosingClass();
        }
        return false;
    }

    /** The {@code config} package is a leaf and must not depend on any feature package. */
    @ArchTest
    static final ArchRule config_package_is_independent =
            noClasses().that().resideInAPackage(BASE + ".config..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(BASE + ".admin..", BASE + ".agent..", BASE + ".ai..",
                            BASE + ".gitea..", BASE + ".github..", BASE + ".gitlab..",
                            BASE + ".bitbucket..", BASE + ".mcp..", BASE + ".prworkflow..",
                            BASE + ".repository..", BASE + ".review..", BASE + ".session..",
                            BASE + ".systemsettings..", BASE + ".webhook..");

    /** The {@code session} package may only use the {@code ai} package. */
    @ArchTest
    static final ArchRule session_package_only_uses_ai =
            noClasses().that().resideInAPackage(BASE + ".session..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(BASE + ".admin..", BASE + ".agent..", BASE + ".gitea..",
                            BASE + ".github..", BASE + ".gitlab..", BASE + ".bitbucket..",
                            BASE + ".mcp..", BASE + ".prworkflow..", BASE + ".repository..",
                            BASE + ".review..", BASE + ".systemsettings..", BASE + ".webhook..");

    /** Orchestrators own the run's repository coordinates (owner/repo/number),
     * never the repository API surface — repository side effects belong to a
     * dedicated component such as {@code notification.WorkflowRetryNotices}. */
    @ArchTest
    static final ArchRule orchestrators_do_not_use_repository_clients =
            noClasses().that().haveSimpleNameEndingWith("Orchestrator")
                    .should().dependOnClassesThat().resideInAPackage(BASE + ".repository..");

    /** The {@code ai} package (incl. its context thread-locals) stays
     * repository-agnostic: the AI layer learns about a pull request or issue
     * only through a sink handed down by the caller, so it never links a
     * provider client or a repository provider package. */
    @ArchTest
    static final ArchRule ai_package_does_not_use_repository_clients =
            noClasses().that().resideInAPackage(BASE + ".ai..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(BASE + ".repository..", BASE + ".gitea..",
                            BASE + ".github..", BASE + ".gitlab..", BASE + ".bitbucket..");
}
