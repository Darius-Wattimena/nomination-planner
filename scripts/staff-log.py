"""
Turns the staff log of the osu! wiki (https://osu.ppy.sh/wiki/en/People/Staff_log) into who joined and left the groups that could
nominate, for the role history of the planner. The wiki stopped updating it on 27 October 2023, osu! keeps the group history since.

    python scripts/staff-log.py                 reads the wiki on GitHub
    python scripts/staff-log.py <folder>        reads <year>.md files from a folder instead

Writes backend/src/main/resources/history/staff-log.json, which the planner reads when importing the ranked history.
"""

import calendar
import json
import re
import sys
import urllib.request
from pathlib import Path

YEARS = range(2007, 2024)
SOURCE = "https://raw.githubusercontent.com/ppy/osu-wiki/master/wiki/People/Staff_log/{year}/en.md"
OUTPUT = Path(__file__).resolve().parent.parent / "backend" / "src" / "main" / "resources" / "history" / "staff-log.json"

# Only the groups whose members could nominate (or bubble) beatmaps, and the NAT, the rest is left out
GROUPS = [
    (re.compile(r"probation", re.I), "PBN"),
    (re.compile(r"beatmap nominator|^bns?$", re.I), "BN"),
    (re.compile(r"nomination assessment|^nat$", re.I), "NAT"),
    (re.compile(r"quality assurance|^qat$", re.I), "QAT"),
    (re.compile(r"mapping assistance|^mat$", re.I), "MAT"),
    (re.compile(r"beatmap appreciation|beatmap approval|^bat$", re.I), "BAT"),
]
MODES = {"osu!taiko": "taiko", "osu!catch": "fruits", "osu!ctb": "fruits", "osu!mania": "mania", "osu!standard": "osu"}

USER = r"\[(?P<name>(?:\\.|\[[^\]]*\]|[^\]\\])*)\]\(https://osu\.ppy\.sh/users/(?P<id>[^)]+)\)"
DATE = r"(?P<date>\d{4}-\d{2}-\d{2})"
PREFIXED = re.compile(rf"^- {DATE}: (?P<verb>Added|Readded|Removed|Moved) {USER} (?P<rest>.*)$")
SUFFIXED = re.compile(rf"^- (?P<verb>Added|Readded|Removed|Moved) {USER} (?P<rest>.*?)\s*\({DATE}\)\s*$")
BARE = re.compile(rf"^- {USER}(?:\s*-\s*[^(]*)?\s*(?:\({DATE}\))?\s*$")
ANY_DATE = re.compile(DATE)


def read(year, folder):
    if folder:
        return (Path(folder) / f"{year}.md").read_text(encoding="utf-8")
    with urllib.request.urlopen(SOURCE.format(year=year)) as response:
        return response.read().decode("utf-8")


def to_groups(text):
    """The groups that could nominate in "A and B", with the modes their names mention"""
    found = []
    for part in re.split(r",\s*|\s+and\s+", text.replace("*", "").strip()):
        part = part.strip()
        modes = sorted({mode for name, mode in MODES.items() if name in part.lower()})
        bare = re.sub(r"osu!(taiko|catch|ctb|mania|standard)\s*", "", part, flags=re.I).strip()
        group = next((group for pattern, group in GROUPS if pattern.search(bare)), None)
        if group:
            found.append((group, modes))
    return found


def valid(date):
    """The wiki has a few dates that don't exist, like 2022-04-31, which become the last day of that month"""
    year, month, day = (int(it) for it in date.split("-"))
    return f"{year:04d}-{month:02d}-{min(day, calendar.monthrange(year, month)[1]):02d}"


def change(user, group, modes, kind, date):
    date = valid(date)
    return {
        "userId": user["id"],
        "username": re.sub(r"\\(.)", r"\1", user["name"]),
        "group": group,
        "kind": kind,
        "at": date,
        "gamemodes": modes,
    }


def parse(text):
    changes = []
    heading = ""
    paragraph_date = None

    for raw in text.splitlines():
        line = re.sub(r"<!--.*?-->", "", raw).rstrip()

        if line.startswith("#"):
            heading = line.lower()
            paragraph_date = None
            continue
        if line and not line.startswith("-"):
            # Like "All users listed below were added on 2017-01-20."
            found = ANY_DATE.search(line)
            paragraph_date = found.group("date") if found else paragraph_date
            continue

        match = PREFIXED.match(line) or SUFFIXED.match(line)
        if match:
            if not match.group("id").isdigit():
                continue
            verb, rest, date = match.group("verb"), match.group("rest"), match.group("date")
            if verb in ("Added", "Readded") and rest.startswith("to "):
                changes += [change(match, group, modes, "Added", date) for group, modes in to_groups(rest[3:])]
            elif verb == "Removed" and rest.startswith("from "):
                changes += [change(match, group, modes, "Removed", date) for group, modes in to_groups(rest[5:])]
            elif verb == "Moved":
                moved = re.match(r"^(?:from (?P<old>.*?) )?to (?P<new>.*)$", rest)
                if moved:
                    old = to_groups(moved.group("old") or "")
                    new = to_groups(moved.group("new"))
                    # Moving between groups that both could nominate is only a change of group
                    changes += [change(match, group, modes, "Removed", date) for group, modes in old]
                    changes += [change(match, group, modes, "Added", date) for group, modes in new]
            continue

        bare = BARE.match(line)
        if bare and bare.group("id").isdigit():
            date = bare.group("date") or paragraph_date
            kind = "Removed" if "removal" in heading else "Added" if "addition" in heading else None
            group = "BAT" if "bat" in heading else "BN" if "nominator" in heading else None
            if date and kind and group:
                changes.append(change(bare, group, [], kind, date))

    return changes


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else None
    changes = [it for year in YEARS for it in parse(read(year, folder))]
    # Each change once, in the order they happened
    unique = {json.dumps(it, sort_keys=True): it for it in changes}.values()
    ordered = sorted(unique, key=lambda it: (it["at"], it["userId"], it["group"], it["kind"]))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(ordered, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")

    by_group = {}
    for it in ordered:
        by_group[it["group"]] = by_group.get(it["group"], 0) + 1
    print(f"Wrote {len(ordered)} changes of {len({it['userId'] for it in ordered})} users to {OUTPUT}: {by_group}")


if __name__ == "__main__":
    main()
