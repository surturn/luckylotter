# LuckLotter / Customer Cadence — MVP Development Improvement & Build Brief

## Purpose

This document is a development brief for the current Customer Cadence / retention-intelligence MVP.

The goal is **not** to turn the product into a full CRM, marketing automation platform, loyalty platform, or enterprise analytics suite.

The goal is to make the existing MVP commercially credible enough to:

1. ingest transaction history from CSV/Excel;
2. learn individual customer purchasing cadence;
3. detect meaningful cadence disruption;
4. prioritize customers worth attempting to recover;
5. record the intervention made;
6. measure whether the customer returned and what revenue followed;
7. produce a clear retention/revenue diagnostic;
8. support a paid pilot without requiring POS integrations.

The commercial strategy is being developed in parallel. Engineering should therefore prioritize **proof of economic value, trustworthiness of metrics, and demoability** over feature volume.

---

# 1. Current Product Understanding

The current product has four primary areas:

- Overview
- Flagged Customers
- Import
- Trigger Settings

The core logic is:

`Transaction Data -> Customer History -> Learn Purchasing Cadence -> Detect Rhythm Break -> Flag Customer -> Intervention/Offer -> Customer Returns -> Measure Result`

The system primarily learns cadence from purchase frequency. A customer needs a minimum history before a meaningful rhythm can be calculated.

The current UI already exposes:

- customers monitored;
- customers who are currently quiet;
- offers sent;
- customers who came back;
- customer status breakdown;
- how far customers are past their rhythm;
- historical flags vs customers who came back;
- sensitivity settings;
- minimum/maximum flagging thresholds;
- repeat-offer cooldown controls;
- total offer ceilings;
- offer type/value.

CSV/Excel is currently the intended data-entry path. POS integrations should remain a later phase.

---

# 2. Product Principle

## Do not build a bigger product yet.

The MVP should become better at answering four questions:

### Detection
**Who is drifting from their normal purchasing behaviour?**

### Prioritization
**Which drifting customers are worth trying to recover first?**

### Intervention
**What action did the business take?**

### Measurement
**Did the customer return, and what economic value followed?**

Everything below should be evaluated against these four questions.

If a proposed feature does not materially improve one of them, defer it.

---

# 3. Highest-Priority Improvements

## P0 — Fix metric consistency and cohort definitions

This is the highest priority before commercial demos.

The current overview screenshot contains numbers that do not obviously reconcile:

- "12 of 30 flagged customers visited again"
- status breakdown: 12 came back, 18 still quiet, 5 not enough data = 35
- customers monitored = 28

These may represent different cohorts or time windows, but the interface does not currently explain that.

### Required change

Every major metric must have a clear definition and denominator.

For example:

> **40% recovered**
> 12 of 30 flagged customers returned within 14 days of intervention.

And supporting metrics should use the same clearly defined cohort where applicable.

### Implementation requirements

Create explicit concepts in the data/model layer for:

- flag event;
- intervention/offer event;
- return event;
- measurement window;
- cohort membership;
- current customer status.

Do not calculate dashboard cards independently using subtly different filters.

Create a single source of truth for dashboard metrics.

### Acceptance criteria

A developer should be able to answer:

- How many customers were monitored during the selected period?
- How many were eligible for cadence detection?
- How many were flagged?
- How many received an intervention?
- How many returned?
- What counts as "returned"?
- Within what time window?
- What is the denominator for the recovery percentage?

The UI should make these definitions understandable without requiring a developer explanation.

---

# 4. P0 — Add Economic / Revenue Measurement

This is the most important missing commercial capability.

The product currently proves customer movement but does not strongly prove financial impact.

The buyer ultimately cares about recovered revenue and the cost of intervention.

## Add to the MVP

Track, where the imported transaction data supports it:

- transaction amount;
- customer total historical spend;
- average transaction value;
- number of purchases;
- normal purchase interval;
- current days since last purchase;
- cadence deviation;
- revenue after intervention;
- intervention/discount value;
- estimated net recovered revenue.

### Suggested dashboard additions

Add a section such as:

**Retention impact**

- Revenue from recovered customers
- Number of recovered customers
- Intervention cost
- Net recovered revenue
- Average recovered customer value

If the data is sufficient, calculate:

