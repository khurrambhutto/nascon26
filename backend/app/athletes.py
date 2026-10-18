"""FR-3.1 public athlete identity and a drift-free follow graph."""
from . import scoring
from .common import fail


def club_of(store, player):
    squad_id = (player.get("registration") or {}).get("squad_id") or player.get("squad_id")
    squad = store.get("squad", squad_id) if squad_id else None
    return store.get("club", squad["club_id"]) if squad else None


def followers(store, athlete_id):
    # Counted from one record per (follower, athlete) pair, so concurrent toggles can never drift.
    return sum(1 for f in store.all("follow") if f["athlete_id"] == athlete_id)


def profile(store, athlete_id):
    player = store.get("player", athlete_id)
    if player is None:
        fail(404, "PLAYER_NOT_FOUND")
    club = club_of(store, player)
    record, upcoming = {"played": 0, "won": 0, "drawn": 0, "lost": 0}, []
    for match in store.all("match"):
        if not club or club["club_id"] not in (match["home_team_id"], match["away_team_id"]):
            continue
        score = scoring.project(store, match["match_id"])
        status = scoring.effective_status(match, score["status"])
        if status == "upcoming":
            upcoming.append({"match_id": match["match_id"], "scheduled_at": match.get("scheduled_at")})
        elif status == "completed":
            record["played"] += 1
            if score["winner_team_id"] is None:
                record["drawn"] += 1
            else:
                record["won" if score["winner_team_id"] == club["club_id"] else "lost"] += 1
    return {
        "player_id": athlete_id,
        "name": player["name"],
        "position": player.get("position"),
        "clubs": [{"club_id": club["club_id"], "name": club["name"]}] if club else [],
        "career": record,
        "upcoming_fixtures": sorted(upcoming, key=lambda m: m["scheduled_at"] or ""),
        "vod_archive": [v for v in store.all("vod") if v["player_id"] == athlete_id],
        "follower_count": followers(store, athlete_id),
    }


def follow(store, athlete_id, user, following):
    """Idempotent per (follower_id, athlete_id): repeating a follow or unfollow changes nothing."""
    if store.get("player", athlete_id) is None:
        fail(404, "PLAYER_NOT_FOUND")
    key = user["user_id"] + "|" + athlete_id
    if following:
        store.put("follow", key, {"follower_id": user["user_id"], "athlete_id": athlete_id})
    else:
        store.remove("follow", key)
    return {"athlete_id": athlete_id, "following": following, "follower_count": followers(store, athlete_id)}
