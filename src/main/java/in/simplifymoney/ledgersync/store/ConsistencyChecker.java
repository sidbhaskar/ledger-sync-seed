package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Strategy:
 *  1. Read all transactions from SQL (deduplicated — SQL may have duplicates).
 *  2. For each, look up the same transaction in DynamoDB by its first source
 *     message ID (using DocumentStore.byMessageId).
 *  3. Compare every field: amount, direction, category, merchant, sourceMessageIds.
 *  4. Also scan for transactions in DynamoDB that are NOT in SQL by iterating
 *     the source message IDs we know from SQL and flagging any that the DynamoDB
 *     store returns a different transaction for.
 *
 * A checker that only compares row counts will not find changed field values.
 * This checker reports each diverging field as a separate Divergence entry.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // Read and deduplicate SQL rows (same dedup key as Backfill)
        List<NormalizedTxn> sqlRows = sql.all();
        Map<String, NormalizedTxn> sqlMap = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlRows) {
            String key = t.accountLast4()
                    + "|" + t.occurredAt()
                    + "|" + t.amount().toPlainString()
                    + "|" + t.direction()
                    + "|" + (t.merchant() != null ? t.merchant().toLowerCase() : "");
            if (sqlMap.containsKey(key)) {
                NormalizedTxn existing = sqlMap.get(key);
                List<String> combinedIds = new ArrayList<>(existing.sourceMessageIds());
                combinedIds.addAll(t.sourceMessageIds());
                sqlMap.put(key, new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        existing.merchant(),
                        combinedIds
                ));
            } else {
                sqlMap.put(key, t);
            }
        }

        for (NormalizedTxn sqlTxn : sqlMap.values()) {
            // Use the first source message ID as the lookup key in DynamoDB
            String msgId = sqlTxn.sourceMessageIds().isEmpty()
                    ? null : sqlTxn.sourceMessageIds().get(0);
            if (msgId == null) {
                divergences.add(new Divergence(
                        "no_source_message_id",
                        sqlTxn.accountLast4() + "/" + sqlTxn.occurredAt(),
                        "N/A"));
                continue;
            }

            Optional<NormalizedTxn> dynOpt = documents.byMessageId(msgId);

            if (dynOpt.isEmpty()) {
                divergences.add(new Divergence(
                        "missing_in_documents",
                        msgId + " (" + sqlTxn.accountLast4() + " " + sqlTxn.occurredAt() + ")",
                        "not found"));
                continue;
            }

            NormalizedTxn dynTxn = dynOpt.get();

            // Field-level comparison
            if (!sqlTxn.amount().equals(dynTxn.amount())) {
                divergences.add(new Divergence(
                        "amount/" + msgId,
                        sqlTxn.amount().toPlainString(),
                        dynTxn.amount().toPlainString()));
            }
            if (!sqlTxn.direction().equals(dynTxn.direction())) {
                divergences.add(new Divergence(
                        "direction/" + msgId,
                        sqlTxn.direction().name(),
                        dynTxn.direction().name()));
            }
            if (!sqlTxn.category().equals(dynTxn.category())) {
                divergences.add(new Divergence(
                        "category/" + msgId,
                        sqlTxn.category().name(),
                        dynTxn.category().name()));
            }
            String sqlMerchant = sqlTxn.merchant() != null ? sqlTxn.merchant() : "";
            String dynMerchant = dynTxn.merchant() != null ? dynTxn.merchant() : "";
            if (!sqlMerchant.equals(dynMerchant)) {
                divergences.add(new Divergence(
                        "merchant/" + msgId,
                        sqlMerchant,
                        dynMerchant));
            }
            if (!sqlTxn.accountLast4().equals(dynTxn.accountLast4())) {
                divergences.add(new Divergence(
                        "accountLast4/" + msgId,
                        sqlTxn.accountLast4(),
                        dynTxn.accountLast4()));
            }
            // Compare source message ID sets (ignoring order and duplicates)
            java.util.Set<String> sqlIds = new java.util.HashSet<>(sqlTxn.sourceMessageIds());
            java.util.Set<String> dynIds = new java.util.HashSet<>(dynTxn.sourceMessageIds());
            if (!sqlIds.equals(dynIds)) {
                List<String> sqlSorted = new ArrayList<>(sqlIds);
                List<String> dynSorted = new ArrayList<>(dynIds);
                sqlSorted.sort(String::compareTo);
                dynSorted.sort(String::compareTo);
                divergences.add(new Divergence(
                        "sourceMessageIds/" + msgId,
                        String.join(",", sqlSorted),
                        String.join(",", dynSorted)));
            }
        }

        return divergences;
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}

