# Part I traceability

Backend statuses come from pytest; mobile statuses were verified on a physical Android phone (Samsung SM-A175F) against the real backend.

| Req ID | Requirement | Implementation | Tests | Status |
|---|---|---|---|---|
| FR-1.1 | JSON sport taxonomy and append-only signed scoring | backend/app/scoring.py; /audit/v1/sports, /events, /score | backend/tests/test_platform.py; test_scoring.py | Backend tests passed |
| FR-1.2 | Configurable season leagues and tournaments | backend/app/scoring.py; /audit/v1/leagues; PATCH /api/v1/leagues/{id}/configuration | test_scoring.py; test_scheduling.py | Registration and configuration tested; fixture registration is caller-driven |
| FR-1.3 | Four fixture algorithms and scheduling | backend/app/fixtures.py; backend/app/scheduling.py | test_fixtures.py; test_scheduling.py | Backend tests passed |
| FR-1.4 | Standings, H2H mini-leagues and qualification | backend/app/standings.py | test_standings.py | Backend tests passed |
| FR-2.1 | Tenant-isolated clubs and profiles | backend/app/auth.py; /api/v1/clubs/{id}; tenant branding API | test_platform.py; test_rbac.py; test_scheduling.py | Backend tests passed |
| FR-2.2 | Transfer, release, loan and immutable history | backend/app/roster.py | test_roster.py | Backend tests passed |
| FR-2.3 | Atomic checkout, mock gateway, signed PDF, outbox, fulfilment/refunds | backend/app/store.py; backend/app/orders.py; backend/app/worker.py | test_platform.py; test_store.py; test_orders.py; live Redis/MinIO check | Backend tests passed; receipt archived and event published |
| FR-5.1 | Five-role authorization matrix | backend/app/auth.py; /audit/v1/rbac/* | test_rbac.py | Full negative matrix passed |
| NFR-3 | Exactly one purchase from 20 buyers of last unit | backend/app/storage.py; backend/app/store.py | test_platform.py::test_checkout_twenty_concurrent_buyers; tests/smoke_backend.py | Passed with PostgreSQL, including two API processes |
| NFR-4 | Persistent mobile queue and ordered conflict sync | backend/app/offline.py; /audit/v1/offline-sync; mobile/.../data/LocalDb.kt (SQLite cache + pending_actions), Repository.kt (sendOrQueue, syncNow), AppViewModel.kt (auto-sync) | test_platform.py::test_offline_replay; test_offline.py; device: offline score + roster action queued, survived force-close, auto-synced on reconnect | Passed (backend + device) |
| NFR-5 | JWT verification, tenant scope, validation, rate limits, secure token storage | backend/app/auth.py; backend/app/main.py; mobile/.../data/SecureStore.kt (JWT and cached login proof encrypted with Android Keystore AES-GCM key) | test_platform.py; test_rbac.py; test_scoring.py; local revision retains queued work and prompts for sign-in on 401 | Backend passed; token-expiry device retest pending |
| NFR-7 | Single-command clean Linux startup and seed | docker-compose.yml; backend/Dockerfile; backend/app/seed.py | Clean volumes + docker compose up -d --build; tests/smoke_backend.py | Runtime passed; local build used a verified dependency cache |

## Mobile app (spec Section 8): `mobile/`, release APK at `mobile/release/app-release.apk`

| Req ID | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| M-8.1 | APK for Android 10+, editable server address, Keystore token | app/build.gradle.kts (minSdk 26, signed release); ui/screens/AccountScreens.kt SettingsScreen; data/SecureStore.kt | apksigner verify; installed on device | Done |
| M-8.2a | Login, role-aware home | ui/screens/LoginScreen.kt; ui/MainShell.kt (management roles land on Management; customer roles see Store) | Original device checks; local updated APK build and backend role tests | Local revision; device retest pending |
| M-8.2b | Fixtures + match detail (live score, timeline, pinned product) | ui/screens/FixturesScreen.kt; MatchScreen.kt (SSE stream, 3 s poll fallback) | Device against real backend | Done |
| M-8.2c | Standings with qualification status | ui/screens/StandingsScreen.kt | Device | Done |
| M-8.2d | Club profile (info, roster, staff, venue) | ui/screens/ClubScreen.kt | Device | Done |
| M-8.2e | Store: catalogue, variants, cart, mock gateway, receipt PDF | ui/screens/StoreScreens.kt | Device: declined card error, paid order with WELCOME10, PDF opened | Done |
| M-8.2f | Roster management: add / transfer / loan / release | ui/screens/RosterScreen.kt; POST /api/v1/clubs/{id}/players | Device + test_roster.py::test_manager_adds_player_to_own_club_only | Done |
| M-8.2g | Scoring entry: score + compensating event (buttons from sport config) | ui/screens/MatchScreen.kt ScoringPanel/TimelineRow | Device: goal 1-0 to 2-0, undo with reason | Done |
| M-8.2h | Always-visible Online/Offline/Syncing + pending queue | ui/components/Components.kt ConnectionStrip; AccountScreens.kt SyncScreen | Device | Done |
| M-8.4 | Offline desk check (cache, airplane mode, queue, force-close, auto-sync, plain-language results) | data/LocalDb.kt; data/Repository.kt; ui/AppViewModel.kt | Device: full flow passed for scoring and roster | Done |
| M-8.6 | UI/HCI: tenant colour + logo, 48dp targets, text+icon status, confirmations, empty states, rotation | ui/theme/Theme.kt; ui/components/Components.kt; bundled club crests, launcher/login logo and store artwork in res/drawable-nodpi | Device incl. landscape; release build + phone crest/store checks; test_platform.py::test_health_reset_login_and_mobile_lists verifies live Lions vs Eagles | Done |

## Local October 18 fixes (uncommitted; original submission APK preserved)

| Req ID | Requirement | Implementation | Verification | Status |
|---|---|---|---|---|
| FIX-LAN | Continue online without USB using a shared LAN; recover from tunnel loss | Repository.probe, SecureStore.wirelessUrl; SettingsScreen; scripts/Start-Wireless.ps1 | Phone signed in at laptop LAN URL with no ADB reverse mapping; unplug/replug fallback retest pending | LAN verified on phone |
| FIX-OFFLINE | Persist before sending, isolate accounts, preview offline scores, restart-safe sync results | LocalDb v2 migration; Repository; SecureStore; OfflineProjection.kt | Four OfflineProjectionTest cases; authenticated replay tests; physical Wi-Fi-off scoring, process restart, reconnect and zero-pending replay in mobile/DEVICE-VERIFICATION.md | Passed for scoring; physical offline roster case remains untested |
| FIX-ROLES | Admin/manager manage merchandise; customer roles shop | MainShell; ManagementScreen product/stock/order forms; backend/app/management.py | test_manage_product_stock_prices_and_permissions; test_management_role_and_tenant_boundaries; admin Management home visible on phone | Backend passed; management home verified on phone |
| FIX-MATCH | Create matches and leagues for configured sports | ManagementScreen league and schedule forms; POST /api/v1/leagues, /api/v1/matches | Four-sport backend tests; LiveApiContractTest admin creation; physical basketball match creation and scoring | Passed for covered flows |
| FIX-THEME | User-requested consistent terracotta theme before and after sign-in | MainActivity.kt; ui/theme/Theme.kt; LoginScreen.kt | Debug APK build; shared theme fixes primary to #B84432 for every role | Local revision; supersedes tenant-specific primary colour in the original UI plan |
| FIX-ARTWORK | Use all six new club logos and two shop images from assets/NEW-ASSETS.md | drawable-nodpi/crest_*.png, training_duffel.png, hydration_bottle.png; TeamBadge; ProductArtwork; shop catalogue/details/management | Asset checksums match source PNGs; Android APK build; test_all_artwork_clubs_and_products_have_real_records | Local implementation |
| FIX-SPORTS | Matches home displays one real fixture from each supported sport | GET /api/v1/fixtures/highlights; demo_catalog.ensure; FixturesScreen All sports filter | test_highlights_include_one_real_match_per_sport; test_highlights_do_not_include_foreign_tenant_matches; test_catalog_startup_is_additive_and_preserves_existing_data | Automated checks; existing football fixtures retained under league filter |
| EXTRA-EMAIL | Deliver a successful purchase's PDF through SMTP without delaying checkout | POST /api/v1/checkout; GET /api/v1/orders/{id}/receipt-email; receipt_email.py; worker.py; CartScreen and ReceiptScreen | test_receipt_email.py (13 cases, including SMTP exchange, TLS ordering, retries, role checks, failed-payment exclusion and multi-worker claim) | Passed; default delivery now captures locally |
| EXTRA-REMINDER | Opt-in phone notification ten minutes before an upcoming match | notifications/MatchReminders.kt; MatchReminderButton.kt; manifest permissions/receiver; MainActivity match deep link | Four timing tests; physical permission prompts, background delivery exactly ten minutes before kickoff and notification navigation | Passed; reboot and OEM restriction scenarios untested |
| EXTRA-LOCAL-MAIL | Fully local email receipt inbox without Google or external mail credentials | local_smtp.py; mailbox.py; mailbox.html; local-mail Compose service; app Open local inbox button | Five backend cases; phone purchase/PDF viewer; local worker delivery; tests/browser_mailbox.py on Chrome, Firefox and Edge | Passed; 165 backend and eleven Android tests passed; APK built |
| FIX-APP-ICON | Apply latest terracotta AO app logo | assets/brand/arenaone-ao-app-icon-v4.png; drawable-nodpi/arenaone_logo.png; manifest launcher icons | Source/resource/APK SHA-256 equality; installed local APK | Applied locally; sign-in remains text-only |
| FIX-PASS-SCARF | Display original pass and scarf shop artwork | ProductArtwork.kt; drawable-nodpi/season_pass.png, supporter_scarf.png | Source/resource/APK hash checks; physical phone catalogue and product-detail review | Applied locally |
| FIX-RAISED-BUTTONS | Restore original button styles after user review | Original Material buttons; pre-existing PrimaryButton/Pill/ScoreButton retained | Debug APK rebuild; no RaisedButtons references remain | Universal shadow styling reverted at user request; artwork and splash retained |
| FIX-SPLASH | Play the provided wave GIF once before app content | SplashScreen.kt; MainActivity.kt; startup theme; raw/arenaone_wave_splash.gif | Build/lint; phone screenshots at different animation times followed by saved session | Applied locally; Android 26/27 fallback not physically tested |
| FIX-CROSS-ACCOUNT-SYNC | Upload saved organizer actions after logout or switching to viewer | Repository.syncAllPending; SecureStore outbox credentials; AccountSyncClient; QueueSyncJob; global queue counts and refresh revision | AccountSyncClientTest (three cases); tests/device_account_switch.py old-build reproduction, legacy queue recovery and fixed reconnect case | Passed on connected phone; viewer updated about 2 seconds after Wi-Fi reconnect without organizer UI login |
| QA-SMTP-RETEST | Verify local SMTP receipt to the confirmed address | Existing local-mail worker and customer-scoped mailbox | Eighteen SMTP/backend tests; tests/smoke_local_receipt.py paid order, SMTP capture and byte-identical PDF; Redis publication/MinIO archival flags | Passed locally for mohammadmohid93@gmail.com; Gmail delivery remains off at user request |
| QA-INTEGRATION | Verify mobile contracts, browser inbox and concurrent API reads | LiveApiContractTest.kt; tests/browser_mailbox.py; tests/k6_smoke.js; mobile/DEVICE-VERIFICATION.md | Three live Android contract tests; three browsers; 151 k6 checks; 20-buyer stock race | Passed; backend-only mobile editor gaps recorded in verification report |

## Part II (Day 2): streaming, QR deep links, guest access, moderation, grid QA

Backend: `backend/tests/test_day2.py` (pytest, 189 backend tests total). Grid: `tests/selenium/test_guest_grid.py` on Selenium Grid 4. Device: `tests/appium/test_mobile_deeplink.py` on a Samsung SM-A175F. Load: `tests/k6_load.js`.

| Req ID | Requirement | Implementation | Tests | Status |
|---|---|---|---|---|
| FR-3.1 | Athlete profile (career, clubs, fixtures, VOD archive) + drift-free follow/unfollow | backend/app/athletes.py; GET /api/v1/athletes/{id}; PUT/DELETE /api/v1/athletes/{id}/follow | test_day2.py::test_athlete_profile_and_follow_graph (10 concurrent toggles → exact count) | Done |
| FR-3.2 | Stream keys generate/rotate/revoke signed with DAY2_SALT; revoked key cannot publish; live ingest severed | backend/app/streaming.py; POST /audit/v1/stream/key; /internal/media/auth (MediaMTX HTTP auth); MediaMTX kick API | test_stream_key_lifecycle; test_media_server_kick_and_stats; live ffmpeg RTMP: revoke closed the connection in ms | Done |
| FR-3.2 | RTMP/WHIP ingest, HLS/WebRTC playback, overlays, VOD to MinIO | docker-compose.yml `media` (MediaMTX 1.9.1); guest.html WebRTC player + score/merch overlay; worker.py archive_recordings; mobile GoLiveScreen (WHIP from camera) | test_in_app_go_live_and_match_page_stream; manual ffmpeg stream → HLS 200, phone WebRTC session established; VOD object in MinIO bucket `vod` | Done (co-stream "composite" is a client-side grid, not server-mixed) |
| FR-3.3 | 4-way co-stream, host-drop handoff, single chat room, invites, mute/spotlight/kick | backend/app/costream.py; POST /audit/v1/costream/session; /api/v1/costream/{id}/invites, /join, /control; /ws/costream/{id} | test_costream_host_drop_handoff_and_replay; test_costream_rules; test_costream_host_controls_invites_and_live_view | Done |
| FR-4.1 | Dynamic QR: fixed short link, state-driven target, SVG/PNG/PDF, privacy-safe scans, nascon:// deep link | backend/app/qr_guest.py; GET /audit/v1/qr/{id}, /export.{fmt}, /q/{code}; AndroidManifest nascon intent-filter; DeepLinkScreen.kt | test_qr_resolution_scan_count_and_dynamic_target; test_qr_postponed_notice_and_short_link; test_qr_other_entities_and_exports; Appium test_deep_link_opens_native_screen_without_login (am start -W: 330 ms cold) | Done |
| FR-4.2 | Zero-account guest player | backend/app/web.py + guest.html (/web/*), POST /api/v1/guest/session | Selenium test_qr_resolution_and_zero_friction_load | Done |
| FR-4.3 | Server-enforced read-only guests (403 GUEST_WRITE_FORBIDDEN) | backend/app/main.py boundaries middleware | test_guest_writes_are_forbidden_server_side; Selenium test_guest_write_block_opens_progressive_modal | Done |
| FR-4.4 | Progressive sign-in and atomic, idempotent cart merge | qr_guest.cart_merge; POST /audit/v1/guest/cart-merge; guest.html auth modal | test_cart_merge_and_network_replay; test_cart_merge_validation_order; Selenium test_guest_cart_staging_and_real_time_score | Done |
| FR-5.2 | Real-time bus + DLQ after 3 bounded retries | backend/app/live.py (Redis counters); /ws/matches, /ws/chat, /ws/costream; moderation.dead_letter | test_member_chat_and_websockets; test_moderation_dlq_and_concurrency | Done |
| FR-5.3 | Leetspeak masking, flood mute, rate limit | backend/app/moderation.py; POST /audit/v1/moderation/test; POST /api/v1/chat | test_moderation_contract_example; test_leetspeak_variants_and_floods; 10-thread kick race | Done |
| NFR-1 | Score push p95 < 500 ms | live.py + /ws/matches/{id} (100 ms change polling) | Selenium test 3 asserts DOM update < 500 ms | Passed |
| NFR-2 | 500 viewers + 50 chat posters, 0% errors, p95 < 800 ms | 4 uvicorn workers; cached hot reads; Postgres max_connections 300 | k6 run tests/k6_load.js: p95 170 ms, 0 failures, ~2.6k req/s | Passed |
| NFR-6 | Prometheus /metrics (5 gauges/counters) | backend/app/observability.py; GET /metrics | test_metrics_and_privacy; live: streams_total 1, bitrate > 0 while publishing | Done |
| NFR-8 | WCAG 2.1 AA guest player | guest.html: ≥4.5:1 contrast, 48 px targets, ARIA labels/live regions, keyboard + Esc | Selenium DOM checks (testids, modal role=dialog) | Done |
| NFR-9 | No IP/UA/cookies in scan analytics; erasure within 1 s | qr_guest.record_scan (count, device family, hour bucket only); DELETE /audit/v1/privacy/user/{id} | test_metrics_and_privacy | Done |
| QA-GRID | Selenium Grid 4 hub + 3 nodes, Appium 2, CI | docker-compose.yml selenium-hub/node-*; .github/workflows/autograde.yml | GET :4444/status ready, 3 nodes; tests/reports/* | Done (Appium cross-surface test is timing-sensitive on the device; passes when run alone) |

## Day 2 completion work, October 18

These additions are local until committed and pushed. Runtime and build evidence is recorded below as checks finish.

| Req ID | Requirement | Implementation | Tests | Status |
|---|---|---|---|---|
| FR-3.3-CHAT | Broadcasting players read and send the audience chat | LiveScreens.kt LiveChat; live-context API | test_real_moderation_persists_and_is_room_scoped; Android build | Implemented; device retest pending |
| FR-4.1-NATIVE | QR browser handoff and native camera scanning | guest.html Open in app; QrScannerScreen.kt; bundled ZXing core 3.5.3 | Android build; Selenium guest suite | Implemented; camera scan device retest pending |
| FR-3.3-MIX | One combined co-stream video | composer.html canvas/audio mixer publishes WHIP; composition.py manages a headless Chrome session on the existing self-hosted grid | Runtime combined-video probe | Runtime verification pending |
| FR-5.2-DELIVERY | Invitation/order push, acknowledgement, three retries and durable DLQ | notifications.py; authenticated notification WebSocket; Android LiveNotifications; worker expiry | test_notification_three_attempts_then_dlq; test_invitation_socket_ack_and_real_disconnect | Backend verification in progress; OS background delivery depends on app process and permission |
| FR-5.3-SANCTIONS | Kicked/muted chat users remain blocked | Transactional per-room chat_guard records | test_real_moderation_persists_and_is_room_scoped | Implemented |
| FR-3.3-DROP | Real disconnect transfers host and terminates empty sessions | Broadcaster presence WebSocket; independent media disconnect monitor | test_invitation_socket_ack_and_real_disconnect | Implemented; external media timing pending |
| FR-4.4-REGISTER | Register, merge and return to playback | auth.register; guest auth toggle; mounted live player and saved VOD timestamp | test_guest_expiration_and_registration | Implemented |
| FR-4.2-EXPIRY | Anonymous tokens expire after 15 minutes | qr_guest token validation and seed expiration | test_guest_expiration_and_registration | Implemented |
| HEALTH-DEPS | Health checks database, Redis and media | main.health preserves status response and probes configured dependencies | Backend regression and runtime /health | Implemented |
| FR-3.3-NATIVE | Join, host, invite and control native co-streams | DeepLinkScreen; CostreamTools; presence socket | Android build; backend host tests | Implemented; full device flow pending |
| FR-4.1-STATUS | Common postponed/cancelled/abandoned event aliases | scoring.validate_event normalizes explicit aliases | test_extra_demo_players_and_status_alias | Implemented; unspecified arbitrary event names remain unsupported |
| DEMO-PLAYERS | Four independent broadcasting accounts | auth.ensure_demo_players; additive startup/reset | test_extra_demo_players_and_status_alias | player/player2/player3/player4@nascon.test, existing demo password |
| FR-3.2-REPLAY | Replay archived MP4 recordings | API read-only recording volume; FileResponse supports range requests; guest VOD player | Runtime archive playback probe | Implemented; runtime retest pending |

The user stopped further test/build work during completion. Earlier Android builds succeeded and that APK was installed. Later foreground-service, broadcaster-lifecycle and recovery changes are source-only until rebuilt. The combined publisher reached `COMPOSITE_READY` on the running self-hosted grid; multi-source video/audio and native camera scanning were not exercised after the stop instruction. Existing services were rebuilt and restarted without resetting database volumes; the final source additions require another rebuild/restart. Do not read the rows above as a claim of full end-to-end acceptance.
