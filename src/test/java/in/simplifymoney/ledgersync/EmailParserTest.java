package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    private RawMessage email(String sender, String body) {
        return new RawMessage("m-test", "email", sender,
                OffsetDateTime.now(), "dev-1", body);
    }

    @Test
    void parsesHdfcDebitWithInr() {
        String body = "Date: Wed, 01 Jul 2026 18:57:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 4821 has been debited with INR 99.99.\n"
                + "Merchant / Remarks: IRCTC\n"
                + "Transaction reference: 8085121323\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@hdfcbank.net", body));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new BigDecimal("99.99")));
        assertEquals("IRCTC", t.merchant());
        assertNull(t.statedBalance());
    }

    @Test
    void parsesHdfcDebitWithRs() {
        String body = "Date: Thu, 02 Jul 2026 11:04:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 4821 has been debited with Rs.76.49.\n"
                + "Merchant / Remarks: RELIANCE SMART\n"
                + "Transaction reference: 7125305049\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@hdfcbank.net", body));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new BigDecimal("76.49")));
        assertEquals("RELIANCE SMART", t.merchant());
    }

    @Test
    void parsesHdfcCredit() {
        String body = "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 4821 has been credited with INR 45,000.\n"
                + "Merchant / Remarks: SALARY CREDIT\n"
                + "Transaction reference: 1597155421\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@hdfcbank.net", body));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(0, t.amount().compareTo(new BigDecimal("45000.00")));
        assertEquals("SALARY CREDIT", t.merchant());
    }

    @Test
    void parsesIciciDebitWithRs() {
        String body = "Date: Mon, 06 Jul 2026 11:25:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 9075 has been debited with Rs.129.67.\n"
                + "Merchant / Remarks: RELIANCE SMART\n"
                + "Transaction reference: 6576810104\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@icicibank.com", body));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new BigDecimal("129.67")));
        assertEquals("RELIANCE SMART", t.merchant());
    }

    @Test
    void parsesIciciDebitWithInr() {
        String body = "Date: Wed, 08 Jul 2026 09:03:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 9075 has been debited with INR 449.67.\n"
                + "Merchant / Remarks: PVR CINEMAS\n"
                + "Transaction reference: 3499813605\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@icicibank.com", body));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(0, t.amount().compareTo(new BigDecimal("449.67")));
    }

    @Test
    void parsesUtcTimezone() {
        String body = "Date: Sat, 18 Jul 2026 18:50:00 +0000\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 4821 has been debited with INR 412.67.\n"
                + "Merchant / Remarks: UBER INDIA\n"
                + "Transaction reference: 4190129089\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@hdfcbank.net", body));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(0, t.amount().compareTo(new BigDecimal("412.67")));
        assertEquals("UBER INDIA", t.merchant());
        assertEquals(0, t.occurredAt().getOffset().getTotalSeconds());
    }

    @Test
    void parsesAmountWithCommas() {
        String body = "Date: Thu, 09 Jul 2026 15:07:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 4821 has been debited with Rs.2,499.50.\n"
                + "Merchant / Remarks: BIGBASKET\n"
                + "Transaction reference: 8142330747\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@hdfcbank.net", body));
        assertTrue(result.isPresent());
        ParsedTxn t = result.get();
        assertEquals(0, t.amount().compareTo(new BigDecimal("2499.50")));
    }

    @Test
    void statedBalanceIsNullForEmails() {
        String body = "Date: Wed, 01 Jul 2026 18:57:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 4821 has been debited with INR 99.99.\n"
                + "Merchant / Remarks: IRCTC\n"
                + "Transaction reference: 8085121323\n\n"
                + "This is a system generated email.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@hdfcbank.net", body));
        assertTrue(result.isPresent());
        assertNull(result.get().statedBalance());
    }

    @Test
    void rejectsSmsMessages() {
        RawMessage sms = new RawMessage("m-sms", "sms", "AD-HDFCBK-S",
                OffsetDateTime.now(), "dev-1",
                "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19.");
        assertFalse(parser.supports(sms));
    }

    @Test
    void rejectsNonTransactionalEmail() {
        String body = "Date: Mon, 01 Jul 2026 10:00:00 +0530\n"
                + "Subject: Your monthly statement\n\n"
                + "Dear Customer,\n\n"
                + "Please find your monthly statement attached.";
        Optional<ParsedTxn> result = parser.parse(
                email("alerts@hdfcbank.net", body));
        assertFalse(result.isPresent());
    }
}
