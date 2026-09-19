package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * DynamoDB implementation of DocumentStore.
 *
 * Table name: Transactions
 * Primary key: PK (HASH) + SK (RANGE)
 *
 * Document shape:
 *   PK  = "ACCOUNT#<last4>"
 *   SK  = "<occurredAt_ISO>#<amount>#<merchant_hash>"
 *   GSI1PK = "MSGID#<messageId>"    (one item per source message ID)
 *   GSI1SK = "<occurredAt_ISO>"
 *
 * Q1 forAccountMonth  → Query PK=ACCOUNT#4821, SK BETWEEN 2026-07 ... 2026-08
 *    DynamoDB examines only the items in that account's partition for the month range.
 *    At 100k txns across 2 accounts (~50k each), for a month with ~300 txns:
 *    Examined: ~300, Returned: ~300 (range query, no table scan).
 *
 * Q2 categoryTotals   → Query PK=ACCOUNT#4821, projects category+amount.
 *    Scans the full account partition, projects two fields.
 *    At 100k/2 accounts = 50k txns per account:
 *    Examined: ~50,000, Returned: ~50,000 (full partition read, but single partition).
 *    Optimisation: a separate aggregation item (PK=ACCOUNT#4821 SK=TOTALS) updated on write
 *    would reduce this to Examined=1 Returned=1. Not implemented here but noted in README.
 *
 * Q3 byMessageId      → Query GSI1 PK=MSGID#<id>
 *    GSI lookup on a unique key.
 *    Examined: 1, Returned: 1.
 *
 * Six numbers (at 100,000 transactions):
 *   Q1 forAccountMonth  : Examined ~300  / Returned ~300
 *   Q2 categoryTotals   : Examined ~50,000 / Returned ~50,000
 *   Q3 byMessageId      : Examined 1 / Returned 1
 */
public final class DynamoDocumentStore implements DocumentStore {

    static final String TABLE = "Transactions";
    static final String GSI   = "GSI1";

    private final DynamoDbClient client;

    /** Production constructor — uses environment variables / instance profile. */
    public DynamoDocumentStore() {
        this.client = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    /** Local/test constructor — points at DynamoDB Local. */
    public DynamoDocumentStore(String endpointOverride) {
        this.client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpointOverride))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder().build())
                .build();
    }

    // -------------------------------------------------------------------------
    // DocumentStore queries
    // -------------------------------------------------------------------------

    /**
     * Q1: one account's transactions for one month, newest first.
     *
     * Queries the main table with PK = "ACCOUNT#<last4>" and SK between
     * the month's start and end ISO strings. Results are sorted by SK
     * (ISO timestamp) descending using ScanIndexForward=false.
     */
    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String pkVal    = "ACCOUNT#" + accountLast4;
        String skStart  = month.toString();          // "2026-07"
        String skEnd    = month.plusMonths(1).toString(); // "2026-08"

        QueryRequest req = QueryRequest.builder()
                .tableName(TABLE)
                .keyConditionExpression("PK = :pk AND SK BETWEEN :start AND :end")
                .expressionAttributeValues(Map.of(
                        ":pk",    AttributeValue.fromS(pkVal),
                        ":start", AttributeValue.fromS(skStart),
                        ":end",   AttributeValue.fromS(skEnd)))
                .scanIndexForward(false)  // newest first (descending SK)
                .build();

        QueryResponse resp = client.query(req);
        List<NormalizedTxn> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : resp.items()) {
            out.add(fromItem(item));
        }
        return out;
    }

    /**
     * Q2: running totals per category for an account.
     *
     * Queries the full account partition and aggregates in Java.
     * A production system should maintain a running-total item to avoid
     * the full partition read — see class javadoc.
     */
    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        String pkVal = "ACCOUNT#" + accountLast4;
        Map<Category, BigDecimal> totals = new HashMap<>();
        for (Category c : Category.values()) totals.put(c, BigDecimal.ZERO.setScale(2));

        String lastKey = null;
        do {
            QueryRequest.Builder reqBuilder = QueryRequest.builder()
                    .tableName(TABLE)
                    .keyConditionExpression("PK = :pk")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(pkVal)))
                    .projectionExpression("category, amount");
            if (lastKey != null) {
                reqBuilder.exclusiveStartKey(Map.of(
                        "PK", AttributeValue.fromS(pkVal),
                        "SK", AttributeValue.fromS(lastKey)));
            }
            QueryResponse resp = client.query(reqBuilder.build());
            for (Map<String, AttributeValue> item : resp.items()) {
                Category cat = Category.valueOf(item.get("category").s());
                BigDecimal amt = new BigDecimal(item.get("amount").n()).setScale(2);
                totals.merge(cat, amt, BigDecimal::add);
            }
            lastKey = resp.lastEvaluatedKey().isEmpty() ? null
                    : resp.lastEvaluatedKey().get("SK").s();
        } while (lastKey != null);

        return totals;
    }

    /**
     * Q3: which transaction did this message produce?
     *
     * Queries GSI1 where GSI1PK = "MSGID#<messageId>". Because we write one
     * GSI item per source message ID, this is a point lookup: Examined=1, Returned=1.
     */
    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        QueryRequest req = QueryRequest.builder()
                .tableName(TABLE)
                .indexName(GSI)
                .keyConditionExpression("GSI1PK = :gsi1pk")
                .expressionAttributeValues(Map.of(
                        ":gsi1pk", AttributeValue.fromS("MSGID#" + messageId)))
                .limit(1)
                .build();

        QueryResponse resp = client.query(req);
        if (resp.items().isEmpty()) return Optional.empty();
        return Optional.of(fromItem(resp.items().get(0)));
    }

    /**
     * Saves a transaction to DynamoDB.
     *
     * Each source message ID gets its own item so Q3 (byMessageId) is a direct
     * GSI lookup. The first source message ID also carries the full transaction
     * data (PK+SK), while subsequent ones are lightweight index-only items that
     * redirect to the canonical item.
     *
     * Using PutItem (not UpdateItem) makes this idempotent: re-saving the same
     * transaction with the same key overwrites with identical data.
     */
    @Override
    public void save(NormalizedTxn txn) {
        String pkVal  = "ACCOUNT#" + txn.accountLast4();
        // SK: ISO timestamp + amount + first 8 chars of merchant hash for uniqueness
        String skVal  = txn.occurredAt().toString()
                + "#" + txn.amount().toPlainString()
                + "#" + Integer.toHexString(txn.merchant().hashCode());

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK",        AttributeValue.fromS(pkVal));
        item.put("SK",        AttributeValue.fromS(skVal));
        item.put("accountLast4", AttributeValue.fromS(txn.accountLast4()));
        item.put("occurredAt",   AttributeValue.fromS(txn.occurredAt().toString()));
        item.put("direction",    AttributeValue.fromS(txn.direction().name()));
        item.put("amount",       AttributeValue.fromN(txn.amount().toPlainString()));
        item.put("category",     AttributeValue.fromS(txn.category().name()));
        item.put("merchant",     AttributeValue.fromS(txn.merchant() != null ? txn.merchant() : ""));
        item.put("sourceMessageIds", AttributeValue.fromS(String.join(",", txn.sourceMessageIds())));

        // For the first source message ID, write the canonical item with GSI1 keys.
        // For every source message ID, write a GSI-only item so byMessageId works.
        String firstMsgId = txn.sourceMessageIds().isEmpty() ? "unknown" : txn.sourceMessageIds().get(0);
        item.put("GSI1PK", AttributeValue.fromS("MSGID#" + firstMsgId));
        item.put("GSI1SK", AttributeValue.fromS(txn.occurredAt().toString()));

        client.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());

        // Write thin GSI pointer items for any additional source message IDs
        for (int i = 1; i < txn.sourceMessageIds().size(); i++) {
            String msgId = txn.sourceMessageIds().get(i);
            Map<String, AttributeValue> ptr = new HashMap<>();
            ptr.put("PK",     AttributeValue.fromS(pkVal));
            ptr.put("SK",     AttributeValue.fromS(skVal + "#ptr#" + msgId));
            ptr.put("GSI1PK", AttributeValue.fromS("MSGID#" + msgId));
            ptr.put("GSI1SK", AttributeValue.fromS(txn.occurredAt().toString()));
            // Store enough to reconstruct the txn from the pointer
            ptr.put("canonicalSK",   AttributeValue.fromS(skVal));
            ptr.put("accountLast4",  AttributeValue.fromS(txn.accountLast4()));
            ptr.put("occurredAt",    AttributeValue.fromS(txn.occurredAt().toString()));
            ptr.put("direction",     AttributeValue.fromS(txn.direction().name()));
            ptr.put("amount",        AttributeValue.fromN(txn.amount().toPlainString()));
            ptr.put("category",      AttributeValue.fromS(txn.category().name()));
            ptr.put("merchant",      AttributeValue.fromS(txn.merchant() != null ? txn.merchant() : ""));
            ptr.put("sourceMessageIds", AttributeValue.fromS(String.join(",", txn.sourceMessageIds())));
            client.putItem(PutItemRequest.builder().tableName(TABLE).item(ptr).build());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private NormalizedTxn fromItem(Map<String, AttributeValue> item) {
        return new NormalizedTxn(
                item.get("accountLast4").s(),
                java.time.OffsetDateTime.parse(item.get("occurredAt").s()),
                Direction.valueOf(item.get("direction").s()),
                new BigDecimal(item.get("amount").n()).setScale(2),
                Category.valueOf(item.get("category").s()),
                item.get("merchant").s(),
                Arrays.asList(item.get("sourceMessageIds").s().split(",")));
    }

    public DynamoDbClient client() {
        return client;
    }
}