`Net Recovered Revenue = Revenue attributable to recovered customers - Intervention Cost`

Do NOT call this "incremental revenue" unless there is a valid control-group methodology.

### Important wording

Until causal testing exists, use language such as:

> "Revenue from customers who returned after intervention"

rather than:

> "Revenue generated by the intervention"

or:

> "Revenue recovered because of the system."

The distinction matters.

---

# 5. P0 — Improve Flagged Customers Into the Main Action Screen

The dashboard tells the user that customers are drifting, but the actual commercial value should become obvious on the Flagged Customers page.

The page should answer:

> "Who should I act on today?"

## Recommended table fields

- Customer
- Last purchase
- Normal cadence
- Current gap
- Days overdue
- Cadence deviation / risk level
- Total spend
- Average transaction value
- Purchase count
- Preferred/recent product if available
- Previous interventions
- Previous return after intervention
- Current recommended action
- Action status

Example:

| Customer | Normal rhythm | Current gap | Value | Risk | Action |
|---|---:|---:|---:|---|---|
| Customer A | 6 days | 13 days | $1,240 | High | Contact |
| Customer B | 9 days | 17 days | $840 | High | Contact |
| Customer C | 7 days | 11 days | $310 | Medium | Review |

The exact labels can change with UX testing.

## Prioritization logic

Do not rank customers only by lateness.

A useful MVP score can combine:

- cadence deviation;
- customer value;
- recency;
- historical response to intervention.

For example, conceptually:

`Priority = Cadence Risk × Customer Value × Intervention Opportunity`

The exact mathematical formula should be treated as an implementation decision and validated with realistic data.

Do not claim that the score is an ML prediction unless it actually is.

---

# 6. P0 — Make Intervention Tracking Explicit

The current product has offer settings, but the MVP needs to distinguish:

**offer configured**

from

**offer actually sent**

and:

**customer returned after offer**

## Add an intervention event

Each intervention should store at minimum:

- customer ID;
- date/time;
- intervention type;
- offer value;
- campaign/source if applicable;
- status;
- whether the customer returned;
- return date;
- revenue from subsequent transaction(s), where measurable.

Possible intervention types for MVP:

- percentage discount;
- fixed discount;
- no-discount reminder;
- manual/custom action.

Do not build a full messaging platform yet.

The business can manually execute the intervention while the product records what happened.

This allows a managed-service or pilot model without requiring SMS/WhatsApp/email integrations.

---

# 7. P0 — Make the Diagnostic Report a First-Class Output

The existing product can already analyze approximately six months of transaction history.

Turn that capability into a defined product output.

## Retention Diagnostic

The MVP should be capable of producing a concise report containing:

### Executive summary

- customers analyzed;
- customers with sufficient history;
- customers showing cadence disruption;
- high-value customers at risk;
- customers who returned after previous interventions;
- major retention opportunity.

### Customer behaviour

- purchase frequency distribution;
- typical purchase intervals;
- repeat-customer distribution;
- cadence segments.

### At-risk customers

- customer;
- normal cadence;
- current gap;
- customer value;
- risk/priority;
- recommended next action.

### Intervention performance

- number flagged;
- number contacted/offered;
- number returned;
- return rate;
- revenue associated with returning customers;
- intervention cost where available.

### Opportunity

Where data supports a reasonable estimate, show:

> Estimated opportunity from currently drifting customers

The methodology must be visible.

Do not manufacture a "recoverable revenue" number from weak assumptions.

---

# 8. P1 — Strengthen the Import Experience

CSV/Excel is not a weakness for the MVP. It can be positioned as:

> **No integration required to start.**

But the import flow must feel reliable.

## Required import capabilities

The import process should validate:

### Required fields

At minimum, determine whether the dataset contains:

- customer identifier;
- transaction date/time;
- transaction amount.

Optional but valuable:

- product/item;
- product category;
- store/location;
- transaction ID;
- customer name/contact identifier.

## Add an import preview

Before processing:

- show detected columns;
- allow mapping;
- show sample rows;
- show row count;
- show date range;
- show unique customers;
- show transaction count;
- highlight invalid rows.

Example:

> 18,421 transactions detected  
> 2,804 customers detected  
> Date range: Jan 1 – Jun 30  
> 97.8% valid rows

Then require confirmation.

## Error handling

