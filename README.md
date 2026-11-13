# Mobile Agent V3.4 — Native multi-agent orchestration

V3.4 adds a complete multi-agent collaboration surface to the native Android app.

## Agent workflow
1. Planner decomposes the task and defines acceptance criteria.
2. Coder receives the plan and shared project context.
3. Reviewer and Debugger run concurrently on the coder handoff.
4. Planner synthesizes the independent findings into a final report.

## Included capabilities
- Four independently configurable agent roles.
- Separate provider protocol, base URL, endpoint, model and API key per role.
- Encrypted profile persistence using Android Keystore AES-GCM.
- Shared run-scoped project context and thread-safe artifact handoffs.
- Parallel reviewer/debugger execution.
- One shared tool registry and permission gateway for every agent.
- Read-only tools execute automatically.
- File writes, pull, push and workflow dispatch require user approval.
- Legacy stage-all commit and terminal execution are blocked in multi-agent mode.
- Serialized tool execution and approval dialogs prevent overlapping approval prompts.
- Run traces persisted in Room, with migration from database version 1 to 2.
- Estimated prompt/completion token accounting, elapsed time and per-agent status.
- Recent run history with selectable persisted execution traces and final-report size summaries; the database retains the latest 30 runs.

## Token accounting
Provider-specific usage fields are not currently parsed by the HTTP adapter. Token figures are estimates based on roughly four characters per token, including prompts, responses and tool results. They are not billing-grade usage figures.

## Tool and project scope
The shared tool gateway uses the existing app-private workspace. The project path entered in the team panel must be inside `filesDir/workspace`. Read-only tools are shared across agents. User-approved mutations pass through one approval callback. Command execution is deliberately blocked in this multi-agent milestone. The legacy commit proposal is blocked because it is not connected to the selective-stage UI workflow.

## Provider compatibility
Each role can use an OpenAI-compatible or Anthropic-compatible endpoint. Third-party providers must support the selected protocol and tool-calling format. Compatibility with every provider/model has not been verified.

## Database
Room schema is version 2. Migration 1→2 adds `agent_runs` and `agent_traces` while preserving existing projects, sessions and messages.

## Verification
Added unit tests for orchestration sequencing, parallel reviewer/debugger execution, profile validation and tool permissions. ZIP integrity is verified at packaging time. Android Gradle compilation, KSP generation, emulator and physical-device tests have not been run in this environment.


## V3.4.1 interface update

The app now launches into a chat-first home screen. The composer remains fixed above the bottom navigation. Projects and Team are primary destinations; Git, Research, and provider/integration configuration are grouped under More. See `V3.4.1-UI-RELEASE-NOTES.md` and the added manual UI checks in `V3.4-TEST-PLAN.md`.
