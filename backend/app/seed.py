"""Repeatable baseline and mobile demo data; sport-specific names live only here."""
from datetime import date, timedelta
from itertools import combinations

from . import auth, roster, scoring
from .common import TIE_BREAKS, check, identifier, integer, obj


def sports():
    base = {"tie_break_order": TIE_BREAKS}
    return [
        {**base, "sport_id": "football", "name": "Football", "scoring_model": "event", "periods": {"count": 2, "duration_minutes": 45}, "scoring_events": {"goal": {"points": 1}}, "draw_allowed": True, "overtime": {"policy": "none"}, "roster": {"min_squad_size": 11, "max_squad_size": 25, "required_positions": {"gk": 1}}},
        {**base, "sport_id": "basketball", "name": "Basketball", "scoring_model": "event", "periods": {"count": 4, "duration_minutes": 12}, "scoring_events": {"free_throw": {"points": 1}, "two_pointer": {"points": 2}, "three_pointer": {"points": 3}}, "draw_allowed": False, "overtime": {"policy": "extra_time", "duration_minutes": 5}, "roster": {"min_squad_size": 5, "max_squad_size": 15, "required_positions": {}}},
        {**base, "sport_id": "cricket", "name": "Cricket", "scoring_model": "innings", "periods": {"innings_per_team": 1, "overs_per_innings": 20, "balls_per_over": 6, "wickets_per_innings": 10}, "scoring_events": {"dot_ball": {"runs": 0, "legal_ball": True, "wicket": False}, "single": {"runs": 1, "legal_ball": True, "wicket": False}, "two": {"runs": 2, "legal_ball": True, "wicket": False}, "four": {"runs": 4, "legal_ball": True, "wicket": False}, "six": {"runs": 6, "legal_ball": True, "wicket": False}, "wide": {"runs": 1, "legal_ball": False, "wicket": False}, "no_ball": {"runs": 1, "legal_ball": False, "wicket": False}, "wicket": {"runs": 0, "legal_ball": True, "wicket": True}}, "draw_allowed": True, "overtime": {"policy": "super_over"}, "roster": {"min_squad_size": 11, "max_squad_size": 18, "required_positions": {"wicketkeeper": 1}}},
        {**base, "sport_id": "esports", "name": "Esports", "scoring_model": "round", "periods": {"best_of": 3}, "scoring_events": {"round_win": {"rounds": 1}}, "draw_allowed": False, "overtime": {"policy": "none"}, "roster": {"min_squad_size": 5, "max_squad_size": 8, "required_positions": {}}},
    ]


