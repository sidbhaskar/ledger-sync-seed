package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IciciSmsParserTest {

    private final IciciSmsParser parser = new IciciSmsParser();

    private RawMessage sms(String body) {
        return new RawMessage("m-test", "sms", IciciSmsParser.SENDER,
                OffsetDateTime.now(), "dev-1", body);
    }

    @Test
    void parsesV1DebitWithInr() {
        Optional<ParsedTxn> result = parser.parse(sms(
                "Dear Customer, Acct XX9075 is debited with INR 22.50 on 01/07/2026 10:22. "
                        + "Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25 -ICICI Bank"));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new java.math.BigDecimal("22.50")));
        assertEquals("UPI/VEGETABLE VENDOR", t.merchant());
        assertEquals(0, t.statedBalance().compareTo(new java.math.BigDecimal("31882.25")));
    }

    @Test
    void parsesV1CreditWithInr() {
        Optional<ParsedTxn> result = parser.parse(sms(
                "Dear Customer, Acct XX9075 is credited with INR 18,000 on 01/07/2026 21:14. "
                        + "Info: NEFT INWARD SELF. Avl Bal Rs.49,882.25 -ICICI Bank"));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(0, t.amount().compareTo(new java.math.BigDecimal("18000.00")));
    }

    @Test
    void parsesV1DebitWithRs() {
        Optional<ParsedTxn> result = parser.parse(sms(
                "Dear Customer, Acct XX9075 is debited with Rs.25 on 04/07/2026 07:54. "
                        + "Info: UPI/STATIONERY. Avl Bal Rs.49,857.25 -ICICI Bank"));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new java.math.BigDecimal("25.00")));
    }

    @Test
    void parsesV2DebitWithInr() {
        Optional<ParsedTxn> result = parser.parse(sms(
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; "
                        + "UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new java.math.BigDecimal("5.00")));
        assertEquals("UPI/BARBER", t.merchant());
        assertEquals(0, t.statedBalance().compareTo(new java.math.BigDecimal("52841.30")));
    }

    @Test
    void parsesV2CreditWithInr() {
        Optional<ParsedTxn> result = parser.parse(sms(
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; "
                        + "INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30"));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(0, t.amount().compareTo(new java.math.BigDecimal("1250.33")));
        assertEquals("INTEREST CREDIT", t.merchant());
    }

    @Test
    void parsesV2WithWholeNumberAmount() {
        Optional<ParsedTxn> result = parser.parse(sms(
                "ICICI Bank Acct XX9075 Dr INR 18 on 13-Aug-2026 21:00; "
                        + "UPI/MILK BOOTH ref no 293880518117. BalAvl Rs 51,210.63"));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new java.math.BigDecimal("18.00")));
    }

    @Test
    void ignoresPromotionalMessages() {
        Optional<ParsedTxn> result = parser.parse(sms(
                "Get a pre-approved Personal Loan of upto Rs.5,00,000 at 10.5% p.a. "
                        + "Click to know more. T&C apply. -ICICI Bank"));
        assertTrue(result.isEmpty());
    }

    @Test
    void ignoresSpamSender() {
        RawMessage spam = new RawMessage("m-spam", "sms", "VK-ICICIB",
                OffsetDateTime.now(), "dev-1",
                "Dear Customer your ICICI netbanking will be suspended today.");
        Optional<ParsedTxn> result = parser.parse(spam);
        assertTrue(result.isEmpty());
    }
}
