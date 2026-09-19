package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.DynamoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry point.
 *
 * migrate apply db/migration/*.sql
 * ingest <corpus.jsonl> read a corpus into the ledger
 * report <out-dir> write ledger.json, summary.json, reconciliation.json
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir> | backfill | check");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2)
                    throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2)
                    throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.allFresh();
                    var rows = store.allRows();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(rows)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                // Move SQL ledger into DynamoDB Local.
                // Reads DYNAMO_ENDPOINT env var, or defaults to localhost:8000.
                String endpoint = System.getenv().getOrDefault("DYNAMO_ENDPOINT", "http://localhost:8000");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    DynamoDocumentStore dynamo = new DynamoDocumentStore(endpoint);
                    Backfill backfill = new Backfill(store, dynamo);
                    Backfill.Result result = backfill.run();
                    System.out.printf("Backfill complete: read=%d written=%d skipped=%d%n",
                            result.read(), result.written(), result.skipped());
                }
            }
            case "check" -> {
                // Consistency check: compare SQL and DynamoDB stores.
                String endpoint = System.getenv().getOrDefault("DYNAMO_ENDPOINT", "http://localhost:8000");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    DynamoDocumentStore dynamo = new DynamoDocumentStore(endpoint);
                    ConsistencyChecker checker = new ConsistencyChecker(store, dynamo);
                    var divergences = checker.check();
                    if (divergences.isEmpty()) {
                        System.out.println("Consistency check passed: stores agree.");
                    } else {
                        System.out.printf("Consistency check FAILED: %d divergence(s)%n", divergences.size());
                        for (var d : divergences) {
                            System.out.printf("  [%s] sql=%s  dynamo=%s%n", d.what(), d.inSql(), d.inDocuments());
                        }
                        System.exit(1);
                    }
                }
            }
            case "diag2" -> {
                var messages = IngestService.readCorpus(Path.of("fixtures/corpus-a.jsonl"));
                var parsers = new Parsers();
                int skipped = 0;
                for (var m : messages) {
                    var p = parsers.parse(m);
                    if (p.isEmpty()) {
                        skipped++;
                        System.out.printf("SKIPPED: id=%s sender=%s channel=%s body=%.120s%n",
                                m.messageId(), m.sender(), m.channel(), m.body());
                    }
                }
                System.out.println("\nTotal skipped: " + skipped);
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }
}
