# Ledger Sync Pipeline

This repository contains the completed data pipeline for parsing, reconciling, and storing ledger transactions into both an H2 SQL database and a highly-available DynamoDB store.

## Prerequisites
- **JDK 21**
- **Docker** (for DynamoDB Local)

## Step-by-Step Execution Guide

**1. Start the Database**
Starts DynamoDB Local via Docker and automatically creates the `Transactions` table.
```bash
docker compose up -d
```

**2. Apply SQL Migrations**
Applies Flyway migrations (including the `V3` migration to support stated balances).
```bash
./gradlew run --args="migrate"
```

**3. Ingest Data**
Parses raw SMS messages, deduplicates them, and securely saves them into the local SQL ledger.
```bash
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
```

**4. Generate Reports**
Generates the ledger JSON, summary statistics, and the new **Reconciliation Report**. The reconciliation engine compares our ledger's running balance against the bank's true stated balances captured directly from the SMS checkpoints.
```bash
./gradlew run --args="report submission"
```

**5. Run the DynamoDB Backfill**
Safely streams and deduplicates all transaction data from SQL into the DynamoDB Document Store. This command is fully idempotent.
```bash
./gradlew run --args="backfill"
```

**6. Verify Consistency**
Runs the strict auditor tool. It performs a rigorous field-level comparison (amount, direction, category, merchant, and sourceMessageIds) across the SQL and DynamoDB stores to ensure 100% data integrity.
```bash
./gradlew run --args="check"
```

**7. Automated Tests & Verification Script**
Run the full test suite (including the regression test for `INC-2026-09-11`) and the pipeline validation script.
```bash
./gradlew test
./verify.sh
```

## Key Architectural Decisions
- **Frozen Models Maintained**: The core `NormalizedTxn` record was left untouched. The reconciliation logic operates on a non-intrusive `LedgerRow` wrapper.
- **Idempotent DynamoDB Integration**: The DynamoDB implementation uses exact `ACCOUNT#...` primary keys to ensure that any re-runs of the Backfill or Ingest pipeline will never create duplicate documents.
- **$O(1)$ Message Lookups**: Configured a `GSI1` inverted index on the DynamoDB `Transactions` table, enabling instantaneous $O(1)$ lookups by `sourceMessageId`.
