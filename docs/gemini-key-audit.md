# Gemini Key Audit

Audited 2026-09-22, Asia/Bangkok. No credentials changed, committed, revoked or regenerated.

## 1. Config source

Observed by launching a minimal Spring Boot context with the project's real config and spring-dotenv 4.0.0, without application services, databases or scheduled jobs:

- Active profile: dev.
- Selected property: `app.ai.gemini.api-keys`.
- Winning property source: `application-dev.properties`.
- Placeholder: `${GEMINI_API_KEYS:${GEMINI_API_KEY:}}`.
- Resolved variable: `GEMINI_API_KEYS`, supplied by the dotenv `env` property source.
- Number of loaded credentials: 3.
- `.env` also contains a legacy `GEMINI_API_KEY`, prefix `AQ.A`, length 53. It is not appended to the selected list.
- Main/dev/prod config has a reverse fallback for the singular property. This does not merge the two lists.
- No Gemini/Google key overrides found in the inspected process, Windows User or Machine environment.
- No JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, MAVEN_OPTS, SPRING_APPLICATION_JSON, SPRING_CONFIG_LOCATION, SPRING_CONFIG_ADDITIONAL_LOCATION or SPRING_PROFILES_ACTIVE overrides in the audit process.
- Saved IntelliJ APMS run configuration contains no Gemini key environment override. An unsaved IDE environment cannot be inferred from saved XML.
- No `.env.local` found in either workspace; frontend has only `.env.example`. No Gemini usage found in frontend source.
- Dockerfile contains no Gemini key and does not copy `.env`; deployed container environment was not available for inspection.
- No Gradle configuration or additional Maven key configuration found. Maven wrapper configuration contains only wrapper setup.
- `crawler.ai.gemini.api-key` remains in properties, but no Java consumer of that property was found.
- Criterion suggestion providers containing older direct property injection are commented-out source, not active components.

## 2. Loaded credentials

| Credential | Prefix | Length | Selected source |
| --- | --- | --- | --- |
| #1 | AQ.A | 53 | app.ai.gemini.api-keys / GEMINI_API_KEYS |
| #2 | AQ.A | 53 | app.ai.gemini.api-keys / GEMINI_API_KEYS |
| #3 | AQ.A | 53 | app.ai.gemini.api-keys / GEMINI_API_KEYS |

The selected list has no duplicate, blank or dummy entries. No semicolon or embedded whitespace found. All three loaded values have the same prefix; no AIza/mixed list observed. The parser strips surrounding quotes/backticks and whitespace, ignores blanks, and splits only on commas. Added a safe warning for semicolon/newline input; these are not silently treated as delimiters.

## 3. Runtime active credential

