# Partner Evaluation Data Sufficiency

## Philosophy
In the context of Partner Role Evaluations, data sufficiency is handled uniquely compared to standard extraction pipelines. AI suggestions are treated as advisory components that must be reviewed; they are never treated as official or inherently complete.

### Nullability over Interpolation
- **Missing Data**: When the AI encounters missing information required for a criterion, it is instructed to return `null` for the data and explicitly note the gap using the `missingDataNotes` field.
- **No Hallucination**: Interpolation, extrapolation, or guessing by the AI is strictly prohibited. The system relies on the absence of data (nulls) to trigger review flags for human operators.

### Needs More Data Workflow
If an AI suggestion indicates missing data, or if the human reviewer deems the available data insufficient:
- The human reviewer can explicitly mark the suggestion using the `needs-more-data` endpoint.
- This changes the `RoleEvaluationDraft` status to reflect the need for further human investigation, explicitly requiring manual intervention before the draft can be approved.

Data sufficiency is ultimately an operator decision supported by the AI, rather than an automated business logic gate.
