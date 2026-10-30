#!/usr/bin/env python3
"""Reads Steam's local files (playtime + achievements) and pushes stats.json
to a private GitHub repo, so the Android app can read it from anywhere.

  python sync.py             sync once
  python sync.py --watch     keep running, sync whenever Steam's files change
  python sync.py --dry-run   build stats.json locally, upload nothing
Only the Python standard library is used.
"""
import argparse
import base64
import hashlib
import json
import re
import struct
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONFIG = HERE / "config.json"
NAMES = HERE / "names_cache.json"   # appid -> game name; edit it to fix a name
ART = HERE / "art_cache.json"       # appid -> cover image URL from the Steam store
OUT = HERE / "stats.json"
STATE = HERE / ".last_hash"
CDN = "https://cdn.cloudflare.steamstatic.com"

DEFAULTS = {
    "steam_path": "",          # empty = auto-detect
    "account_id": "",          # empty = auto-detect (newest folder in Steam\userdata)
    "repo": "OWNER/REPO",      # private GitHub repo, e.g. "hamza/game-stats"
    "branch": "main",
    "path": "stats.json",
    "token": "",               # fine-grained token, Contents: read & write, that repo only
    "exclude_appids": [480],   # 480 = Spacewar test app
}


# ---------- VDF parsers ----------
def parse_text_vdf(text):
    tok = re.findall(r'"((?:[^"\\]|\\.)*)"|(\{)|(\})', text)
    pos = 0

    def block():
        nonlocal pos
        d = {}
        while pos < len(tok):
            k, o, c = tok[pos]
            if c:
                pos += 1
                return d
            pos += 1
            if pos >= len(tok):
                break
            v, o2, _ = tok[pos]
            pos += 1
            d[k] = block() if o2 else v
        return d

    return block()


def parse_bin_vdf(b):
    pos = 0

    def cstr():
        nonlocal pos
        e = b.index(b"\x00", pos)
        s = b[pos:e].decode("utf-8", "replace")
        pos = e + 1
        return s

    def obj():
        nonlocal pos
        d = {}
        while pos < len(b):
            t = b[pos]
            pos += 1
            if t == 8:
                return d
            k = cstr()
            if t == 0:
                d[k] = obj()
            elif t == 1:
                d[k] = cstr()
            elif t in (2, 4, 6):
                d[k] = struct.unpack_from("<i", b, pos)[0]
                pos += 4
            elif t == 3:
                d[k] = struct.unpack_from("<f", b, pos)[0]
                pos += 4
            elif t == 7:
                d[k] = struct.unpack_from("<Q", b, pos)[0]
                pos += 8
            elif t == 10:
                d[k] = struct.unpack_from("<q", b, pos)[0]
                pos += 8
            else:
                raise ValueError(f"unknown VDF type {t}")
        return d

    return obj()


def ci(d, key):
    """case-insensitive dict lookup"""
    for k, v in d.items():
        if k.lower() == key.lower():
            return v
    return {}


# ---------- Steam locations ----------
def find_steam():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as k:
            p = Path(winreg.QueryValueEx(k, "SteamPath")[0])
            if p.exists():
                return p
    except Exception:
        pass
    for p in (r"C:\Program Files (x86)\Steam", r"C:\Program Files\Steam"):
        if Path(p).exists():
            return Path(p)
    sys.exit("Steam folder not found. Set steam_path in config.json")


def find_account(steam):
    best = None
    for d in (steam / "userdata").iterdir():
        f = d / "config" / "localconfig.vdf"
        if d.name.isdigit() and f.exists():
            if best is None or f.stat().st_mtime > best[0]:
                best = (f.stat().st_mtime, d.name)
    if not best:
        sys.exit("No localconfig.vdf found in Steam\\userdata. Set account_id in config.json")
    return best[1]


# ---------- data ----------
def pick(v):
    if isinstance(v, dict):
        return v.get("english") or next(iter(v.values()), "")
    return v or ""


