# Local verification — October 18, 2026

All work remains uncommitted. Git HEAD remains `7813699`. The locked release APK was not overwritten. The installed test application is **ArenaOne Local**, package `com.nascon.league.local`.

## Artwork and build

- Applied the newest terracotta AO launcher icon, `assets/brand/arenaone-ao-app-icon-v4.png`. Source, Android drawable and packaged APK bytes match (SHA-256 `0a76e667f634a84d13e6c541d1e4aac5cb86e11e8f331bcb093b824c169bcc75`).
- Sign-in still shows the text-only **ArenaOne** title, as requested.
- `testDebugUnitTest lintDebug assembleDebug` succeeded. Eleven tests passed: four offline projections, four reminder timing cases, three live Retrofit/PostgreSQL contract tests. No tests skipped in the opt-in live run.
- Lint: no errors; warnings include synchronous durable preference writes, intentional LAN cleartext configuration, launcher shape and API/style suggestions. Dependencies were not upgraded.

## Automated backend and integration checks

| Check | Result |
|---|---|
| Full backend pytest suite | 165 passed; 93% rounded statement coverage, including 100% auth/store coverage. The standalone worker loop is outside unit-test coverage; its actual delivery was checked below. |
| Disposable PostgreSQL acceptance probe | Health, mobile reads, cross-tenant denial, PDF receipt and idempotent offline replay passed. |
| Concurrent checkout burst | Twenty simultaneous buyers for one stock item: one success, nineteen conflicts, no overselling. |
| Live score stream | An SSE connection received the committed score and updated event timeline. |
| Actual Android Retrofit DTOs | All five roles decode visible leagues/clubs/products, four-sport fixtures, match details, sports, standings, checkout preferences and orders. Staff inventory and league-management reads decode successfully. |
| Android client mutations | Create leagues and matches for all four sports; create product/update inventory; purchase/decode PDF; manager pack/ship/deliver/refund passed on the disposable server. |
| Local inbox browser integration | Chrome, Firefox and Microsoft Edge passed login, receipt list, message details, PDF button and logout via existing Selenium Grid. |
| k6 read load | Five concurrent users for twenty seconds: 151/151 checks passed; zero failed HTTP requests; request p95 approximately 103 ms on this laptop. |
| Live worker integration | Phone purchase `ord-e5d2ea9c-252`: local SMTP captured the receipt; its outbox record confirms Redis publication and MinIO PDF archival. No external email was sent. |

The destructive `tests/smoke_backend.py` probe ran against a separate database (`arenaone_qa_20261018`) and API on port 8081. It did **not** reset the main app database. The phone used the main API at `http://192.168.1.2:8080/`.

The temporary `arenaone-qa-api` container was stopped after verification; its separate database was retained. Start that container again before rerunning the local port-8081 commands below. Main application services remain running.

## Connected-phone checks

- Admin signed in and received the management workspace. The schedule form created a real basketball fixture at venue `DeviceQA`; it appeared in Matches and opened with basketball scoring rules.
- Android notification and exact-alarm permission screens completed. The reminder notification arrived at `02:30:10 PKT` for the test kickoff at `02:40:10 PKT`, while the app was on the Home screen. Its message said “Starts in 10 minutes.” Tapping it opened the correct match.
- Started that match and recorded a three-pointer online. Disabled Wi-Fi, recorded another three-pointer and observed an immediate score preview of 6–0 with one action waiting.
- Force-stopped/reopened the app while offline. The cached match and 6–0 preview survived. Re-enabled Wi-Fi; replay applied automatically. PostgreSQL confirmed score 6–0 at version 3, and Sync showed zero waiting actions and an applied result. Wi-Fi was restored.
- The phone had no `adb reverse` entries. API requests used Wi-Fi; the USB cable supplied installation/control only.
- Player signed in to the customer Store, purchased one hydration bottle, received the paid receipt and opened its PDF in the installed phone viewer. The SMTP worker captured the same order locally.
- Organizer: its assigned-league schedule form opened, with no shop-management tools. Manager: inventory and its own club's roster opened, with no match-scheduling tools or other-club roster buttons. Viewer: customer Store was available and the club profile exposed no roster-management button. All role checks completed successfully.
- Viewer signed out, Wi-Fi was disabled, and the same account signed in again using its previously cached credentials. The club profile opened from saved data. Wi-Fi was restored afterward.

The main database now includes the explicitly created DeviceQA fixture and the phone purchase above. Existing demo data was preserved.

## Integration limits found

These capabilities exist in the backend but currently have **no dedicated mobile editor**: venue availability (`PUT /api/v1/venues/{id}`), match time/venue rescheduling (`POST /api/v1/matches/{id}/reschedule`), advanced league configuration (`PATCH /api/v1/leagues/{id}/configuration`) and tenant branding (`PATCH /api/v1/tenants/{id}/branding`). Sport-rule registration and fixture-algorithm generation are available through the specified audit contract, not mobile creation screens. The mobile schedule screen selects an already configured sport through a league.

Permanent deletion and account registration are not implemented application features. Match cancellation, order refunds, player release and scoring compensation preserve their history.

The phone smoke checks used adb/UIAutomator. Appium server/client were not available in this shell; no Appium-specific run is claimed. Reboot delivery, OEM battery restriction scenarios, notification denial/revocation, offline roster transfer on the physical phone and every individual form edge case were not exhaustively exercised. Backend roster/permission/validation cases and reminder timing/projection tests passed, but those do not replace every device scenario.

