# Lucky Seat: how listing, sign-in and entry work

Lucky Seat has no public API. Everything below is what its own web app (an Angular SPA at
`https://www.luckyseat.com`) calls, worked out in October 2026 by reading its network traffic and its
published JavaScript bundle. These endpoints are internal and can change without notice.

**Verified** = observed responding in a real signed-in session. **From client code** = read in the
site's JS bundle but not captured on the wire (the browser tooling used here doesn't show request bodies).

All paths are under `https://www.luckyseat.com/api`. Requests must come from a browser engine: a plain
HTTP client (including `curl` with a non-browser User-Agent) gets `403 Forbidden`. The app therefore
makes these calls with `fetch()` from a hidden WebView (`sites/WebFetcher.kt`).

## The one rule that shapes the app: entry needs a reCAPTCHA

Creating an entry sends a `captchaResponse` taken from Google reCAPTCHA (`grecaptcha.getResponse()`).
The site's own form refuses to submit without it ("Please Complete Captcha"). The app does not try to
produce, replay or skip that value. Everything around it is automated; the user ticks the box and
taps Submit.

## Listing (no sign-in)

| Call | Returns |
|---|---|
| `GET /showmanagement/getshowlist` | JSON array of shows. Used fields: `showId`, `showName`, `showAlias`, `market`, `timezone`, `isActive`, `isTempClosed`. New York shows have `market == "New York City"`. |
| `GET /showmanagement/getshowdetail/{showAlias}` | One show's detail: price, `maxTickets`, fees, terms. |
| `GET /performancemanagement/getfirstshowperformancesschedule/{showId}` | Performances, no per-user data. |

Verified. The schedule has `perfDateTimeList` (and `dayPerfTimes`). Each performance has:

- `performanceId` (int). Also the `id` of its checkbox on the entry form.
- `perfSchedule`: local show time, `2026-10-09T19:00:00` (no zone; use the show's `timezone`).
- `openString`: `"Monday, September 28, 2026 12:00 PM EDT"` when entries open. Parse the date and time, drop the zone abbreviation, apply the show's zone.
- `closeTime`: local, `2026-10-08T09:30:00`, when entries close (usually the morning before the show). `drawTime` and `purchaseDeadline` follow the same format.
- `userEntred` (sic): whether the signed-in user has entered it. Only meaningful on the signed-in schedule.

Performances open in **batches**: many share an `openString`, and a show can have several batches
open at once. A single entry can cover performances from different batches.

## Sign-in

`POST /account/login` with JSON `{"email": "...", "password": "..."}` (field names from client code).
Verified: returns 200 with `token`, `firstName`, `phone`, `phoneVerified`, `email`. No CAPTCHA was seen
on this call.

- `token` is a JWT valid about 20 days (`exp` claim). Authenticated calls send `Authorization: Bearer <token>`.
- The web app keeps the token in `localStorage` if "remember me" is ticked, otherwise in
  `sessionStorage`, next to `firstName`, `phone`, `phoneVerified`, `email`.
- **Quirk:** the site header sends any signed-in user to `/account/account-settings` when the stored
  `phone` is empty. Anything that pre-seeds a session must include the phone. It can be fetched with
  `GET /account/getLoggedInUserDetails` (verified keys: `firstName`, `lastName`, `email`, `zipCode`,
  `mobileNumber`, `country`, `phoneVerified`, marketing flags, `twitterHandle`).
- `phoneVerified == "false"` blocks entering ("Phone not verified").

## Signed-in schedule

`GET /performancemanagement/getshowperformancesschedule/{showId}` with the bearer token. Same shape as
the public schedule, with `userEntred` filled in. Verified. An expired or invalid token gets 401/403.

## Entering

`POST /entrymanagement/createentry` with the bearer token (verified to exist and return 200 on a real
entry). The JSON body is built from these fields (**from client code**):

| Field | Meaning |
|---|---|
| `showId` | `showId` from the show list. |
| `selectedPerfs` | The performances ticked on the form (checkbox ids are `performanceId`s). Exact element format not captured. |
| `selectedTickets` | Ticket count, 1 to the show's `maxTickets` (2 for Hadestown). |
| `captchaResponse` | reCAPTCHA token. Required. |
| `mobileNotification`, `showMarketing`, `venueMarketing`, `skill` | Form options. |
| `twitterShareResponse`, `facebookShareResponse`, `twitterHandle`, `specialSelectCheckboxResponse`, `accessableSeatRequired` | Optional/social/special-section options, normally empty. |

On success the reply includes `phoneVerified`; the site then goes to `/dash/mydashboard`. Errors come
back as strings (for example one containing "Phone not verified").

Lucky Seat's rules: one win per person ("you can only win once, so choose only the performances you
can attend").

## Entry form (DOM), for pre-filling

On `/dash/shows/{showAlias}` once signed in (verified):

- One `<input type="checkbox" id="{performanceId}">` per performance. Ticking it with `.click()` updates the form.
- Ticket count: `<input type="number" min="0" max="N">` with `.form-number-minus` / `.form-number-plus` divs. Click those rather than setting the value, so the site's own handlers run.
- reCAPTCHA container: `#capcha_element`. Submit button: "Submit Entry".
- Angular builds all of this after load, so wait for the elements to exist.

`/shows/{alias}` redirects to `/dash/shows/{alias}` when signed in.

## Your entries

- `GET /entrymanagement/getallentries/{page}`: array of the user's entries (verified, page 0 and 1 returned the same data; the app walks pages until one adds nothing new). Keys: `showId`, `showName`, `perfId`, `perfSchedule`, `selectedTickets`, `drawTime`, `timestamp`, `isWinner`, `result` (e.g. "Drawing on Monday, October 12, 2026"), `claimable`, `claimStatus`, `winnerLink`, `redemptionUrl`, `performanceTimePassed`, and others.
- `GET /entrymanagement/getallentriescount`, `getrecententriescount`: counts (verified, return a number).
- `GET /entrymanagement/getentryresult/{id}`: one entry's result (from client code, not called).
- `GET /entrymanagement/getrecententries/{n}`: did not respond within 6 seconds when called with 1 and 10; avoid.

## Where this lives in the app

- `sites/LuckySeatSite.kt`: listing, entered-status, `myEntries()`.
- `sites/LuckySeatAuth.kt`: sign-in, encrypted token storage, session seeding.
- `ui/SiteBrowser.kt`: signed-in in-app browser and the form pre-fill script.
- `sites/WebFetcher.kt`: the WebView that makes the calls.
