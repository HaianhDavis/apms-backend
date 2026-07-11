# Deferred Owner Profile Fields

During the architecture alignment for the canonical FPT Owner Organization Profile, several fields and structures were evaluated but deferred for future iterations.

## Deferred Fields

1. **Strategic Direction (strategicDirection)**
   - **Reason**: The initial phase focuses on explicit factual data that can be consistently extracted and merged. Strategic direction is highly subjective and depends on internal leadership alignment, making it difficult to automate via extraction without hallucination risks.

2. **Generic Non-Technology Capabilities**
   - **Reason**: Only specific `technologyCapabilities` (under `innovation`) were included. General operational or non-technology capabilities are often too broadly defined (e.g. "strong leadership") to be cleanly categorized without excessive prompt tuning.

## Kept Conventions

- **Revenue Tier**: Remained at `companySize.revenueTier` (it was not moved to the `financial` extension) to preserve existing sizing logic and prevent breaking changes across the candidate and profile sizing lifecycle.
