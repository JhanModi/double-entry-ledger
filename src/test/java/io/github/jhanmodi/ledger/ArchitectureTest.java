package io.github.jhanmodi.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.jhanmodi.ledger.ledger.AccountId;
import io.github.jhanmodi.ledger.money.ModuleRuleViolation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules, checked against the compiled main code (test code is excluded): no floating point, and module
 * boundaries.
 *
 * <p>No floating point (ADR-0002): main code may not declare float/double fields, parameters, or return types, and may
 * not call any method or constructor that takes or returns one, such as {@code new BigDecimal(double)} or
 * {@code BigDecimal.doubleValue()}. Limitation: ArchUnit reads class signatures and calls, not arithmetic inside a
 * method body, so a local {@code double} computed with plain operators would slip through. Code review covers that.
 */
class ArchitectureTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.jhanmodi.ledger");

    private static final Set<String> FLOATING_POINT_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    private static final DescribedPredicate<JavaClass> FLOATING_POINT =
            DescribedPredicate.describe("float or double", type -> FLOATING_POINT_TYPES.contains(type.getName()));

    private static final String REASON = "money must be exact; see ADR-0002";

    static final ArchRule NO_FLOATING_POINT_FIELDS =
            noFields().should().haveRawType(FLOATING_POINT).because(REASON);

    static final ArchRule NO_FLOATING_POINT_RETURN_TYPES =
            noMethods().should().haveRawReturnType(FLOATING_POINT).because(REASON);

    static final ArchRule NO_FLOATING_POINT_PARAMETERS = noCodeUnits()
            .should()
            .haveRawParameterTypes(DescribedPredicate.<List<JavaClass>>describe(
                    "a float or double parameter",
                    parameters -> parameters.stream().anyMatch(FLOATING_POINT)))
            .because(REASON);

    static final ArchRule NO_CALLS_THAT_TAKE_OR_RETURN_FLOATING_POINT = noClasses()
            .should()
            .callCodeUnitWhere(DescribedPredicate.<JavaCall<?>>describe(
                    "a method or constructor that takes or returns float or double",
                    call -> call.getTarget().getRawParameterTypes().stream().anyMatch(FLOATING_POINT)
                            || FLOATING_POINT.test(call.getTarget().getRawReturnType())))
            .because(REASON);

    private static final List<ArchRule> FLOATING_POINT_RULES = List.of(
            NO_FLOATING_POINT_FIELDS,
            NO_FLOATING_POINT_RETURN_TYPES,
            NO_FLOATING_POINT_PARAMETERS,
            NO_CALLS_THAT_TAKE_OR_RETURN_FLOATING_POINT);

    @Test
    void mainCodeNeverUsesFloatingPoint() {
        FLOATING_POINT_RULES.forEach(rule -> rule.check(MAIN_CLASSES));
    }

    /** Proves each rule really catches what it claims to, so a passing check above means something. */
    @Test
    void eachFloatingPointRuleCatchesAViolation() {
        JavaClasses violations = new ClassFileImporter().importClasses(FloatingPointViolations.class);

        FLOATING_POINT_RULES.forEach(
                rule -> assertThatThrownBy(() -> rule.check(violations)).isInstanceOf(AssertionError.class));
    }

    // --- Module boundaries (ADR-0001) ---

    private static final String BASE = "io.github.jhanmodi.ledger.";

    /**
     * Who may depend on whom: each module only on the ones below it, so no module can grow a hidden cycle. The order
     * is web, then transfers, then idempotency, then ledger, then clients, then audit; money may be used by any of
     * ledger, transfers, and web. Classes in the base package (the application class and its configuration) belong to no
     * module.
     *
     * <p>Empty layers are allowed so the rule can be checked against a handful of classes (see the test below). What
     * ArchUnit can't check is SQL inside strings, so "only the ledger writes entries" is enforced by the ledger's
     * package-private repository and by code review, not here.
     */
    static final ArchRule MODULES_DEPEND_ONLY_DOWNWARD = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("web")
            .definedBy(BASE + "web..")
            .layer("transfers")
            .definedBy(BASE + "transfers..")
            .layer("idempotency")
            .definedBy(BASE + "idempotency..")
            .layer("ledger")
            .definedBy(BASE + "ledger..")
            .layer("clients")
            .definedBy(BASE + "clients..")
            .layer("audit")
            .definedBy(BASE + "audit..")
            .layer("money")
            .definedBy(BASE + "money..")
            .whereLayer("web")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("transfers")
            .mayOnlyBeAccessedByLayers("web")
            .whereLayer("idempotency")
            .mayOnlyBeAccessedByLayers("transfers", "web")
            .whereLayer("ledger")
            .mayOnlyBeAccessedByLayers("idempotency", "transfers", "web")
            .whereLayer("clients")
            .mayOnlyBeAccessedByLayers("idempotency", "ledger", "transfers", "web")
            .whereLayer("audit")
            .mayOnlyBeAccessedByLayers("clients", "idempotency", "ledger", "transfers", "web")
            .whereLayer("money")
            .mayOnlyBeAccessedByLayers("ledger", "transfers", "web")
            .because("modules depend only downward (ADR-0001)");

    @Test
    void modulesDependOnlyDownward() {
        MODULES_DEPEND_ONLY_DOWNWARD.check(MAIN_CLASSES);
    }

    /** Proves the rule catches a real violation, and fails for that reason rather than some other. */
    @Test
    void theModuleRuleCatchesAViolation() {
        JavaClasses violation = new ClassFileImporter().importClasses(ModuleRuleViolation.class, AccountId.class);

        assertThatThrownBy(() -> MODULES_DEPEND_ONLY_DOWNWARD.check(violation))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ModuleRuleViolation.class.getName())
                .hasMessageContaining(AccountId.class.getName());
    }

    /** Deliberately breaks every floating-point rule. Lives in test code, so the real check above never sees it. */
    @SuppressWarnings("unused")
    static class FloatingPointViolations {

        double balance;

        double ratio() {
            return 0;
        }

        void apply(double rate) {}

        BigDecimal fromDouble() {
            return new BigDecimal(0.1);
        }
    }
}
