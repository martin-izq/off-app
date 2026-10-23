# Off

**Life happens off.** Off is an Android game you win by putting your phone down. You earn points for the time your phone stays locked, and you play it best with friends: going over on social media feeds a group pot that is only settled when you meet in person.

No account, no servers, no ads. The app has no INTERNET permission: everything is computed and stored on the phone, and groups sync face to face by QR code.

[Website](https://martin-izq.github.io/off-app/) · [Privacy policy](https://martin-izq.github.io/off-app/privacy) · [Play Console notes](docs/play-console.md)

## How the game works

- **Locked time:** 1 point per 30 minutes locked, 8 am to midnight, plus a bonus for every 2 hours straight.
- **Unplugged steps:** steps from Health Connect count only while the phone is locked. Workouts such as cycling or gym count by minutes.
- **Fewer unlocks** than your weekly average add points when the day closes.
- **Sessions** of 30 min, 1 h or 2 h double their blocks if you never unlock.
- **Social media** over your weekly average costs 1 point every 5 minutes.
- **Goal and streak:** hitting the daily goal pays a bonus that the streak multiplies, with one free pass a week.
- **IRL groups:** debt from social media goes into each group's pot. At a meetup, phones exchange QR codes and every phone computes the same split. During a hangout, blocks with every phone locked count double and the first to unlock pays 5 points.
- **Sharing:** weekly cards in four designs, a card for each settled pot and a year in review in December.

The game rules are fixed constants in `Score.kt` and `Ronda.kt`.

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 36, minSdk 28).

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests for scoring, IRL and sharing
./gradlew assembleDebug -PidSuffix=.prueba   # a separate test copy that installs next to the real app
```

On first launch the app needs usage access (Settings › Usage access › Off). Health Connect and notifications are optional.

## Project layout

Everything lives in one package, `app.getoff`, under `app/src/main/java/app/getoff/`:

| File | What it holds |
| --- | --- |
| `Score.kt` | Pure scoring rules: blocks, rests, unlocks, steps, exercise, social media, goal, streak, levels |
| `Ronda.kt` | Pure IRL logic: pot split, hangout bonus, first to unlock, the QR code format |
| `Usage.kt` | Reads Android usage events, builds each day and the `Snapshot` the UI shows |
| `Health.kt` | Health Connect: steps and workouts |
| `Store.kt` | SharedPreferences storage |
| `ScoreWorker.kt`, `Session.kt` | Background scoring every 15 minutes, notifications, sessions |
| `Today.kt`, `History.kt`, `Irl.kt`, `Sheets.kt`, `Onboarding.kt` | Compose screens |
| `Invite.kt` | Play Store invite links that carry the group in the install referrer |
| `Share.kt`, `WeekCards.kt` | Share images drawn with Android Canvas at 1080×1920 |
| `Theme.kt` | Colors, Geist, shared components and the `t("es", "en")` text helper |
| `Widget.kt` | Home screen widgets |

**Languages:** English is the default and Spanish is used when the device is in Spanish. User-facing text is written inline as `t("español", "English")`; `strings.xml` is only used for text that lives in XML (widgets).

**Store listing:** texts, the icon, the feature graphic and screenshots for English and Spanish (es-419) are in `fastlane/metadata/android/`.

## Fonts

Geist and Geist Mono by Vercel, under the SIL Open Font License 1.1.
