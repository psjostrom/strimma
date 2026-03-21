# IOB Calculation

Strimma computes Insulin on Board (IOB) using an exponential decay model.

---

## What is IOB?

IOB (Insulin on Board) is the estimated amount of rapid-acting insulin still active in your body from recent boluses. It's critical for:

- **Preventing insulin stacking** — knowing how much insulin is still working before taking more
- **Understanding glucose trends** — active insulin will continue lowering glucose
- **Meal dosing** — accounting for remaining insulin when calculating a new dose

---

## The Model

Strimma uses an **exponential decay model** parameterized by tau (τ), the insulin time constant:

$$
\text{IOB}(t) = \text{dose} \times \left(1 + \frac{t}{\tau}\right) \times e^{-t/\tau}
$$

where:

- `dose` = original bolus in units
- `t` = minutes since the bolus
- `τ` (tau) = time constant for the insulin type (minutes)

### Behavior

- At `t = 0`: IOB = dose (100% active)
- At `t = τ`: IOB = dose × 2 × e⁻¹ ≈ 73.6% of dose
- At `t = 2τ`: IOB ≈ 40.6% of dose
- At `t = 3τ`: IOB ≈ 19.9% of dose
- At `t = 5τ`: IOB ≈ 4.0% of dose (effectively zero)

The lookback window is **5 × τ** — beyond this, remaining IOB is negligible.

---

## Insulin Types

| Insulin | τ (tau) | Lookback Window | Peak |
|---------|---------|----------------|------|
| **Fiasp** | 55 min | 4.6 hours | ~55 min |
| **Lyumjev** | 50 min | 4.2 hours | ~50 min |
| **NovoRapid / Humalog** | 75 min | 6.3 hours | ~75 min |
| **Custom** | DIA × 12 min | DIA hours | DIA / 5 hours |

### Custom DIA

If you select the Custom insulin type, you set the DIA (Duration of Insulin Action) in hours. Tau is derived:

```
τ = DIA (hours) × 60 / 5 = DIA × 12 minutes
```

Example: 5-hour DIA → τ = 60 minutes, lookback = 5 hours.

---

## Computation

Total IOB is the sum of individual IOB contributions from all boluses within the lookback window:

```
Total IOB = Σ IOB(t_i) for each bolus i where t_i < 5 × τ
```

The result is rounded to **1 decimal place** (e.g., 2.3U).

---

## Data Source

IOB requires treatment data from Nightscout. Enable treatment sync in **Settings > Treatments**. Strimma fetches bolus data from the Nightscout treatments endpoint every 5 minutes.

Only treatments with an `insulin` value are included in the IOB calculation. Carb entries and basal rates don't contribute to IOB (basal insulin has a different pharmacokinetic profile).

---

## Choosing Your Insulin Type

Select the insulin that matches what your pump or pen uses for boluses:

- **Fiasp (Faster Aspart)** — ultra-rapid, fastest onset and shortest duration
- **Lyumjev (Lispro-aabc)** — ultra-rapid, similar to Fiasp
- **NovoRapid (Aspart) / Humalog (Lispro)** — standard rapid-acting, slower onset and longer duration
- **Custom** — if your endocrinologist has given you a specific DIA

!!! tip
    If you're unsure, start with the insulin you use and adjust the type if the IOB values don't match your experience. Your diabetes team can advise on the DIA for your insulin.

---

## Limitations

- IOB only accounts for **bolus** insulin, not basal
- The model assumes a standard absorption profile — actual absorption varies with injection site, temperature, activity, and individual physiology
- IOB from manual injections is only reflected if the injection is logged in Nightscout
