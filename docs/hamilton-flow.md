# Hamilton lottery flow (Android app)

The Broadway Hamilton lottery is entered in the official Hamilton app (package `com.hamilton.app`);
there is no web listing we can use. Mapped October 2026 by reading the app's screens over adb
(`uiautomator dump`) and screenshots, WITHOUT submitting an entry. The app exposes only content
descriptions (no view ids), so every element is found by its description.

## Screens

| # | Screen | What identifies it | Action |
|---|---|---|---|
| 1 | Home | Clickable tile with description `Lottery` | Tap it |
| 2 | Lottery list | Tabs `BROADWAY / TAMPA / ORLANDO`; one card per performance with a description like `Richard Rodgers Theatre, New York, NY / PERFORMANCE TIME / October 20, 2026 / 7:00pm / LOTTERY CLOSING TIME / October 15, 2026 / 12:00pm / <countdown>` | Pick the BROADWAY tab, tap a card |
| 3 | Entry page | `Number of Tickets` with a `-` button, the count, and a `+` button (disabled at the 2-ticket max) | Set the count |
| 4 | Same page, scrolled | Checkbox `I accept the Term of Service, Privacy Notice, and lottery's official rules...`; checkbox `I have reviewed my profile and understand winning tickets must be picked up ... in person with a valid legal id ...`; button `Submit Your Entry` (not clickable until both are ticked) | Tick both, tap Submit |
| 5 | Back on the list after Submit | The performance's card becomes a banner: `YOU'VE ENTERED! / You have successfully entered / the lottery for / October 20, 7:00pm` (no PERFORMANCE TIME text any more) | The runner checks for this banner. The screen right after Submit itself still hasn't been seen. |

The page also shows the entrant's name and an "Update your Profile" link; the runner never reads or
edits them. No CAPTCHA, payment or login screen appeared.

## Notes

- Entered performances stop looking like cards (no `PERFORMANCE TIME`), so an entered one is never entered twice. The star count in the header also goes up (365 -> 370 after one entry).
- Several performances are listed at once (one card each), each with its own closing time, so the user's
  days and times choose which cards to enter.
- The app has a setting "Send me Broadway lottery reminder notifications" (on by default). The
  accessibility service turns those notifications into a tap-to-enter alert. Their wording has not been
  observed yet.
- The two checkboxes are consent statements. The runner ticks them for the user, so the first run asks
  for an explicit OK in this app (`Repo.hamiltonConsent`).
- Same constraints as TodayTix: the phone must be awake and unlocked, and the user starts each run.
