# Infra Ticket INFRA-003: IAM Bootstrap User (Temporary)

**Requested by plan**: [020-docs-s3-deployment](../plans-by-ai/020-docs-s3-deployment.md)
**Status**: Pending

## What is needed

A temporary IAM user named `github-docs-deploy-bootstrap` with a programmatic
access key, granted an inline policy scoped to `s3:PutObject`, `s3:GetObject`,
`s3:ListBucket`, and `s3:DeleteObject` on the docs bucket (INFRA-001) only.
This user is intentionally short-lived: it is deleted at the end of Plan 020
Stage 3 once OIDC auth (INFRA-004) is working.

## Why it is needed

### Business need

Before investing in OIDC federation infrastructure, the team needs to confirm
that the S3 bucket is correctly configured, the pipeline's sync logic is
correct, and the file paths are right. Using a temporary IAM user for Stages 1
and 2 of the deployment pipeline lets the team validate the end-to-end doc sync
story within hours — without waiting for the multi-step OIDC setup. This
reduces the risk that the whole pipeline is only validated once all pieces land
simultaneously.

### Technical dependency

Plan 020 Stage 1 (W2) requires AWS credentials to be stored as GitHub Secrets.
These credentials come from the bootstrap user's access key. Without them, the
`aws-actions/configure-aws-credentials` step has no input and the workflow
fails at authentication.

## How to retrieve / provision

### Via the AWS Console

1. Open AWS Console → IAM → **Users** → **Create user**.
2. Set username to `github-docs-deploy-bootstrap`.
3. Do **not** grant console access.
4. On the Permissions step, choose **Attach policies directly** → **Create inline policy**.
5. Use the JSON editor and paste:
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
6. Name the policy `docs-deploy-bootstrap-policy` and create it.
7. After creating the user, go to the user → **Security credentials** →
   **Create access key** → choose **Other** as the use case.
8. Download or copy the **Access key ID** and **Secret access key** immediately
   (shown only once).

### Via Pulumi (minimal script)

> Drop this block into the relevant Pulumi stack. No scaffolding — just the
> resource declaration with hardcoded values.

```typescript
import * as aws from "@pulumi/aws";

const bootstrapUser = new aws.iam.User("github-docs-deploy-bootstrap", {
    name: "github-docs-deploy-bootstrap",
    tags: { project: "codegen-ksp", "managed-by": "pulumi", lifecycle: "temporary" },
});

new aws.iam.UserPolicy("github-docs-deploy-bootstrap-policy", {
    user: bootstrapUser.name,
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

const accessKey = new aws.iam.AccessKey("github-docs-deploy-bootstrap-key", {
    user: bootstrapUser.name,
});

export const accessKeyId     = accessKey.id;
export const secretAccessKey = accessKey.secret; // store in a secret manager, not in state
```

**After provisioning**: store `accessKeyId` as `AWS_ACCESS_KEY_ID` and
`secretAccessKey` as `AWS_SECRET_ACCESS_KEY` in GitHub repository Secrets.
Delete this user and its access key after Plan 020 Stage 3 (W9) is complete
and OIDC auth is confirmed working.
