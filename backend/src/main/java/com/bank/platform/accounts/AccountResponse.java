package com.bank.platform.accounts;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The account wire shape. Lives with the entity it describes - a change to the
 * account response must not reach into the ledger package to find its DTO.
 * Mapped by {@link AccountMapper}, the single construction site.
 *
 * <p>Loan debt fields ( section 7): a drawn loan's authoritative
 * figures are derived server-side from the policy state - principal owed,
 * the unpaid interest on top of it, total owed, and available credit (credit
 * headroom on PRINCIPAL, never balance-derived). They are null on every
 * non-loan account. Amounts travel as decimal strings so JSON never loses
 * the ledger's 4-decimal precision.
 */
public record AccountResponse(
    UUID id,
    String iban,
    AccountType type,
    String balance,
    AccountStatus status,
    @Schema(nullable = true, description = "Outstanding drawn principal on a LOAN; null otherwise")
    String principalOwed,
    @Schema(nullable = true, description = "Unpaid interest on a LOAN (total owed minus principal); null otherwise")
    String interestOwed,
    @Schema(nullable = true, description = "Total amount owed on a LOAN; null otherwise")
    String totalOwed,
    @Schema(nullable = true, description = "Credit still available on a LOAN (limit minus principal); null otherwise")
    String availableCredit) {}
