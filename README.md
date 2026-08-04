# Vespian

A predictive sleep model for **Delayed Sleep Phase Syndrome (DSPS)** and ADHD.

Most sleep apps try to force a fixed bedtime. That works for neurotypical people
and burns out everyone else. Vespian does the opposite: it does not tell you when
you *should* sleep. It predicts when you **will** be able to fall asleep, and
how confident it is about that prediction.

## What it does

- Reads sleep sessions, sleep stages, heart rate and SpO2 from **Health Connect**.
- Stores every night in a local Room database, forever. Raw heart rate and light
  samples are kept for 90 days, which is all the model ever looks back at.
- Samples ambient light through the phone's light sensor.
- Fits a **two-process model** of sleep regulation (Borbély, 1982) with a
  particle filter, and reports a confidence percentage with every prediction.
- Talks to you through a **Telegram bot**: two taps in the morning, one push in
  the evening.

No server. No account. No cloud. Everything runs on the phone.

## Why the confidence percentage matters

A sleep model with three nights of data is guessing. A sleep model with sixty
nights is not. Most apps hide this and present both with the same authority.
Vespian never shows a bare time. It shows a time, a range, and how much it trusts
itself.

| Confidence | What you get |
|---|---|
| ≥ 75% | Exact time. Plan around it. |
| 50–74% | Time plus a range. |
| < 50% | Range only. Never a single point. |

## ⚠️ Read this before you fork

**This project is built around one specific setup:**

- Xiaomi Smart Band 9 Active
- Mi Fitness app
- Health Connect on Android
- realme GT Neo 5 (ColorOS / realme UI)

**Your setup is almost certainly different, and it will not just work.**

Wearables disagree about everything that matters here. Some report sleep stages,
some only report "asleep". Some write background heart rate around the clock,
some only write heart rate during workouts — and if yours does the latter, the
nightly heart-rate minimum, which this model uses as a circadian phase marker,
simply does not exist for you. Vendor apps differ in whether they backfill
history, how often they sync, and whether they sync at all without being opened
by hand. Android OEMs differ wildly in how aggressively they kill background
work.

So treat this as **a reference implementation and a set of ideas**, not a
product. Fork it, read `HealthRepo.kt` first, and adapt the ingestion layer to
whatever your device actually produces. The model itself is device-agnostic;
the plumbing is not.

The built-in **Self-test** screen exists exactly for this. Run it first. It will
tell you which of these assumptions hold on your device before you waste time.

## Build

No local toolchain required. This repository builds itself on GitHub Actions.

1. Fork or copy this repository. Keep it **public** — Actions minutes are free
   and unlimited for public repositories.
2. Push. The workflow runs automatically.
3. Open the **Actions** tab, pick the run, download the `vespian-apk` artifact.

The Gradle wrapper is intentionally absent. Its `.jar` is a binary and cannot be
uploaded through the GitHub web editor on a phone, which is the whole point of
this setup. CI installs Gradle itself.

## Install

The APK is debug-signed and meant for sideloading. Health Connect requires
Google's approval only for Play Store distribution — a personal sideloaded build
needs none.

On first launch, grant the Health Connect permissions, then run the self-test.

## Languages

English and Russian. Switch in Settings, or leave it on system default.

## Project layout

```
app/src/main/java/dev/vespian/
  MainActivity.kt          status screen
  SettingsActivity.kt      language, self-test
  diag/SelfTest.kt         end-to-end diagnostics
  db/                      Room: nights, light, answers, model, meta
  health/HealthRepo.kt     Health Connect ingestion
```

## Status

| Part | Contents | State |
|---|---|---|
| 1 | Skeleton, database, Health Connect ingestion, settings, self-test | ✅ done |
| 2 | Particle filter, two-process model, predictions | ✅ done |
| 3 | Telegram bot, light sensor service, background jobs, widget | ✅ done |
| 4 | Screens | ✅ done |

Known gaps, in the order they are worth closing:

- The confidence percentage is derived from the width of the band, not
  calibrated against the hit rate the app already records.
- No automated tests. The model math is only ever checked by living with it.
- Drinks are asked as a count per day, without the time they were consumed.
- SpO2 is imported and stored but not yet used by the model.
- Naps shorter than two hours are ignored entirely.

## Not a medical device

Vespian is a hobby project, not a medical device, and it does not diagnose or
treat anything. Its predictions are a model's best guess from your own past
nights, and the model is sometimes wrong — which is why every prediction is
shown with how confident it is. Nothing here replaces a doctor, and nothing here
should be used to decide anything about medication.

## License

GNU General Public License, version 3 or later. The full text is in
[LICENSE](LICENSE).

```
Copyright (C) Vespian contributors

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see <https://www.gnu.org/licenses/>.
```

This notice is written to be permanent. It carries no year and no version
number of the app, and it says "version 3 or, at your option, any later
version", so it covers every build that has ever been made from this repository
and every build made from it in the future. Releasing an update does not require
touching it. Copyright is credited to "Vespian contributors" rather than to a
list of names, so a new contributor does not require an edit either.

In short: anyone may use, study, change and share this app for free, and anyone
who distributes a changed version has to publish their source too, under the
same licence. It cannot be turned into a closed paid product.
