# Secure Remote Manager — MCP Server Edition

An Android MCP server that exposes the user's saved SSH connections to AI
agents (Claude, etc.). Also functions as a lightweight SSH client for
manual use.

## Stack
Kotlin, Jetpack Compose, MVVM, JSch (mwiede fork), MCP Kotlin SDK,
Ktor CIO, AndroidX Security-Crypto.

## Modules
- app — Compose UI, nav, foreground service
- core:design — Aurora design system
- core:security — encrypted config storage
- core:tunnel — userspace tunnel interface (mock by default)
- core:ssh — JSch client
- core:domain — ToolRegistry, CallDispatcher
- core:ssh-tools — SSH tool specs for MCP
- core:server-mcp — ToolSpec → MCP Tool mapping
- core:server-http — Ktor host, loopback-only, bearer auth

## Build
GitHub Actions builds every push. Newest APK: https://github.com/ehoeve786/SecureRemote/releases/download/latest/SecureRemote.apk

## License
Apache-2.0
