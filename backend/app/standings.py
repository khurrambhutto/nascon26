"""Rebuild tables and recursively resolve tied mini-leagues from score logs."""
import hashlib

from .scoring import project


def standings(store, league_id):
    league = store.require("league", league_id)
    sport = store.require("sport", league["sport_id"])
    teams = league["teams"]
    points, spots = league["points"], league["qualification"]["spots"]
    rows = {team: {"rank": 0, "team_id": team, "played": 0, "won": 0, "drawn": 0, "lost": 0, "scored": 0, "conceded": 0, "diff": 0, "points": 0, "fair_play": 0, "status": "still_possible", "resolved_by": None} for team in teams}
    remaining = dict.fromkeys(teams, 0)
    h2h = {a: {b: {"played": 0, "points": 0, "scored": 0, "conceded": 0} for b in teams if b != a} for a in teams}
    completed = []
    for fixture in league["fixtures"]:
        score = project(store, fixture["match_id"])
        if score["status"] != "completed" or score["outcome"] == "undecided":
            remaining[fixture["home"]] += 1
            remaining[fixture["away"]] += 1
            continue
        completed.append(score)
        for side, other in (("home", "away"), ("away", "home")):
            a, b = score[side], score[other]
            row = rows[a["team_id"]]
            result = "drawn" if score["outcome"] == "draw" else "won" if score["winner_team_id"] == a["team_id"] else "lost"
            earned = points[{"won": "win", "lost": "loss", "drawn": "draw"}[result]]
            row["played"] += 1
            row[result] += 1
            row["scored"] += a["score"]
            row["conceded"] += b["score"]
            row["points"] += earned
            row["fair_play"] += a["fair_play"]
            cell = h2h[a["team_id"]][b["team_id"]]
            cell["played"] += 1
            cell["points"] += earned
            cell["scored"] += a["score"]
            cell["conceded"] += b["score"]

    def differential(team, matches):
        scored = conceded = faced = bowled = 0
        for score in matches:
            if team not in (score["home"]["team_id"], score["away"]["team_id"]):
                continue
            a, b = (score["home"], score["away"]) if score["home"]["team_id"] == team else (score["away"], score["home"])
            scored += a["score"]
            conceded += b["score"]
            if sport["scoring_model"] == "innings":
                config = sport["periods"]
                full = config["overs_per_innings"] * config["balls_per_over"]
                faced += full if a["wickets"] >= config["wickets_per_innings"] else a["balls"]
                bowled += full if b["wickets"] >= config["wickets_per_innings"] else b["balls"]
        if sport["scoring_model"] != "innings":
            return scored - conceded
        bpo = sport["periods"]["balls_per_over"]
        return round((scored * bpo / faced if faced else 0) - (conceded * bpo / bowled if bowled else 0), 3)

    for team, row in rows.items():
        row["diff"] = differential(team, completed)
        maximum = row["points"] + points["win"] * remaining[team]
        if sum(rows[x]["points"] + points["win"] * remaining[x] >= row["points"] for x in teams if x != team) < spots:
            row["status"] = "clinched"
        elif sum(rows[x]["points"] > maximum for x in teams if x != team) >= spots:
            row["status"] = "eliminated"

    def resolve(group):
        if len(group) <= 1:
            return group
        mini = [m for m in completed if m["home"]["team_id"] in group and m["away"]["team_id"] in group]
        for criterion in league["tie_break_order"]:
            if criterion == "draw_lots":
                values = {t: hashlib.sha256(f"{league['lots_seed']}|{league_id}|{t}".encode()).hexdigest() for t in group}
            elif criterion == "h2h_points":
                values = {t: sum(h2h[t][x]["points"] for x in group if x != t) for t in group}
            elif criterion == "h2h_diff":
                values = {t: differential(t, mini) for t in group}
            else:
                values = {t: rows[t][{"overall_diff": "diff"}.get(criterion, criterion)] for t in group}
            if len(set(values.values())) <= 1:
                continue
            result = []
            for value in sorted(set(values.values()), reverse=criterion != "draw_lots"):
                subgroup = resolve([t for t in group if values[t] == value])
                result.extend(subgroup)
                # The last member above the next subgroup is separated by this criterion.
                if len(result) < len(group):
                    rows[subgroup[-1]]["resolved_by"] = criterion
            return result
        return group

    ordered = []
    for value in sorted({rows[t]["points"] for t in teams}, reverse=True):
        ordered.extend(resolve([t for t in teams if rows[t]["points"] == value]))
    for i, team in enumerate(ordered, 1):
        rows[team]["rank"] = i
    return {"league_id": league_id, "sport_id": sport["sport_id"], "qualification_spots": spots, "table": [rows[t] for t in ordered], "h2h": h2h}
