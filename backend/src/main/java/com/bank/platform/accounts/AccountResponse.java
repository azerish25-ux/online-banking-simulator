package com.bank.platform.accounts;

import java.util.UUID;

/**
 * The account wire shape. Lives with the entity it describes - a change to the
 * account response must not reach into the ledger package to find its DTO.
 * Mapped by {@link AccountMapper}, the single construction site. The domain
 * enums serialize by name, so the JSON is unchanged - but the OpenAPI
 * contract now lists the exact legal values instead of an open string.
 */
public record AccountResponse(UUID id, String iban, AccountType type, String balance,
    AccountStatus status) {}