Do not silently discard malformed data.

Show:

- invalid date rows;
- missing customer IDs;
- invalid amounts;
- duplicate transaction IDs if applicable.

Allow the user to download or inspect an error report if practical.

---

# 9. P1 — Add Data Health / Coverage Information

Cadence analysis depends heavily on data quality.

The dashboard should eventually communicate confidence/coverage.

For example:

> 2,804 customers imported  
> 1,941 have enough purchase history for cadence analysis  
> 863 have insufficient history

This is better than hiding uncertainty.

Use clear statuses:

- Enough data
- Learning
- Insufficient data
- Flagged
- Returned
- Quiet

The current UI already uses "Not enough data"; preserve that concept.

---

# 10. P1 — Make Cadence Explainable

The product's strongest conceptual advantage is that it compares a customer against their own rhythm.

Make this extremely visible.

For each flagged customer, show something like:

> Usually visits every **6 days**  
> Last visit was **13 days ago**  
> **7 days beyond normal rhythm**

This is more persuasive than a generic "at risk" label.

The user should be able to understand why the system flagged someone without needing to trust a black-box AI explanation.

---

# 11. P1 — Preserve and Improve Trigger Settings

The current trigger-settings approach is good and should remain.

Current concepts include:

- sensitivity;
- never flag before;
- always flag by;
- minimum time between offers;
- repeat threshold;
- maximum total offers;
- rolling-day budget;
- offer type/value.

## Improvement

Add short "what this means" explanations.

For example:

> **Balanced sensitivity**
> A customer who normally visits every 7 days is flagged after approximately 11 quiet days.

The system should show a live example when settings change.

Example:

> At this setting, a customer with a 6-day cadence would be flagged after approximately X days.

This makes the configuration useful to non-technical operators.

---

# 12. P1 — Add Time-Window / Cohort Selection

The dashboard currently shows historical activity.

Make reporting periods explicit.

Useful MVP options:

- Last 7 days
- Last 30 days
- Last 8 weeks
- Custom period

But be careful:

The cadence model itself should not be accidentally reset simply because the dashboard reporting window changes.

Separate:

**model/history period**

from:

**reporting period.**

---

# 13. P1 — Add a Small "Why This Customer?" Detail View

Clicking a flagged customer should reveal a simple timeline.

Example:

### Customer A

Purchase history:

- Jan 3
- Jan 9
- Jan 15
- Jan 21
- Jan 27

Normal cadence: ~6 days

Expected next purchase: Feb 2

Current date: Feb 10

Status: **8 days beyond normal**

Intervention:

Feb 10 — 15% offer

Return:

Feb 13 — $42 purchase

This is extremely useful in demos because it makes the system understandable.

---

# 14. P1 — Add Product Preference Only If Data Already Supports It

The product concept includes products/items most frequently purchased.

If product-level data exists in the import:

Show:

- most frequently purchased item/category;
- recent item/category;
- item/category associated with returning customers.

Potential future use:

> "Customer normally buys X."

Then an intervention can be more relevant.

However:

**Do not build recommendation-engine complexity yet.**

Simple historical preference is enough for MVP.

---

# 15. P2 — Add Controlled Experimentation

This should be built after the basic measurement system works.

The long-term goal is to establish whether interventions actually improve retention.

For example:

- flagged customers receiving an offer;
- flagged customers receiving no offer/control;
- compare return rates.

Eventually:

`Incremental Lift = Treatment Return Rate - Control Return Rate`

This is much more commercially powerful than simply showing "came back after an offer."

But do not fake statistical confidence.

For the simple MVP, build the data model so an experiment/control group can be added later.

---

# 16. P2 — Prepare for Multiple Intervention Types

Do not make the product fundamentally a discount engine.

The current percentage-off setting is useful for MVP validation, but the product should conceptually support:

- no incentive;
- reminder;
- percentage discount;
- fixed discount;
- loyalty reward;
- product-specific offer;
- manually defined intervention.

The immediate implementation can remain simple.

The important thing is not to hard-code the product's identity around "25% off."

---

# 17. P2 — Multi-Location Awareness

Only build this if target ICP research indicates multi-location businesses are a strong early segment.

Potential future structure:

`Organization -> Locations -> Customers -> Transactions`

This would allow:

