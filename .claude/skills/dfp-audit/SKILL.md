---
name: dfp-audit
description: Scan the codebase for DFP (Decimal64) misuse — raw Java comparison operators used on DFP-encoded long values instead of Decimal64Utils helper methods. Run periodically to catch regressions.
context: fork
allowed-tools:
  - Read
  - Grep
  - Glob
---

# DFP Misuse Audit

DFP encodes decimals as `long` with a special bit layout — raw Java operators (`==`, `!=`, `<`, `>`) produce silently wrong results on these values.

**Exception:** comparing against the null sentinel with `==`/`!=` is correct and intentional:
```java
if (price != TypeConstants.DECIMAL_NULL) { ... }  // OK
if (maxLoss != Decimal64Utils.NULL) { ... }        // OK
```

## Scan

Run both greps (covers both operand orderings):

```
grep -rn -E "(==|!=|<=|>=|<|>)\s*Decimal64Utils\.(ZERO|ONE|TWO|NaN|POSITIVE_INFINITY|NEGATIVE_INFINITY|MAX_VALUE|MIN_VALUE)" --include="*.java" .
```

```
grep -rn -E "Decimal64Utils\.(ZERO|ONE|TWO|NaN|POSITIVE_INFINITY|NEGATIVE_INFINITY|MAX_VALUE|MIN_VALUE)\s*(==|!=|<=|>=|<|>)" --include="*.java" .
```

Filter out any hits involving `NULL` or `DECIMAL_NULL` — those are the sentinel exception above.

## Fix

| Wrong | Correct |
|-------|---------|
| `x == Decimal64Utils.ZERO` | `Decimal64Utils.isZero(x)` |
| `x != Decimal64Utils.ZERO` | `Decimal64Utils.isNonZero(x)` |
| `x > Decimal64Utils.ZERO` | `Decimal64Utils.isPositive(x)` |
| `x < Decimal64Utils.ZERO` | `Decimal64Utils.isNegative(x)` |
| `x >= Decimal64Utils.ZERO` | `!Decimal64Utils.isNegative(x)` |
| `x <= Decimal64Utils.ZERO` | `!Decimal64Utils.isPositive(x)` |
| `x == y` | `Decimal64Utils.equals(x, y)` |
| `x < y` | `Decimal64Utils.isLess(x, y)` |
| `x > y` | `Decimal64Utils.isGreater(x, y)` |
