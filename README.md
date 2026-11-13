# Mobile AI Coding V1

An Android-first, serverless AI chat + local project file assistant. The app calls model APIs directly and uses Android's Storage Access Framework (SAF) to access only the project directory selected by the user.

## V1 features

- Claude Mode (Anthropic Messages API protocol)
- OpenAI Mode (Chat Completions protocol)
- Custom base URL, endpoint path, API key, model ID and optional custom headers
- Streaming text responses (SSE)
- Native tool-call parsing for both protocols
- Local tools: list files, read files, search filenames/text, create/edit files, apply full-file patches, delete files
- Approval dialog before every write/delete
- Folder-scoped access using Android SAF
- Encrypted preferences for API credentials (AndroidX Security Crypto)
- Local conversation history in app preferences

## Build

1. Install Android Studio (recent stable version) and Android SDK Platform 35.
2. Open this folder as an existing Gradle project.
3. Allow Gradle sync to download dependencies.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).

Or from a machine with Gradle installed:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

This source archive does not include the Gradle wrapper JAR. Android Studio can open and build the project directly; alternatively generate a wrapper locally with `gradle wrapper`.

## Configure

Open Settings (gear icon):

- Mode: Claude or OpenAI
- Base URL: for example `https://api.anthropic.com` or `https://api.openai.com/v1`
- Endpoint: `/v1/messages` for Claude; `/chat/completions` for OpenAI Chat Completions when base URL already ends in `/v1`
- API key and model ID
- Optional custom headers as `Name: value`, one per line

For Claude, the app sends `anthropic-version: 2023-06-01` and `x-api-key` headers. For OpenAI, it sends `Authorization: Bearer ...`.

Third-party gateways vary. Their base URL, endpoint, required headers and model ID must match that gateway's documentation. The app does not claim every provider is fully protocol-compatible.

## Project folder

Tap the folder icon and select the project root using Android's folder picker. Grant access when prompted. The app remembers the permission when Android allows persistable access.

## Safety

- Read tools are available to the model after a project is selected.
- Every create/edit/delete tool call pauses for user approval.
- Paths are resolved relative to the selected folder; absolute paths and `..` traversal are rejected.
- The app cannot guarantee isolation from malicious code or a compromised model provider. This MVP does not execute shell commands.
- Avoid selecting folders containing secrets if you do not want their contents sent to your model provider.
- API keys are stored in encrypted app preferences. They are never intentionally included in model prompts.

## Current limitations

- OpenAI Chat Completions and Anthropic Messages are implemented. OpenAI Responses API is not yet implemented.
- Model discovery is not standardized across providers; enter the model ID manually.
- File operations are text-oriented and intended for source/config files, not binary files.
- The first version uses a simple local conversation persistence implementation; it is not a multi-device sync system.
- Streaming tool-call argument assembly is implemented for the supported protocols, but gateways may differ in their SSE behavior.
- No Git, terminal, Python, Node.js, MCP, background autonomous execution or multi-agent support in V1.

## GitHub Actions

A workflow is included at `.github/workflows/android.yml`. Push to `main` or `master`, or run it manually from the Actions tab. It builds a debug APK and uploads it as a workflow artifact. The workflow uses Gradle 8.9 and JDK 17.

The selected model and gateway must support the corresponding native tool/function-calling protocol for file tools to work. If a model only supports plain text chat, it will not be able to invoke local file operations in this V1 build.
