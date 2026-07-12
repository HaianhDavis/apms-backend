# Competitor Product-Market Overlap

## Overview
This document specifies the behavior of the `productMarketOverlapScore` automatic proposal in Phase 2B.

## Calculation Methodology
The score is calculated using the Jaccard similarity index between the target company's products/markets and the reference company's products/markets.

### Normalization
All comparison strings are normalized before calculation:
1. Unicode NFC normalization.
2. Conversion to lowercase.
3. Trimming of leading/trailing whitespace.
4. Collapsing of multiple spaces into a single space.

### Product Overlap
Compares `Product.name` from the reference organization against `Product.category` from the target organization to account for variations in naming while targeting the same category.

### Fallbacks and Completeness
If either side (target or reference) has null or empty lists without a manual review decision, a `null` score is proposed along with coverage warnings. Missing components are NOT silently reweighted.
