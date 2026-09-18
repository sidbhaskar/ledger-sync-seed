package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    @Test
    void deduplicatesAcrossChannels() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(
                Path.of("fixtures/corpus-a.jsonl"));

        // 522 raw messages produce exactly 257 unique transactions
        assertEquals(257, stats.transactionsWritten());

        // Account 4821: 146 transactions
        long acct4821 = store.all().stream()
                .filter(t -> "4821".equals(t.accountLast4()))
                .count();
        assertEquals(146, acct4821);

        // Account 9075: 91 transactions
        long acct9075 = store.all().stream()
                .filter(t -> "9075".equals(t.accountLast4()))
                .count();
        assertEquals(91, acct9075);
    }

    @Test
    void mergedSourceMessageIdsGrow() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        // At least some transactions should have more than 1 source message
        // (SMS + email duplicates merged)
        long multiSource = store.all().stream()
                .filter(t -> t.sourceMessageIds().size() > 1)
                .count();
        // We know 43 email pairs + re-delivered copies were merged
        // so many transactions should have 2+ source IDs
        assertEquals(true, multiSource > 0,
                "expected some transactions with merged source message IDs");
    }

    @Test
    void messagesSkippedMatchesNonTransactional() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(
                Path.of("fixtures/corpus-a.jsonl"));

        // 21 skipped = non-transactional SMS
        // (delivery notifications, Swiggy updates, OTP, phishing, promos)
        assertEquals(21, stats.messagesSkipped());
    }
}
