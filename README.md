# MWP FM Player — native Android

The native Android player for [My Weird Prompts](https://myweirdprompts.com): Kotlin,
Jetpack Compose and Media3 (ExoPlayer + MediaSessionService). It reads the same catalogue
as the MWP FM web app and sits alongside the wrapped web app; it does not replace it.

| | Native (this repo) | Wrapped web app |
| --- | --- | --- |
| Package | `com.myweirdprompts.fm.player` | `com.myweirdprompts.fm` |
| Repo | My-Weird-Prompts/MWP-FM-Native | [My-Weird-Prompts/MWP-FM-Android](https://github.com/My-Weird-Prompts/MWP-FM-Android) (Bubblewrap TWA) |
| UI code | here | `radio/` in My-Weird-Prompts, loaded live from `stream.myweirdprompts.com` |
| Playback | own foreground `mediaPlayback` service | runs in Chrome |
| Downloads | any folder (all-files access) | browser storage |
| Home-screen widget | yes | no |

Both can be installed at once (different packages).

## What it does (v1.0, 2026-11-01)

- **Feed:** `https://stream.myweirdprompts.com/api/radio.json` (the PWA's feed, served by
  `website/src/pages/api/radio.json.ts`), cached on disk with its ETag (`FeedRepository`).
- **Screens:** Latest (by day), Channels (art grid → channel page with Shuffle), Search
  (title/description/tags), Downloads, Settings, Now Playing.
- **Playback:** `PlaybackService` (Media3 `MediaSessionService`): background playback,
  notification and lock-screen controls, audio focus, pause on headphone unplug, resume
  position per episode (saved every 10 s and on pause), auto-play next (setting), skip
  lengths from Settings applied at press time (`SkipPlayer`). Headset play with the app
  closed resumes the last queue (`onPlaybackResumption`).
- **Scrubber:** thin 4 dp line, thumb grows while dragging, previews and seeks on release
  (`ui/Scrubber.kt`), same behaviour as the PWA.
- **Downloads:** WorkManager jobs (`DownloadWorker`) to `<folder>/<slug>.<ext>` via a
  `.part` file. With **All files access** the folder is any path the listener picks
  (default `/storage/emulated/0/Podcasts/MWP FM`); without it, the app's private
  external folder. The folder picker is `ACTION_OPEN_DOCUMENT_TREE`, converted to a plain
  path (`Storage.treeUriToPath`). What is downloaded is found by scanning the folders, so
  files copied in by hand count.
- **Widget** (`widget/`): resizable; full (art, title, prev/back/play/fwd/next) → one row of
  five buttons → play/pause + next. The service pushes state; buttons go to
  `WidgetActionReceiver`, which connects a `MediaController` and runs the command. Play
  with nothing loaded resumes the last queue saved in `Prefs`.

## Gotchas found while building (verified on an API 34 emulator, 2026-11-01)

- **A BroadcastReceiver's own Context may not bind to a service**
  (`ReceiverCallNotAllowedException`). `WidgetActionReceiver` builds its MediaController
  on `applicationContext`.
- **An app update cancels every PendingIntent the app issued**, which leaves widget
  buttons dead until the widget is redrawn. `PlayerWidget` listens for
  `MY_PACKAGE_REPLACED` and redraws; `App.onCreate` redraws too.
- **A killed process never runs `onDestroy`**, so the widget could keep showing Pause.
  `App.onCreate` resets the saved state to paused.
- **Media3 strips `localConfiguration` between controller and session.** The playable
  URL travels in `requestMetadata.mediaUri`; `PlaybackService.resolve()` rebuilds the item
  and swaps in the downloaded file if there is one.
- The 2026.09 Compose BOM and navigation 2.10 need **compileSdk 37** (AGP 9.5, Gradle 9.7.1).

## Build

Toolchain is the one MWP-FM-Android documents (`~/.bubblewrap/config.json`: JDK 17 and
`~/Development/Android/Sdk` with platform `android-37.0`, build-tools 36.0.0).

```bash
scripts/build.sh     # signed release → dist/mwp-fm-player-<version>.apk, checks the signer
./gradlew assembleDebug
```

### Signing

| | |
| --- | --- |
| Keystore | `~/.config/mwp-fm-native/release.keystore` (PKCS12, alias `mwp-fm-native`), read via `keystore.properties` beside it, or `MWP_FM_NATIVE_STOREFILE` / `_STOREPASSWORD` / `_KEYALIAS` / `_KEYPASSWORD` env vars |
| Cert SHA-256 | `73:BF:2C:D3:57:DA:66:4B:0E:30:7C:7A:AA:E6:62:9C:A4:97:3B:BB:EE:6F:5A:55:B7:18:00:C3:51:18:27:C6` |
| Backup | 1Password vault `My Weird Prompts`: document "MWP FM Player (native Android) — release keystore", item "MWP FM Player (native Android) — keystore password" (`vo45kbnbykzhhywgewflgv2gva`). Password also in Infisical `mwp`/`prod` as `MWP_FM_NATIVE_STOREPASSWORD` |

Restore the key, never regenerate it: a new key breaks in-place upgrades for every install.

## Release

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. `scripts/build.sh`.
3. GitHub release `v<version>` (tier small: v1.0, v1.1, …; see CLAUDE.md) with `dist/mwp-fm-player-<version>.apk` attached.
4. The F-Droid repo ([My-Weird-Prompts/fdroid](https://github.com/My-Weird-Prompts/fdroid))
   picks up the newest release hourly (`gh workflow run publish.yml -R My-Weird-Prompts/fdroid`
   to publish at once).
5. Upload the APK to R2 `mwppublic` as `mwp-fm-player.apk` (latest) and
   `mwp-fm-player-<version>.apk` (served at `cdn.myweirdprompts.com`).
