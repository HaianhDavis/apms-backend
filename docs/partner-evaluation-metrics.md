# Partner Evaluation Metrics

The system uses standard metric definitions to collect objective data before performing subjective algorithmic partner scoring.

## Supported Partner Metrics

Definitions reside in the `PartnerMetricDefinition` enum.

| Metric Key                    | Data Type  | Period Type   | Example Usage |
|-------------------------------|------------|---------------|---------------|
| `nps_score`                   | DECIMAL    | POINT_IN_TIME | Snapshot of partner relationship satisfaction. (-100 to 100) |
| `revenue_generated`           | CURRENCY   | PERIOD        | Track revenue performance over a quarter. |
| `cost_savings_achieved`       | CURRENCY   | PERIOD        | Efficiency optimization tracking. |
| `delivery_on_time_rate`       | PERCENTAGE | PERIOD        | Supply chain or service SLA compliance. |
| `joint_initiatives_completed` | COUNT      | PERIOD        | Quantify mutual strategic projects. |
| `compliance_audit_passed`     | BOOLEAN    | POINT_IN_TIME | Single event representing audit certification. |

These facts (targets vs actuals) act as evidence for calculating the eventual relationship score mapping.
The actual scoring algorithms (e.g., AHP evaluation, normalized performance scoring) will consume these facts in a later phase.
