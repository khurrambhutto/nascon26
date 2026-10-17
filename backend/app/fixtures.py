"""Custom tournament construction; NetworkX is used only for general matching."""
import math

import networkx as nx

from .common import check, fail, identifier, integer, obj


def generate(body):
    obj(body)
    format_ = body.get("format")
    limits = {"round_robin": (2, 40), "swiss": (2, 64), "single_elim": (2, 128), "double_elim": (3, 128)}
    check(isinstance(format_, str) and format_ in limits, "format")
    teams = body.get("teams")
    lo, hi = limits[format_]
    check(isinstance(teams, list) and lo <= len(teams) <= hi, "teams")
    for team in teams:
        identifier(team, "teams")
    check(len(set(teams)) == len(teams), "teams")
    params = obj(body.get("params", {}), "params")
    if format_ == "round_robin":
        return round_robin(teams, params)
    if format_ == "swiss":
        return swiss(teams, params)
    return elimination(teams, params, format_ == "double_elim")


def round_robin(teams, params):
    n = len(teams)
    legs = integer(params.get("legs", 1), "legs", 1, 2)
    withdrawals = params.get("withdrawals", [])
    check(isinstance(withdrawals, list), "withdrawals")
    policy = params.get("withdrawal_policy", "forfeit_remaining")
    check(policy in ("void_all", "forfeit_remaining"), "withdrawal_policy")
    count = (n - 1 if n % 2 == 0 else n) * legs
    withdrawn = {}
    for item in withdrawals:
        obj(item, "withdrawals")
        check(item.get("team_id") in teams, "withdrawals.team_id")
        withdrawn[item["team_id"]] = integer(item.get("after_round"), "after_round", 0, count)
    wheel = teams[:] + ([None] if n % 2 else [])
    positions = {team: i for i, team in enumerate(teams)}
    odd_size = n if n % 2 else n - 1

    def orient(a, b):
        ia, ib = positions[a], positions[b]
        if n % 2 == 0 and (ia == n - 1 or ib == n - 1):
            anchor, other = (a, b) if ia == n - 1 else (b, a)
            return (anchor, other) if positions[other] % 2 == 0 else (other, anchor)
        return (a, b) if (ib - ia) % odd_size <= odd_size // 2 else (b, a)

    first = []
    for _ in range(len(wheel) - 1):
        pairs, byes = [], []
        for i in range(len(wheel) // 2):
            a, b = wheel[i], wheel[-1-i]
            if a is None or b is None:
                byes.append(b if a is None else a)
            else:
                pairs.append(orient(a, b))
        first.append((pairs, byes))
        wheel = [wheel[0], wheel[-1], *wheel[1:-1]]
    rounds = []
    summary = {"total": 0, "scheduled": 0, "forfeit": 0, "voided": 0}
    for leg in range(1, legs + 1):
        for pairs, byes in first:
            number = len(rounds) + 1
            matches = []
            for index, (home, away) in enumerate(pairs, 1):
                if leg == 2:
                    home, away = away, home
                status, awarded = "scheduled", None
                if home in withdrawn and away in withdrawn:
                    status = "voided"
                elif policy == "void_all" and (home in withdrawn or away in withdrawn):
                    status = "voided"
                elif home in withdrawn and number > withdrawn[home]:
                    status, awarded = "forfeit", away
                elif away in withdrawn and number > withdrawn[away]:
                    status, awarded = "forfeit", home
                matches.append({"match_id": f"RR-L{leg}-R{number}-M{index}", "leg": leg, "home": home, "away": away, "status": status, "awarded_to": awarded})
                summary["total"] += 1
                summary[status] += 1
            rounds.append({"round": number, "matches": matches, "byes": byes})
    return {"format": "round_robin", "legs": legs, "team_count": n, "round_count": count, "rounds": rounds, "summary": summary}


def swiss(teams, params):
    n = len(teams)
    total = integer(params.get("total_rounds", math.ceil(math.log2(n))), "total_rounds", 1, n - 1)
    round_ = integer(params.get("round", 1), "round", 1, total)
    history = params.get("history", [])
    check(isinstance(history, list) and len(history) == round_ - 1, "history")
    score = dict.fromkeys(teams, 0)
    opponents = {team: [] for team in teams}
    byes = set()
    rounds_seen = set()
    for entry in history:
        obj(entry, "history")
        r = integer(entry.get("round"), "history.round", 1, round_ - 1)
        check(r not in rounds_seen, "history.round")
        rounds_seen.add(r)
        matches = entry.get("matches")
        check(isinstance(matches, list) and len(matches) == n // 2, "history.matches")
        bye = entry.get("bye")
        check((bye in teams) if n % 2 else bye is None, "history.bye")
        seen = set()
        if bye is not None:
            score[bye] += 2
            byes.add(bye)
            seen.add(bye)
        for match in matches:
            obj(match, "history.matches")
            a, b = match.get("home"), match.get("away")
            check(a in teams and b in teams and a != b and a not in seen and b not in seen, "history.matches")
            result = match.get("result")
            check(result in ("home", "away", "draw"), "history.result")
            score[a] += 2 if result == "home" else 1 if result == "draw" else 0
            score[b] += 2 if result == "away" else 1 if result == "draw" else 0
            opponents[a].append(b)
            opponents[b].append(a)
            seen.update((a, b))
    buchholz = {t: sum(score[o] for o in opponents[t]) for t in teams}
    seed = {t: i for i, t in enumerate(teams)}
    ordered = sorted(teams, key=lambda t: (-score[t], -buchholz[t], seed[t]))
    table = [{"rank": i, "team_id": t, "score": score[t] / 2, "buchholz": buchholz[t] / 2} for i, t in enumerate(ordered, 1)]
    if round_ == 1:
        m = n // 2
        bye = teams[-1] if n % 2 else None
        pairs = [(teams[i], teams[i+m]) for i in range(m)]
    else:
        candidates = sorted((t for t in teams if t not in byes), key=lambda t: (score[t], -seed[t])) if n % 2 else [None]
        pairs = None
        for bye in candidates:
            active = [t for t in ordered if t != bye]
            graph = nx.Graph()
            graph.add_nodes_from(active)
            for i, a in enumerate(active):
                for b in active[i+1:]:
                    if b not in opponents[a]:
                        graph.add_edge(a, b, weight=abs(score[a] - score[b]))
            # Blossom finds a globally minimum cost maximum-cardinality matching.
            matching = nx.min_weight_matching(graph, weight="weight")
            if len(matching) * 2 == len(active):
                pairs = [tuple(sorted(pair, key=lambda t: seed[t])) for pair in matching]
                pairs.sort(key=lambda pair: seed[pair[0]])
                break
        if pairs is None:
            fail(409, "NO_VALID_PAIRING", round=round_)
    return {"format": "swiss", "round": round_, "total_rounds": total, "pairings": [{"match_id": f"SW-R{round_}-M{i}", "home": a, "away": b} for i, (a, b) in enumerate(pairs, 1)], "bye": bye, "standings": table}


def seed_order(size):
    order = [1, 2]
    current = 2
    while current < size:
        current *= 2
        order = [value for seed in order for value in (seed, current + 1 - seed)]
    return order


def elimination(teams, params, double):
    size = 1 << (len(teams) - 1).bit_length()
    k = size.bit_length() - 1
    third = params.get("third_place_match", False)
    reset = params.get("bracket_reset", False)
    check(type(third) is bool and type(reset) is bool, "third_place_match", "bracket_reset")
    results = params.get("results", [])
    check(isinstance(results, list), "results")
    nodes, known, resolved = {}, {}, set()

    def make(mid, bracket, r, index):
        node = {"match_id": mid, "bracket": bracket, "round": r, "index": index, "home": None, "away": None, "home_seed": None, "away_seed": None, "bye": False, "winner": None, "loser": None, "winner_to": None, "loser_to": None}
        nodes[mid] = node
        known[mid] = set()
        return node

    winners = []
    for r in range(1, k+1):
        matches = [make(f"{'WR' if double else 'R'}{r}M{i}", "winners" if double else "main", r, i) for i in range(1, size // 2**r + 1)]
        winners.append({"round": r, "matches": matches})
    for r in range(k-1):
        for i, node in enumerate(winners[r]["matches"]):
            node["winner_to"] = {"match_id": winners[r+1]["matches"][i//2]["match_id"], "slot": "home" if i % 2 == 0 else "away"}
    order = seed_order(size)
    for i, node in enumerate(winners[0]["matches"]):
        seeds = sorted(order[2*i:2*i+2])
        node["home_seed"], node["away_seed"] = seeds[0], seeds[1] if seeds[1] <= len(teams) else None
        node["home"] = teams[seeds[0]-1]
        node["away"] = teams[seeds[1]-1] if seeds[1] <= len(teams) else None
        known[node["match_id"]] = {"home", "away"}
        node["bye"] = node["away"] is None
    losers, finals = [], []
    if double:
        for r in range(1, 2*(k-1)+1):
            count = size // 2**(((r+1)//2)+1)
            losers.append({"round": r, "matches": [make(f"LR{r}M{i}", "losers", r, i) for i in range(1, count+1)]})
        for i, node in enumerate(winners[0]["matches"]):
            node["loser_to"] = {"match_id": losers[0]["matches"][i//2]["match_id"], "slot": "home" if i % 2 == 0 else "away"}
        for ri in range(1, k):
            target_round = losers[2*ri-1]["matches"]
            for i, node in enumerate(winners[ri]["matches"]):
                node["loser_to"] = {"match_id": target_round[len(target_round)-1-i]["match_id"], "slot": "away"}
        for ri in range(len(losers)-1):
            for i, node in enumerate(losers[ri]["matches"]):
                target = losers[ri+1]["matches"][i if ri % 2 == 0 else i//2]
                node["winner_to"] = {"match_id": target["match_id"], "slot": "home" if ri % 2 == 0 or i % 2 == 0 else "away"}
        gf = make("GF1", "grand_final", 1, 1)
        gf.update(conditional=False, required=True)
        finals.append(gf)
        winners[-1]["matches"][0]["winner_to"] = {"match_id": "GF1", "slot": "home"}
        losers[-1]["matches"][0]["winner_to"] = {"match_id": "GF1", "slot": "away"}
        if reset:
            gf2 = make("GF2", "grand_final", 2, 1)
            gf2.update(conditional=True, required=None)
            finals.append(gf2)
            gf["winner_to"] = {"match_id": "GF2", "slot": "home"}
            gf["loser_to"] = {"match_id": "GF2", "slot": "away"}
    elif third and k >= 2:
        make("TP", "third_place", k, 1)
        for i, node in enumerate(winners[-2]["matches"]):
            node["loser_to"] = {"match_id": "TP", "slot": "home" if i == 0 else "away"}
    requested = {}
    for result in results:
        obj(result, "results")
        check(result.get("match_id") in nodes and result.get("match_id") not in requested, "results.match_id")
        identifier(result.get("winner"), "results.winner")
        requested[result["match_id"]] = result["winner"]
    seed_map = {t: i+1 for i, t in enumerate(teams)}
    changed = True
    while changed:
        changed = False
        for mid, node in nodes.items():
            if mid in resolved or known[mid] != {"home", "away"}:
                continue
            if mid == "GF2" and not node["required"]:
                continue
            participants = [node[s] for s in ("home", "away") if node[s] is not None]
            if mid in requested:
                check(requested[mid] in participants, "results.winner")
                winner = requested[mid]
            elif len(participants) <= 1:
                winner = participants[0] if participants else None
                node["bye"] = True
            else:
                continue
            loser = next((p for p in participants if p != winner), None)
            node["winner"], node["loser"] = winner, loser
            resolved.add(mid)
            changed = True
            if mid == "GF1" and reset:
                nodes["GF2"]["required"] = winner == node["away"]
            for key, participant in (("winner_to", winner), ("loser_to", loser)):
                target = node[key]
                if target:
                    dest = nodes[target["match_id"]]
                    slot = target["slot"]
                    dest[slot] = participant
                    dest[slot + "_seed"] = seed_map.get(participant)
                    known[dest["match_id"]].add(slot)
    check(all(mid in resolved for mid in requested), "results")
    if double:
        return {"format": "double_elim", "bracket_size": size, "bracket_reset": reset, "winners": winners, "losers": losers, "grand_final": finals}
    if "TP" in nodes:
        winners.append({"round": k, "matches": [nodes["TP"]]})
    return {"format": "single_elim", "bracket_size": size, "rounds": winners}
