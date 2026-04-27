# Infra Ticket INFRA-004: IAM Role for GitHub Actions OIDC Deploy

**Requested by plan**: [020-docs-s3-deployment](../plans-by-ai/020-docs-s3-deployment.md)
**Status**: Pending

## What is needed

An IAM role named `github-docs-deploy` in the target AWS account. Its trust
policy allows `sts:AssumeRoleWithWebIdentity` from GitHub's OIDC provider
(INFRA-002), scoped to the exact repository (`repo:<org>/<repo>:ref:refs/heads/main`)
so only `main`-branch workflow runs can assume it. Its permission policy grants
`s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`, and `s3:ListBucket` on the
docs bucket only (INFRA-001). No other permissions.

## Why it is needed

### Business need

Long-lived IAM access keys in GitHub Secrets are a persistent credential that
can be exfiltrated via log output, dependency-chain attacks, or compromised
runner environments. An OIDC-assumed role issues session tokens valid for one
hour and is non-exportable outside the workflow run — dramatically shrinking
the blast radius of a pipeline compromise. This directly addresses cloud
security best practices (AWS Well-Architected Security Pillar, SEC02-BP03)
and removes the operational burden of rotating static keys. Once this role
exists, the team can onboard additional pipelines to the same OIDC provider
without provisioning new credentials.

### Technical dependency

Plan 020 Stage 3 (W8) replaces the `aws-access-key-id` / `aws-secret-access-key`
inputs with `role-to-assume: arn:aws:iam::<ACCOUNT_ID>:role/github-docs-deploy`.
Without this role, the `configure-aws-credentials` action has no target to
assume and the step fails. INFRA-002 (OIDC provider) must be provisioned first
as this role's trust policy references that provider's ARN.

## How to retrieve / provision

### Via the AWS Console

1. Open AWS Console → IAM → **Roles** → **Create role**.
2. Choose **Web identity** as the trusted entity type.
3. Select the identity provider
   `token.actions.githubusercontent.com` (registered in INFRA-002).
4. Set **Audience** to `sts.amazonaws.com`.
5. Add a condition: `token.actions.githubusercontent.com:sub` =
   `repo:<YOUR_ORG>/<YOUR_REPO>:ref:refs/heads/main`
   (replace `<YOUR_ORG>/<YOUR_REPO>` with the actual GitHub org and repo name).
6. On the Permissions step, choose **Create inline policy** with JSON:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": [
         "s3:PutObject",
         "s3:GetObject",
         "s3:DeleteObject",
         "s3:ListBucket"
       ],
       "Resource": [
         "arn:aws:s3:::codegen-ksp-docs",
         "arn:aws:s3:::codegen-ksp-docs/*"
       ]
     }]
   }
   ```
7. Name the role `github-docs-deploy`.
8. Note the role ARN:
   `arn:aws:iam::<ACCOUNT_ID>:role/github-docs-deploy`.

### Via Pulumi (minimal script)

> Drop this block into the relevant Pulumi stack. No scaffolding — just the
> resource declaration with hardcoded values.

```typescript
import * as aws from "@pulumi/aws";

// Replace with your values:
const GITHUB_ORG  = "<YOUR_ORG>";
const GITHUB_REPO = "<YOUR_REPO>";
const ACCOUNT_ID  = "<YOUR_ACCOUNT_ID>";
const OIDC_PROVIDER_ARN = `arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com`;

const deployRole = new aws.iam.Role("github-docs-deploy", {
    name: "github-docs-deploy",
    assumeRolePolicy: JSON.stringify({
        Version: "2012-10-17",
        Statement: [{
            Effect: "Allow",
            Principal: { Federated: OIDC_PROVIDER_ARN },
            Action: "sts:AssumeRoleWithWebIdentity",
            Condition: {
                StringEquals: {
                    "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
                    "token.actions.githubusercontent.com:sub":
                        `repo:${GITHUB_ORG}/${GITHUB_REPO}:ref:refs/heads/main`,
                },
            },
        }],
    }),
    tags: { project: "codegen-ksp", "managed-by": "pulumi" },
});

new aws.iam.RolePolicy("github-docs-deploy-policy", {
    role: deployRole.name,
    policy: JSON.stringify({
        Version: "2012-10-17",
        Statement: [{
            Effect: "Allow",
            Action: ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:ListBucket"],
            Resource: [
                "arn:aws:s3:::codegen-ksp-docs",
                "arn:aws:s3:::codegen-ksp-docs/*",
            ],
        }],
    }),
});

export const deployRoleArn = deployRole.arn;
```

**After provisioning**: paste `deployRoleArn` into plan 020 Stage 3 W8 where
`role-to-assume` is referenced, and remove the Blocked status for INFRA-004
in `ROADMAP.md`.