def achievements(appid, stats_file, schema_file):
    cache = parse_bin_vdf(stats_file.read_bytes()).get("cache", {})
    schema = parse_bin_vdf(schema_file.read_bytes())
    schema = schema.get(str(appid)) or next(iter(schema.values()), {})
    items = []
    for sid, stat in schema.get("stats", {}).items():
        if not isinstance(stat, dict) or "bits" not in stat:
            continue
        mine = cache.get(sid, {})
        mine = mine if isinstance(mine, dict) else {}
        mask = int(mine.get("data", 0)) & 0xFFFFFFFF
        times = mine.get("AchievementTimes", {})
        for bit, b in stat["bits"].items():
            disp = b.get("display", {})
            t = int(times.get(bit, 0) or 0)
            icon, gray = disp.get("icon"), disp.get("icon_gray")
            items.append({
                "id": b.get("name", f"{sid}_{bit}"),
                "name": pick(disp.get("name")),
                "desc": pick(disp.get("desc")),
                "hidden": bool(int(disp.get("hidden", 0) or 0)),
                "unlocked": bool((mask >> int(bit)) & 1) or t > 0,
                "time": t,
                "icon": icon or "",
                "icon_gray": gray or "",
            })
    return items


def load_meta(appids, fetch):
    names = json.loads(NAMES.read_text(encoding="utf-8")) if NAMES.exists() else {}
    art = json.loads(ART.read_text(encoding="utf-8")) if ART.exists() else {}
    todo = [a for a in appids if str(a) not in art]  # art is saved for every game we have asked about
    if fetch and todo:
        print(f"Looking up names and cover art for {len(todo)} games (cached afterwards)...")
    for a in todo if fetch else []:
        k = str(a)
        for attempt in range(3):
            try:
                url = f"https://store.steampowered.com/api/appdetails?appids={a}&filters=basic"
                req = urllib.request.Request(url, headers={"User-Agent": "game-stats-sync"})
                with urllib.request.urlopen(req, timeout=15) as r:
                    data = json.load(r).get(k, {})
                if data.get("success"):
                    names.setdefault(k, data["data"]["name"])  # keeps names you edited
                    art[k] = data["data"].get("header_image", "")
                else:
                    art[k] = ""
                NAMES.write_text(json.dumps(names, indent=1, ensure_ascii=False), encoding="utf-8")
                ART.write_text(json.dumps(art, indent=1, ensure_ascii=False), encoding="utf-8")
                break
            except urllib.error.HTTPError as e:
                if e.code in (403, 429) and attempt < 2:
                    print("  Steam is rate-limiting requests, waiting 2 minutes...")
                    time.sleep(120)
                    continue
                print(f"  lookup failed for {a}: {e}")
                break
            except Exception as e:
                print(f"  lookup failed for {a}: {e}")
                break
        time.sleep(0.6)
    return names, art


def art_urls(appid, art):
    """Best URL first (from the store), then CDN fallbacks the app tries in order."""
    urls = [art.get(str(appid)) or ""]
    for f in ("header.jpg", "capsule_616x353.jpg", "library_hero.jpg", "capsule_231x87.jpg"):
        urls.append(f"{CDN}/steam/apps/{appid}/{f}")
    return [u for i, u in enumerate(urls) if u and u not in urls[:i]]


