package in.simplifymoney.ledgersync.parse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank SMS carry a local date and time and no timezone. The customer, the bank
 * and the branch are all in India, so these are IST.
 */
public final class Dates {

    private Dates() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final List<DateTimeFormatter> SMS_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH));

    /** Parse a local date-time written by a bank, as IST. */
    public static OffsetDateTime ist(String dateAndTime) {
        for (DateTimeFormatter f : SMS_FORMATS) {
            try {
                return LocalDateTime.parse(dateAndTime.trim(), f).atOffset(IST);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    private static final DateTimeFormatter RFC2822_LOCAL =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH);

    private static final Pattern RFC2822_OFFSET = Pattern.compile("(\\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2})\\s+([+-]\\d{4})");

    /** Parse an RFC 2822 date string like "Wed, 01 Jul 2026 09:02:00 +0530". */
    public static OffsetDateTime rfc2822(String dateStr) {
        if (dateStr == null) return null;
        Matcher m = RFC2822_OFFSET.matcher(dateStr.trim());
        if (!m.find()) return null;
        try {
            LocalDateTime local = LocalDateTime.parse(m.group(1), RFC2822_LOCAL);
            String raw = m.group(2);
            int sign = raw.charAt(0) == '+' ? 1 : -1;
            int hours = Integer.parseInt(raw.substring(1, 3));
            int mins = Integer.parseInt(raw.substring(3, 5));
            ZoneOffset offset = ZoneOffset.ofHoursMinutes(sign * hours, sign * mins);
            return local.atOffset(offset);
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return null;
        }
    }
}
