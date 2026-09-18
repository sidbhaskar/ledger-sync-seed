package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Handles two formats:
 * V1: "Dear Customer, Acct XX9075 is debited with INR 22.50 on ..."
 * V2: "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; ..."
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) .*? "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>[^;]+) ref no \\d+\\.");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher v1 = V1.matcher(m.body());
        if (v1.find()) return build(m, v1);

        Matcher v2 = V2.matcher(m.body());
        if (v2.find()) return build(m, v2);

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, Matcher match) {
        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(match.group("when"));
        if (amount == null || at == null) return Optional.empty();

        String dir = match.group("dir");
        Direction d = ("debited".equals(dir) || "Dr".equals(dir))
                ? Direction.DEBIT : Direction.CREDIT;
        return Optional.of(new ParsedTxn(match.group("acct"), at, d, amount,
                match.group("merchant").trim(), Amounts.statedBalance(m.body()),
                m.messageId()));
    }
}
