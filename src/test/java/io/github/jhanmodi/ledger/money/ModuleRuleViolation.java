package io.github.jhanmodi.ledger.money;

import io.github.jhanmodi.ledger.ledger.AccountId;

/**
 * Deliberately breaks the module rules: money is the lowest module and may depend on no other, but this depends on
 * ledger. ArchitectureTest imports it on its own to prove the rule catches such a dependency. It lives in test code, so
 * the real check of the main code never sees it.
 */
@SuppressWarnings("unused")
public final class ModuleRuleViolation {

    private AccountId notAllowedHere;
}
