package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            int microCount = 0;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct))
                    continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT)
                            transferredOut = transferredOut.add(t.amount());
                        else
                            transferredIn = transferredIn.add(t.amount());
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    /**
     * Reconciliation: for each account, replay transactions in time order and
     * check that the running balance matches the bank's stated balance wherever
     * the bank quoted one.
     *
     * A discrepancy entry is written whenever the ledger-derived balance diverges
     * from the bank's stated balance at a given transaction. The stated_balance
     * is then used as the new running balance anchor to prevent cascading errors.
     */
    public static Map<String, Object> reconciliation(List<in.simplifymoney.ledgersync.model.LedgerRow> rows) {
        // Group rows by account, in time order (they are already ordered)
        Map<String, List<in.simplifymoney.ledgersync.model.LedgerRow>> byAccount =
                new java.util.LinkedHashMap<>();
        for (in.simplifymoney.ledgersync.model.LedgerRow row : rows) {
            byAccount.computeIfAbsent(row.txn().accountLast4(), k -> new java.util.ArrayList<>())
                    .add(row);
        }

        List<Object> discrepancies = new java.util.ArrayList<>();

        for (Map.Entry<String, List<in.simplifymoney.ledgersync.model.LedgerRow>> e : byAccount.entrySet()) {
            String acct = e.getKey();
            List<in.simplifymoney.ledgersync.model.LedgerRow> acctRows = e.getValue();

            // Find the first row that has a stated balance — that becomes our anchor.
            BigDecimal running = null;
            for (in.simplifymoney.ledgersync.model.LedgerRow row : acctRows) {
                NormalizedTxn t = row.txn();
                if (running == null) {
                    if (row.statedBalance() != null) {
                        // Work backward: if the first stated balance is after the first txn,
                        // anchor from the stated balance and reverse-apply the transaction.
                        running = switch (t.direction()) {
                            case DEBIT -> row.statedBalance().add(t.amount());
                            case CREDIT -> row.statedBalance().subtract(t.amount());
                        };
                    }
                    // Apply first txn forward once we have an anchor
                    if (running != null) {
                        running = switch (t.direction()) {
                            case DEBIT -> running.subtract(t.amount());
                            case CREDIT -> running.add(t.amount());
                        };
                    }
                    continue;
                }
                // Apply the transaction to the running balance
                running = switch (t.direction()) {
                    case DEBIT -> running.subtract(t.amount());
                    case CREDIT -> running.add(t.amount());
                };
                // If this message quoted a balance, check it
                if (row.statedBalance() != null) {
                    BigDecimal diff = running.subtract(row.statedBalance());
                    if (diff.compareTo(BigDecimal.ZERO) != 0) {
                        Map<String, Object> d = new java.util.LinkedHashMap<>();
                        d.put("account_last4", acct);
                        d.put("occurred_at", t.occurredAt().toString());
                        d.put("amount", t.amount().toPlainString());
                        d.put("merchant", t.merchant());
                        d.put("ledger_balance", running.setScale(2).toPlainString());
                        d.put("bank_stated_balance", row.statedBalance().toPlainString());
                        d.put("difference", diff.setScale(2).toPlainString());
                        d.put("note", "Ledger balance diverges from bank-stated balance by "
                                + diff.setScale(2).toPlainString());
                        discrepancies.add(d);
                        // Anchor to the bank's stated balance to avoid cascading errors
                        running = row.statedBalance();
                    }
                }
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }



    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values())
            out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
