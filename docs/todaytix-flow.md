# TodayTix lottery flow (Android app)

TodayTix lotteries and rush tickets are available only in the TodayTix phone app (package
`com.todaytix.TodayTix`). The website lists which shows have them (`/nyc/category/rush-tickets`) but
has no entry, no times and no API we can use. Mapped October 2026 by reading the app's screens over
adb (`uiautomator dump`) while navigating, on one real lottery. Screens can change with app updates.

## Screens, in order

| # | Screen (activity) | What identifies it | Action |
|---|---|---|---|
| 1 | Home, "Lottery & Rush" filter (`MainActivity`) | `filter_label` ("N matching results"); each show is a card whose content description is the show name | Find card by name (scroll), tap it |
| 2 | Show page (`ProductDetailsActivity`) | Text "Enter now" (only while open) and "Open until Today at 9:00 AM" below the fold | Tap "Enter now" |
| 3 | "How many tickets?" (`LotteryActivity`) | id `header_view`; buttons with text "1" and "2" | Tap the count |
| 4 | "Select showtimes" (every row arrives **pre-ticked**, so the runner sets each box exactly) | Rows with `date_label` ("Sun, Oct 11"), `time_label` ("7:00 PM"), `checkbox_view`; `next_button` | Tick matching rows, tap Next |
| 5 | "Contact Information" | Prefilled name/email/phone (never read or written); `action_button` text "Enter" | Tap Enter. **This is the real submit.** |
| 6 | "Share to increase odds" | `header_view`; Instagram/Facebook share buttons | Confirms the entry. Close with `close_button`; never share. |

Entries are listed under **Orders > Lottery** (`show_title`, `show_date_part_1` e.g. "Sun, Oct 11,
7:00 PM", `num_seats`, `badge` "Entered"). The app reads it first to skip performances already entered.

No CAPTCHA, payment or login screen appeared anywhere in this flow.

## Notes

- The performance a lottery covers is only visible at step 4, after choosing a ticket count.
- Step 5's button is the submit, and step 6 is shown *after* it. (An early assumption that Enter was not
  final was wrong; one real Rocky Horror entry was made while confirming this.)
- The home list is a Compose list whose positions shift; always find cards by name, never by coordinates.
- Android won't let an app silently launch another app's screens from the background, and an
  accessibility service can only act on a screen that is showing, so a run needs the phone awake and
  unlocked and is started by the user (a notification tap or a show's "Enter lottery now" button).
- The service is limited to the TodayTix package in `res/xml/todaytix_service.xml`. The runner stops on
  any login, payment or verification text (`guardUnexpected`).

## How an entry gets started

TodayTix publishes no open times, so the app can't predict one. Two triggers instead:

1. **TodayTix's own alert.** The user turns on a show's lottery alert inside the TodayTix app. The
   accessibility service (limited to the TodayTix package) receives that notification event, and if its
   text mentions a selected TodayTix show and a lottery-ish word (`lottery`, `enter`, `open`,
   `drawing`, `win`), the app posts its own "TodayTix lottery open: <show>" notification. Tapping it
   opens the app, which launches TodayTix and runs the flow above for that show only. The wording of
   TodayTix's alerts has not been observed yet, so the matching is deliberately loose and may need
   tuning after the first real alert.
2. **A button on the show's card** ("Enter lottery now") for manual use.

Both need the phone awake and unlocked, since Android won't let an app drive another app's screens from
the background.

- A first run stopped on the Contact screen without submitting. The button is found, enabled and clickable
  in a screen dump, so the working theory is that it ignores the accessibility click action (the earlier
  buttons accept it). That has NOT been confirmed on the phone: the runner now presses it with a real touch
  if the click doesn't move the screen on, and the service config sets `canPerformGestures` for that.