def reset(store, body=None):
    body = obj(body or {})
    seed = integer(body.get("seed", 42), "seed")
    stock = obj(body.get("stock", {"var-jersey-001-M": 1, "var-jersey-001-L": 5}), "stock")
    for key, value in stock.items():
        identifier(key, "stock")
        integer(value, "stock", 0)
    store.clear()
    store.put("meta", "seed", seed)
    for config in sports():
        scoring.create_sport(store, config)
    for tenant_id, name, color in (("tenant-alpha", "Alpha Sports", "#B84432"), ("tenant-beta", "Beta Athletics", "#2E7D32")):
        store.put("tenant", tenant_id, {"tenant_id": tenant_id, "name": name, "primary_color": color, "logo_url": None})
    clubs = [("club-alpha-1", "Alpha Lions", "tenant-alpha"), ("club-alpha-2", "Alpha Wolves", "tenant-alpha"), ("demo-club-3", "Campus Bears", "tenant-alpha"), ("demo-club-4", "City Eagles", "tenant-alpha"), ("club-beta-1", "Beta Lions", "tenant-beta")]
    for cid, name, tenant in clubs:
        store.put("club", cid, {"club_id": cid, "name": name, "tenant_id": tenant, "home_venue": {"name": "Campus Ground", "lat": 33.656, "lng": 73.016}, "socials": {"x": "@" + cid, "instagram": "@" + cid}, "staff": [{"name": "Ahmed Khan", "role": "coach"}, {"name": "Sara Ali", "role": "manager"}, {"name": "Zain Shah", "role": "physio"}], "stats": [{"season": "2025", "played": 10, "won": 6, "drawn": 2, "lost": 2}]})
    teams = [c[0] for c in clubs[:4]]
    fixtures = [{"match_id": f"demo-m{i}", "home": a, "away": b, "scheduled_at": f"2026-10-{17+i:02d}T08:00:00Z", "venue": "Campus Ground"} for i, (a, b) in enumerate(combinations(teams, 2), 1)]
    scoring.register_league(store, {"league_id": "league-alpha-1", "sport_id": "football", "teams": teams, "fixtures": fixtures, "qualification": {"spots": 2}, "name": "Alpha Sports Festival", "tenant_id": "tenant-alpha"})
    for lid, tenant in (("league-alpha-2", "tenant-alpha"), ("league-beta-1", "tenant-beta")):
        scoring.register_league(store, {"league_id": lid, "sport_id": "football", "teams": ["club-alpha-1", "club-alpha-2"] if tenant == "tenant-alpha" else ["club-beta-1", "demo-beta-2"], "fixtures": [], "qualification": {"spots": 1}, "tenant_id": tenant, "name": "Development League" if tenant == "tenant-alpha" else "Beta Championship"})
    for i in range(1, 4):
        mid = f"demo-m{i}"
        events = [("match_start", {}), ("score", {"team_id": fixtures[i-1]["home"], "event_type": "goal"})]
        if i < 3:
            events.append(("match_end", {}))
        for j, (kind, payload) in enumerate(events, 1):
            scoring.append_event(store, {"event_id": f"demo-event-{i}-{j}", "match_id": mid, "type": kind, "payload": payload})
    squads, players = [], []
    for index, (cid, _, tenant) in enumerate(clubs[:4], 1):
        sid = "demo-squad-" + str(index)
        squads.append({"squad_id": sid, "club_id": cid, "tenant_id": tenant, "name": "First Squad"})
        for i in range(5):
            pid = f"player-alpha-{index}" if index <= 2 and i == 0 else f"demo-player-{index}-{i}"
            players.append({"player_id": pid, "name": ["Ali", "Bilal", "Hamza", "Usman", "Daniyal"][i] + f" {index}", "position": "gk" if i == 0 else "df" if i < 3 else "fw", "squad_id": sid, "role": "captain" if i == 0 else "starter"})
    roster.setup(store, {"league": {"league_id": "league-alpha-1", "tenant_id": "tenant-alpha", "sport_id": "football", "transfer_window": {"open": (date.today()-timedelta(days=30)).isoformat(), "close": (date.today()+timedelta(days=30)).isoformat()}, "max_squad_size": 25, "required_positions": {"gk": 1}}, "squads": squads, "players": players, "matches_in_progress": []})
    roster.setup(store, {"league": {"league_id": "league-beta-1", "tenant_id": "tenant-beta", "sport_id": "football", "transfer_window": {"open": "2026-01-01", "close": "2027-12-31"}, "required_positions": {}}, "squads": [{"squad_id": "demo-beta-squad", "club_id": "club-beta-1", "tenant_id": "tenant-beta", "name": "Beta First"}], "players": [{"player_id": "player-beta-1", "name": "Beta Player", "position": "gk", "squad_id": "demo-beta-squad", "role": "starter"}, {"player_id": "player-beta-2", "name": "Beta Forward", "position": "fw", "squad_id": "demo-beta-squad", "role": "starter"}]})
    for short, role in auth.ROLES.items():
        email = short + "@nascon.test"
        user = {"user_id": "u-" + short, "email": email, "name": role.replace("_", " ").title(), "role": role, "tenant_id": "tenant-alpha", "league_id": "league-alpha-1" if short == "organizer" else None, "club_id": "club-alpha-1" if short == "manager" else None, "player_id": "player-alpha-1" if short == "player" else None, "password_hash": auth.password_hash("Nascon@26", email)}
        store.put("user", user["user_id"], user)
    store.put("product", "prod-jersey-001", {"product_id": "prod-jersey-001", "name": "Alpha Home Jersey", "tenant_id": "tenant-alpha", "club_id": "club-alpha-1", "category": "apparel", "description": "Official club jersey", "variant_ids": ["var-jersey-001-M", "var-jersey-001-L"]})
    for size, default in (("M", 1), ("L", 5)):
        vid = "var-jersey-001-" + size
        store.put("variant", vid, {"variant_id": vid, "label": size + " / Blue", "size": size, "color": "Blue", "price_minor": 450000, "currency": "PKR", "stock": stock.get(vid, default)})
    for vid, count in stock.items():
        if not store.get("variant", vid):
            store.put("variant", vid, {"variant_id": vid, "label": vid, "size": None, "color": None, "price_minor": 450000, "currency": "PKR", "stock": count})
    for suffix, name, category, price in (("scarf", "Club Supporter Scarf", "accessories", 120000), ("pass", "Digital Season Pass", "digital", 80000)):
        pid, vid = "demo-product-" + suffix, "demo-variant-" + suffix
        store.put("product", pid, {"product_id": pid, "name": name, "tenant_id": "tenant-alpha", "club_id": "club-alpha-1", "category": category, "description": name, "variant_ids": [vid]})
        store.put("variant", vid, {"variant_id": vid, "label": "Standard", "size": None, "color": None, "price_minor": price, "currency": "PKR", "stock": 20})
    match = store.get("match", "demo-m3")
    match["pinned_product_id"] = "prod-jersey-001"
    store.put("match", "demo-m3", match)
    day2(store)
    store.put("meta", "seeded", True)
    from .demo_catalog import ensure
    ensure(store)
    return {"status": "reset_complete", "seed": seed, "sports": ["football", "basketball", "cricket", "esports"], "tenants": ["tenant-alpha", "tenant-beta"], "stock": {v["variant_id"]: v["stock"] for v in store.all("variant") if v["variant_id"] in stock or v["variant_id"].startswith("var-jersey-")}}


def day2(store):
    """Phase II baseline: live match-m1, fixed QR short links, a guest token and an empty member cart."""
    for number, (kind, payload) in enumerate([("match_init", {"sport_id": "football", "home_team_id": "club-alpha-1", "away_team_id": "club-alpha-2", "league_id": "league-alpha-1"}),
                                             ("match_start", {}), ("score", {"team_id": "club-alpha-1", "event_type": "goal"})], 1):
        scoring.append_event(store, {"event_id": f"seed-m1-{number}", "match_id": "match-m1", "type": kind, "payload": payload})
    match = store.get("match", "match-m1")
    match.update(pinned_product_id="prod-jersey-001", scheduled_at="2026-10-18T09:00:00Z", venue="Campus Ground")
    store.put("match", "match-m1", match)
    for entity_id, code in (("match-m1", "m1-live"), ("player-alpha-1", "p-alpha-1"), ("club-alpha-1", "c-alpha-1")):
        store.put("qr", entity_id, {"entity_id": entity_id, "short_code": code, "scan_count": 0, "devices": {}, "buckets": {}})
        store.put("qr_code", code, {"entity_id": entity_id})
    store.put("guest_token", "guest_anon_tok_7721", {"guest_token": "guest_anon_tok_7721", "role": "guest", "merged_into": None})
    store.put("cart", "player-alpha-1", {"user_id": "player-alpha-1", "items": []})
