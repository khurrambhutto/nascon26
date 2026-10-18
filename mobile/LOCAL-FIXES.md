# Local connectivity, offline and management fixes

These changes are local only. No commit or push is needed to build or test them.
The locked Day 1 APK at `mobile/release/app-release.apk` is preserved.

## Use the phone without a USB cable

1. Keep Docker Desktop and the backend running on the laptop.
2. Put the phone and laptop on the same Wi-Fi or phone hotspot. Campus Wi-Fi may isolate devices.
3. Run `powershell -File scripts/Start-Wireless.ps1` to print the laptop's LAN addresses.
4. In the app's Server address settings, enter `http://<laptop-Wi-Fi-IP>:8080/`, then **Save and test connection**.
5. A saved wireless address is tried automatically if the USB address stops answering. The app checks the backend every ten seconds, including while USB previously appeared healthy.
6. If Windows blocks connections, run `powershell -File scripts/Start-Wireless.ps1 -AllowFirewall` from an administrator PowerShell. This permits TCP 8080 only from the local subnet.

`127.0.0.1` on the phone refers to the phone. It needs `adb reverse tcp:8080 tcp:8080`, and that tunnel can disappear after disconnecting USB. Wireless access needs a reachable laptop address; an unplugged phone cannot reach a laptop without a network path.

Build a local APK using the current LAN address (it may change when switching networks):

```powershell
cd mobile
./gradlew.bat testDebugUnitTest assembleDebug -PapiBaseUrl=http://<laptop-Wi-Fi-IP>:8080/
```

Install `mobile/app/build/outputs/apk/debug/app-debug.apk`. It appears as **ArenaOne Local** (`com.nascon.league.local`) alongside the submitted app. The existing APK is signed with a different key, so replacing it with this laptop's signature would require an uninstall. This separate debug package preserves the original app and any unsynced data in it. Sign in online once in ArenaOne Local before testing offline. Local data is not shared between the two packages. Release builds retain the original application ID. Do not replace the locked submission APK during the overnight lock.

## Offline verification on a physical device

1. Sign in online. Visit Sync and wait for **Preparing saved screens** to finish. Initial successful sign-in warms league, club, sport, fixture, match, standings and store caches.
2. Open an upcoming match as admin/organizer. Turn off Wi-Fi/mobile data, start the match, add scores and undo a score. The local score and timeline should change; Sync should show pending actions.
3. Force-stop and reopen the app. The session, cached screens and pending actions should remain.
4. Restore Wi-Fi. Actions should replay in order once, with applied/duplicate/rejected outcomes retained after restart. A conflict uses the server result.
5. Repeat with a manager's transfer/loan/release. Test a deliberately invalid move to see the server rejection and authoritative roster restored.
6. Sign out with pending actions and use another previously authenticated account offline. The original account's action details remain private, but its saved work uploads automatically on reconnect, without signing back into that account. Delivery uses its own encrypted JWT; the current viewer's privileges are never used. First-time accounts require an online sign-in; cached offline sign-in checks the password against an encrypted salted PBKDF2 proof.
7. Expired tokens retain the queue and display a sign-in message in Sync. Sign in online again to replay it.

The reconnect callback, foreground retry and native persisted `QueueSyncJob` scan all pending owners for this server. Completed upload refreshes fixture/standings views. Dedicated outbox credentials are removed when that owner's queue drains. Older queued work can recover the encrypted original-account token already saved for offline sign-in. Android may defer background jobs; system force-stop blocks background work until reopening the app.

Shopping payments, new players, league/match creation, and inventory management need a live server. Cached reads and scoring/roster replay support offline use.

## Role-specific screens

- Admin: Management home, create league with sport/team selection, schedule match, shop inventory/prices, add products, order fulfilment/refunds, all club rosters.
- Organizer: Management home and scheduling for the assigned league; match scoring, status and product pinning.
- Club manager: own club roster, own shop inventory/products and eligible orders.
- Player/viewer: Matches, Table, Store, Club, Me and order history.

Create a basketball/cricket/esports league as admin, select its teams, then schedule a match. Its sport rules drive the existing scoring panel. Organizers can schedule only in their assigned league.

