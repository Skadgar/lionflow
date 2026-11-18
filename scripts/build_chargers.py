#!/usr/bin/env python3
"""Builds the fast-charger map tiles that the trip planner downloads.

Runs in GitHub Actions (weekly), after osmium has cut every charging station out of the OpenStreetMap Europe file
(Geofabrik) into a GeoJSON sequence. This script keeps the ones with a CCS plug and writes t/<tileLat>_<tileLon>.json
for every 2x2 degree tile, in the same shape as an Overpass answer with only the tags the app reads.
Usage: build_chargers.py INPUT.geojsonseq OUTDIR
"""
import json, os, shutil, sys, time

SRC, OUT = sys.argv[1], sys.argv[2]
LAT = range(17, 36)     # 34..72 N
LON = range(-13, 23)    # 26 W..46 E
KEEP = {"name", "operator", "brand", "network", "capacity", "socket:type2_combo", "charge", "maxpower"}
KIND = {"n": "node", "w": "way", "r": "relation", "a": "way"}


def center(geom):
    t = geom.get("type")
    c = geom.get("coordinates")
    if t == "Point":
        return c[1], c[0]
    if t == "Polygon":
        ring = c[0]
    elif t == "MultiPolygon":
        ring = c[0][0]
    else:
        return None
    ring = ring[:-1] if len(ring) > 1 else ring
    return sum(p[1] for p in ring) / len(ring), sum(p[0] for p in ring) / len(ring)


def has_ccs(t):
    for k in ("socket:type2_combo", "socket:tesla_supercharger_ccs"):
        v = str(t.get(k, "")).strip().lower()
        if v and v not in ("0", "no"):
            return True
    return False


def main():
    tiles = {}
    n = 0
    with open(SRC, encoding="utf-8") as f:
        for line in f:
            line = line.strip("\x1e\n ")
            if not line:
                continue
            try:
                ft = json.loads(line)
            except ValueError:
                continue
            t = ft.get("properties") or {}
            if t.get("amenity") != "charging_station" or not has_ccs(t):
                continue
            c = center(ft.get("geometry") or {})
            if not c:
                continue
            lat, lon = c
            uid = str(ft.get("id") or t.get("@id") or "")
            kind = KIND.get(uid[:1], "node")
            num = uid[1:] if uid[:1] in KIND else uid
            try:
                num = int(num)
            except ValueError:
                continue
            tags = {k: v for k, v in t.items() if k in KEEP or k.endswith(":output") or k == "charging_station:output"}
            key = f"{int(lat // 2)}_{int(lon // 2)}"
            tiles.setdefault(key, []).append({"type": kind, "id": num, "lat": round(lat, 5), "lon": round(lon, 5), "tags": tags})
            n += 1
    shutil.rmtree(os.path.join(OUT, "t"), ignore_errors=True)
    os.makedirs(os.path.join(OUT, "t"))
    for key, els in tiles.items():
        with open(os.path.join(OUT, "t", key + ".json"), "w", encoding="utf-8") as f:
            json.dump({"elements": els}, f, separators=(",", ":"), ensure_ascii=False)
    built = [f"{ty}_{tx}" for ty in LAT for tx in LON]      # every tile of the grid; tiles without a file are empty
    with open(os.path.join(OUT, "meta.json"), "w") as f:
        json.dump({"updated": time.strftime("%Y-%m-%d", time.gmtime()), "size": 2, "tiles": built}, f, separators=(",", ":"))
    print(f"{n} chargers in {len(tiles)} tiles")
    if n < int(os.environ.get("MIN_CHARGERS", "5000")):
        sys.exit("far too few chargers found: the input must be broken")


main()
