#!/usr/bin/env python3
"""Builds the fast-charger map tiles that the trip planner downloads.

Runs in GitHub Actions (weekly). For every 2x2 degree tile of Europe it asks Overpass (OpenStreetMap) for charging
stations with a CCS plug and writes t/<tileLat>_<tileLon>.json in the same shape as an Overpass answer, with only the
tags the app reads. Tiles that fail keep the previous version, so one busy server never loses data.
Usage: build_chargers.py OUTDIR   (OUTDIR may already hold the previous tiles)
"""
import json, math, os, sys, time, urllib.parse, urllib.request

OUT = sys.argv[1]
ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
LAT = range(17, 36)     # 34..72 N
LON = range(-13, 23)    # 26 W..46 E
KEEP = {"name", "operator", "brand", "network", "capacity", "socket:type2_combo", "charge", "maxpower"}
UA = "LionFlow-charger-tiles (https://github.com/thocubeat/lionflow)"


def query(s, w, n, e):
    b = f"({s},{w},{n},{e})"
    q = ("[out:json][timeout:180];("
         f'node["amenity"="charging_station"]["socket:type2_combo"]{b};'
         f'way["amenity"="charging_station"]["socket:type2_combo"]{b};'
         f'node["amenity"="charging_station"]["socket:tesla_supercharger_ccs"]{b};'
         ");out center tags;")
    last = None
    for attempt in range(6):
        url = ENDPOINTS[attempt % len(ENDPOINTS)]
        try:
            req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": q}).encode(), headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=200) as r:
                j = json.load(r)
            if j.get("remark") and not j.get("elements"):
                raise RuntimeError("server gave up: " + j["remark"])
            return j
        except Exception as ex:  # noqa: BLE001
            last = ex
            time.sleep(5 + attempt * 10)
    raise last


def slim(j):
    out = []
    for el in j.get("elements", []):
        c = el.get("center") or el
        if "lat" not in c or "lon" not in c:
            continue
        t = el.get("tags", {})
        if str(t.get("socket:type2_combo", "")).strip().lower() in ("0", "no"):
            continue
        tags = {k: v for k, v in t.items() if k in KEEP or k.endswith(":output") or k == "charging_station:output"}
        # Drop sockets that don't deliver fast charging power info we use: keep only the output of CCS-ish keys.
        out.append({"type": el["type"], "id": el["id"], "lat": round(c["lat"], 5), "lon": round(c["lon"], 5), "tags": tags})
    return out


def main():
    os.makedirs(os.path.join(OUT, "t"), exist_ok=True)
    meta_path = os.path.join(OUT, "meta.json")
    built = set()
    if os.path.exists(meta_path):
        built = set(json.load(open(meta_path)).get("tiles", []))
    failed = 0
    for ty in LAT:
        for tx in LON:
            name = f"{ty}_{tx}"
            try:
                els = slim(query(ty * 2, tx * 2, ty * 2 + 2, tx * 2 + 2))
            except Exception as ex:  # noqa: BLE001
                failed += 1
                print("FAILED", name, ex, flush=True)
                continue
            path = os.path.join(OUT, "t", name + ".json")
            if els:
                with open(path, "w") as f:
                    json.dump({"elements": els}, f, separators=(",", ":"), ensure_ascii=False)
            elif os.path.exists(path):
                os.remove(path)
            built.add(name)
            print(name, len(els), flush=True)
            time.sleep(1.5)
    with open(meta_path, "w") as f:
        json.dump({"updated": time.strftime("%Y-%m-%d", time.gmtime()), "size": 2, "tiles": sorted(built)}, f, separators=(",", ":"))
    print("done, failed tiles:", failed)
    if failed > len(LAT) * len(LON) // 4:
        sys.exit(1)


main()
