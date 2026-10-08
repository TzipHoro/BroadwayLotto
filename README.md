# Broadway Lotto

Android app (Kotlin, Jetpack Compose) that watches Broadway lotteries for the shows you pick and
alerts you when one opens with performances on days/times you can attend.

## Run it
Open this folder in Android Studio, let Gradle sync, run on a device/emulator (API 26+).
Debug builds forget which lotteries were already alerted on every launch, so open ones re-trigger.

## Sites
- **Lucky Seat** (New York market): real listings, sign-in, entered-status, and a signed-in in-app
  browser that pre-ticks matching performances. The site requires a reCAPTCHA on every entry, so the
  user ticks that and submits; the app does not try to bypass it.
- **Broadway Direct**: real listings; alerts and opens the show page. Entry is not automated yet.
- **TodayTix**: mock data only.

Both real sites reject plain HTTP clients (403), so listings are fetched through a hidden WebView
(`sites/WebFetcher`), the same browser engine a user would use.

## How it works
- `model/`: Watch (show + site + optional per-show availability), default availability, Lottery,
  EntryRecord, JSON-backed `Repo`.
- `sites/SiteAdapter`: one adapter per site (`LuckySeatSite`, `BroadwayDirectSite`, `MockSite`).
- `sites/LuckySeatAuth`: sign-in and session. The password is never stored by the app: the UI offers
  it to Google Password Manager (Credential Manager). Only the session token is kept, encrypted with
  an Android Keystore key; when it expires the user is asked to sign in again (one approval).
- `engine/EntryEngine`: per show, gathers everything enterable right now that fits the user's
  availability, sends one alert per show, and records per-lottery progress separately from the
  visible Activity log.
- `engine/Scheduler`: exact alarm at the next opening time plus a 15-minute WorkManager safety net.
- `ui/`: Shows, Entered (what the sites say you've entered), Activity, Settings; `SiteBrowser` is the
  in-app signed-in browser.

## Notes
- Check each site's terms before adding automation; keep to one entry per show per person.
- Lucky Seat's listing and entry endpoints are internal and can change without notice.
