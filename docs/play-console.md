---
title: Off · Play Console
---

# Play Console: what to fill in

Notes for publishing Off on Google Play. The store listing texts and images live in `fastlane/metadata/android/` (en-US and es-419), in the layout fastlane and Gradle Play Publisher expect.

## Before the first upload

- [ ] Create the Google Play developer account (USD 25, one time) and verify identity.
- [ ] Check the testing rule for new personal accounts in Play Console. As of 2026 it was a closed test with at least 12 testers for 14 days before production access.
- [ ] Generate the upload key and keep the keystore file and its passwords outside the repo (a password manager plus a backup copy). Without it, no more updates can be published.
- [ ] Create the app: name "Off", default language English (United States), app, free.
- [ ] Add the Spanish (Latin America, es-419) translation for the listing.

## App content answers

**Privacy policy URL:** https://martin-izq.github.io/off-app/privacy

**Ads:** No, the app has no ads.

**App access:** All features are available without an account or login. Usage access must be granted on first launch (Settings › Usage access › Off).

**Target audience:** 18 and over (teens 13–17 may be added later). Not designed for children.

**Data safety:**
- Does the app collect or share user data? **No.** Off has no INTERNET permission and nothing leaves the device. Health, usage and group data are processed on the device only. IRL group data moves phone to phone by QR code, scanned by the user.
- Is data encrypted in transit? Not applicable: there is no network transfer.
- Can users request deletion? Uninstalling the app deletes all data.

**Health Connect declaration** (Health apps form and the Health Connect permissions declaration):
- Permissions: READ_STEPS, READ_EXERCISE, READ_HEALTH_DATA_IN_BACKGROUND.
- Purpose: fitness and wellness gamification. Steps taken while the phone is locked and workout minutes give points in the game. Background read lets the daily score update while the app is closed.
- The app never writes health data, never transfers it off the device and never uses it for ads.
- Rationale screen: the app handles `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` and `VIEW_PERMISSION_USAGE` with an explanation screen.

**Usage access (PACKAGE_USAGE_STATS):** not a Play-restricted permission, but the listing and onboarding explain it: it is the core of the game (locked time, unlocks, social media time).

**Camera:** used only by the QR scanner for IRL meetups.

**Content rating questionnaire:** no violence, no sexual content, no gambling with real money (the group "pot" is game points only, never money), no user-generated content shared online, no location sharing.

**News app / COVID / government:** No.

## Release

- versionCode 1, versionName 0.1 (app/build.gradle.kts).
- Build an Android App Bundle (`./gradlew bundleRelease`) once the release signing is set up.
