package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Idempotent: safe to run more than once and after a partial failure.
 *  - SQL has no uniqueness constraint — this backfill deduplicates in Java
 *    before writing to DynamoDB.
 *  - DynamoDB PutItem with the same PK+SK overwrites with identical data,
 *    so re-running produces the same result.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    /**
     * Reads all rows from SQL, deduplicates them (the SQL store may contain
     * duplicate rows from when it ran without a uniqueness constraint), then
     * writes each unique transaction to the document store.
     *
     * Deduplication key: (accountLast4, occurredAt, amount, direction, merchant).
     * When merging duplicates the sourceMessageIds lists are unioned.
     */
    public Result run() {
        List<NormalizedTxn> allRows = source.all();
        long read = allRows.size();

        // Deduplicate: group by (account, time, amount, direction, merchant)
        Map<String, NormalizedTxn> seen = new LinkedHashMap<>();
        for (NormalizedTxn t : allRows) {
            String key = t.accountLast4()
                    + "|" + t.occurredAt()
                    + "|" + t.amount().toPlainString()
                    + "|" + t.direction()
                    + "|" + (t.merchant() != null ? t.merchant().toLowerCase() : "");

            seen.merge(key, t, (existing, newer) -> {
                // Merge sourceMessageIds lists, keeping order and deduplicating
                List<String> merged = new ArrayList<>(existing.sourceMessageIds());
                for (String id : newer.sourceMessageIds()) {
                    if (!merged.contains(id)) merged.add(id);
                }
                merged.sort(String::compareTo);
                return new NormalizedTxn(
                        existing.accountLast4(), existing.occurredAt(),
                        existing.direction(), existing.amount(),
                        existing.category(), existing.merchant(),
                        merged);
            });
        }

        long written = 0;
        long skipped = 0;
        for (NormalizedTxn txn : seen.values()) {
            try {
                target.save(txn);
                written++;
            } catch (Exception e) {
                System.err.println("Backfill: failed to write " + txn.accountLast4()
                        + "/" + txn.occurredAt() + ": " + e.getMessage());
                skipped++;
            }
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}

