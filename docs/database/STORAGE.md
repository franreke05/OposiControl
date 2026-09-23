# Storage

## Database Redesign V1 preservation gate — 2026-08-21

Storage was read-only verified after the database grant changes: 6 buckets and
15 objects remain present. No bucket, object, Storage policy, MIME rule or size
limit was modified.

## Live read-only reconciliation — 2026-08-20

The authorized project exposes six buckets: private `exam-resources` (10 MB), private `question-resources` (50 MB), private `recursos-pregunta` (50 MB), private `chat-attachments` (20 MB), public `news-assets` (10 MB) and public `news-images` (10 MB). Active code references `exam-resources` and `news-assets`. The other four have no active local consumer confirmed and remain `UNKNOWN`/possible legacy or external contracts.

The PDF/import contract remains protected. No bucket, object, policy, MIME rule or size limit was changed. Consolidation requires an object/path inventory and consumer sign-off.

Storage buckets, upload paths and resource flows are documented from code/SQL as `CODE_EXPECTED` or `DOCUMENTED`. Live buckets/policies are `LIVE_VERIFIED: NO`.

PDF/resource upload is therefore a contract and risk area, not a verified deployment fact.

The local SQL inventory contains one bucket literal, while code references upload flows for exam resources and news assets. Bucket existence, object policies, MIME/size constraints and service-role exposure were not verified live. No upload or storage mutation occurred.