- location comparison;
- cadence patterns by location;
- intervention performance by location.

Do not build a complicated enterprise hierarchy before customer validation.

---

# 18. Do NOT Build These Yet

Explicitly defer:

- POS integrations;
- Shopify/Square/Toast/etc. integrations;
- WhatsApp API automation;
- SMS infrastructure;
- email marketing;
- full CRM;
- loyalty programme;
- customer messaging inbox;
- computer vision;
- CCTV integrations;
- advanced predictive ML;
- LLM-generated marketing campaigns;
- enterprise SSO;
- complex permissions;
- dozens of analytics dashboards;
- mobile app;
- elaborate billing system.

The current product is sufficient to validate whether businesses will pay for retention intelligence.

---

# 19. Recommended MVP Architecture Direction

Keep the existing architecture unless there is a concrete technical problem.

The MVP needs clean domain concepts more than additional infrastructure.

Recommended conceptual entities:

### Organization

- id
- name
- timezone
- currency
- settings

### Customer

- id
- organization_id
- external_customer_id
- name/contact where available

### Transaction

- id
- organization_id
- customer_id
- timestamp
- amount
- product/item
- category
- location
- external_transaction_id

### CadenceProfile

- customer_id
- purchase_count
- average_interval
- median_interval if supported
- last_purchase
- expected_next_purchase
- current_gap
- cadence_status
- confidence/data sufficiency

### FlagEvent

- customer_id
- flagged_at
- reason
- cadence_at_flag
- gap_at_flag
- priority/risk
- status

### Intervention

- customer_id
- flag_event_id
- type
- value
- sent_at
- status

### ReturnEvent

- customer_id
- intervention_id
- transaction_id
- returned_at
- transaction_amount
- measurement_window

These names are conceptual. Adapt them to the existing codebase rather than creating unnecessary duplicate models.

---

# 20. Data Processing Requirements

The cadence engine should remain deterministic and explainable for the MVP.

Minimum logic:

1. group transactions by customer;
2. sort chronologically;
3. calculate intervals between purchases;
4. require a minimum number of purchases;
5. establish a baseline cadence;
6. calculate current gap;
7. compare current gap against configured sensitivity;
8. create a flag when threshold is crossed;
9. enforce offer cooldown/budget rules;
10. record intervention;
11. detect subsequent purchase;
12. associate the return with the intervention according to a defined measurement window.

### Important

Avoid using only a simple arithmetic mean if the data is noisy enough to make it misleading.

Evaluate whether median or a robust cadence calculation is more appropriate.

Do not over-engineer this into a sophisticated ML model yet.

The objective is:

> **reliable, explainable, useful.**

---

# 21. Currency and Internationalization

Because the intended market is global:

Do not hard-code KES into the product.

Organization-level settings should support:

- currency;
- timezone;
- date format;
- locale where practical.

The analytics engine should work with transaction amounts without assuming a particular currency.

For early pilots, currency conversion is unnecessary.

Report revenue in the business's native transaction currency.

---

# 22. Demo / Sample Data

Create a reliable demo dataset representing a realistic repeat-purchase business.

The dataset should contain:

- several hundred or several thousand customers;
- repeat transactions;
- different purchasing cadences;
- high-value and low-value customers;
- customers who are still active;
- customers who have gone quiet;
- customers who returned after intervention;
- enough history to demonstrate the cadence engine.

Do not use obviously fake/random numbers in the final commercial demo.

The dashboard should tell one coherent story from the same underlying dataset.

---

# 23. Product Copy Improvements

The UI should avoid generic "AI" language unless technically necessary.

Prefer:

> **Know when customers are drifting from their normal buying rhythm.**

over:

> AI-powered customer analytics.

Prefer:

> **Customers currently drifting**

over:

> At-risk AI segments.

Prefer:

> **12 of 30 flagged customers returned**

over:

> 40% retention improvement.

Prefer:

> **7 days beyond normal rhythm**

over:

> High churn probability.

The product should communicate observable behaviour, not exaggerated prediction.

---

# 24. Commercially Important Dashboard Direction

The eventual overview should tell this story:

## Retention overview

### Customers drifting
**18**

Customers currently outside their normal purchasing rhythm.

### High-value customers drifting
**6**

Customers with meaningful historical value who are overdue.