The audit Spring runtime selected index 0 (display #1), prefix AQ.A, length 53. `getApiKey()` and `getCurrentKey().value()` refer to that selected list, but differ in availability handling. No running APMS application was identified before the audit; the inspected JetBrains JVM was the IDE compile server. This observation does not establish which key a historical extraction used.

## 4. Company Extraction

`GeminiExtractionProvider` and `CompanyIdentityDetectionService` use `GeminiRequestExecutor`, which obtains `GeminiApiKeyManager.getCurrentKey()` and passes `key.value()` to the HTTP lambda. Company extraction interpolates that argument into the query parameter. Both HTTP clients now have opt-in metadata diagnostics.

## 5. Contract Extraction and other services

| Component | Key method | Credential selection | Live extraction prefix |
| --- | --- | --- | --- |
| GeminiExtractionProvider | Executor -> getCurrentKey | active available key | Not replayed |
| CompanyIdentityDetectionService | Executor -> getCurrentKey | active available key | Not replayed |
| GeminiPartnerContractExtractionProvider | getApiKey | current active index, bypasses executor | Not replayed |
| ContractExtractionService (text/multimodal) | getApiKey -> trim | captured active key before local retry loop | Not replayed |
| FinancialExtractionService (text/Excel) | getApiKey -> trim | captured active key before local retry loop | Not replayed |
| GeminiAssistantProvider | getApiKey | captured active key before local retry loop | Not replayed |
| OwnerGeminiAssistantProvider | getApiKey | captured active key before local retry loop | Not replayed |
| AiExtractionService | getApiKey | mock-mode check, not an HTTP send | N/A |

All listed services share the manager in an application context. The audited context's list is entirely AQ.A, but table rows are not claims of separately observed live business requests. Contract and Financial text/multimodal requests send the key in both query and x-goog-api-key header. Their endpoint uses v1beta; Company/PartnerContract/Assistant/Identity use v1.

## 6. HTTP call

At 09:28:19 local time, the opt-in audit probe executed this real flow:

`Boot config -> GeminiApiKeyManager -> GeminiRequestExecutor -> operation.execute(key.value()) -> RestClient -> HTTP interceptor -> Gemini`

- Credential #1: prefix AQ.A, length 53.
- Endpoint: `https://generativelanguage.googleapis.com/v1/models/gemini-3.6-flash:generateContent`.
- Query credential observed on the actual outgoing HttpRequest: AQ.A, length 53.
- Header credential: absent for this probe, matching the Company query-auth pattern.
- Response: HTTP 200 at 09:28:22.
- Request: tiny synthetic prompt `Reply OK`, maxOutputTokens 16, no user document sent.
- Response body intentionally not logged. There was no error response body to diagnose.

This is a successful live authentication/generation probe, not an 81-page extraction or proof of remaining daily/minute quota. The previous 429 response cannot be reconstructed from this successful call.

## 7. Conclusion

The local Spring runtime and real HTTP probe actually used AQ.A. This prefix was accepted by the configured endpoint with HTTP 200. Other deployments and historical requests remain unknown. Prefix alone is not a validity check.

## 8. Problems found

- Several services bypass the executor, so its concurrency, retry and failover policies do not cover the whole application.
- `getApiKey()` does not consult the unavailable set; a direct caller can receive an unavailable active key.
- Current manager resets all unavailable keys when all are unavailable, without cooldown. 429 still causes immediate failover, so transient quota exhaustion can cause repeated attempts across credentials.
- Legacy fallback configuration obscures whether a property originally came from the singular or plural variable; runtime property-source diagnostics make this visible.
- Existing service error logs can emit raw response bodies or exception stacks containing credential-bearing URIs. The added diagnostics never log these values; they do not sanitize all pre-existing logging paths.
- `.env` is already tracked by Git. It was not changed or staged during this audit.

## 9. Required fixes and diagnostics

Implemented: DEBUG-only startup property-source and credential metadata, pre-executor metadata, actual HTTP query/header metadata and response status for every active Gemini client, delimiter warning, parser and HTTP secrecy tests, opt-in local Boot/runtime probe.

Enable locally with the command-line argument:

```text
--logging.level.com.apms.domain.ai.service.provider=DEBUG
```

At the normal INFO level, credential prefixes are not logged. No production default was changed. Diagnostics omit response bodies and query strings.

Remaining recommended fixes: unify direct HTTP callers under the executor, replace 429 lockout/reset with bounded cooldown, sanitize existing HTTP error logs, and remove secret files from version-control tracking under a separate explicit change. No credential rotation/deletion was performed.

Verification: 25 existing executor tests; 5 new manager tests; 1 new mocked HTTP diagnostic test; 1 opt-in local runtime test; 7 partner-contract tests; 9 contract-service tests passed (48 distinct tests). The live probe received 200. `git diff --check` passed. Existing Maven duplicate-jsoup warning remains.

Config-only audit (no external HTTP):

```powershell
.\mvnw.cmd '-Dtest=GeminiLocalConfigurationAuditTest' '-Dgemini.local.audit=true' test
```

Adding `-Dgemini.local.http=true` explicitly enables a tiny live generation request and may use API quota. Neither audit runs in the default test suite without its opt-in property.
