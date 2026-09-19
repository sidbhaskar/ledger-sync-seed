package in.simplifymoney.ledgersync.model;

import java.math.BigDecimal;

/**
 * A NormalizedTxn paired with the bank-quoted balance from the source message.
 *
 * statedBalance is null when the source message (e.g. an email alert) does not
 * include a balance figure. It is always populated for HDFC and ICICI SMS
 * messages that carry an "Avl Bal" or "BalAvl" field.
 *
 * Used by reconciliation to verify the ledger-derived balance matches the
 * bank's own running total at each checkpoint.
 */
public record LedgerRow(NormalizedTxn txn, BigDecimal statedBalance) {
}
