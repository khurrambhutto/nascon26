"""Additive demo fixtures and merchandise for the bundled mobile artwork."""
from . import scoring


def ensure(store):
    # Only missing records are inserted: restarts preserve edits, scores and purchased stock.
    clubs = [
        ("club-karakoram-falcons", "Karakoram Falcons CC", "Campus Cricket Oval"),
        ("club-indus-strikers", "Indus Strikers CC", "Campus Cricket Oval"),
        ("club-lahore-thunder", "Lahore Thunder CC", "Lahore Ground"),
        ("club-coastal-sharks", "Coastal Sharks CC", "Coastal Ground"),
        ("club-neon-lynx", "Neon Lynx Esports", "Arena Gaming Lounge"),
        ("club-ember-phoenix", "Ember Phoenix Esports", "Arena Gaming Lounge"),
    ]
    for cid, name, venue in clubs:
        if not store.get("club", cid):
            store.put("club", cid, {"club_id": cid, "name": name, "tenant_id": "tenant-alpha",
                "home_venue": {"name": venue, "lat": None, "lng": None}, "socials": {}, "staff": [], "stats": []})
    examples = [
        ("basketball", "Campus Basketball Cup", ["club-alpha-1", "club-alpha-2"], "Sports Hall", "2026-10-24T08:00:00Z"),
        ("cricket", "Campus Cricket Cup", [c[0] for c in clubs[:4]], "Campus Cricket Oval", "2026-10-25T08:00:00Z"),
        ("esports", "Campus Esports Series", [c[0] for c in clubs[4:]], "Arena Gaming Lounge", "2026-10-26T08:00:00Z"),
    ]
    for sport, name, teams, venue, when in examples:
        lid = "demo-league-" + sport
        if not store.get("league", lid):
            matches = [{"match_id": "demo-" + sport + "-1", "home": teams[0], "away": teams[1], "scheduled_at": when, "venue": venue}]
            if sport == "cricket":
                matches.append({"match_id": "demo-cricket-2", "home": teams[2], "away": teams[3], "scheduled_at": "2026-10-25T12:00:00Z", "venue": "Coastal Ground"})
            scoring.register_league(store, {"league_id": lid, "sport_id": sport, "name": name,
                "tenant_id": "tenant-alpha", "teams": teams, "fixtures": matches, "qualification": {"spots": 1}})
    for suffix, name, description, price in [
        ("duffel", "ArenaOne Training Duffel", "Club training bag with a spacious main compartment and shoulder strap.", 280000),
        ("bottle", "ArenaOne Hydration Bottle", "Reusable insulated sports bottle for training and match days.", 150000),
    ]:
        pid, vid = "demo-product-" + suffix, "demo-variant-" + suffix
        if not store.get("product", pid):
            store.put("product", pid, {"product_id": pid, "name": name, "description": description,
                "category": "accessories", "tenant_id": "tenant-alpha", "club_id": "club-alpha-1", "variant_ids": [vid]})
        if not store.get("variant", vid):
            store.put("variant", vid, {"variant_id": vid, "label": "Standard", "size": None, "color": None,
                "price_minor": price, "currency": "PKR", "stock": 20})
