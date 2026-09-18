# Ledger Sync — Codebase Analysis & Assignment Breakdown

## Project Overview

`ledger-sync-seed` is a Simplify Money service that reads **bank SMS and emails** from a phone and turns them into a **trustable financial ledger** for a user.

**Input:** `fixtures/corpus-a.jsonl` — 522 raw messages (SMS + emails)
**Output:** 3 JSON files — `ledger.json`, `summary.json`, `reconciliation.json`

---

## Codebase Architecture

```
App.java           — CLI: migrate | ingest <file> | report <dir>
SelfCheck.java     — Runs pipeline in-memory, compares against expected totals

parse/
  Parsers.java           — Routes message to correct parser
  HdfcSmsParser.java     — Parses HDFC Bank SMS (V1, V2, CARD formats) ✅ Working
  IciciSmsParser.java    — Parses ICICI Bank SMS (only 1 of 2+ formats) ⚠️ Incomplete
  EmailParser.java       — Stub: throws UnsupportedOperationException ❌ Not implemented
  Amounts.java           — Extracts transaction amount + balance from message body
  Dates.java             — Parses bank date strings as IST

ingest/
  IngestService.java     — Reads corpus, parses each message, saves one txn per message

store/
  LedgerStore.java         — Interface
  SqlLedgerStore.java      — H2/JDBC implementation (no uniqueness constraint!)
  InMemoryLedgerStore.java — In-memory store (for SelfCheck/tests)
  DocumentStore.java       — Interface (NOT IMPLEMENTED)
  Backfill.java            — SQL→Doc store migration (NOT IMPLEMENTED)
  ConsistencyChecker.java  — Proves stores agree (NOT IMPLEMENTED)

report/
  Reports.java  — Generates ledger.json, summary.json; reconciliation NOT IMPLEMENTED
```

---

## The 3 Accounts in the Corpus

| Account | Bank | Type | Opening Balance | Closing Balance | Expected Txns |
|---------|------|------|----------------|----------------|---------------|
| **4821** | HDFC | Savings | ₹48,211.40 | ₹41,126.34 | 146 |
| **9075** | ICICI | Savings | ₹31,904.75 | ₹51,210.63 | 91 |
| **3310** | HDFC | Credit Card | (counted against 4821) | — | — |

---

## Expected Totals (corpus-a-totals.json)

| Metric | Account 4821 | Account 9075 |
|--------|-------------|-------------|
| Transactions | 146 | 91 |
| Spend | ₹87,068.38 | ₹39,058.11 |
| Income | ₹101,340.83 | ₹41,450.33 |
| Micro count | 52 | 45 |
| Micro total | ₹2,357.51 | ₹2,086.34 |
| Transferred out | ₹25,000.00 | ₹6,000.00 |
| Transferred in | ₹6,000.00 | ₹25,000.00 |

---

## The 4 Categories

| Category | Definition |
|----------|-----------|
| **SPEND** | Money left the user and is gone |
| **INCOME** | Money arrived and is theirs |
| **MICRO** | A UPI debit of ₹100 or less. Still spending, but rolled up |
| **TRANSFER** | One leg of moving own money between own accounts |

- `spend` = sum of SPEND only (excludes MICRO and TRANSFER)
- `income` = sum of INCOME only (excludes TRANSFER)
- `micro_total` = sum of MICRO
- MICRO still appears individually in ledger.json; collapsed in summary.json

---

## The Incident — INC-2026-09-11

### What Happened
A customer on account `4821` sees a **₹92,213.10 spend** on a water can. They actually paid **₹5**.

### The Triggering Message (corpus line 24)
```json
{"message_id":"m-00022-2f118b","channel":"sms","sender":"AD-HDFCBK-S",
 "body":"Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}
```

### Root Cause — `Amounts.java:18`
```java
private static final Pattern AMOUNT =
    Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})");
```

The regex requires a **decimal point** (`\.[0-9]{2}`) in the amount. When the message says `Rs.5 debited` (no decimal), it **skips** `Rs.5` and matches `Rs.92,213.10` (the available balance) instead.

