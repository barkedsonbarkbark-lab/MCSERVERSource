# MCSERVER

Mobile-first Android application for managing exactly one Minecraft Java Edition server at `app_data/servers/main`.

## Current architecture

- Android app: Kotlin + Jetpack Compose.
- One managed Minecraft server configuration and one Minecraft process.
- Paper installation uses the Paper API for the selected Minecraft version.
- Geyser mode installs Geyser-Spigot from Modrinth metadata filtered for the selected Minecraft version and Paper loader.
- Server output is persisted to the app server log and surfaced in the Console tab.
- The foreground service owns server start/stop/restart and keeps the same repository instance as the activity.
- Backups exclude the backups directory itself.
- Render service source lives in `render/`.
- The Network screen shows current LAN addresses, can install Playit's official Paper plugin for a public Java tunnel, and keeps Tailscale as an optional private-network route.

## Render networking boundary

Render web services provide the Render control panel and authenticated WebSocket agent. They do not expose a raw public Minecraft TCP port or UDP for Bedrock, so the Render deployment is not a Minecraft game tunnel. The service leaves its optional Java TCP listener disabled unless `PUBLIC_JAVA_PORT` is configured on infrastructure that really exposes that port. Setting this variable on Render does not make the port public.

For friends on the same Wi-Fi, use the LAN addresses in the app. For public Java access without hosting fees, the app can install Playit's community-maintained Paper plugin; the plugin prints the claim flow and address in the Minecraft Console. This route depends on Playit's service and network availability. Its published compatibility table confirms older Paper releases through 1.19; later Minecraft versions are unverified. The plugin provides Java TCP, not public Bedrock UDP. Tailscale remains available for private Java/Bedrock access when all players join the same tailnet.

## Build configuration

Render settings for the Android build are supplied as Gradle project properties:

- `RENDER_BASE_URL`
- `RENDER_TUNNEL_TOKEN`
- `MCSERVER_CONTACT_URL` (used in requests to PaperMC's downloads service)

The example values are in `.env.example`. Never commit a real tunnel token.

The app expects an Android-compatible Java runtime at `app_data/runtimes/current/bin/java`. A desktop JDK installed on the development PC is not treated as the server runtime shipped with the Android app.

## Known implementation boundary

The provider registry currently has a real installer for Paper and Geyser-on-Paper. Vanilla, Purpur, Spigot, Bukkit-compatible, Fabric, Forge, and NeoForge entries fail explicitly instead of pretending to be installed.

The Android SDK, ADB, and Gradle distribution are in `.tools`. Build a debug APK with `.tools/gradle-9.1.0/bin/gradle.bat :app:assembleDebug` (omit `--offline` if the needed dependencies are not cached).

## Render local run

The Go module requires a Go toolchain to build. Set `MCSERVER_TOKEN` before starting the service. The Render deployment exposes health/status and the authenticated Android agent WebSocket. On a host with actual raw TCP ingress, `PUBLIC_JAVA_PORT` enables the optional Java relay listener; Render does not provide that ingress.
