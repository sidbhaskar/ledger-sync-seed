**What broke:** The transaction parser failed to detect whole-number amounts (like `Rs.5`), causing it to extract the larger "available balance" amount instead.
**How we found it:** Customer reports of inflated debits led to tracing the parsed values back to the regex pattern in `Amounts.java`.
**Who was affected:** Any user who had a whole-number transaction (no decimal places) where the bank also included their available balance in the same SMS.
**Why it cannot happen again:** The regex in `Amounts.java` has been fixed to make decimals optional (`(\.\d{1,2})?`), and we added `IncidentRegressionTest.java` that asserts whole-number amounts are strictly parsed.
