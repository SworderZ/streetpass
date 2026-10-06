import hashlib
import os
import sqlite3
from datetime import datetime, timedelta, timezone
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

DB = os.getenv("DB_PATH", "/data/streetpass.db")
API_KEY = os.getenv("TELEMETRY_KEY", "")
app = FastAPI(title="StreetPass Statistics API", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["https://sworderz.github.io"], allow_methods=["GET"], allow_headers=["*"])

class Telemetry(BaseModel):
    installation_id: str = Field(min_length=16, max_length=128)
    country: str = Field(min_length=2, max_length=2)
    app_version: str = Field(min_length=1, max_length=32)

def db():
    c = sqlite3.connect(DB)
    c.execute("CREATE TABLE IF NOT EXISTS installations (id TEXT PRIMARY KEY, country TEXT NOT NULL, version TEXT NOT NULL, last_seen TEXT NOT NULL)")
    return c

@app.get("/health")
def health(): return {"status": "ok"}

@app.post("/v1/telemetry", status_code=204)
def telemetry(item: Telemetry):
    if API_KEY and hashlib.sha256(item.installation_id.encode()).hexdigest() == "":
        raise HTTPException(401, "unauthorized")
    country = item.country.upper()
    if country == "XX": raise HTTPException(400, "country required")
    now = datetime.now(timezone.utc).isoformat()
    with db() as c:
        c.execute("INSERT INTO installations VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET country=excluded.country, version=excluded.version, last_seen=excluded.last_seen", (item.installation_id, country, item.app_version, now))

@app.get("/v1/stats")
def stats():
    cutoff = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat()
    with db() as c:
        total = c.execute("SELECT COUNT(*) FROM installations").fetchone()[0]
        active = c.execute("SELECT COUNT(*) FROM installations WHERE last_seen >= ?", (cutoff,)).fetchone()[0]
        rows = c.execute("SELECT country, COUNT(*) n FROM installations GROUP BY country ORDER BY n DESC").fetchall()
    return {"total_users": total, "active_30d": active, "countries": [{"code": x, "users": n} for x, n in rows], "updated_at": datetime.now(timezone.utc).isoformat()}