def collect(steam, account, cfg, fetch_names=True):
    local = steam / "userdata" / account / "config" / "localconfig.vdf"
    root = parse_text_vdf(local.read_text(encoding="utf-8", errors="replace"))
    store = ci(root, "UserLocalConfigStore")
    apps = ci(ci(ci(ci(store, "Software"), "Valve"), "Steam"), "apps")
    exclude = {int(x) for x in cfg.get("exclude_appids", [])}

    games = {}
    for a, v in apps.items():
        if not a.isdigit() or not isinstance(v, dict) or int(a) in exclude:
            continue
        minutes = int(v.get("Playtime", 0) or 0)
        last = int(v.get("LastPlayed", 0) or 0)
        if minutes > 0 or last > 0:
            games[int(a)] = {
                "minutes": minutes,
                "minutes_2wks": int(v.get("Playtime2wks", 0) or 0),
                "last_played": last,
            }

    ach = {}
    stats_dir = steam / "appcache" / "stats"
    for f in stats_dir.glob(f"UserGameStats_{account}_*.bin"):
        appid = int(f.stem.rsplit("_", 1)[1])
        schema = stats_dir / f"UserGameStatsSchema_{appid}.bin"
        if appid in exclude:
            continue
        if not schema.exists():
            print(f"  no schema file for {appid}, achievements skipped")
            continue
        try:
            items = achievements(appid, f, schema)
        except Exception as e:
            print(f"  skipped achievements for {appid}: {e}")
            continue
        if items:
            ach[appid] = items
            games.setdefault(appid, {"minutes": 0, "minutes_2wks": 0, "last_played": 0})

    names, art = load_meta(sorted(games), fetch_names)
    out = []
    for appid, g in games.items():
        items = ach.get(appid, [])
        out.append({
            "appid": appid,
            "name": names.get(str(appid), f"App {appid}"),
            **g,
            "art": art_urls(appid, art),
            "achievements": {
                "unlocked": sum(1 for i in items if i["unlocked"]),
                "total": len(items),
                "list": items,
            },
        })
    out.sort(key=lambda x: x["last_played"], reverse=True)
    return {"generated_at": int(time.time()), "games": out}


# ---------- GitHub ----------
def gh(method, url, token, body=None):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "game-stats-sync",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def push(cfg, text):
    if not cfg["token"] or cfg["repo"] == "OWNER/REPO":
        sys.exit("Fill in repo and token in config.json first.")
    url = f"https://api.github.com/repos/{cfg['repo']}/contents/{cfg['path']}"
    sha = None
    try:
        sha = gh("GET", f"{url}?ref={cfg['branch']}", cfg["token"]).get("sha")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    body = {
        "message": "sync stats",
        "content": base64.b64encode(text.encode("utf-8")).decode(),
        "branch": cfg["branch"],
    }
    if sha:
        body["sha"] = sha
    gh("PUT", url, cfg["token"], body)


# ---------- main ----------
def signature(steam, account):
    files = [steam / "userdata" / account / "config" / "localconfig.vdf"]
    files += list((steam / "appcache" / "stats").glob(f"UserGameStats_{account}_*.bin"))
    return tuple(sorted((str(p), p.stat().st_mtime_ns) for p in files if p.exists()))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--watch", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-names", action="store_true", help="skip game-name lookups")
    ap.add_argument("--steam", help="Steam folder (overrides config)")
    args = ap.parse_args()

    if not CONFIG.exists():
        CONFIG.write_text(json.dumps(DEFAULTS, indent=2))
        print(f"Created {CONFIG.name}, fill it in before uploading.")
    cfg = {**DEFAULTS, **json.loads(CONFIG.read_text())}
    steam = Path(args.steam or cfg["steam_path"] or find_steam())
    account = cfg["account_id"] or find_account(steam)

    def run():
        data = collect(steam, account, cfg, not args.no_names)
        text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
        OUT.write_text(text, encoding="utf-8")
        n_ach = sum(g["achievements"]["total"] for g in data["games"])
        print(f"{len(data['games'])} games, {n_ach} achievements")
        if args.dry_run:
            return
        stable = {k: v for k, v in data.items() if k != "generated_at"}
        h = hashlib.sha1(json.dumps(stable, sort_keys=True).encode()).hexdigest()
        if STATE.exists() and STATE.read_text() == h:
            print("No changes, nothing uploaded.")
            return
        push(cfg, text)
        STATE.write_text(h)
        print("Uploaded.")

    run()
    if args.watch:
        last = signature(steam, account)
        print("Watching Steam files...")
        while True:
            time.sleep(60)
            sig = signature(steam, account)
            if sig != last:
                time.sleep(15)  # let Steam finish writing
                last = signature(steam, account)
                try:
                    run()
                except Exception as e:
                    print("Sync failed:", e)


if __name__ == "__main__":
    main()
