package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.LedgerRow;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.List;

/**
 * Where transactions live.
 *
 * Note what this interface does NOT promise: that saving the same transaction
 * twice results in one row.
 */
public interface LedgerStore {

    void save(NormalizedTxn txn);

    /**
     * Save a transaction alongside the bank-quoted balance from its source
     * message. Used by IngestService to persist stated balances for later
     * reconciliation. Default delegates to save(txn) so existing
     * implementations (InMemoryLedgerStore) continue to work unchanged.
     */
    default void saveWithBalance(NormalizedTxn txn, BigDecimal statedBalance) {
        save(txn);
    }

    List<NormalizedTxn> all();

    /**
     * Returns all rows including stated_balance. Default wraps all() with
     * null balance so existing implementations don't break.
     */
    default List<LedgerRow> allRows() {
        return all().stream().map(t -> new LedgerRow(t, null)).toList();
    }

    long count();
}
