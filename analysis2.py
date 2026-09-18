import json
from collections import defaultdict
import re

messages = []
with open(r'E:\Simplify Money Assignment\ledger-sync-seed\fixtures\corpus-a.jsonl', 'r', encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if line:
            messages.append(json.loads(line))

body_to_ids = defaultdict(list)
for m in messages:
    body_to_ids[m['body']].append(m['message_id'])

exact_dupes = {b: ids for b, ids in body_to_ids.items() if len(ids) > 1}
print("Bodies with exact duplicates:", len(exact_dupes))
total_exact_dupes = sum(len(ids) - 1 for ids in exact_dupes.values())
print("Extra messages from exact body duplication:", total_exact_dupes)

for body, ids in list(exact_dupes.items())[:3]:
    print("  IDs:", ids)
    print("  Body:", body[:120])
    print()

original_bodies = set()
re_delivered = 0
for m in messages:
    if m['body'] in original_bodies:
        re_delivered += 1
    else:
        original_bodies.add(m['body'])

print("Total messages:", len(messages))
print("Unique bodies:", len(original_bodies))
print("Re-delivered:", re_delivered)

non_txn_senders = ['BP-DELHVY', 'AX-SWGGYX', 'VK-ICICIB']
non_txn_pats = ['OTP for txn', 'pre-approved Personal Loan', 'Avl Bal in a/c']

orig_msgs = []
seen = set()
for m in messages:
    if m['body'] not in seen:
        seen.add(m['body'])
        orig_msgs.append(m)

parseable = 0
nontxn = 0
for m in orig_msgs:
    skip = m['sender'] in non_txn_senders
    if not skip:
        for p in non_txn_pats:
            if p in m['body']:
                skip = True
                break
    if skip:
        nontxn += 1
    else:
        parseable += 1

print("\nOf unique-body msgs:", len(orig_msgs))
print("  Parseable:", parseable)
print("  Non-txn:", nontxn)

orig_sms = sum(1 for m in orig_msgs if m['channel'] == 'sms')
orig_email = sum(1 for m in orig_msgs if m['channel'] == 'email')
print("  SMS:", orig_sms)
print("  Email:", orig_email)

# Count SMS+Email cross-channel pairs among original messages
# The 94 groups found earlier include the re-delivered copies
# Let's see: how many of the 94 groups are real cross-channel pairs vs SMS re-delivers?
print("\n=== BREAKDOWN ===")
print("522 total messages")
print("185 are exact-body re-deliveries (m-003xx through m-005xx)")
print("337 are unique-body messages")
print("  Of those 337: ~20 non-transactional, ~43 emails, ~274 SMS")
print()
print("Of the 274 unique SMS, some are ALSO reported via email")
print("Email-to-SMS duplicates found in unique messages: see below")
