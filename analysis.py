import json
import re
from collections import defaultdict, Counter

messages = []
with open(r'E:\Simplify Money Assignment\ledger-sync-seed\fixtures\corpus-a.jsonl', 'r', encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if line:
            messages.append(json.loads(line))

print("Total messages:", len(messages))

sms_count = sum(1 for m in messages if m['channel'] == 'sms')
email_count = sum(1 for m in messages if m['channel'] == 'email')
print("SMS messages:", sms_count)
print("Email messages:", email_count)

# Check for duplicate message_ids
msg_ids = [m['message_id'] for m in messages]
id_counts = Counter(msg_ids)
dupes = {k: v for k, v in id_counts.items() if v > 1}
print("\nDuplicate message_ids:", len(dupes))
for mid, cnt in dupes.items():
    print(f"  {mid}: appears {cnt} times")

def extract_txn_info(msg):
    body = msg['body']
    channel = msg['channel']
    
    # Determine account
    account = None
    if '**4821' in body or 'ending 4821' in body or 'XX4821' in body:
        account = '4821'
    elif 'XX9075' in body or 'ending 9075' in body or '**9075' in body:
        account = '9075'
    
    if account is None:
        return None
    
    # Determine direction
    direction = None
    body_lower = body.lower()
    if any(kw in body_lower for kw in ['debited', 'spent', 'dr ']):
        direction = 'DEBIT'
    elif any(kw in body_lower for kw in ['credited', 'cr ', 'received']):
        direction = 'CREDIT'
    
    if direction is None:
        return None
    
    # Skip non-transactional SMS
    if 'Avl Bal in a/c' in body and 'Download HDFC' in body:
        return None
    if 'OTP for txn' in body:
        return None
    if 'pre-approved Personal Loan' in body:
        return None
    if 'E-mandate!' in body and 'will be deducted' in body:
        return None
    
    # Extract amount
    amount = None
    amounts = re.findall(r'(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{1,2})?)', body)
    
    if channel == 'email':
        if amounts:
            amount = amounts[0].replace(',', '')
    else:
        card_match = re.search(r'(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{1,2})?)\s*spent on HDFC Bank Card', body)
        if card_match:
            amount = card_match.group(1).replace(',', '')
        elif amounts:
            if ('Avl Bal' in body or 'Avl Limit' in body or 'BalAvl' in body or 'Available Balance' in body) and len(amounts) > 1:
                amount = amounts[0].replace(',', '')
            else:
                amount = amounts[0].replace(',', '')
    
    if amount is None:
        return None
    
    try:
        amount_val = float(amount)
    except:
        return None
    
    # Extract merchant
    merchant = None
    m = re.search(r'to\s+(UPI/[A-Z][A-Z /]*?)(?:\.|\s+Avl)', body)
    if m:
        merchant = m.group(1).strip()
    if merchant is None:
        m = re.search(r'to\s+([A-Z][A-Z ]+?)(?:\. Avl|\. Not)', body)
        if m:
            merchant = m.group(1).strip()
    if merchant is None:
        m = re.search(r'at\s+([A-Z][A-Z ]+?)\s+on\s+\d', body)
        if m:
            merchant = m.group(1).strip()
    if merchant is None:
        m = re.search(r'Info:\s+(.+?)\.\s+Avl', body)
        if m:
            merchant = m.group(1).strip()
    if merchant is None:
        m = re.search(r';\s+(.+?)\s+ref no', body)
        if m:
            merchant = m.group(1).strip()
    if merchant is None:
        m = re.search(r'Merchant / Remarks:\s+(.+)', body)
        if m:
            merchant = m.group(1).strip()
    if merchant is None:
        m = re.search(r'(?:To|From):\s+(.+?)\n', body)
        if m:
            merchant = m.group(1).strip()
    if merchant is None:
        m = re.search(r'for\s+(.+?)\.?\s+Avl', body)
        if m:
            merchant = m.group(1).strip()
    
    # Extract transaction date from body
    txn_date = None
    m = re.search(r'on\s+(\d{2}-\d{2}-\d{2})\s+at\s+(\d{2}:\d{2})', body)
    if m:
        parts = m.group(1).split('-')
        txn_date = f"20{parts[2]}-{parts[1]}-{parts[0]}T{m.group(2)}:00+05:30"
    if txn_date is None:
        m = re.search(r'on\s+(\d{2}/\d{2}/\d{4})\s+(\d{2}:\d{2})', body)
        if m:
            parts = m.group(1).split('/')
            txn_date = f"{parts[2]}-{parts[1]}-{parts[0]}T{m.group(2)}:00+05:30"
    if txn_date is None:
        months = {'Jan':'01','Feb':'02','Mar':'03','Apr':'04','May':'05','Jun':'06',
                  'Jul':'07','Aug':'08','Sep':'09','Oct':'10','Nov':'11','Dec':'12'}
        m = re.search(r'on\s+(\d{2})-(\w+)-(\d{4})\s+(\d{2}:\d{2})', body)
        if m:
            mon = months.get(m.group(2), '01')
            txn_date = f"{m.group(3)}-{mon}-{m.group(1)}T{m.group(4)}:00+05:30"
    if txn_date is None:
        months2 = {'Jan':'01','Feb':'02','Mar':'03','Apr':'04','May':'05','Jun':'06',
                   'Jul':'07','Aug':'08','Sep':'09','Oct':'10','Nov':'11','Dec':'12'}
        m = re.search(r'On:\s+(\d{2})\s+(\w+)\s+(\d{2})\s+(\d{2}:\d{2})', body)
        if m:
            mon = months2.get(m.group(2), '01')
            yr = '20' + m.group(3)
            txn_date = f"{yr}-{mon}-{m.group(1)}T{m.group(4)}:00+05:30"
    if txn_date is None:
        months3 = {'Jan':'01','Feb':'02','Mar':'03','Apr':'04','May':'05','Jun':'06',
                   'Jul':'07','Aug':'08','Sep':'09','Oct':'10','Nov':'11','Dec':'12'}
        m = re.search(r'Date:\s+\w+,\s+(\d{2})\s+(\w+)\s+(\d{4})\s+(\d{2}:\d{2}):\d{2}', body)
        if m:
            mon = months3.get(m.group(2), '01')
            txn_date = f"{m.group(3)}-{mon}-{m.group(1)}T{m.group(4)}:00+05:30"
    
    return {
        'message_id': msg['message_id'],
        'channel': channel,
        'account': account,
        'amount': amount_val,
        'merchant': merchant,
        'txn_date': txn_date,
        'received_at': msg['received_at'],
        'body_snippet': body[:150].replace('\n', ' | '),
        'sender': msg['sender']
    }

txns = []
non_txn = []
for msg in messages:
    info = extract_txn_info(msg)
    if info:
        txns.append(info)
    else:
        non_txn.append(msg)

print("\nMessages parsed as transactions:", len(txns))
print("Messages not parsed:", len(non_txn))

print("\n--- Non-transactional messages ---")
for m in non_txn:
    body = m['body'][:120].replace('\n', ' | ')
    print(f"  {m['message_id']} ({m['channel']}, {m['sender']}): {body}")

# Find duplicates
from datetime import datetime

def parse_dt(s):
    if s is None:
        return None
    try:
        return datetime.fromisoformat(s)
    except:
        return None

key_groups = defaultdict(list)
for t in txns:
    if t['txn_date'] is None:
        continue
    merchant_norm = (t['merchant'] or '').upper().strip()
    merchant_clean = re.sub(r'^UPI/', '', merchant_norm).strip()
    key = (t['account'], t['amount'], merchant_clean)
    key_groups[key].append(t)

print("\n" + "="*80)
print("DUPLICATE PAIRS (SMS + Email for same transaction)")
print("="*80)

dup_count = 0
dup_message_ids = set()
examples = []

for key, group in sorted(key_groups.items()):
    sms_msgs = [t for t in group if t['channel'] == 'sms']
    email_msgs = [t for t in group if t['channel'] == 'email']
    
    if sms_msgs and email_msgs:
        for sms in sms_msgs:
            for email in email_msgs:
                dup_count += 1
                dup_message_ids.add(sms['message_id'])
                dup_message_ids.add(email['message_id'])
                
                sms_dt = parse_dt(sms['txn_date'])
                email_dt = parse_dt(email['txn_date'])
                time_diff = None
                if sms_dt and email_dt:
                    time_diff = abs((sms_dt - email_dt).total_seconds()) / 60
                
                rec_dt = parse_dt(sms['received_at'])
                email_rec_dt = parse_dt(email['received_at'])
                recv_diff = None
                if rec_dt and email_rec_dt:
                    recv_diff = abs((rec_dt - email_rec_dt).total_seconds()) / 60
                
                merchant_match = (sms['merchant'] or '').upper().strip() == (email['merchant'] or '').upper().strip()
                
                examples.append({
                    'key': key,
                    'sms': sms,
                    'email': email,
                    'time_diff_min': time_diff,
                    'recv_diff_min': recv_diff,
                    'merchant_exact_match': merchant_match
                })

print("\nTotal duplicate pairs found:", dup_count)
print("Unique message_ids involved in duplicates:", len(dup_message_ids))

# Also find SMS-only duplicates (same account, amount, merchant, same day)
sms_only_groups = defaultdict(list)
for t in txns:
    if t['channel'] != 'sms':
        continue
    if t['txn_date'] is None:
        continue
    day = t['txn_date'][:10]
    merchant_norm = (t['merchant'] or '').upper().strip()
    merchant_clean = re.sub(r'^UPI/', '', merchant_norm).strip()
    key = (t['account'], t['amount'], merchant_clean, day)
    sms_only_groups[key].append(t)

sms_dupes = {k: v for k, v in sms_only_groups.items() if len(v) > 1}
print("\n" + "="*80)
print("SMS-ONLY DUPLICATES (same account+amount+merchant+day)")
print("="*80)
print("Count:", len(sms_dupes))
for key, group in sms_dupes.items():
    print(f"\n  Key: {key}")
    for t in group:
        print(f"    {t['message_id']}: {t['body_snippet'][:100]}")

print("\n" + "="*80)
print("TOP 10 CONCRETE DUPLICATE EXAMPLES")
print("="*80)

for i, ex in enumerate(examples[:10], 1):
    sms = ex['sms']
    email = ex['email']
    print(f"\n--- Example {i} ---")
    print(f"  Account: {ex['key'][0]}, Amount: {ex['key'][1]}, Merchant key: {ex['key'][2]}")
    print(f"  SMS:   {sms['message_id']} | sender={sms['sender']}")
    print(f"         {sms['body_snippet']}")
    print(f"         Txn date: {sms['txn_date']}")
    print(f"  Email: {email['message_id']} | sender={email['sender']}")
    print(f"         {email['body_snippet']}")
    print(f"         Txn date: {email['txn_date']}")
    td = ex['time_diff_min']
    rd = ex['recv_diff_min']
    print(f"  Txn date diff:  {td:.0f} min" if td is not None else "  Txn date diff: N/A")
    print(f"  Receive delay:  {rd:.0f} min" if rd is not None else "  Receive delay: N/A")
    print(f"  Merchant exact: {ex['merchant_exact_match']}")

# Analysis
print("\n" + "="*80)
print("TIME DIFFERENCE ANALYSIS")
print("="*80)

time_diffs = [ex['time_diff_min'] for ex in examples if ex['time_diff_min'] is not None]
if time_diffs:
    print(f"  Txn-time diff (SMS vs Email):")
    print(f"    Min:    {min(time_diffs):.0f} min")
    print(f"    Max:    {max(time_diffs):.0f} min")
    print(f"    Mean:   {sum(time_diffs)/len(time_diffs):.0f} min")
    print(f"    Within 0 min (exact): {sum(1 for d in time_diffs if d == 0)}/{len(time_diffs)}")
    print(f"    Within 5 min: {sum(1 for d in time_diffs if d <= 5)}/{len(time_diffs)}")
    print(f"    Within 30 min: {sum(1 for d in time_diffs if d < 30)}/{len(time_diffs)}")
    print(f"    Within 60 min: {sum(1 for d in time_diffs if d < 60)}/{len(time_diffs)}")
    print(f"    Over 60 min: {sum(1 for d in time_diffs if d >= 60)}/{len(time_diffs)}")

recv_diffs = [ex['recv_diff_min'] for ex in examples if ex['recv_diff_min'] is not None]
if recv_diffs:
    print(f"\n  Email receive delay vs SMS receive:")
    print(f"    Min:    {min(recv_diffs):.0f} min")
    print(f"    Max:    {max(recv_diffs):.0f} min")
    print(f"    Mean:   {sum(recv_diffs)/len(recv_diffs):.0f} min")

# Merchant name mismatches
print("\n" + "="*80)
print("MERCHANT NAME MISMATCHES")
print("="*80)
mismatches = [ex for ex in examples if not ex['merchant_exact_match']]
print(f"  Total mismatched: {len(mismatches)}/{len(examples)}")
for ex in mismatches[:15]:
    sms_m = ex['sms']['merchant']
    email_m = ex['email']['merchant']
    print(f"  SMS: '{sms_m}' vs Email: '{email_m}' (acct={ex['key'][0]}, amt={ex['key'][1]})")

# Amount differences
print("\n" + "="*80)
print("AMOUNT DIFFERENCES BETWEEN SMS AND EMAIL")
print("="*80)
# Should be 0 since we key by amount
print("  (All pairs match on amount by construction of the key)")

# Summary
print("\n" + "="*80)
print("FINAL SUMMARY")
print("="*80)
print(f"  Total raw messages:       {len(messages)}")
print(f"  Messages skipped:         {len(non_txn)}")
print(f"  Total parsed txns:        {len(txns)}")
print(f"  Duplicate pairs (SMS+Email): {dup_count}")
print(f"  Unique message_ids in dupes: {len(dup_message_ids)}")
print(f"  SMS-only duplicate groups:   {len(sms_dupes)}")
print(f"  Expected unique txns:        257")
print(f"  After simple dedup (SMS+Email): {len(txns) - dup_count}")

# Dedup estimate
all_dupe_ids = set()
for ex in examples:
    all_dupe_ids.add(ex['email']['message_id'])
print(f"  Email messages that are duplicates: {len(all_dupe_ids)}")
print(f"  After removing duplicate emails:    {len(txns) - len(all_dupe_ids)}")
