# Broadway Direct lottery entry

Read directly from the site on 2026-10-12 (read-only; nothing was submitted).

## Listing
`https://lottery.broadwaydirect.com/` lists shows; each `/show/<slug>/` page has one `tr.lotteries-row` per
performance (row id = performance time as a Unix timestamp). The status cell wording changes with the date:

| State | Text | Notes |
|---|---|---|
| Upcoming, before midnight | `Opens 10/13/26 at 9:00 am` | dated |
| Upcoming, after midnight | `Opens Today at 9:00 am` | **"Today"/"Tomorrow", no date.** Missing this once caused "it's open" alerts at midnight. |
| Open | `Active/ Closes Today at 3:00 pm` | row also has an `a[href*=enter-lottery]` link |
| Closed | `Closed` | |

Open lotteries link to `/enter-lottery/?lottery=<id>&window=popup`. Each lottery is for ONE performance.
Times are the venue's (America/New_York). Typical windows: 9 AM to 3 PM the day before the show; some shows
open at 10 AM, The Lost Boys 7 AM to 2 PM.

## Entry form (`form[name=dlslot-entry-form]`, plain POST)
| Field id | Notes |
|---|---|
| `dlslot_name_first`, `dlslot_name_last` | must match the entrant's government ID |
| `dlslot_ticket_qty` | select: values `""` (0), `1`, `2` |
| `dlslot_email` | |
| `dlslot_dob_month` / `_day` / `_year` | numeric text, MM / DD / YYYY |
| `dlslot_country` | select by label: USA (value 2), CANADA (3), OTHER (5) |
| `dlslot_zip` | |
| `dlslot_agree` | "I agree to the Terms & Conditions" checkbox |
| hidden: `dlslot_nonce`, `dlslot_website` | |

**A Cloudflare Turnstile widget (`.cf-turnstile` with a sitekey) is part of the form.** The app does not try
to solve or skip it.

## What the app does
Tapping an open-lottery alert opens the entry page in the in-app browser and fills every field above from
the saved profile (Settings: name, email, tickets, date of birth, zip, country), then scrolls to the
agreement box. Left to the user: the agreement checkbox, the Turnstile check and the Enter button.

## Status lookup (separate)
`/check-status/` takes last name, email and date of birth (POST) and showed "No lottery entries were found."
for an account with none. Only works before the performance starts. Winners pay there ("Pay Now", 60 minutes).
The layout of an actual result has not been seen.
