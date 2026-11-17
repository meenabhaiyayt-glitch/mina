"""Home panel data: read-only snapshots of the user's connected accounts.

GET /v1/home/data returns the small, app-renderable summary the home
panels need: upcoming calendar events and recent mail, fetched through
the user's own Composio connections. Read-only listing tools only, one
bounded attempt per toolkit; nothing is written and no approval gate is
involved. Sections degrade to a status string instead of failing the
whole response, so one broken account never blanks the panel page.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from mina_api.database.session import get_db
from mina_api.security.auth import CurrentUser

router = APIRouter(prefix="/home", tags=["home"])

# Toolkits the home panels read, in the order the app renders them.
SECTIONS: list[tuple[str, str]] = [("calendar", "googlecalendar"), ("mail", "gmail")]

# Verbs that mark a listing (read-only) tool worth trying.
_LISTING_VERBS = ("LIST", "FIND", "FETCH", "GET_", "SEARCH")

MAX_ROWS = 5
TOMORROW = timedelta(days=7)
YESTERDAY = timedelta(days=-1)


def _iso(at: datetime) -> str:
    return at.astimezone(timezone.utc).isoformat()


def _status_of(conn: dict | None) -> str:
    """ok when a connection is live; reconnect when it exists but expired."""
    if conn is None:
        return "not_connected"
    status = str(conn.get("status") or "").upper()
    return "ok" if status in {"ACTIVE", "ACTIVE_CONNECTION", "CONNECTED"} else "reconnect"


def _fillable_args(schema: dict, now: datetime) -> dict | None:
    """Required arguments a read-only snapshot can fill on its own.

    Returns None when the tool needs input we cannot guess (a calendar id
    we do not know, a free-text query we would be inventing). Optional
    fields are left empty; the tool's own defaults apply.
    """
    args: dict[str, Any] = {}
    params = schema.get("input_parameters")
    entries: list[dict] = params if isinstance(params, list) else []
    for param in entries:
        if not isinstance(param, dict) or not param.get("required"):
            continue
        name = str(param.get("name") or "").lower()
        value: Any = None
        if any(k in name for k in ("calendar_id", "calendarid", "calendar")):
            value = "primary"
        elif any(k in name for k in ("time_min", "time_start", "start_time", "from")):
            value = _iso(now + YESTERDAY)
        elif any(k in name for k in ("time_max", "time_end", "end_time", "until", "to")):
            value = _iso(now + TOMORROW)
        elif any(k in name for k in ("max_results", "limit", "max", "count", "page_size")):
            value = MAX_ROWS
        elif name in {"query", "q", "search"}:
            value = ""
        elif any(k in name for k in ("unread", "read", "include", "filter")):
            value = False
        if value is None:
            return None
        args[param["name"]] = value
    return args


def _first_list(payload: Any) -> list[dict]:
    """The first list of dicts anywhere in a tool payload."""
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        for value in payload.values():
            found = _first_list(value)
            if found:
                return found
    return []


def _event_row(item: dict) -> dict | None:
    title = str(item.get("summary") or item.get("title") or item.get("name") or "").strip()
    start = item.get("start")
    if isinstance(start, dict):
        start = start.get("dateTime") or start.get("date")
    start = start or item.get("start_time") or item.get("startTime") or item.get("startDateTime") or ""
    end = item.get("end")
    if isinstance(end, dict):
        end = end.get("dateTime") or end.get("date")
    end = end or item.get("end_time") or item.get("endTime") or ""
    if not title:
        return None
    row = {"title": title, "start": str(start or ""), "end": str(end or "")}
    location = item.get("location") or item.get("description")
    if location:
        row["detail"] = str(location)[:200]
    return row


def _mail_row(item: dict) -> dict | None:
    sender_raw = item.get("from") or item.get("sender") or item.get("from_email") or ""
    if isinstance(sender_raw, dict):
        sender_raw = sender_raw.get("email") or sender_raw.get("name") or ""
    sender = str(sender_raw).strip()
    subject = str(item.get("subject") or item.get("title") or "").strip()
    if not sender and not subject:
        return None
    snippet = str(item.get("snippet") or item.get("body") or item.get("preview") or "")[:160]
    date = str(item.get("date") or item.get("received_at") or item.get("internal_date") or "")
    unread = item.get("unread")
    if unread is None:
        label = item.get("labelIds") or item.get("labels") or []
        unread = isinstance(label, (list, str)) and "UNREAD" in str(label)
    return {"from": sender, "subject": subject, "snippet": snippet, "date": date, "unread": bool(unread)}


def _rows(section: str, payload: Any) -> list[dict]:
    items = _first_list(payload)
    rows = []
    for item in items:
        row = _event_row(item) if section == "calendar" else _mail_row(item)
        if row is not None:
            rows.append(row)
        if len(rows) >= MAX_ROWS:
            break
    return rows


def _data_payload(result: dict) -> Any:
    """ComposioService.execute returns the result excerpt as JSON text."""
    if not result.get("successful"):
        return None
    raw = result.get("data_excerpt")
    if raw is None:
        return None
    try:
        return json.loads(raw) if isinstance(raw, str) else raw
    except (ValueError, TypeError):
        return raw


async def _snapshot(service, db: AsyncSession, user_id: UUID, toolkit: str, query: str, section: str) -> dict:
    now = datetime.now(timezone.utc)
    try:
        tools = await service.search_tools(query, toolkits=[toolkit], limit=25)
    except HTTPException as exc:
        return {"status": "error", "rows": [], "detail": str(exc.detail)[:200]}
    candidates = [
        t for t in tools
        if any(v in str(t.get("slug") or "").upper() for v in _LISTING_VERBS)
        and t.get("slug")
    ]
    for tool in candidates[:4]:
        slug = str(tool["slug"])
        try:
            schema = await service.get_tool_schema(slug)
        except HTTPException:
            continue
        if not schema:
            continue
        args = _fillable_args(schema, now)
        if args is None:
            continue
        try:
            result = await service.execute(user_id, db, slug, args)
        except HTTPException as exc:
            detail = exc.detail if not isinstance(exc.detail, dict) else exc.detail.get("hint") or exc.detail.get("error") or ""
            return {"status": "error", "rows": [], "detail": str(detail)[:200]}
        payload = _data_payload(result)
        rows = _rows(section, payload) if payload is not None else []
        if result.get("successful"):
            if rows:
                return {"status": "ok", "rows": rows}
            # A successful empty listing is a real answer; keep it.
            return {"status": "ok", "rows": []}
    return {"status": "error", "rows": [], "detail": "no readable listing tool"}


@router.get("/data")
async def home_data(
    request: Request,
    user: CurrentUser,
    db: AsyncSession = Depends(get_db),
) -> dict:
    service = getattr(request.app.state, "composio", None)
    if service is None or not getattr(service, "available", False):
        return {
            section: {"status": "unavailable", "rows": [], "detail": "composio not configured"}
            for section, _ in SECTIONS
        }
    try:
        connections = await service.list_connections(user.id, db)
    except HTTPException as exc:
        return {
            section: {"status": "error", "rows": [], "detail": str(exc.detail)[:200]}
            for section, _ in SECTIONS
        }
    by_toolkit = {str(c.get("toolkit") or "").lower(): c for c in connections}
    out: dict[str, dict] = {}
    for section, toolkit in SECTIONS:
        status = _status_of(by_toolkit.get(toolkit))
        if status == "not_connected":
            out[section] = {"status": status, "rows": []}
        elif status == "reconnect":
            out[section] = {"status": "reconnect", "rows": []}
        else:
            out[section] = await _snapshot(service, db, user.id, toolkit, query_for(section), section)
    return out


def query_for(section: str) -> str:
    return "list upcoming calendar events" if section == "calendar" else "list recent emails"
