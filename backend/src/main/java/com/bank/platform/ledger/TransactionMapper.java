package com.bank.platform.ledger;

import com.bank.platform.ledger.TransferDtos.OperationListItem;
import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import com.bank.platform.ledger.TransferDtos.TransferResponse;
import java.util.Map;
import java.util.UUID;

/** Sole construction site for transaction wire shapes. */
public final class TransactionMapper {

  private TransactionMapper() {}

  /**
   * Customer/account surface (history, feeds). Reversal rows keep their
   * factual linkage, but the operator's mandatory reason and the
   * has-this-been-reversed lookup are internal notes: always null here.
   * The idempotency key is also null: a history row can be a LEG of another
   * user's operation, so serving its key would disclose the originator's
   * client token to the counterparty. Keys live on the receipt shapes only.
   */
  public static TransactionResponse toResponse(Transaction tx, Map<UUID, String> ibans) {
    return new TransactionResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getKind(),
        tx.getStatus(),
        tx.getCreatedAt().toString(),
        tx.getPostedAt() == null ? null : tx.getPostedAt().toString(),
        tx.isFlagged(), tx.isReviewed(),
        tx.getReversesTransactionId(),
        null,
        null,
        null);
  }

  /**
   * The receipt face (one operation by id or by key, for the caller's own
   * dispatch): carries the idempotency key so a client can verify the answer
   * names the operation it sent.
   */
  public static TransactionResponse toReceiptResponse(Transaction tx, Map<UUID, String> ibans) {
    return new TransactionResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getKind(),
        tx.getStatus(),
        tx.getCreatedAt().toString(),
        tx.getPostedAt() == null ? null : tx.getPostedAt().toString(),
        tx.isFlagged(), tx.isReviewed(),
        tx.getReversesTransactionId(),
        null,
        null,
        tx.getIdempotencyKey());
  }

  /**
   * Operator surface (admin consoles): the full reversal picture: the reason
   * on a REVERSAL row and, via {@code reversalByOriginalId} (original-id → its
   * reversal row id, see TransactionRepository.reversalIndexBy), whether a
   * POSTED row has already been reversed so the console never offers a second.
   * The reversal faces never surface a raw idempotency key.
   */
  public static TransactionResponse toAdminResponse(Transaction tx, Map<UUID, String> ibans,
      Map<UUID, UUID> reversalByOriginalId) {
    return new TransactionResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getKind(),
        tx.getStatus(),
        tx.getCreatedAt().toString(),
        tx.getPostedAt() == null ? null : tx.getPostedAt().toString(),
        tx.isFlagged(), tx.isReviewed(),
        tx.getReversesTransactionId(),
        tx.getReversalReason(),
        reversalByOriginalId.get(tx.getId()),
        null);
  }

  /**
   * The recovery-list shape: transaction fields plus the idempotency key and
   * the originating account whose namespace the key is unique on.
   */
  public static OperationListItem toOperationListItem(Transaction tx, Map<UUID, String> ibans) {
    return new OperationListItem(
        tx.getId(),
        tx.getFromAccountId() != null ? tx.getFromAccountId() : tx.getToAccountId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getKind(),
        tx.getStatus(),
        tx.getCreatedAt().toString(),
        tx.getPostedAt() == null ? null : tx.getPostedAt().toString(),
        tx.getIdempotencyKey());
  }

  public static TransferResponse toTransferResponse(Transaction tx, Map<UUID, String> ibans) {
    return new TransferResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.get(tx.getFromAccountId()),
        tx.getToAccountId() == null ? null : ibans.get(tx.getToAccountId()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getKind(),
        tx.getStatus(),
        tx.getCreatedAt().toString(),
        tx.getPostedAt() == null ? null : tx.getPostedAt().toString(),
        tx.isFlagged(),
        tx.getIdempotencyKey());
  }
}
