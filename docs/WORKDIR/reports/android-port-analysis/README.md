# Android port — engineering analysis (branch `New-UI`)

A full technical analysis of `redaf12-Decode/Generals-Android-Port` at branch `New-UI`, produced
2026-11-04. **Analysis only — no source, build or configuration file was changed.**

## Deliverables

| File | What it answers |
|---|---|
| [`PROJECT_OVERVIEW.md`](PROJECT_OVERVIEW.md) | What the repository is, its lineage, its branch situation, and the eight questions the analysis was asked to answer |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Directory roles, the 25 Java classes, the 8 activities, data flow from home-screen tap to game loop, the marker-file interface, lifecycles, the coupling map, platform isolation |
| [`BUILD_REQUIREMENTS.md`](BUILD_REQUIREMENTS.md) | Every tool and version, what is in the repo vs. fetched vs. generated, the two halves of the build, the step-by-step procedure, Gradle and signing configuration, release procedure |
| [`FEATURES_ANALYSIS.md`](FEATURES_ANALYSIS.md) | 16 implemented feature areas, each with its files, mechanism, dependencies, limits and opportunities — plus what is *not* implemented |
| [`UI_ANALYSIS.md`](UI_ANALYSIS.md) | The launcher: screens, navigation, the `UiKit` component library, the design tokens, the **navy/gold** palette, adaptation, state, error handling, reuse, and the twelve improvements that preserve the visual identity |
| [`ENGINE_ANALYSIS.md`](ENGINE_ANALYSIS.md) | The engine's source and version, library load order, arguments and environment, the startup sequence, every Java↔native crossing, storage, backends, architectures, limitations, and the safe extension points |
| [`ISSUES_AND_RISKS.md`](ISSUES_AND_RISKS.md) | 17 sections of defects, duplication, dead code, coupling, concurrency, performance, compatibility, build warnings, security, resource handling and documentation drift — each with quoted evidence and a confidence level |
| [`ROADMAP.md`](ROADMAP.md) | Prioritised improvement plan (P0–P7) with problem, change, files, dependencies, risk, priority and prerequisites; ground rules; execution order; six open questions for the owner |
| [`CI_AND_WORKFLOWS.md`](CI_AND_WORKFLOWS.md) | GitHub Actions: every workflow, `build-android.yml` step by step, the run history, the root cause of the observed failure, and whether CI meets the project's real requirements |

## How to read it

Every claim is tagged:

* **[FACT]** — read directly from a file, the GitHub API, or a measurement.
* **[INFERENCE]** — a conclusion drawn from several facts, with the reasoning given.
* **[CONFIRMED] / [LIKELY] / [SUSPECTED]** — the confidence attached to a defect in
  `ISSUES_AND_RISKS.md`.

Unverified areas are listed explicitly at the end of each report rather than guessed at.

## The three things to know first

1. **`New-UI` is a mirror branch.** `main`'s `mirror.yml` overwrites it every 6 hours from
   `MYSOREZ/GeneralsZH-Android-Port`. Do not commit to it.
2. **The release path is broken on this fork.** `main` holds a different project shape and cannot be
   fast-forwarded from `New-UI`; CI builds only Zero Hour at 30 Hz, which cannot satisfy
   `publish-release.yml`. Both facts are in `ISSUES_AND_RISKS.md` §5 and §11.
3. **The launcher is the only part of this tree without tests.** 14 853 lines of Java, no test
   source set, and the riskiest code (mod import, update verification, BIG parsing) is exactly the
   code where a mistake is silent. `ROADMAP.md` §5 proposes the first three tests.
