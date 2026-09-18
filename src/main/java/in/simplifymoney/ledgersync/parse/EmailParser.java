package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Both HDFC (alerts@hdfcbank.net) and ICICI (alerts@icicibank.com) use the
 * same body template:
 *
 * Date: Wed, 01 Jul 2026 09:02:00 +0530
 * Subject: Transaction alert on your account
 *
 * Dear Customer,
 *
 * Your account ending 4821 has been debited with INR 99.99.
 * Merchant / Remarks: IRCTC
 * Transaction reference: 8085121323
 *
 * This is a system generated email.
 */
public final class EmailParser implements MessageParser {

        private static final Pattern DATE_LINE = Pattern.compile("^Date:\\s*(.+)$", Pattern.MULTILINE);

        private static final Pattern BODY = Pattern.compile(
                        "Your account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) "
                                        + "with (?:INR|Rs\\.?)\\s*(?<amt>[0-9,]+(?:\\.[0-9]{1,2})?)\\.\n"
                                        + "Merchant / Remarks: (?<merchant>[^\n]+)");

        @Override
        public boolean supports(RawMessage m) {
                return "email".equals(m.channel());
        }

        @Override
        public Optional<ParsedTxn> parse(RawMessage m) {
                Matcher dateMatch = DATE_LINE.matcher(m.body());
                if (!dateMatch.find())
                        return Optional.empty();
                OffsetDateTime at = Dates.rfc2822(dateMatch.group(1));
                if (at == null)
                        return Optional.empty();

                Matcher bodyMatch = BODY.matcher(m.body());
                if (!bodyMatch.find())
                        return Optional.empty();

                BigDecimal amount = new BigDecimal(
                                bodyMatch.group("amt").replace(",", "")).setScale(2);
                Direction d = "debited".equals(bodyMatch.group("dir"))
                                ? Direction.DEBIT
                                : Direction.CREDIT;

                return Optional.of(new ParsedTxn(
                                bodyMatch.group("acct"), at, d, amount,
                                bodyMatch.group("merchant").trim(),
                                null,
                                m.messageId()));
        }
}
