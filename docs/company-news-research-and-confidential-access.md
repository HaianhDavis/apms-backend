# Company News Research & Confidential Access

This feature enables Staff to research and submit confidential company news and allows Business Owners to view them with step-up SMS OTP authentication.

## Business Flow
1. **Manager** creates `COMPANY_NEWS_RESEARCH` task assigning a **Staff** member.
2. **Staff** adds drafts (title, source URL, content, optional image), and submits.
3. **Manager** reviews. Approval creates immutable `CompanyIntelligenceArticle` records in MongoDB.
4. **Business Owner** requests an OTP challenge for `CONFIDENTIAL_COMPANY_NEWS`.
5. APMS sends a 6-digit OTP to the Owner's verified phone number.
6. Owner verifies OTP and receives a short-lived (10 min) Step-Up JWT.
7. Owner calls confidential API endpoints, passing both normal JWT and `X-Step-Up-Token`.
8. Endpoints validate both tokens and return `Cache-Control: no-store` headers.

## OTP Security Rules
- **Never Logged**: OTP is never logged in any environment, nor is it stored in plaintext. It is hashed via BCrypt with a server-side pepper.
- **Max Attempts**: 5 attempts per OTP.
- **Expiration**: OTP expires in 5 minutes.
- **Rate Limiting**: Max 5 challenges per 15 minutes per account.
- **Resend Cooldown**: 60 seconds minimum between OTP requests. Previous active challenges are invalidated.
- **Prerequisites**: Account must have a verified phone number (`phoneNumber` and `phoneVerifiedAt` fields).

## Step-Up Token Claims
The Step-Up token is signed with a separate secret (`JWT_STEP_UP_SECRET`).
- `sub`: account ID
- `tokenType`: `STEP_UP`
- `purpose`: `CONFIDENTIAL_COMPANY_NEWS`
- `authMethod`: `SMS_OTP`
- `exp`: 10 minutes from issuance

## Image Security
Uploaded images are stored via the generic `StorageService` but are not exposed publicly. They can only be retrieved via `/api/v1/company-profiles/{id}/confidential-news/{articleId}/image`, which strictly validates the Step-Up Token and Business Owner access rights.

## Required Environment Variables
- `JWT_STEP_UP_SECRET`: Separate secret for Step-Up tokens (at least 32 chars).
- `OTP_PEPPER`: Pepper appended to OTP before hashing.
- If no SMS provider bean exists in production, requests return `HTTP 503 MFA_DELIVERY_UNAVAILABLE`.
