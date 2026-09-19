package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Deduplicates in two layers:
 * 1. Exact body match — re-delivered SMS copies share the same body text.
 * 2. Cross-channel — same transaction reported via both SMS and email,
 * matched on (account, amount, merchant, minute).
 *
 * After deduplication, a transfer resolution pass promotes matching
 * debit/credit
 * pairs across the user's own accounts (identified by IMPS/P2A or similar
 * cross-bank merchant prefixes) from SPEND/INCOME to TRANSFER.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        // Layer 1: exact body dedup — re-delivered copies have identical body text
        Map<String, RawMessage> uniqueBodies = new LinkedHashMap<>();
        Map<String, List<String>> bodyToMessageIds = new LinkedHashMap<>();
        for (RawMessage m : messages) {
            String body = m.body();
            bodyToMessageIds.computeIfAbsent(body, k -> new ArrayList<>()).add(m.messageId());
            uniqueBodies.putIfAbsent(body, m);
        }

        // Keep a map from NormalizedTxn → statedBalance (from the first source message)
        // so we can persist the balance checkpoint alongside each transaction.
        List<NormalizedTxn> txns = new ArrayList<>();
        int skipped = 0;
        Map<NormalizedTxn, java.math.BigDecimal> balances = new java.util.HashMap<>();
        for (Map.Entry<String, RawMessage> e : uniqueBodies.entrySet()) {
            Optional<ParsedTxn> p = parsers.parse(e.getValue());
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            List<String> allIds = bodyToMessageIds.get(e.getKey());
            NormalizedTxn txn = toTransaction(p.get(), allIds);
            txns.add(txn);
            if (p.get().statedBalance() != null) {
                balances.put(txn, p.get().statedBalance());
            }
        }

        // Layer 2: cross-channel dedup — SMS + email for the same transaction
        List<NormalizedTxn> deduped = dedup(txns);

        // Layer 3: transfer resolution — promote matching debit+credit pairs
        // across different accounts to TRANSFER (data-driven, no hardcoded names)
        List<NormalizedTxn> resolved = resolveTransfers(deduped);

        for (NormalizedTxn t : resolved) {
            // Preserve statedBalance if we have one for this (or its pre-merge) txn.
            // After dedup the key object may differ, so match by sourceMessageIds.
            java.math.BigDecimal sb = balances.get(t);
            if (sb == null) {
                // Try to find balance from any of the merged source messages
                for (Map.Entry<NormalizedTxn, java.math.BigDecimal> entry : balances.entrySet()) {
                    if (!java.util.Collections.disjoint(
                            entry.getKey().sourceMessageIds(), t.sourceMessageIds())) {
                        sb = entry.getValue();
                        break;
                    }
                }
            }
            store.saveWithBalance(t, sb);
        }

        return new Stats(messages.size(), resolved.size(), skipped);
    }

    /**
     * Merge transactions that describe the same bank event.
     * Matched on (account, amount, merchant_normalized, occurredAt_minute).
     */
    private List<NormalizedTxn> dedup(List<NormalizedTxn> txns) {
        txns.sort(Comparator.comparing(NormalizedTxn::occurredAt));

        LinkedHashMap<String, NormalizedTxn> seen = new LinkedHashMap<>();
        for (NormalizedTxn t : txns) {
            String key = dedupKey(t);
            seen.merge(key, t, (existing, newer) -> {
                List<String> merged = new ArrayList<>(existing.sourceMessageIds());
                merged.addAll(newer.sourceMessageIds());
                return new NormalizedTxn(
                        existing.accountLast4(), existing.occurredAt(),
                        existing.direction(), existing.amount(),
                        existing.category(), existing.merchant(),
                        merged.stream().sorted().toList());
            });
        }
        return new ArrayList<>(seen.values());
    }

    private String dedupKey(NormalizedTxn t) {
        return t.accountLast4()
                + "|" + t.amount().toPlainString()
                + "|" + normalizeMerchant(t.merchant())
                + "|" + t.occurredAt().toLocalDateTime().withSecond(0).withNano(0)
                + "|" + t.direction();
    }

    private String normalizeMerchant(String merchant) {
        String m = merchant.toUpperCase();
        if (m.startsWith("UPI/"))
            m = m.substring(4);
        return m.trim();
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p, List<String> sourceMessageIds) {
        Category c = categorize(p);
        return new NormalizedTxn(p.accountLast4(), p.occurredAt(), p.direction(),
                p.amount(), c, p.merchant(), sourceMessageIds.stream().sorted().toList());
    }

    private Category categorize(ParsedTxn p) {
        String merchant = p.merchant() == null ? "" : p.merchant().toUpperCase();
        // MICRO: any UPI debit of ₹100 or less.
        // Merchant may be "UPI/MERCHANT" or "UPI MANDATE VERIFY" (no slash) — both
        // start with "UPI".
        if (p.direction() == Direction.DEBIT
                && merchant.startsWith("UPI")
                && p.amount().compareTo(new java.math.BigDecimal("100.00")) <= 0) {
            return Category.MICRO;
        }
        return p.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    /**
     * Data-driven transfer resolution.
     *
     * For every IMPS/P2A (or NEFT-self) debit on account A we look for a
     * matching credit on a DIFFERENT account B with the same payee name and
     * amount, arriving within 10 minutes. Both legs are relabelled TRANSFER.
     *
     * This intentionally does NOT hardcode any payee name — it works for any
     * corpus where the user's own accounts share a common IMPS alias.
     */
    private List<NormalizedTxn> resolveTransfers(List<NormalizedTxn> txns) {
        // Only consider IMPS/P2A legs as candidate transfers.
        // (NEFT INWARD SELF has no matching debit leg in our data, so keep as INCOME.)
        List<NormalizedTxn> candidates = txns.stream()
                .filter(t -> normaliseImpsPayee(t.merchant()) != null)
                .toList();

        // Index credits by (payee, amount) -> list of credit txns
        Map<String, List<NormalizedTxn>> creditIndex = new HashMap<>();
        for (NormalizedTxn t : candidates) {
            if (t.direction() == Direction.CREDIT) {
                String key = normaliseImpsPayee(t.merchant()) + "|" + t.amount().toPlainString();
                creditIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        }

        // For each debit, try to find a matching credit on a DIFFERENT account
        Map<NormalizedTxn, Category> overrides = new HashMap<>();
        for (NormalizedTxn debit : candidates) {
            if (debit.direction() != Direction.DEBIT)
                continue;
            String key = normaliseImpsPayee(debit.merchant()) + "|" + debit.amount().toPlainString();
            List<NormalizedTxn> credits = creditIndex.getOrDefault(key, List.of());
            for (NormalizedTxn credit : credits) {
                if (credit.accountLast4().equals(debit.accountLast4()))
                    continue; // same account
                long minutes = Math.abs(
                        Duration.between(debit.occurredAt(), credit.occurredAt()).toMinutes());
                if (minutes <= 10) {
                    overrides.put(debit, Category.TRANSFER);
                    overrides.put(credit, Category.TRANSFER);
                    break;
                }
            }
        }

        if (overrides.isEmpty())
            return txns;

        return txns.stream().map(t -> {
            Category c = overrides.get(t);
            if (c == null)
                return t;
            return new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                    t.amount(), c, t.merchant(), t.sourceMessageIds());
        }).toList();
    }

    /**
     * Returns the normalised payee name for IMPS/P2A transactions, or null if
     * the merchant is not an IMPS/P2A transfer.
     *
     * Strips the "IMPS/P2A/" prefix, then takes only the first line/segment
     * (HDFC V2 bodies embed ref-nos and newlines after the name).
     */
    private String normaliseImpsPayee(String merchant) {
        if (merchant == null)
            return null;
        String upper = merchant.toUpperCase();
        int idx = upper.indexOf("IMPS/P2A/");
        if (idx < 0)
            return null;
        String after = upper.substring(idx + "IMPS/P2A/".length());
        // Take only the first word-run (stop at newline, ref, or semicolon)
        return after.split("[\n;]")[0].trim();
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {
    }
}
