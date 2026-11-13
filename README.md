# Mobile Agent V2.7 — Approval-gated mobile coding agent

Android Kotlin + Jetpack Compose starter. Model inference uses a configurable external HTTPS provider. Workspace, local Git, and tool dispatch run on Android; GitHub Actions provides optional remote CI/build/test execution. No custom VPS/backend is required.

## V2.7 additions

### Agent approval proposals

The model can propose the following operations, but cannot execute them directly:

- `propose_git_commit` — local commit. **Stages all current repository changes**, then commits with the supplied message and author identity.
- `propose_git_pull` — pulls remote changes into the current branch.
- `propose_git_push` — publishes local commits to the configured remote.
- `propose_trigger_workflow` — requests a GitHub Actions `workflow_dispatch` for a named workflow and ref.

Each proposal pauses the agent and displays a human approval card. The operation runs only after the user taps **Approve and execute**. Rejection is returned to the model as a tool result. If an operation fails, the failure is returned; the UI does not claim it succeeded.

Read-only agent tools remain available for web research, saved research, local Git status/diff/branches, and GitHub Actions runs/jobs/logs/artifacts. File edits still require explicit approval and an original-file hash check.

### Build/test integration

A GitHub Actions workflow is included at `.github/workflows/android-ci.yml`. On push, pull request, or manual dispatch it:
1. Sets up JDK 17 and Gradle 8.9.
2. Runs `:app:testDebugUnitTest`.
3. Builds the debug APK with `:app:assembleDebug`.
4. Uploads the APK as a 7-day workflow artifact.

This is **remote CI**, not an on-device shell or terminal. The app does not execute arbitrary commands on Android. The agent can inspect workflow runs, jobs, and logs; after an approved push, CI will run from the repository workflow.

## Setup

1. Open the project in Android Studio (recent stable version recommended).
2. Allow Gradle sync to resolve dependencies.
3. Build and install the `app` debug variant, or push the project to GitHub and use the included CI workflow.
4. Configure provider protocol, HTTPS base URL, endpoint, model ID, and provider API key.
5. For web search, enter a Brave Search API key.
6. For GitHub Actions, enter a fine-grained GitHub token and repository owner/name.
7. Clone a GitHub HTTPS repository from the Git tab into the app-private workspace.

## GitHub token permissions

Use a fine-grained token scoped to only the target repository. Depending on intended operations:
- Repository Contents: **Read** for clone/inspection; **Read and write** for push.
- Actions: **Read** for run/job/log/artifact inspection; **Read and write** for workflow dispatch.
- Avoid organization-wide or classic broad-scope tokens when a repository-scoped token is sufficient.

The same token is held in Agent screen memory and sent only to GitHub. It is not added to model prompts and is not persisted by the app. The provider and Brave keys are also screen-memory-only (the model provider key has its separate existing Keystore-backed config store).

## Approval boundaries and cautions

- File changes require approval and hash recheck.
- Agent Git mutations (commit, pull, push) and workflow dispatch are proposal-only and require a separate approval tap.
- **Commit stages every current repository change**, not selected files. Review Git status/diff before approving.
- Pull may modify files and can encounter merge conflicts.
- Push publishes commits to the remote.
- Workflow dispatch starts a remote job that may consume CI minutes or access repository secrets according to that workflow's own permissions.
- Branch creation/checkout remain manual in the Git tab.
- Never approve an operation based solely on model-generated claims; inspect the displayed target and scope.

## Important limitations / not production-ready

- **Not compiled or device-tested in this environment.** The ZIP archive can be verified, but this does not prove Android build success.
- JGit Android runtime compatibility still needs verification on a real device.
- Git diff remains a summary rather than a complete patch. Commit stages all changes. Merge conflict resolution UI is not included.
- Workflow dispatch currently supports a workflow filename or numeric ID plus a ref; workflow input parameters are not implemented.
- GitHub API client follows redirects for GitHub's own API/download endpoints. Web retrieval uses a separate client with redirects disabled.
- Web retrieval is baseline SSRF protection: HTTPS only, redirects disabled, DNS resolution checked for private/local IPs, and response size/content type bounded. DNS rebinding and advanced network edge cases are not comprehensively solved.
- HTML extraction is lightweight regex cleanup, not a full browser/DOM parser.
- Saved research is app-private but not encrypted. Artifact extraction quotas need stronger enforcement.
- File edit is still full-file replacement with a diff preview, not a context-aware patch.
- No persistent/resumable sessions, MCP, on-device terminal, multi-agent orchestration, or comprehensive automated test suite.
- The included CI test task currently runs the project's unit-test source set; no comprehensive application tests have been added yet.

## Security note

This is a development prototype. Review source and workflow permissions, run dependency/static analysis checks, and validate provider compatibility and JGit behavior before using important repositories or credentials.
