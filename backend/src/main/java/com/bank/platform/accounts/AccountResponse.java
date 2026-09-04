package com.bank.platform.accounts;

import java.util.UUID;

/**
 * The account wire shape. Lives with the entity it describes - a change to the
 * account response must not reach into the ledger package to find its DTO.
 * Mapped by {@link AccountMapper}, the single construction site.
 */
public record AccountResponse(UUID id, String iban, String type, String balance, String status) {}
