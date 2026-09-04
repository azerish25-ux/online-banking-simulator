package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountService;
import com.bank.platform.common.Brand;
import com.bank.platform.common.Money;
import com.bank.platform.accounts.AccountRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatementService {

  private final AccountService accounts;
  private final AccountRepository accountRepository;
  private final TransactionRepository transactions;
  private final long maxRows;

  public StatementService(
      AccountService accounts,
      AccountRepository accountRepository,
      TransactionRepository transactions,
      @Value("${app.statement.max-rows:5000}") long maxRows) {
    this.accounts = accounts;
    this.accountRepository = accountRepository;
    this.transactions = transactions;
    this.maxRows = maxRows;
  }

  public record Statement(Account account, List<Transaction> rows, Instant from, Instant to) {}

  @Transactional(readOnly = true)
  public Statement customerStatement(String email, UUID accountId, LocalDate from, LocalDate to) {
    Account account = accounts.accountDetail(email, accountId);
    LocalDate safeFrom = from != null ? from : defaultFrom();
    LocalDate safeTo = to != null ? to : defaultTo();
    return new Statement(account, rows(accountId, safeFrom, safeTo), startOf(safeFrom), startOf(safeTo));
  }

  @Transactional(readOnly = true)
  public Statement adminStatement(UUID accountId, LocalDate from, LocalDate to) {
    Account account = accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    LocalDate safeFrom = from != null ? from : defaultFrom();
    LocalDate safeTo = to != null ? to : defaultTo();
    return new Statement(account, rows(accountId, safeFrom, safeTo), startOf(safeFrom), startOf(safeTo));
  }

  @Transactional(readOnly = true)
  public Map<UUID, String> ibanMap(List<Transaction> rows) {
    Set<UUID> ids = rows.stream()
        .flatMap(tx -> Stream.of(tx.getFromAccountId(), tx.getToAccountId()))
        .filter(value -> value != null)
        .collect(Collectors.toSet());
    return accountRepository.findAllById(ids).stream()
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
    Instant fromInstant = startOf(safeFrom);
    Instant toInstant = startOf(safeTo);
    // Guard before loading: a statement for a huge window must not pull every
    // matching row into memory just to render (or to stream out as CSV).
    long matching = transactions.historyCount(accountId, fromInstant, toInstant);
    if (matching > maxRows) {
      throw new TransferValidationException(
          "Statement covers " + matching + " transactions (max " + maxRows
              + "); narrow the date range");
    }
    return transactions.statementRows(accountId, fromInstant, toInstant);
  }

  private static Instant startOf(LocalDate date) {
    return date.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /** Renders a one-or-more-page bank statement. All layout is code - no templates to drift. */
  public byte[] renderPdf(Statement statement) {
    Account account = statement.account();
    // Only POSTED rows ever moved money: HELD/CANCELLED rows are intents, so
    // they must not shift the opening balance or appear as movements.
    List<Transaction> posted = statement.rows().stream()
        .filter(tx -> tx.getStatus() == TxStatus.POSTED)
        .toList();
    Map<UUID, String> ibans = ibanMap(statement.rows());
    BigDecimal net = BigDecimal.ZERO;
    for (Transaction tx : posted) {
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

      List<String[]> table = new ArrayList<>();
      table.add(new String[] {"Date", "Description", "Amount"});
      for (Transaction tx : posted) {
        boolean in = account.getId().equals(tx.getToAccountId());
        String desc = tx.getMemo() != null ? tx.getMemo()
            : (in ? "Transfer from " + shortIban(ibans.get(tx.getFromAccountId()))
                  : "Transfer to " + shortIban(ibans.get(tx.getToAccountId())));
        table.add(new String[] {
            tx.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
            truncate(desc, 52),
            (in ? "+" : "-") + Money.plain(tx.getAmount())});
      }

      String[] header = {
          Brand.PDF_STATEMENT_HEADER,
          "IBAN " + account.getIban() + "  ·  " + account.getType().name() + "  ·  " + account.getStatus().name(),
          "Period " + statement.from().atZone(ZoneOffset.UTC).toLocalDate()
              + " to " + statement.to().atZone(ZoneOffset.UTC).toLocalDate(),
          "Opening " + Money.usd(opening) + "   ·   Closing " + Money.usd(closing),
          "Generated " + Instant.now().atZone(ZoneOffset.UTC).toLocalDate()};

      // One page at a time, each line placed at an absolute position (no
      // cumulative text-matrix offsets to go wrong). The statement masthead
      // prints on the first page; the Date/Description/Amount column heading
      // repeats on EVERY page so later pages keep their context.
      String columnHeading = String.format("%-12s %-52s %12s", table.get(0)[0], table.get(0)[1], table.get(0)[2]);
      boolean firstPage = true;
      int row = 1; // table.get(0) is the column-heading row itself
      do {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDPageContentStream cs = new PDPageContentStream(doc, page);
        float y = page.getMediaBox().getHeight() - margin;
        if (firstPage) {
          for (int h = 0; h < header.length; h++) {
            textLine(cs, h == 0 ? bold : plain, h == 0 ? 16 : 10, margin, y, safe(header[h]));
            y -= h == 0 ? 24 : 14;
          }
          y -= 8;
          firstPage = false;
        }
        textLine(cs, bold, 9, margin, y, columnHeading);
        y -= 13;
        while (row < table.size() && y >= 90) {
          String[] data = table.get(row);
          textLine(cs, plain, 9, margin, y,
              safe(String.format("%-12s %-52s %12s", data[0], data[1], data[2])));
          y -= 13;
          row++;
        }
        cs.close();
      } while (row < table.size());
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

  /** One text line at an absolute position - no cross-line state to corrupt. */
  private static void textLine(PDPageContentStream cs, PDType1Font font, float size,
      float x, float y, String text) throws IOException {
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(x, y);
    cs.showText(text);
    cs.endText();
  }

  /** Truncates on code-point boundaries so a cut never splits a surrogate pair. */
  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    int[] points = value.codePoints().limit(max).toArray();
    return new String(points, 0, points.length);
  }
}
