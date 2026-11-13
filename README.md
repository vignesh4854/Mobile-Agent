# Mobile Agent V3.1 — Native patch and recovery engine

V3.1 builds on the V3.0 native Android foundation and adds a bounded unified-diff pipeline.

## New in V3.1
- Unified diff parser for one workspace-relative file per patch.
- Rejects absolute paths, parent traversal, sensitive files, rename patches and file deletions.
- Preview generation without changing the workspace.
- Context-aware hunk application with exact-position validation and unique-context fallback.
- Stale-preview protection: approval is tied to the file SHA-256 captured during preview.
- Backup-backed undo that refuses to overwrite changes made after the patch.
- Bounded LCS side-by-side diff rows and reusable Compose PatchReviewCard.
- Unit tests for parsing, traversal rejection, diff output, apply/undo and stale preview rejection.

## Apply flow
1. Agent proposes a unified diff.
2. `PatchEngine.preview()` validates and renders the proposed result without mutation.
3. UI displays `PatchReviewCard`.
4. After explicit user approval, caller invokes `applyApproved(diff, preview.beforeSha256)`.
5. The returned transaction token can be passed to `undo()`.

The review card is a reusable component and is not yet wired into the existing chat approval flow. Agent tool-schema integration and persistent checkpoint indexing remain follow-up work. The transaction descriptor is currently in memory; callers must persist it if undo across app restarts is required.

## Limits and safety
- Patch text max 1 MB.
- Workspace snapshot uses the existing 512 KB file read limit.
- Diff is limited to 2,000 lines and 1.5 million LCS cells.
- One file per unified diff.
- File deletions and renames are intentionally unsupported.
- Backups are app-private but not encrypted.
- Undo checks the current file hash before restoring.
- This is not a secure OS sandbox. Use test repositories until Android build and security review are complete.

## Build and verification
Use Android Studio with JDK 17 or included GitHub Actions. Run `:app:testDebugUnitTest` and `:app:assembleDebug`.

ZIP integrity is verified when this archive is produced. Android Gradle compilation, KSP, emulator and device testing have not been performed in this environment.