## Additional artwork and mixed-sport Matches home

The six cricket/esports crests listed in `assets/NEW-ASSETS.md` are bundled in `drawable-nodpi` and used in match, standings and club views. The training duffel and hydration bottle images appear in the customer catalogue/details and staff product management. Alpha transparency and original proportions are retained. The launcher now uses `assets/brand/arenaone-ao-app-icon-v4.png`, copied unchanged to `arenaone_logo.png`; the sign-in page keeps its text-only ArenaOne title.

Matches starts with **All sports**, showing one actual football, basketball, cricket and esports fixture. Sport labels identify each card. Choose a league filter for its complete fixture list, including the original six football fixtures and both cricket fixtures (covering all four cricket clubs). Demo clubs/leagues/products are added on startup without resetting existing scores, stock or user changes.

## Email receipts

Checkout has an editable receipt address field; leave it blank to opt out and download the PDF in the app. Successful payment queues the signed PDF for the local SMTP worker. Declined/failed payments never create mail jobs. The receipt screen polls status while open and offers **Open local inbox**. An unavailable inbox does not undo the purchase or hide the PDF.

The default Compose configuration points to the internal `local-mail:1025` SMTP sink. It needs no Google credentials or internet access; this mode captures messages without delivering them to a public inbox. Existing captures remain available at `http://<laptop-IP>:8080/local-mail` using the purchasing account (or admin).

The user's ignored local `.env` now overrides those defaults with Gmail SMTP, STARTTLS and external delivery. Its app password stays in that ignored file. A real purchase-receipt test was accepted by Gmail SMTP with the generated PDF attachment. `.env.example` retains the credential-free local defaults for clean clones.

## Ten-minute match reminders

1. Open an upcoming match and tap **Remind me · 10 minutes before**.
2. Allow Android notifications and, when requested, **Alarms & reminders** access for precise timing. If either permission is denied, the app explains the missing access and does not claim that a reminder is armed.
3. The saved alarm triggers ten minutes before the configured kickoff. Selecting a match already within the ten-minute window schedules a notification shortly instead. Tap the notification to open that match; tap **Cancel reminder** beforehand to remove it.
4. Bookings persist through normal process death, reboot and app updates. Signing out suspends that account's alarms; signing back in restores future bookings. Notification permission can be changed in Android Settings.
5. A received reschedule moves the alarm; a received postponement, cancellation or match start removes it. The receiver makes a bounded attempt to refresh match state before notifying. When offline, it uses the last saved schedule and cannot know about unseen server changes.
6. Android controls delivery under battery restrictions. Force-stopping an app in system Settings blocks background delivery until it is opened again; this is distinct from closing it in Recents.

Physical test: schedule a demo match 12 minutes ahead, allow permissions, enable a reminder, close the app, and verify the notification roughly two minutes later. Repeat after reboot, after rescheduling, and after denying permission. Keep this test separate from the required offline scoring/roster desk check.

The connected-phone run on October 18 verified permission prompts, exact reminder delivery while backgrounded, notification-to-match navigation, Wi-Fi-only API access without USB reverse forwarding, offline score persistence across a process restart, and automatic replay after reconnect. Detailed results and remaining backend-only controls are in `DEVICE-VERIFICATION.md`.

## Shop artwork, raised buttons and animated startup

The original season pass and supporter scarf PNGs are restored in `assets/products` and bundled as `season_pass.png` / `supporter_scarf.png`. `ProductArtwork` uses them in the catalogue, product details and staff product management, preserving their proportions.

The added universal raised-button treatment was reverted at the user's request. Filled, outlined, text and icon actions use their original Material styling, and the shop plus controls are flat again. Only the pre-existing `PrimaryButton`, `Pill`, scoring controls and raised cards retain their original bottom edges. Colours, callbacks and test tags are unchanged.

Startup follows `assets/brand/WAVE-SPLASH.md`: the non-looping wave GIF reveals AO on terracotta, holds the finished mark, then opens sign-in or the saved session. Native `ImageDecoder` plays it on Android 28+, with `Movie` for Android 26/27. Disposal stops the decoder; a five-second fallback prevents a stuck launch. No network download or new dependency is needed. Recomposition does not replay the splash.
