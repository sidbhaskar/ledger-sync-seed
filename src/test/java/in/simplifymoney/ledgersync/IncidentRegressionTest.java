package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression test for INC-2026-09-11.
 *
 * Root cause: Amounts.java regex required a decimal point, so "Rs.5" was skipped
 * and the parser matched "Rs.92,213.10" (the available balance) instead.
 *
 * This test FAILED before the fix to Amounts.java (making decimal optional).
 * It PASSES after.
 *
 * The existing test suite was green throughout the incident because no test
 * exercised the case where the transaction amount is a whole number and the
 * message also contains a larger decimal amount (the balance). The existing
 * AmountsTest only verified amounts that already had decimal places.
 */
class IncidentRegressionTest {

    private static final HdfcSmsParser PARSER = new HdfcSmsParser();

    /**
     * The exact message that triggered INC-2026-09-11.
     * Before fix: amount extracted = 92213.10 (the available balance).
     * After fix:  amount extracted = 5.00     (the actual transaction).
     */
    @Test
    void wholeNumberAmountNotMistakenForBalance() {
        RawMessage m = new RawMessage(
                "m-00004-9c11ae",
                "sms",
                HdfcSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-04T07:19:00+05:30"),
                "dev-3f1a90c47b21",
                "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. "
                        + "Avl Bal: Rs.92,213.10. Not you? Call 18002586161");

        Optional<ParsedTxn> result = PARSER.parse(m);

        assertTrue(result.isPresent(), "Parser should have recognised this as a transaction");
        ParsedTxn txn = result.get();

        // The transaction amount must be ₹5.00, NOT the balance ₹92,213.10
        assertEquals(new BigDecimal("5.00"), txn.amount(),
                "Amount should be 5.00 (the debit), not 92213.10 (the balance). "
                        + "This is the root cause of INC-2026-09-11.");

        assertEquals("4821", txn.accountLast4());
        assertEquals("UPI/WATER CAN", txn.merchant().trim());

        // The stated balance should be captured separately as the checkpoint value
        assertEquals(new BigDecimal("92213.10"), txn.statedBalance(),
                "statedBalance should capture Rs.92,213.10 for reconciliation checkpoints");
    }

    /**
     * Variant: same pattern with a non-UPI whole-number debit.
     * Ensures the fix is not limited to "Rs.5" but works for any whole-number amount.
     */
    @Test
    void otherWholeNumberAmountsAreCorrect() {
        RawMessage m = new RawMessage(
                "m-test-001",
                "sms",
                HdfcSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-10T10:00:00+05:30"),
                "dev-test",
                "Rs 250 debited from a/c **4821 on 10-07-26 at 10:00 to BIGBASKET. "
                        + "Avl Bal: Rs.71,500.00. Not you? Call 18002586161");

        Optional<ParsedTxn> result = PARSER.parse(m);
        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("250.00"), result.get().amount(),
                "Whole-number amount Rs 250 should parse as 250.00");
    }
}
