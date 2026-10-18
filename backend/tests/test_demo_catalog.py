from app import demo_catalog
from app.storage import transaction


def test_highlights_include_one_real_match_per_sport(client, login):
    headers = login("viewer")
    matches = client.get("/api/v1/fixtures/highlights", headers=headers).json()
    assert len(matches) == 4
    assert {m["sport_id"] for m in matches} == {"football", "basketball", "cricket", "esports"}
    assert len({m["match_id"] for m in matches}) == 4
    assert next(m for m in matches if m["sport_id"] == "football")["match_id"] == "match-m1"
    assert next(m for m in matches if m["sport_id"] == "cricket")["home_name"] == "Karakoram Falcons CC"
    assert next(m for m in matches if m["sport_id"] == "esports")["away_name"] == "Ember Phoenix Esports"
    for match in matches:
        details = client.get('/api/v1/matches/' + match["match_id"], headers=headers).json()
        assert details["sport_id"] == match["sport_id"]
        assert details["home"]["team_id"] == match["home_team_id"]
    assert len(client.get("/api/v1/fixtures?league_id=league-alpha-1", headers=headers).json()) == 6
    assert client.get("/api/v1/fixtures/highlights").status_code == 401


def test_all_artwork_clubs_and_products_have_real_records(client, login):
    headers = login("viewer")
    clubs = client.get("/api/v1/clubs", headers=headers).json()
    names = {c["name"] for c in clubs}
    assert {"Karakoram Falcons CC", "Indus Strikers CC", "Lahore Thunder CC", "Coastal Sharks CC", "Neon Lynx Esports", "Ember Phoenix Esports"} <= names
    products = client.get("/api/v1/products", headers=headers).json()
    extra = [p for p in products if p["product_id"] in ("demo-product-duffel", "demo-product-bottle")]
    assert len(extra) == 2
    for product in extra:
        assert product["variants"][0]["stock"] == 20
        assert product["variants"][0]["price_minor"] > 0


def test_catalog_startup_is_additive_and_preserves_existing_data(client):
    with transaction() as db:
        variant = db.require("variant", "demo-variant-duffel")
        variant["stock"] = 7
        db.put("variant", variant["variant_id"], variant)
        league = db.require("league", "demo-league-cricket")
        league["name"] = "Renamed cricket cup"
        db.put("league", league["league_id"], league)
        before = {kind: len(db.all(kind)) for kind in ("match", "club", "product")}
        demo_catalog.ensure(db)
        demo_catalog.ensure(db)
        assert before == {kind: len(db.all(kind)) for kind in before}
        assert db.require("variant", "demo-variant-duffel")["stock"] == 7
        assert db.require("league", "demo-league-cricket")["name"] == "Renamed cricket cup"
        assert db.require("match", "demo-m3")["events"]


def test_highlights_do_not_include_foreign_tenant_matches(client, login):
    admin = login("admin")
    league = client.post("/api/v1/leagues", headers=admin, json={"name": "Foreign basketball",
        "sport_id": "basketball", "tenant_id": "tenant-beta", "teams": ["club-beta-1", "club-beta-2"]})
    # The missing foreign club is rejected; seed a minimal foreign fixture directly for the visibility test.
    assert league.status_code == 404
    with transaction() as db:
        original = db.require("match", "demo-basketball-1")
        foreign = {**original, "match_id": "foreign-match", "league_id": "league-beta-1", "tenant_id": "tenant-beta"}
        db.put("match", foreign["match_id"], foreign)
    assert all(m["match_id"] != "foreign-match" for m in client.get("/api/v1/fixtures/highlights", headers=login("viewer")).json())
