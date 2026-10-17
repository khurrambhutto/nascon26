from collections import Counter
import itertools

import pytest

from app.common import DomainError
from app.fixtures import generate


@pytest.mark.parametrize("n", [2, 3, 4, 5, 6, 7, 8, 11, 16, 39, 40])
@pytest.mark.parametrize("legs", [1, 2])
def test_round_robin_properties(n, legs):
    teams = [f"t{i}" for i in range(n)]
    result = generate({"format": "round_robin", "teams": teams, "params": {"legs": legs}})
    all_matches = [m for r in result["rounds"] for m in r["matches"]]
    assert len(all_matches) == n*(n-1)//2*legs
    assert set(Counter(tuple(sorted((m["home"], m["away"]))) for m in all_matches).values()) == {legs}
    for round_ in result["rounds"]:
        appearances = [t for m in round_["matches"] for t in (m["home"], m["away"])] + round_["byes"]
        assert sorted(appearances) == sorted(teams)
        assert len(round_["byes"]) == n % 2
    for leg in range(1, legs+1):
        matches = [m for m in all_matches if m["leg"] == leg]
        for t in teams:
            assert abs(sum(m["home"] == t for m in matches)-sum(m["away"] == t for m in matches)) <= 1
    if legs == 2:
        assert len({(m["home"], m["away"]) for m in all_matches}) == n*(n-1)


@pytest.mark.parametrize("policy", ["void_all", "forfeit_remaining"])
def test_round_robin_withdrawals(policy):
    result = generate({"format": "round_robin", "teams": ["a", "b", "c", "d", "e"], "params": {"withdrawals": [{"team_id": "a", "after_round": 1}, {"team_id": "b", "after_round": 2}], "withdrawal_policy": policy}})
    for round_ in result["rounds"]:
        for match in round_["matches"]:
            pair = {match["home"], match["away"]}
            if {"a", "b"} <= pair or (policy == "void_all" and pair & {"a", "b"}):
                assert match["status"] == "voided"
            elif ("a" in pair and round_["round"] > 1) or ("b" in pair and round_["round"] > 2):
                assert match["status"] == "forfeit"
                assert match["awarded_to"] in pair - {"a", "b"}


@pytest.mark.parametrize("n", [2, 3, 4, 5, 8, 17, 64])
def test_swiss_first_round(n):
    teams = [f"t{i}" for i in range(n)]
    result = generate({"format": "swiss", "teams": teams})
    assert result["bye"] == (teams[-1] if n % 2 else None)
    assert [(m["home"], m["away"]) for m in result["pairings"]] == [(teams[i], teams[i+n//2]) for i in range(n//2)]


def test_swiss_history_and_no_valid_pairing():
    result = generate({"format": "swiss", "teams": ["t1", "t2", "t3", "t4", "t5"], "params": {"round": 2, "total_rounds": 3, "history": [{"round": 1, "bye": "t5", "matches": [{"home": "t1", "away": "t3", "result": "home"}, {"home": "t2", "away": "t4", "result": "draw"}]}]}})
    assert result["bye"] == "t3"
    assert [(r["team_id"], r["score"], r["buchholz"]) for r in result["standings"]] == [("t1", 1, 0), ("t5", 1, 0), ("t2", .5, .5), ("t4", .5, .5), ("t3", 0, 1)]
    # Four players, three rounds where a triangle has already met all outsiders:
    # maximum-cardinality matching must be checked, not just greedily paired.
    history = [{"round": 1, "bye": None, "matches": [{"home": "a", "away": "b", "result": "home"}, {"home": "c", "away": "d", "result": "home"}]}]
    result = generate({"format": "swiss", "teams": ["a", "b", "c", "d"], "params": {"round": 2, "total_rounds": 3, "history": history}})
    assert {frozenset((m["home"], m["away"])) for m in result["pairings"]}.isdisjoint({frozenset(("a", "b")), frozenset(("c", "d"))})


@pytest.mark.parametrize("n", [2, 3, 5, 6, 8, 17, 128])
def test_single_elimination_seeding_byes_and_advancement(n):
    teams = [f"t{i}" for i in range(1, n+1)]
    result = generate({"format": "single_elim", "teams": teams, "params": {"third_place_match": True}})
    nodes = {m["match_id"]: m for r in result["rounds"] for m in r["matches"]}
    first = result["rounds"][0]["matches"]
    assert sum(m["bye"] for m in first) == result["bracket_size"] - n
    assert len(nodes) == result["bracket_size"] - 1 + int(n > 2)
    assert len(result["rounds"]) == result["bracket_size"].bit_length() - 1
    for node in first:
        if node["bye"]:
            assert node["winner"] == node["home"]
            target = node["winner_to"]
            assert nodes[target["match_id"]][target["slot"]] == node["home"]
    if n == 6:
        assert [(m["home"], m["away"]) for m in first] == [("t1", None), ("t4", "t5"), ("t2", None), ("t3", "t6")]
        advanced = generate({"format": "single_elim", "teams": teams, "params": {"results": [{"match_id": "R1M2", "winner": "t4"}]}})
        assert advanced["rounds"][1]["matches"][0]["away"] == "t4"


@pytest.mark.parametrize("n", [3, 4, 5, 8, 16, 128])
def test_double_elimination_graph(n):
    result = generate({"format": "double_elim", "teams": [f"t{i}" for i in range(n)], "params": {"bracket_reset": True}})
    nodes = {m["match_id"]: m for r in result["winners"]+result["losers"] for m in r["matches"]}
    nodes.update({m["match_id"]: m for m in result["grand_final"]})
    assert sum(len(r["matches"]) for r in result["losers"]) == result["bracket_size"]-2
    fed = Counter()
    for node in nodes.values():
        if node["bracket"] == "winners":
            assert node["loser_to"] is not None
        if node["bracket"] == "losers":
            assert node["loser_to"] is None
        for key in ("winner_to", "loser_to"):
            if node[key]:
                target = node[key]
                assert target["match_id"] in nodes
                fed[target["match_id"], target["slot"]] += 1
    for mid, node in nodes.items():
        if not mid.startswith("WR1"):
            assert fed[mid, "home"] == fed[mid, "away"] == 1
    assert result["grand_final"][1]["required"] is None


@pytest.mark.parametrize("away_wins", [False, True])
def test_double_elimination_reset(away_wins):
    teams = ["a", "b", "c", "d"]
    params = {"bracket_reset": True, "results": []}
    for _ in range(20):
        result = generate({"format": "double_elim", "teams": teams, "params": params})
        nodes = [m for r in result["winners"]+result["losers"] for m in r["matches"]]+result["grand_final"]
        ready = next((m for m in nodes if m["home"] and m["away"] and m["winner"] is None and m["match_id"] != "GF2"), None)
        if ready is None:
            break
        params["results"].append({"match_id": ready["match_id"], "winner": ready["away"] if ready["match_id"] == "GF1" and away_wins else ready["home"]})
    assert result["grand_final"][1]["required"] == away_wins


@pytest.mark.parametrize("body", [{}, {"format": "unknown", "teams": []}, {"format": "round_robin", "teams": ["a", "a"]}, {"format": "round_robin", "teams": ["a", "b"], "params": {"withdrawals": [{"team_id": "x", "after_round": 0}]}}, {"format": "single_elim", "teams": ["a", "b"], "params": {"results": [{"match_id": "R1M1", "winner": "x"}]}}])
def test_fixture_validation(body):
    with pytest.raises(DomainError) as exc:
        generate(body)
    assert exc.value.status == 400
