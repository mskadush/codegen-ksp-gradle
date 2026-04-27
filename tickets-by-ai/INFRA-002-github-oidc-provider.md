# Infra Ticket INFRA-002: GitHub Actions OIDC Identity Provider

**Requested by plan**: [020-docs-s3-deployment](../plans-by-ai/020-docs-s3-deployment.md)
**Status**: Pending

## What is needed

An IAM OpenID Connect (OIDC) Identity Provider in the target AWS account,
configured for GitHub Actions' token issuer
(`https://token.actions.githubusercontent.com`). This provider is created
once per AWS account and is reused by all GitHub Actions workflows that use
OIDC. Audience must be set to `sts.amazonaws.com`.

## Why it is needed

### Business need

Storing long-lived IAM access keys as GitHub Secrets is a significant security
risk: keys can leak through log output, are difficult to rotate, and have no
automatic expiry. OIDC federation eliminates the need for any stored credentials.
GitHub's short-lived OIDC token is exchanged for a temporary STS session at
workflow runtime — keys that expire in an hour, scoped to a specific role,
with no secret material to leak. This directly satisfies any compliance
requirement around least-privilege access and secrets hygiene (SOC 2, ISO
27001 CC6.1, etc.).

### Technical dependency

Plan 020 Stage 3 (W8) replaces static IAM credentials with OIDC token
exchange. Without this identity provider registered in AWS, `AssumeRoleWithWebIdentity`
returns an error and the pipeline cannot authenticate. The OIDC provider is a
prerequisite for INFRA-004 (IAM deploy role) — that role's trust policy
references this provider.

## How to retrieve / provision

### Via the AWS Console

1. Open AWS Console → IAM → **Identity providers** → **Add provider**.
2. Choose **OpenID Connect**.
3. Set **Provider URL** to `https://token.actions.githubusercontent.com`.
4. Click **Get thumbprint** (AWS fetches and validates the TLS certificate fingerprint).
5. Set **Audience** to `sts.amazonaws.com`.
6. Click **Add provider**.
7. Note the provider ARN (format:
   `arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com`).

### Via Pulumi (minimal script)

> Drop this block into the relevant Pulumi stack. No scaffolding — just the
> resource declaration with hardcoded values.

```typescript
import * as aws from "@pulumi/aws";

const githubOidcProvider = new aws.iam.OpenIdConnectProvider("github-actions-oidc", {
    url: "https://token.actions.githubusercontent.com",
    clientIdLists: ["sts.amazonaws.com"],
    // Thumbprint list is managed by AWS; providing a known value avoids an
    // extra HTTP call at apply time — update if GitHub rotates their cert.
    thumbprintLists: ["6938fd4d98bab03faadb97b34396831e3780aea1"],
    tags: {
        project: "codegen-ksp",
        "managed-by": "pulumi",
    },
});

export const oidcProviderArn = githubOidcProvider.arn;
```

**After provisioning**: paste `oidcProviderArn` into INFRA-004's trust policy
and remove the Blocked status for INFRA-002 in `ROADMAP.md`.
