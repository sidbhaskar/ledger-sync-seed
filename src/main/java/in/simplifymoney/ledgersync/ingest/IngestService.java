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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 *    matched on (account, amount, merchant, minute).
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

        // Parse each unique body once
        List<NormalizedTxn> txns = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, RawMessage> e : uniqueBodies.entrySet()) {
            Optional<ParsedTxn> p = parsers.parse(e.getValue());
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            List<String> allIds = bodyToMessageIds.get(e.getKey());
            txns.add(toTransaction(p.get(), allIds));
        }

        // Layer 2: cross-channel dedup — SMS + email for the same transaction
        List<NormalizedTxn> deduped = dedup(txns);

        for (NormalizedTxn t : deduped) {
            store.save(t);
        }

        return new Stats(messages.size(), deduped.size(), skipped);
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
        if (m.startsWith("UPI/")) m = m.substring(4);
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
        Category c = p.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
        return new NormalizedTxn(p.accountLast4(), p.occurredAt(), p.direction(),
                p.amount(), c, p.merchant(), sourceMessageIds.stream().sorted().toList());
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