### app.log Evidence
```
parse.Amounts  msg=m-00004-9c11ae body_head="Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10."
parse.Amounts  msg=m-00004-9c11ae amount_extracted=92213.10
```

### Blast Radius
This affects every message where the amount is a whole number (no paise) but the stated balance has decimal places. In `corpus-a.jsonl`, this affects at least:
- `m-00022-2f118b` — `Rs.5` debited, extracted as `92213.10`
- `m-legacy-0041` in seed data — same bug

### Why Existing Tests Passed
The test suite (`NormalizedTxnContractTest`, `AmountsTest`) doesn't test this edge case. They test that amounts have 2 decimal places and that `Amounts.first()` works for `Rs.` prefixed amounts with decimals — but not the case where the first `Rs.` figure is a whole number and the second (balance) is the one matched.

### ICICI Parser Also Has This Issue
```java
// IciciSmsParser.java:37
BigDecimal amount = Amounts.first(m.body());
```
ICICI messages like `Acct XX9075 is debited with Rs.25 on 04/07/2026 07:54. Info: UPI/STATIONERY. Avl Bal Rs.49,857.25` also have whole-number amounts that will be matched against the balance instead.

---

## All Issues To Fix (8 Items, Priority Order)

### 1. Amount Extraction Bug (The Incident) 🔥
- **File:** `src/main/java/.../parse/Amounts.java:18`
- **Problem:** Regex `\.[0-9]{2}` requires decimal point in amount
- **Fix:** Make decimal optional: `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{1,2})?)`
- **Also need:** Handle whole-number amounts that should become `.00`

### 2. EmailParser Is A Stub ❌
- **File:** `src/main/java/.../parse/EmailParser.java`
- **Problem:** Throws `UnsupportedOperationException`. 56+ emails dropped.
- **Impact:** Same transactions appear in both SMS and email — needed for deduplication
- **Fix:** Parse HDFC and ICICI email formats

**HDFC Email Format:**
```
Date: Wed, 01 Jul 2026 09:02:00 +0530
Subject: Transaction alert on your account

Dear Customer,

Your account ending 4821 has been credited with INR 45,000.
Merchant / Remarks: SALARY CREDIT
Transaction reference: 1597155421

This is a system generated email.
```

**ICICI Email Format:** Similar structure with account, amount, merchant, reference.

### 3. ICICI Parser Incomplete ⚠️
- **File:** `src/main/java/.../parse/IciciSmsParser.java`
- **Problem:** Only handles `INR X.XX` format
- **Missing:** `Rs.XX` format: `Dear Customer, Acct XX9075 is debited with Rs.25 on 04/07/2026 07:54`
- **Fix:** Add second regex for `Rs.` prefix

### 4. No Deduplication ❌
- **File:** `src/main/java/.../ingest/IngestService.java:71-74`
- **Problem:** 1 message = 1 transaction. Same transaction appears as both SMS and email.
- **Result:** 323 transactions produced vs 257 expected
- **Fix:** Match transactions on (account + amount + merchant + date window), merge `source_message_ids`

### 5. Categories Wrong ⚠️
- **File:** `src/main/java/.../ingest/IngestService.java:72`
- **Problem:** `DEBIT→SPEND`, `CREDIT→INCOME` only
- **Missing:** MICRO (UPI debit ≤ ₹100), TRANSFER (inter-account transfers)
- **Expected:** 52 micro txns (acct 4821), 45 micro txns (acct 9075)

### 6. Summary Report Wrong ⚠️
- **File:** `src/main/java/.../report/Reports.java`
- **Problem:** Doesn't roll up MICRO or exclude TRANSFER
- **Fix:** Correct summary logic

### 7. Reconciliation Not Implemented ❌
- **File:** `src/main/java/.../report/Reports.java:72-73`
- **Problem:** Throws `UnsupportedOperationException`
- **Fix:** Compare bank-stated balances against ledger-derived balances

