# Blockthis

A native Android app blocker. Create multiple block lists, each with its own
apps and its own rules — weekly schedules, manual focus sessions, daily usage
limits, or a plain on/off block — and optionally make a list **strict** so
that, while it is blocking, neither the block nor Blockthis itself can be
switched off or uninstalled.

Built with Kotlin and zero external dependencies (platform APIs only).

## Features

- **Multiple block lists** — e.g. *Social media*, *Games*, *Work hours* — each
  with its own set of apps and rules, all active in parallel.
- **Four blocking modes per list:**
  - **Always** — blocked whenever the list's switch is on.
  - **Schedule** — weekly time windows per day of the week (e.g. Mon–Fri
    09:00–17:00). Windows may cross midnight (22:00–06:00), and equal
    start/end means 24 hours. Multiple windows per list.
  - **Focus sessions** — start a timed block on demand (e.g. 90 minutes).
  - **Daily limit** — the apps are usable until their combined foreground
    time for the day exceeds an allowance (e.g. 60 min), then blocked until
    midnight.
- **Strict mode** — while a strict list is actively blocking:
  - the list locks itself: it cannot be edited, toggled off or deleted;
  - the uninstall path is closed: package installers, the Play Store and the
    sensitive Settings screens (device admin, accessibility, app info / app
    lists) are blocked by the same overlay that blocks apps;
  - with device admin enabled, Android itself refuses to uninstall the app.
- **Paranoid option** — additionally block the *entire* system Settings app
  while any strict block runs.
- **Safety rails** — the launcher, dialer/emergency apps, SystemUI and the
  keyboard can never be blocked; *Always* lists can never be strict, so a
  mistake is always recoverable. For a deliberately permanent strict block,
  use a strict schedule covering 24 h every day.

## Install & set up

1. Install the APK (`dist/Blockthis-debug.apk`, or build it yourself — see
   below). Sideloading requires allowing installs from your browser/file
   manager.
2. Open Blockthis and tap **Blocking service** → enable *Blockthis* under
   Accessibility. This is what detects and covers blocked apps; without it
   nothing is blocked.
3. Tap **Uninstall protection** → activate the device admin. Optional but
   recommended: it is what makes the app undeletable during strict blocks.
4. Create a block list: pick apps, pick a mode, optionally enable **Strict**.

## How enforcement works

An `AccessibilityService` receives window-change events and re-evaluates the
foreground app every 5 seconds (so a block that starts while you are inside an
app is enforced within seconds, and daily-limit usage is metered). When a
blocked surface is in the foreground it draws a full-screen accessibility
overlay and bounces you to the home screen.

Uninstall protection is two layers:

1. A device admin with **no policies**. While active, Android refuses to
   uninstall the app; removing it requires the device-admin settings screen.
2. During any active strict block, the accessibility service blocks the
   screens that could undo the setup: device-admin settings, accessibility
   settings, app-info/app-list pages, every package installer/uninstaller
   dialog, and the Play Store (which can also uninstall apps).

Outside of strict blocks, everything can be turned off normally.

## Honest limitations

No non-system app can make itself literally impossible to remove — that would
require device-owner provisioning. What Blockthis does is what the established
strict blockers do: close every normal path while a block runs. Determined
circumvention is still possible via **safe mode** (system boots without
third-party apps), **ADB** from a computer, or OEM settings apps whose screen
titles the heuristics don't recognize (the *paranoid* option covers most of
those by blocking Settings wholesale). A factory reset always works. If you
need it harder than that, the honest answer is device-owner/MDM provisioning,
which is a different setup for a dedicated device.

Also worth knowing:

- Daily-limit metering counts foreground time only while the accessibility
  service is running; it is not driven by the system's UsageStats.
- A 24/7 strict schedule really is permanent until its window would end
  (i.e. never) — safe mode/ADB aside. Create one only on purpose.
- The bundled `dist/Blockthis-debug.apk` is signed with a throwaway debug
  key. If you later build your own APK, uninstall the old one first
  (signatures won't match), which requires no strict block to be running.

## Building

Standard Android Gradle project (AGP 8.10, Kotlin 2.0, compileSdk 35,
minSdk 26). With Android Studio: open and run. From the command line:

```
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/src/main/java/com/blockthis/app/
  model/Model.kt        Block lists, modes, schedule rules (+ JSON persistence)
  data/Store.kt         Profile store (SharedPreferences) and daily usage meter
  engine/BlockEngine.kt Schedule math, block decisions, self-protection rules
  service/BlockerAccessibilityService.kt  Enforcement: events, tick, overlay
  admin/BlockerDeviceAdminReceiver.kt     Empty device admin (uninstall guard)
  ui/                   Main screen, list editor, app picker
```

## Privacy

Everything stays on the device. No network access, no analytics, no data
collection. The accessibility service reads only the foreground package name
and window titles of Settings screens; it does not read or store screen
content or input.
