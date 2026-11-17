"""Self-hosted connector logos.

Composio's own logo URLs serve SVG, which the app's image loader cannot
decode, so the catalog points at these bundled official PNG icons instead.
"""

from __future__ import annotations

import re
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

router = APIRouter()

LOGO_DIR = Path(__file__).resolve().parents[2] / "assets" / "logos"
_NAME = re.compile(r"^[a-z0-9_]+\.png$")


@router.get("/logos/{filename}", include_in_schema=False)
async def connector_logo(filename: str) -> FileResponse:
    if not _NAME.match(filename):
        raise HTTPException(status_code=404, detail="logo not found")
    path = LOGO_DIR / filename
    if not path.is_file():
        raise HTTPException(status_code=404, detail="logo not found")
    return FileResponse(
        path,
        media_type="image/png",
        headers={"Cache-Control": "public, max-age=86400"},
    )