## Repeatable checks

```powershell
& backend/.venv/Scripts/python.exe -m pytest backend/tests -q
& backend/.venv/Scripts/python.exe tests/browser_mailbox.py
# WARNING: smoke_backend resets its target. Use a disposable server only.
& backend/.venv/Scripts/python.exe tests/smoke_backend.py http://127.0.0.1:8081
& backend/.venv/Scripts/python.exe tests/smoke_stream.py http://127.0.0.1:8081

# In mobile/, with JAVA_HOME and ANDROID_HOME set:
$env:ARENAONE_TEST_BASE_URL='http://127.0.0.1:8081/'
./gradlew.bat testDebugUnitTest lintDebug assembleDebug '-PapiBaseUrl=http://192.168.1.2:8080/'
# Without ARENAONE_TEST_BASE_URL the three destructive live contract tests skip.
```

The load script is `tests/k6_smoke.js`; set its `BASE_URL` to the disposable API when running the cached `grafana/k6:0.54.0` image.

## Follow-up UI verification: artwork, raised actions and startup

The original season-pass and scarf PNGs now appear in customer catalogue/detail and staff shop views. Source, bundled drawable and packaged APK bytes match. The bundled non-looping wave GIF also matches its source exactly.

The connected phone showed distinct wave-animation frames, then opened the saved session without user input. Sign-in/account switching and product detail navigation were exercised. After user review, the newly added universal button shadows were reverted, including End match and shop plus controls. The original raised primary/scoring buttons and pills remain. Action colours and callbacks are unchanged. The sign-in page still has no logo above ArenaOne.

The updated APK was built and installed. The follow-up `testDebugUnitTest lintDebug assembleDebug` run passed: eight non-live tests passed, three opt-in live tests skipped because the disposable API was stopped. Those three had passed in the earlier full integration run above. Lint reported warnings and no errors. No backend behavior changed in this UI follow-up. Android 26/27 animated fallback has not been tested on a physical device.

## Account-switch offline-sync regression

Root cause: logout hid the organizer's queue from the automatic-sync observer, cleared its active JWT and left replay dependent on signing into that same account again. The server had never received the viewer's missing score.

Delivery now scans every queued account for the current server independently of the visible session. Each batch captures the originating account's encrypted JWT and URL before suspending; switching users cannot change the batch's credentials. The dedicated outbox token is removed when the queue drains. A native persisted JobScheduler task provides background retry. Expired/rejected authorization keeps the actions and requests original-account reauthentication; it never borrows viewer permissions. Existing queues can recover their original encrypted cached-login token.

Phone verification with `tests/device_account_switch.py`:

1. **Old APK reproduction:** organizer saved a goal offline, signed out, Wi-Fi returned and viewer signed in. The server score stayed unchanged and the goal remained in the organizer queue.
2. **Legacy queue recovery:** installed the fix while viewer remained the saved account. Original pending organizer actions uploaded and the viewer showed the authoritative score without organizer UI login. The device queue cleared. This also delivered previously stuck user work.
3. **New APK reconnect:** organizer saved a fresh goal offline, signed out and viewer signed in using cached credentials while still offline. The viewer did not inherit the organizer's private score preview. Wi-Fi reconnect uploaded the goal in about **2.08 seconds**, and the viewer score updated without organizer UI login. The queue cleared.
4. Test goals were removed with signed compensating events; user scoring events and history were retained. Wi-Fi was restored.

Three new JVM HTTP tests verify captured organizer credentials survive logout/viewer login, expired original credentials do not fall back to viewer credentials, and queues cannot cross server identities. Eleven non-live Android tests pass; the three pre-existing opt-in live tests remain skipped with their disposable API stopped. Build and lint pass.

Immediate foreground reconnect and legacy recovery were tested. JobScheduler registration/execution was observed, but reboot/OS battery-throttling behavior was not exhaustively tested. Android force-stop still blocks background work until reopening the app.

## SMTP retest after the sync fix

The user confirmed `mohammadmohid93@gmail.com` and then selected **Keep local email delivery for now**. External/Gmail delivery was not enabled and no email password was stored or used.

- `test_receipt_email.py` and `test_local_mail.py`: **18 passed**.
- `tests/smoke_local_receipt.py`: order `smtp-retest-58612acc3114` completed a mock purchase, the local SMTP sink captured its receipt for that exact address, and the authenticated inbox returned the identical **1,799-byte** PDF from checkout.
- PostgreSQL outbox flags confirm Redis publication and MinIO PDF archival.
- The address is a local recipient label. Read the receipt in `/local-mail` using `player@nascon.test` (or admin); it does not arrive in the public Gmail inbox.

## Subsequent real Gmail delivery test

The user later supplied an app password and explicitly requested real delivery. The ignored local `.env` now uses external SMTP, `smtp.gmail.com:587`, verified STARTTLS, and the authorized Gmail sender. No credential was added to source files or reports.

Order `gmail-test-56798f0da89c` completed an authenticated mock checkout with the requested recipient. The existing worker authenticated to Gmail SMTP and sent its **1,802-byte PDF** attachment; the job reached `sent` in external mode. This confirms SMTP acceptance; recipient Inbox/Spam placement was not inspected. Existing local-inbox messages were preserved. Nothing was committed or pushed.