### 8. DocumentStore, Backfill, ConsistencyChecker ❌
- **Files:** `DocumentStore.java`, `Backfill.java`, `ConsistencyChecker.java`
- **Problem:** All interfaces with `throw new UnsupportedOperationException`
- **Fix:** MongoDB/DynamoDB implementation with docker compose

---

## Non-Transaction Messages To Filter Out

| Sender | Type | Count | Must ignore |
|--------|------|-------|-------------|
| `BP-DELHVY` | Delivery notifications | 10 | Yes |
| `AX-SWGGYX` | Swiggy order updates | 7 | Yes |
| `VK-ICICIB` | Phishing/spam (fake ICICI) | 5 | Yes |
| `AD-HDFCBK-S` (OTP) | OTP messages | 1+ | Yes |

---

## Message Formats In The Corpus

### HDFC SMS (Working — HdfcSmsParser)

**V1:** `Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10.`
**V2:** Multi-line `Sent/Received ... To/From: ... On: ... A/c: XX4821`
**CARD:** `Rs 1,249.99 spent on HDFC Bank Card x3310 at BLINKIT on 03-07-26 11:51.`

### ICICI SMS (Partial — IciciSmsParser)

**V1 (working):** `Dear Customer, Acct XX9075 is debited with INR 18,000 on 01/07/2026 21:14. Info: NEFT INWARD SELF. Avl Bal Rs.49,882.25`
**V2 (missing):** `Dear Customer, Acct XX9075 is debited with Rs.25 on 04/07/2026 07:54. Info: UPI/STATIONERY. Avl Bal Rs.49,857.25`

### HDFC Email (Not implemented — EmailParser stub)
```
Date: Wed, 01 Jul 2026 09:02:00 +0530
Subject: Transaction alert on your account
Dear Customer,
Your account ending 4821 has been credited with INR 45,000.
Merchant / Remarks: SALARY CREDIT
Transaction reference: 1597155421
```

### ICICI Email (Not implemented — EmailParser stub)
Similar format to HDFC emails.

---

## Transfer Detection

Inter-account transfers between 4821 (HDFC) and 9075 (ICICI):
- **4821 → 9075:** IMPS/P2A/PARAG KAPOOR debits from 4821 + ICICI credits on 9075
- **9075 → 4821:** IMPS/P2A/PARAG KAPOOR debits from 9075 + HDFC credits on 4821
- Expected: `transferred_out=25000.00` + `transferred_in=6000.00` for acct 4821

---

## Current State

| Metric | Current | Expected |
|--------|---------|----------|
| Transactions | 323 | 257 |
| Messages skipped | 199 | — |
| Balance match | Way off | Exact |

---

## Frozen Files (DO NOT EDIT)

- `src/main/java/.../model/NormalizedTxn.java`
- `src/main/java/.../model/Category.java`
- `src/test/.../NormalizedTxnContractTest.java`

---

## Run Commands

```bash
./verify.sh                          # compile + run pipeline, no network
./gradlew test                       # test suite
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

---

## Key Design Decisions Needed

1. **Dedup strategy:** Match on (account + amount + merchant + date window)? Or bank transaction reference from emails?
2. **Transfer detection:** IMPS/P2A with merchant `PARAG KAPOOR` — match debit on one account with credit on the other by amount + date
3. **Email parsing:** HDFC emails have a consistent format; ICICI emails are similar
4. **Document store choice:** DynamoDB vs MongoDB (assignment prefers DynamoDB)
5. **Reconciliation logic:** Compare `statedBalance` from last message vs running balance from transactions
6. **Amount handling:** Make regex handle whole numbers, normalize to 2 decimal places

---

## Suggested Fix Order

1. **Fix Amounts.java regex** — handle whole-number amounts (the incident)
2. **Complete ICICI parser** — catch the `Rs.` prefix format
3. **Implement EmailParser** — parse HDFC and ICICI emails
4. **Add deduplication** — merge duplicate transactions
5. **Add MICRO + TRANSFER categories** — classify correctly
6. **Fix Reports** — correct summary + implement reconciliation
7. **DocumentStore + Backfill + ConsistencyChecker** — the final stretch
