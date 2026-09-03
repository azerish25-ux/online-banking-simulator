package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatementService {

  private final MoneyService money;
  private final AccountRepository accounts;
  private final TransactionRepository transactions;

  public StatementService(
      MoneyService money, AccountRepository accounts, TransactionRepository transactions) {
    this.money = money;
    this.accounts = accounts;
    this.transactions = transactions;
  }

  public record Statement(Account account, List<Transaction> rows, Instant from, Instant to) {}

  @Transactional(readOnly = true)
  public Statement customerStatement(String email, UUID accountId, LocalDate from, LocalDate to) {
    Account account = money.accountDetail(email, accountId);
    LocalDate safeFrom = from != null ? from : defaultFrom();
    LocalDate safeTo = to != null ? to : defaultTo();
    return new Statement(account, rows(accountId, safeFrom, safeTo), startOf(safeFrom), startOf(safeTo));
  }

  @Transactional(readOnly = true)
  public Statement adminStatement(UUID accountId, LocalDate from, LocalDate to) {
    Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    LocalDate safeFrom = from != null ? from : defaultFrom();
    LocalDate safeTo = to != null ? to : defaultTo();
    return new Statement(account, rows(accountId, safeFrom, safeTo), startOf(safeFrom), startOf(safeTo));
  }

  @Transactional(readOnly = true)
  public Map<UUID, String> ibanMap(List<Transaction> rows) {
    java.util.Set<UUID> ids = rows.stream()
        .flatMap(tx -> java.util.stream.Stream.of(tx.getFromAccountId(), tx.getToAccountId()))
        .filter(value -> value != null)
        .collect(Collectors.toSet());
    return accounts.findAllById(ids).stream()
        .collect(Collectors.toMap(Account::getId, Account::getIban));
  }

  public static LocalDate defaultFrom() {
    return LocalDate.now(ZoneOffset.UTC).minusDays(30);
  }

  public static LocalDate defaultTo() {
    return LocalDate.now(ZoneOffset.UTC).plusDays(1);
  }

  private List<Transaction> rows(UUID accountId, LocalDate from, LocalDate to) {
    LocalDate safeFrom = from != null ? from : defaultFrom();
    LocalDate safeTo = to != null ? to : defaultTo();
    return transactions.statementRows(accountId, startOf(safeFrom), startOf(safeTo));
  }

  private static Instant startOf(LocalDate date) {
    return date.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /** Renders a one-or-more-page bank statement. All layout is code - no templates to drift. */
  public byte[] renderPdf(Statement statement) {
    Account account = statement.account();
    Map<UUID, String> ibans = ibanMap(statement.rows());
    BigDecimal net = BigDecimal.ZERO;
    for (Transaction tx : statement.rows()) {
      if (account.getId().equals(tx.getToAccountId())) net = net.add(tx.getAmount());
      if (account.getId().equals(tx.getFromAccountId())) net = net.subtract(tx.getAmount());
    }
    BigDecimal closing = account.getBalance();
    BigDecimal opening = closing.subtract(net);

    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      float margin = 48;
      float y = 0;
      PDPage page = null;
      PDPageContentStream cs = null;
      int line = 0;

      java.util.List<String[]> table = new java.util.ArrayList<>();
      table.add(new String[] {"Date", "Description", "Amount (USD)"});
      for (Transaction tx : statement.rows()) {
        boolean in = account.getId().equals(tx.getToAccountId());
        String desc = tx.getMemo() != null ? tx.getMemo()
            : (in ? "Transfer from " + shortIban(ibans.get(tx.getFromAccountId()))
                  : "Transfer to " + shortIban(ibans.get(tx.getToAccountId())));
        table.add(new String[] {
            tx.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
            desc.length() > 52 ? desc.substring(0, 52) : desc,
            (in ? "+" : "-") + tx.getAmount().toPlainString()});
      }

      String[] header = {
          "Northbank - Account statement",
          "IBAN " + account.getIban() + "  ·  " + account.getType().name() + "  ·  " + account.getStatus().name(),
          "Period " + statement.from().atZone(ZoneOffset.UTC).toLocalDate()
              + " to " + statement.to().atZone(ZoneOffset.UTC).toLocalDate(),
          "Opening " + opening.toPlainString() + " USD   ·   Closing " + closing.toPlainString() + " USD",
          "Generated " + Instant.now().atZone(ZoneOffset.UTC).toLocalDate()};

      boolean atPageTop = false;
      for (int i = 0; i < table.size(); i++) {
        if (cs == null || y < 90) {
          if (cs != null) {
            cs.endText();
            cs.close();
          }
          page = new PDPage(PDRectangle.A4);
          doc.addPage(page);
          cs = new PDPageContentStream(doc, page);
          y = page.getMediaBox().getHeight() - margin;
          if (line == 0) {
            for (int h = 0; h < header.length; h++) {
              cs.beginText();
              cs.setFont(h == 0 ? bold : plain, h == 0 ? 16 : 10);
              cs.newLineAtOffset(margin, y);
              cs.showText(safe(header[h]));
              cs.endText();
              y -= h == 0 ? 24 : 14;
            }
            y -= 8;
          }
          cs.beginText();
          atPageTop = true;
          line = 1;
        }
        String[] row = table.get(i);
        cs.setFont(i == 0 ? bold : plain, 9);
        if (atPageTop) {
          cs.newLineAtOffset(margin, y);
          atPageTop = false;
        } else {
          cs.newLineAtOffset(0, -13);
        }
        y -= 13;
        cs.showText(safe(String.format("%-12s %-52s %12s", row[0], row[1], row[2])));
      }
      if (cs != null) {
        cs.endText();
        cs.close();
      }
      doc.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Could not render statement PDF", ex);
    }
  }

  private static String shortIban(String iban) {
    return iban == null ? "external" : "..." + iban.substring(Math.max(0, iban.length() - 6));
  }

  private static String safe(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      sb.append(c > 127 ? '?' : c);
    }
    return sb.toString();
  }
}
