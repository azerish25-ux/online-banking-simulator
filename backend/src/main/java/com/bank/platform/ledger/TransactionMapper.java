package com.bank.platform.ledger;

import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import com.bank.platform.ledger.TransferDtos.TransferResponse;
import java.util.Map;
import java.util.UUID;

/** Sole construction site for transaction wire shapes. */
public final class TransactionMapper {

  private TransactionMapper() {}

  public static TransactionResponse toResponse(Transaction tx, Map<UUID, String> ibans) {
    return new TransactionResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getKind().name(),
        tx.getStatus().name(),
        tx.getCreatedAt().toString(), tx.isFlagged(), tx.isReviewed());
  }

  public static TransferResponse toTransferResponse(Transaction tx, Map<UUID, String> ibans) {
    return new TransferResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.get(tx.getFromAccountId()),
        tx.getToAccountId() == null ? null : ibans.get(tx.getToAccountId()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getStatus().name(),
        tx.getCreatedAt().toString(), tx.isFlagged());
  }
}
