# Plan 020: GitHub Actions Docs Deployment to S3

**Created**: 2026-04-26
**Supersedes**: none

## Checklist

- [ ] Stage 1 — Tracer bullet: hardcoded upload of one file via static IAM credentials
  - [ ] W1. Create `.github/workflows/deploy-docs.yml` with hardcoded bucket and single-file upload
  - [ ] W2. Add `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` to GitHub Actions secrets (temporary)
  - [ ] W3. Manually trigger workflow and verify file appears in S3
- [ ] Stage 2 — Sync full `docs/` directory
  - [ ] W4. Replace single-file step with `aws s3 sync docs/ s3://<bucket>/docs/`
  - [ ] W5. Include root `README.md` as `s3://<bucket>/README.md`
  - [ ] W6. Verify all 8 files land in S3 with correct paths
- [ ] Stage 3 — Switch to OIDC auth (riskiest)
  - [ ] W7. Add OIDC permission block to workflow (`id-token: write`)
  - [ ] W8. Replace static-credential step with `aws-actions/configure-aws-credentials` using `role-to-assume`
  - [ ] W9. Remove IAM access key secrets from GitHub
  - [ ] W10. Trigger workflow and verify OIDC token exchange succeeds (no stored credentials)
- [ ] Stage 4 — Add triggers and branch guardrails
  - [ ] W11. Add `on: push` trigger scoped to `docs/**` and `README.md` path filters
  - [ ] W12. Restrict deployment to `main` branch only
  - [ ] W13. Add `--delete` flag to `s3 sync` so removed docs are purged from S3
- [ ] Stage 5 — Refactor and tidy
  - [ ] W14. Extract bucket name and region into workflow `env:` block
  - [ ] W15. Add a `--dryrun` job on pull requests (no deploy, just validate sync plan)
  - [ ] W16. Add workflow status badge to `README.md`

---

## Approach

We are building a GitHub Actions CI/CD pipeline that syncs the project's
markdown documentation (`docs/annotations/` and root `README.md`) to an S3
bucket on every push to `main` that touches docs files. The docs currently
live as plain `.md` files — no rendering step is required; S3 will serve the
raw markdown (or a downstream viewer can render them).

**Riskiest integration: GitHub Actions OIDC federation with AWS IAM.** AWS
must trust GitHub's OIDC token issuer, and the IAM role's trust policy must
be scoped to the exact repo and branch — any misconfiguration causes a silent
401/403 during the `AssumeRoleWithWebIdentity` call. Because this integration
spans two external systems (GitHub token issuer ↔ AWS STS), it is validated
mid-sequence, not first and not last.

The sequence retires risk in this order: first prove S3 write access is
reachable at all (Stage 1, using temporary static credentials), then expand
to the full file tree (Stage 2), then swap in the production-grade OIDC auth
(Stage 3 — the dangerous boundary), then add operational refinements (Stage
4), and finally clean up the YAML (Stage 5).

Static IAM credentials used in Stage 1 are intentionally temporary scaffolding.
They are removed at the end of Stage 3 and should never be committed to the
repo. CloudFront distribution and any HTML rendering of markdown are explicitly
deferred — they can follow this plan as a separate infrastructure extension.

---

## Step Sequence

### Stage 1 — Tracer bullet: hardcoded single-file upload via static credentials

> Blocked by:
> [INFRA-001-s3-docs-bucket](../tickets-by-ai/INFRA-001-s3-docs-bucket.md),
> [INFRA-003-iam-bootstrap-user](../tickets-by-ai/INFRA-003-iam-bootstrap-user.md)

- **W1.** Create `.github/workflows/deploy-docs.yml` with:
  - `on: workflow_dispatch` (manual trigger only for now)
  - single job `deploy` running on `ubuntu-latest`
  - `aws-actions/configure-aws-credentials@v4` step using
    `aws-access-key-id` / `aws-secret-access-key` secrets
  - One `aws s3 cp` step that uploads `docs/annotations/ClassField.md` to
    `s3://HARDCODED-BUCKET-NAME/docs/annotations/ClassField.md`
  - Region hardcoded to `us-east-1` (update after INFRA-001 resolves)

  **Verify:** Workflow run shows green; `aws s3 ls s3://<bucket>/docs/annotations/`
  returns `ClassField.md`.

- **W2.** In GitHub → Settings → Secrets, add `AWS_ACCESS_KEY_ID` and
  `AWS_SECRET_ACCESS_KEY` from the IAM bootstrap user (INFRA-003 output).

  **Verify:** Workflow sees the secrets (no "Context access might be invalid" warning).

- **W3.** Run the workflow manually via GitHub UI (`workflow_dispatch`).

  **Verify:** AWS Console → S3 → bucket → `docs/annotations/ClassField.md` is present
  with correct content.

**Demo:**
1. Open the repo on GitHub → Actions → "Deploy Docs" → Run workflow.
2. Watch the run complete green.
3. Open AWS S3 Console, navigate to the bucket, confirm `docs/annotations/ClassField.md` exists.

---

### Stage 2 — Sync full docs directory

- **W4.** Replace the single `aws s3 cp` step with:
  ```yaml
  - run: aws s3 sync docs/ s3://HARDCODED-BUCKET-NAME/docs/
  ```
  **Verify:** All 7 files under `docs/annotations/` appear in S3.