### Returned after intervention
**12 / 30**

Flagged customers who purchased again within the defined measurement window.

### Revenue from returned customers
**$X**

Transaction revenue associated with customers who returned.

### Intervention cost
**$Y**

Discount/incentive cost recorded.

### Net value
**$Z**

Revenue minus recorded intervention cost.

Then:

**Who needs attention?**

Top high-priority customers.

Then:

**How is the programme performing?**

Historical flags, interventions and returns.

This makes the dashboard commercially oriented instead of merely analytical.

---

# 25. Minimum Feature Set for the "Sellable MVP"

The MVP should be considered ready for serious paid-pilot testing when it can reliably do all of the following:

- [ ] Import CSV/Excel transaction data
- [ ] Validate and preview imported data
- [ ] Identify unique customers
- [ ] Calculate customer purchase intervals
- [ ] Establish individual cadence
- [ ] Handle insufficient data
- [ ] Detect cadence disruption
- [ ] Apply configurable sensitivity
- [ ] Prevent excessive repeat flagging
- [ ] Prioritize flagged customers
- [ ] Show why each customer was flagged
- [ ] Show customer value
- [ ] Record intervention
- [ ] Detect/record return
- [ ] Associate return with intervention
- [ ] Calculate return rate with a clearly defined denominator
- [ ] Show revenue from returning customers
- [ ] Show intervention cost where available
- [ ] Generate a retention diagnostic
- [ ] Keep all dashboard metrics internally consistent
- [ ] Support different currencies
- [ ] Provide a coherent demo dataset

---

# 26. What "Done" Means

Do not define completion as:

> "All pages look good."

Define completion as:

> **A business can provide six months of transaction data, understand which customers are drifting from their normal behaviour, identify the customers worth acting on, record an intervention, observe returns, and understand the resulting economic outcome without needing an analyst.**

That is the MVP.

---

# 27. Suggested Development Sequence

## Phase 1 — Trust

1. Audit current data model and cadence calculations.
2. Fix dashboard metric inconsistencies.
3. Establish explicit cohort/measurement definitions.
4. Add automated tests around metric calculations.
5. Validate edge cases.

## Phase 2 — Actionability

6. Improve flagged-customer table.
7. Add customer detail/timeline.
8. Add customer-value metrics.
9. Add prioritization.

## Phase 3 — Measurement

10. Add intervention event tracking.
11. Add return-event attribution.
12. Add revenue/intervention-cost calculations.
13. Update dashboard around economic outcomes.

## Phase 4 — Data quality

14. Improve CSV/Excel import mapping.
15. Add validation and preview.
16. Add data coverage/health indicators.

## Phase 5 — Diagnostic

17. Build the retention diagnostic/report output.
18. Ensure report numbers match dashboard numbers.
19. Make methodology/definitions visible.

## Phase 6 — Demo readiness

20. Create coherent demo dataset.
21. Run end-to-end test from import to return measurement.
22. Test the product using several different business profiles.
23. Fix confusing UX.
24. Freeze the MVP for commercial validation.

---

# 28. Development Guardrail

The developer/agent should ask before introducing a feature that materially expands scope.

Use this test:

### Does this feature help us:

**A. Detect**

**B. Prioritize**

**C. Intervene**

**D. Measure**

If not, it probably belongs after market validation.

The current business strategy is to validate demand before significant additional engineering investment. The product should therefore become **commercially measurable, not feature-heavy**.

---

# 29. What We Are NOT Assuming Yet

The development team should not assume:

- restaurants are the final ICP;
- cafés are the final ICP;
- the product is SaaS-first;
- discounts are the best intervention;
- POS integrations are required;
- AI/ML is required for the product to sell;
- a specific country is the target market;
- a specific pricing model is final.

Those decisions are being investigated separately through ICP and market validation.

Engineering should build the underlying MVP so it can support the strongest commercial direction once the market evidence is collected.

---

# 30. Final Development Objective

The objective is not:

> Build the most sophisticated retention platform.

It is:

> **Build the smallest credible system that can demonstrate that identifying customers who are drifting from their normal buying rhythm can create measurable economic value for a business.**

If the market responds positively, the next engineering phase can focus on integrations, automation, experimentation, richer interventions and scale.

Until then:

**Do not build the castle. Prove someone wants the house.**
