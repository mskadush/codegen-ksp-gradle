# Infra Ticket INFRA-001: S3 Docs Bucket

**Requested by plan**: [020-docs-s3-deployment](../plans-by-ai/020-docs-s3-deployment.md)
**Status**: Pending

## What is needed

An S3 bucket (private, versioning enabled) in `us-east-1` named
`<project>-docs` (e.g. `codegen-ksp-docs`). Block all public access.
Enable server-side encryption (SSE-S3). Tag with `project=codegen-ksp` and
`managed-by=pulumi`.

## Why it is needed

### Business need

The project's annotation API documentation currently exists only on developers'
local machines and in the source repository. There is no canonical, stable URL
where teammates, API consumers, or onboarding engineers can read the latest
docs. Hosting in S3 gives every stakeholder a single durable location that
stays current automatically on each merge to `main` — removing the "check the
repo" friction and supporting future integrations (internal portal, ChatBot
knowledge base, etc.).

### Technical dependency

Plan 020 Stage 1 (W1) requires a destination bucket before any upload step can
run. Without the bucket, the pipeline has no target and all subsequent stages
are blocked.

## How to retrieve / provision

### Via the AWS Console

1. Open AWS Console → S3 → **Create bucket**.
2. Set **Bucket name** to `codegen-ksp-docs` (must be globally unique; adjust if taken).
3. Set **Region** to `us-east-1`.
4. Under **Block Public Access**, leave all four checkboxes **checked** (keep private).
5. Enable **Bucket Versioning**.
6. Under **Default encryption**, choose **SSE-S3** (AES-256).
7. Click **Create bucket**.
8. Note the bucket name and ARN from the bucket overview page.

### Via Pulumi (minimal script)

> Drop this block into the relevant Pulumi stack. No scaffolding — just the
> resource declaration with hardcoded values.

```typescript
import * as aws from "@pulumi/aws";

const docsBucket = new aws.s3.BucketV2("codegen-ksp-docs", {
    bucket: "codegen-ksp-docs",
    tags: {
        project: "codegen-ksp",
        "managed-by": "pulumi",
    },
});

new aws.s3.BucketVersioningV2("codegen-ksp-docs-versioning", {
    bucket: docsBucket.id,
    versioningConfiguration: { status: "Enabled" },
});

new aws.s3.BucketServerSideEncryptionConfigurationV2("codegen-ksp-docs-sse", {
    bucket: docsBucket.id,
    rules: [{
        applyServerSideEncryptionByDefault: { sseAlgorithm: "AES256" },
    }],
});

new aws.s3.BucketPublicAccessBlock("codegen-ksp-docs-public-access", {
    bucket: docsBucket.id,
    blockPublicAcls: true,
    blockPublicPolicy: true,
    ignorePublicAcls: true,
    restrictPublicBuckets: true,
});

export const bucketName = docsBucket.id;
export const bucketArn  = docsBucket.arn;
```

**After provisioning**: paste `bucketName` into plan 020 Stage 1 W1 where
`HARDCODED-BUCKET-NAME` appears, and remove the Blocked status for INFRA-001
in `ROADMAP.md`.