- **W5.** Add a second sync step:
  ```yaml
  - run: aws s3 cp README.md s3://HARDCODED-BUCKET-NAME/README.md
  ```
  **Verify:** Root `README.md` visible in S3 at `s3://<bucket>/README.md`.

- **W6.** Trigger workflow, confirm all 8 files land correctly.

  **Verify:** `aws s3 ls --recursive s3://<bucket>/` returns exactly 8 objects
  matching the local doc tree.

**Demo:**
1. Run workflow manually.
2. In AWS Console, list all objects in the bucket — 8 files, paths matching local structure.

---

### Stage 3 — Switch to OIDC auth (riskiest cross-system boundary)

> Blocked by:
> [INFRA-002-github-oidc-provider](../tickets-by-ai/INFRA-002-github-oidc-provider.md),
> [INFRA-004-iam-deploy-role](../tickets-by-ai/INFRA-004-iam-deploy-role.md)

- **W7.** Add OIDC permissions to the workflow job:
  ```yaml
  permissions:
    id-token: write
    contents: read
  ```
  **Verify:** Linting passes; no YAML syntax errors.

- **W8.** Replace the `configure-aws-credentials` step — remove
  `aws-access-key-id` / `aws-secret-access-key` inputs and add:
  ```yaml
  role-to-assume: arn:aws:iam::<ACCOUNT_ID>:role/github-docs-deploy
  role-session-name: docs-deploy-${{ github.run_id }}
  ```
  **Verify:** Workflow run shows `AssumeRoleWithWebIdentity` call in the step
  output (not `AssumeRole`).

- **W9.** Delete `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` secrets from
  GitHub repository settings.

  **Verify:** Secrets list no longer shows the two IAM keys.

- **W10.** Trigger the workflow and confirm S3 sync still succeeds using only the
  OIDC-assumed role.

  **Verify:** Workflow green; no credential-related errors; S3 objects updated.

**Demo:**
1. Run workflow manually.
2. Confirm run is green with step output showing role assumption via OIDC token.
3. Verify no stored IAM secrets remain in GitHub Settings → Secrets.
4. Confirm S3 objects are updated (check Last Modified timestamp).

---

### Stage 4 — Triggers, branch guard, and delete sync

- **W11.** Change `on:` to:
  ```yaml
  on:
    push:
      branches: [main]
      paths:
        - 'docs/**'
        - 'README.md'
  ```
  **Verify:** Push to `main` touching a doc file triggers the workflow;
  push touching only `.kt` files does NOT trigger it.

- **W12.** Add a job-level condition as a belt-and-suspenders guard:
  ```yaml
  if: github.ref == 'refs/heads/main'
  ```
  **Verify:** Manually triggered from a non-main branch skips the job.

- **W13.** Add `--delete` to the sync command so files removed from `docs/`
  are also removed from S3. Delete a local doc, push to main, confirm the
  S3 object is gone.

  **Verify:** `aws s3 ls --recursive s3://<bucket>/docs/` shows the deleted
  file is absent after the push.

**Demo:**
1. Commit a change to `docs/annotations/ClassField.md` on `main`.
2. Watch GitHub Actions auto-trigger the workflow.
3. Confirm the updated file timestamp in S3.
4. Delete `docs/annotations/SupportingTypes.md`, push — confirm it's removed from S3.

---

### Stage 5 — Refactor and tidy (no behaviour change)

- **W14.** Extract repeated values into a top-level `env:` block:
  ```yaml
  env:
    AWS_REGION: us-east-1
    S3_BUCKET: <bucket-name>
  ```
  Replace all hardcoded strings with `${{ env.S3_BUCKET }}` etc.

- **W15.** Add a `dry-run` job that runs on pull requests:
  ```yaml
  on:
    pull_request:
      paths: ['docs/**', 'README.md']
  ```
  The job runs `aws s3 sync --dryrun` and prints what would change — no actual
  upload. Requires read-only IAM role or same OIDC role (S3 list + get only).

- **W16.** Add a workflow status badge to `README.md`:
  ```markdown
  ![Docs Deploy](https://github.com/<org>/<repo>/actions/workflows/deploy-docs.yml/badge.svg)
  ```

<!-- No Demo block — pure refactor, no visible behaviour change beyond the badge. -->

---

## Recommendations (read before starting)

1. **Spike the OIDC trust policy before Stage 3.** The `Condition` block in the
   IAM trust policy must exactly match GitHub's token claims (`sub`, `aud`,
   `repository`). Test with a minimal `sts:AssumeRoleWithWebIdentity` call in
   a throwaway workflow first — a mismatched condition produces an opaque
   `AccessDenied` with no hint about which claim failed.
2. **Decide on bucket visibility before INFRA-001.** Public bucket (open URLs,
   simple) vs. private bucket (requires pre-signed URLs or CloudFront OAC to
   read). This choice affects the bucket policy and whether CloudFront is in
   scope. Current plan assumes private bucket; docs are fetched by authenticated
   consumers or developers with AWS access.
3. **Do not commit static IAM credentials.** The Stage 1 bootstrap credentials
   go into GitHub Secrets only. Rotate and delete them at the end of Stage 3.
4. **`--delete` flag (Stage 4) is destructive.** Test it on a non-production
   bucket first. If the sync source path is ever wrong it will empty the bucket.
   Add a sanity check (object count before/after) before enabling in production.
