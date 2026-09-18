# **Software Engineer/Intern (Backend, Java) — Assignment**

**Simplify Money · Ledger Sync**

Submission window: **48 hours.**

You are encouraged to use Claude, Copilot, Cursor or any AI tool. We use them too. What we're evaluating is what you do with the output — whether you can tell good from plausible, and whether the thing actually works when we run it.

One thing worth knowing before you start: **most of this assignment is not in this document.** It is in the data and in the code we hand you. Read both.

---

## **In one paragraph**

Simplify Money tells a user where their money went. To do that, something has to read the bank SMS and bank emails on their phone and turn them into a ledger the user can trust. We are giving you that service, half-finished, with a live incident open against it and 

 real-shaped messages to run through it. You are going to finish it, fix the incident, and move its storage onto a document store.

---

## **Task 0 — Mandatory (everyone)**

1. Download the Simplify Money app and complete your profile.  
2. Refer it to **3 friends**.  
3. Collect brutally honest feedback from them and compile a **one-pager** with screenshots — including specifically what they *didn't* like.  
4. Follow us on [LinkedIn](https://www.linkedin.com/company/simplify-money/), [Instagram](https://www.instagram.com/simplifymoney.ai), [YouTube](https://www.youtube.com/@simplify_money).

## **Task 1 — Use the real thing first**

In the app, connect a data source and open **Track**. Let it read your messages and watch what it does with them.

Then write a **teardown** (1–2 pages):

* Screenshot each stage — the permission request, the sync, the list it produced  
* Find at least **two transactions it got wrong or missed**, and show them  
* Where did you trust it? Where did you not?  
* Three things you'd change, and why

Do this before you write any code. Task 2 is the engine behind that screen, and you will build the wrong thing if you have not seen what it is for.

---

# **Task 2 — The build**

Fork this repository:

**github.com/simplify-money/ledger-sync-seed**

Its README repeats the specification below and is the source of truth if the two ever disagree.

## **What goes in**

fixtures/corpus-a.jsonl — 522 lines, one JSON object per line, each a single SMS or email exactly as the phone uploaded it:

{"message\_id":"m-00004-9c11ae","channel":"sms","sender":"AD-HDFCBK-S",

 "received\_at":"2026-07-04T07:19:00+05:30","device\_id":"dev-3f1a90c47b21",

 "body":"Rs.5 debited from a/c \*\*4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}

Three accounts appear in it: two savings accounts and one credit card.

message\_id is assigned by the phone when it uploads. It identifies **that upload**, not the underlying message.

## **What comes out**

Three files, written by report \<dir\>.

### **1\. ledger.json — one entry per real transaction**

{"transactions": \[

  {"account\_last4":"4821","occurred\_at":"2026-07-04T20:24:00+05:30",

   "direction":"debit","amount":"2499.50","category":"SPEND",

   "merchant":"AMAZON PAY","source\_message\_ids":\["m-00087-1a2b3c","m-00089-77de01"\]}

\]}

* occurred\_at — when the **bank says the transaction happened**, in IST. Not when the message arrived.  
* amount — always two decimal places, always positive. direction carries the sign.  
* source\_message\_ids — every message that evidences this one transaction. There is often more than one.  
* merchant — whatever the bank called the other side. Not graded.

### **2\. summary.json — per-account totals**

{"accounts": {

  "4821": {"spend":"87068.38","income":"101340.83",

           "micro\_count":52,"micro\_total":"2357.51",

           "transferred\_out":"25000.00","transferred\_in":"6000.00"}

}}

### **3\. reconciliation.json — anything your ledger cannot account for**

{"discrepancies": \[

  {"account\_last4":"4821","occurred\_at":"...","amount":"...","note":"..."}

\]}

We are not telling you how to find these, or whether there are any. Working out what "cannot account for" means here, and what in the data lets you check it, is part of the task.

## **The four categories**

Every transaction gets exactly one.

| Category | What it means |
| :---- | :---- |
| SPEND | Money left the user and is gone |
| INCOME | Money arrived and is theirs |
| MICRO | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed one by one |
| TRANSFER | One leg of the user moving their own money **between their own accounts**. The money really moved, but it is neither spending nor income — counting it as either inflates both sides |

spend is the sum of SPEND only. It does not include MICRO or TRANSFER. income likewise excludes TRANSFER.

MICRO still appears individually in ledger.json. It is summary.json where it collapses to a count and a total.

## **Your checkpoint**

fixtures/corpus-a-totals.json gives you the expected transaction count, the opening and closing balance, and the category totals per account. No row-level answers. Use it to check yourself.

---

# **Task 3 — The incident**

incident/INC-2026-09-11.md is open against this service. A customer is being shown a ₹92,213.10 spend on a water can. They spent ₹5.

Reproduce it, find the cause, work out how many transactions it affects and what decides whether a given message is affected, fix it, and add the test that fails before your fix and passes after.

Then **five lines** for the incident channel: what broke, how you found it, who was affected, why it cannot recur. Five lines, not a page.

The existing test suite is green, and was green the whole time this was happening in production. Work out why before you write your own test.

---

# **Task 4 — Move the ledger to a document store**

The ledger is coming off SQL. **DynamoDB preferred, MongoDB acceptable** — your choice, and say why. It must come up from your docker compose up.

DocumentStore in the seed declares the only three queries this service makes:

1. one account's transactions for one month, newest first  
2. running totals per category for an account  
3. given a message id, which transaction did it produce

Design your documents so the engine serves these directly. **We are not going to tell you what a document should look like** — that decision is the exercise.

For each of the three, report **how many items the engine examined versus how many it returned, at 100,000 transactions**. DynamoDB gives you ScannedCount and Count; MongoDB gives you totalDocsExamined and nReturned. Six numbers, in your README.

Then two pieces of machinery:

* **Backfill** — moves what is already in SQL across. The SQL store has been running without a uniqueness guarantee for a long time, and this will be run more than once, including after a partial failure.  
* **ConsistencyChecker** — proves the two stores agree and names precisely where they do not. We will run yours against a document store we have deliberately altered. It has to find what we changed. A checker that compares row counts will not.

---

## **Non-negotiables**

**Every real transaction appears exactly once.** Not zero times, not twice.

**Nothing that isn't a transaction gets in.** The corpus contains messages that mention money and are not transactions. Some of them are hostile.

**Re-running changes nothing.** Feed the same corpus twice, or a corpus that overlaps one you have already processed, and the ledger must be identical. Our harness does exactly this.

**Money is exact.** Two decimal places. Your totals agree with corpus-a-totals.json to the paisa.

**Your numbers are honest.** If your ledger does not reconcile, say so and say why. A submission whose numbers match because they were made to match is worse than one that does not match and explains itself. We check for this specifically.

**A transaction is traceable.** We will pick a row from your ledger and ask which messages produced it. Your system answers that from its own records.

**NormalizedTxn, Category and NormalizedTxnContractTest are frozen.** Do not edit them. Everything behind them is yours.

---

## **We run a corpus you haven't seen**

After you submit we run your service against a **different corpus** — same shape, same kinds of messages, different data. Build for that, not for corpus-a.

---

## **Running it**

A deployed URL is welcome but **not required** — hosting costs money and that is not what we are testing.

Required:

* **One documented command** brings everything up, document store included  
* ./verify.sh still works  
* A **walkthrough recording, max 5 minutes** (unlisted YouTube or Loom): boot → corpus ingested → three files produced → totals reconciled → the incident reproduced and fixed → backfill → consistency checker → tests passing

---

## **Write-up**

A README.md that gets us running in under five minutes, plus:

**Decision log — 8 to 10 entries.** What you decided, what you rejected, why. The interesting ones are where you were not sure, and where the data changed your mind. If it reads generated, that is a fail — we are reading it for your judgment, not your prose.

**What the data made you decide.** The corpus forces choices this document does not cover. List them. What you saw, what you chose, what you would do with more time.

**Your document model,** and the six examined-vs-returned numbers.

**AI disclosure.** Which tools, for what. Then one concrete case where the AI's output was **wrong or worse than what you wrote** — both versions, and the difference. Everyone uses these tools; we want to see you driving.

**What's unfinished.** Ran out of time is fine. Pretending it works is not.

---

## **Submitting**

Within **48 hours**, email **talent.acquisition@simplifymoney.in** — subject:

Simplify Money | Software Engineer/Intern \- BE | \<Your Name\>

* Public repo link (or zip) — **real commit history**, not one squashed commit  
* Walkthrough recording link  
* ledger.json, summary.json, reconciliation.json for corpus-a  
* Your five-line incident note  
* README.md, decision log, AI disclosure, unfinished list  
* Task 1 Track teardown  
* Task 0 one-pager (referrals \+ feedback)  
* Updated CV

**Do not open a pull request** against the seed repository. It is closed automatically and is not counted as a submission.

Two rounds follow if you are shortlisted: a technical round, then a founder's round.

---

## **Questions?**

Ask them. Email us. Guessing when you could have clarified is a worse signal than asking.

Good luck — we're looking forward to running your code.

