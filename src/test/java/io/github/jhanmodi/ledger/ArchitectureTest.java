package io.github.jhanmodi.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules, checked against the compiled main code (test code is excluded).
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
