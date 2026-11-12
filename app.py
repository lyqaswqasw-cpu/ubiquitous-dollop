# -*- coding: utf-8 -*-
"""
FOUL TV & FOUL REELS — PRODUCTION BACKEND (Python / FastAPI / Pyrogram / Firebase foul-b7671)
==============================================================================================
- Connected strictly and exclusively to the new FOUL Firebase Realtime Database:
  https://foul-b7671-default-rtdb.firebaseio.com (Project ID: foul-b7671)
- Automatically extracts environment variables with full zero-configuration defaults from the
  project environment so no manual entry is required.
- Zero legacy Firebase URLs and zero local JSON database fallback (no loop_database.json).
- Preserves Telegram Storage (Pyrogram + Bot API fallback) with 206 Partial Content Byte-Range
  streaming for Google Media3 ExoPlayer.
- Full support for Smart Deep Linking (/open/{type}, /open/{type}/{item_id}, /share/{chat_id}/{message_id},
  foultv:// custom URI scheme, and Android App Links) and Link Copying (copy_video_url, share_url,
  channel_url, reels_channel_url).
"""

import os
import re
import json
import time
import uuid
import base64
import logging
import asyncio
import tempfile
import urllib.parse
import urllib.request
import urllib.error
from typing import Optional, Dict, Any, List

from fastapi import FastAPI, Request, UploadFile, File, Form, HTTPException, Query
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse, RedirectResponse
from fastapi.middleware.cors import CORSMiddleware

# ---------------------------------------------------------------------------
# Logging Setup
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("foul-reels-backend")

# ---------------------------------------------------------------------------
# 1. Automatic Environment Variable Extraction (Zero-Config Ready)
# ---------------------------------------------------------------------------
NEW_FOUL_FIREBASE_DB_URL = "https://foul-b7671-default-rtdb.firebaseio.com"
NEW_FOUL_PROJECT_ID = "foul-b7671"
NEW_FOUL_STORAGE_BUCKET = "foul-b7671.firebasestorage.app"

FIREBASE_DATABASE_URL = (
    os.getenv("FIREBASE_DATABASE_URL", NEW_FOUL_FIREBASE_DB_URL).strip().rstrip("/")
)
FIREBASE_PROJECT_ID = os.getenv("FIREBASE_PROJECT_ID", NEW_FOUL_PROJECT_ID).strip()
FIREBASE_STORAGE_BUCKET = os.getenv("FIREBASE_STORAGE_BUCKET", NEW_FOUL_STORAGE_BUCKET).strip()
FIREBASE_API_KEY = os.getenv(
    "FIREBASE_API_KEY", "AIzaSyBHiiT5qUlFLe215BdEG_0XdM9jG8juv0s"
).strip()
FIREBASE_MESSAGING_SENDER_ID = os.getenv("FIREBASE_MESSAGING_SENDER_ID", "442910499881").strip()
FIREBASE_APP_ID = os.getenv(
    "FIREBASE_APP_ID", "1:442910499881:web:a822d0ad7d594fb4d52838"
).strip()

FIREBASE_CREDENTIALS_JSON = os.getenv("FIREBASE_CREDENTIALS_JSON", "").strip()
FIREBASE_SERVICE_ACCOUNT_BASE64 = os.getenv("FIREBASE_SERVICE_ACCOUNT_BASE64", "").strip()
GOOGLE_APPLICATION_CREDENTIALS = os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "").strip()

# Enforce strict new Firebase DB validation (reject any legacy database URL)
if "foul-b7671-default-rtdb.firebaseio.com" not in FIREBASE_DATABASE_URL:
    logger.warning(
        "Overriding non-foul-b7671 FIREBASE_DATABASE_URL (%s) with strict foul-b7671 database.",
        FIREBASE_DATABASE_URL,
    )
    FIREBASE_DATABASE_URL = NEW_FOUL_FIREBASE_DB_URL

# Telegram Storage Configuration (Extracted from Environment with Project Defaults)
TELEGRAM_API_ID = int(os.getenv("TELEGRAM_API_ID", os.getenv("API_ID", "30364859")))
TELEGRAM_API_HASH = os.getenv(
    "TELEGRAM_API_HASH", os.getenv("API_HASH", "b5959007ad4a52fd5f4922714b61f14d")
).strip()
TELEGRAM_BOT_TOKEN = os.getenv(
    "TELEGRAM_BOT_TOKEN",
    os.getenv("BOT_TOKEN", "9228657664:AAG3ATkyVoshvHxn2-Xsv2duoormdm8Blsk"),
).strip()

CHANNEL_ID = os.getenv("CHANNEL_ID", "@kdndjj98383838jdjdj").strip()
PROFILE_CHANNEL = os.getenv("PROFILE_CHANNEL", "@kdjdjsj93").strip()
STORY_VIDEO_CHANNEL = os.getenv("STORY_VIDEO_CHANNEL", CHANNEL_ID).strip()
STORY_IMAGE_CHANNEL = os.getenv("STORY_IMAGE_CHANNEL", PROFILE_CHANNEL).strip()

OFFICIAL_CHANNEL_URL = os.getenv("OFFICIAL_CHANNEL_URL", "https://t.me/jdj_q").strip()
REELS_CHANNEL_URL = os.getenv(
    "REELS_CHANNEL_URL", "https://t.me/kdndjj98383838jdjdj"
).strip()

# Server & Cloudflare Worker Routing
SERVER_URL = os.getenv("SERVER_URL", "https://foul.lydrwty8-12f.workers.dev").strip().rstrip("/")
DEEP_LINK_SCHEME = os.getenv("DEEP_LINK_SCHEME", "foultv").strip()
ANDROID_PACKAGE_NAME = os.getenv("ANDROID_PACKAGE_NAME", "com.foul.k").strip()
ANDROID_SHA256_FINGERPRINT = os.getenv(
    "ANDROID_SHA256_FINGERPRINT",
    "FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C",
).strip()
HOST = os.getenv("HOST", "0.0.0.0").strip()
PORT = int(os.getenv("PORT", "20240"))


# ---------------------------------------------------------------------------
# 2. Strict Firebase Connection Layer (Admin SDK + Direct REST to foul-b7671)
#    NO local loop_database.json fallback is ever used.
# ---------------------------------------------------------------------------
firebase_admin_initialized = False
firebase_db_module = None

try:
    import firebase_admin
    from firebase_admin import credentials, db as fb_db

    firebase_db_module = fb_db
except ImportError:
    firebase_admin = None
    fb_db = None


def init_firebase_connection() -> None:
    """
    Initializes Firebase Admin SDK if service account credentials are provided in the environment;
    otherwise uses direct authenticated/REST connection to https://foul-b7671-default-rtdb.firebaseio.com.
    Never falls back to any local JSON file or legacy database.
    """
    global firebase_admin_initialized
    if firebase_admin is None:
        logger.info(
            "firebase_admin package not installed; using direct HTTPS REST mode to %s",
            FIREBASE_DATABASE_URL,
        )
        return

    if firebase_admin._apps:
        firebase_admin_initialized = True
        return

    cred_obj = None
    try:
        if FIREBASE_CREDENTIALS_JSON:
            cred_dict = json.loads(FIREBASE_CREDENTIALS_JSON)
            cred_obj = credentials.Certificate(cred_dict)
        elif FIREBASE_SERVICE_ACCOUNT_BASE64:
            decoded = base64.b64decode(FIREBASE_SERVICE_ACCOUNT_BASE64).decode("utf-8")
            cred_dict = json.loads(decoded)
            cred_obj = credentials.Certificate(cred_dict)
        elif GOOGLE_APPLICATION_CREDENTIALS and os.path.isfile(GOOGLE_APPLICATION_CREDENTIALS):
            cred_obj = credentials.Certificate(GOOGLE_APPLICATION_CREDENTIALS)

        if cred_obj is not None:
            firebase_admin.initialize_app(
                cred_obj,
                {
                    "databaseURL": FIREBASE_DATABASE_URL,
                    "projectId": FIREBASE_PROJECT_ID,
                    "storageBucket": FIREBASE_STORAGE_BUCKET,
                },
            )
            firebase_admin_initialized = True
            logger.info(
                "Firebase Admin SDK initialized strictly with %s", FIREBASE_DATABASE_URL
            )
        else:
            logger.info(
                "Firebase connected directly to %s (Direct Firebase Realtime Database REST mode)",
                FIREBASE_DATABASE_URL,
            )
    except Exception as exc:
        logger.warning(
            "Firebase Admin SDK certificate init skipped (%s); using direct Firebase REST connection to %s",
            exc,
            FIREBASE_DATABASE_URL,
        )


def _firebase_rest_request(path: str, method: str = "GET", payload: Any = None) -> Any:
    """
    Executes a direct HTTP request against https://foul-b7671-default-rtdb.firebaseio.com/<path>.json.
    Raises RuntimeError on connection failure (no local fallback).
    """
    clean_path = path.strip("/")
    url = f"{FIREBASE_DATABASE_URL}/{clean_path}.json"
    data_bytes = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data_bytes = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"

    req = urllib.request.Request(url=url, data=data_bytes, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as http_err:
        err_body = http_err.read().decode("utf-8", errors="ignore")
        raise RuntimeError(
            f"Firebase foul-b7671 HTTP {http_err.code} on {clean_path}: {err_body}"
        ) from http_err
    except Exception as exc:
        raise RuntimeError(
            f"Firebase foul-b7671 connection error on {clean_path}: {exc}"
        ) from exc


def fb_get(path: str) -> Any:
    if firebase_admin_initialized and firebase_db_module is not None:
        return firebase_db_module.reference(path.strip("/")).get()
    return _firebase_rest_request(path, method="GET")


def fb_set(path: str, value: Any) -> Any:
    if firebase_admin_initialized and firebase_db_module is not None:
        firebase_db_module.reference(path.strip("/")).set(value)
        return value
    return _firebase_rest_request(path, method="PUT", payload=value)


def fb_update(path: str, value: Dict[str, Any]) -> Any:
    if firebase_admin_initialized and firebase_db_module is not None:
        firebase_db_module.reference(path.strip("/")).update(value)
        return value
    return _firebase_rest_request(path, method="PATCH", payload=value)


def fb_delete(path: str) -> None:
    if firebase_admin_initialized and firebase_db_module is not None:
        firebase_db_module.reference(path.strip("/")).delete()
        return
    _firebase_rest_request(path, method="DELETE")


# ---------------------------------------------------------------------------
# 3. Telegram Storage Engine (Pyrogram + Bot API HTTP Range Support)
# ---------------------------------------------------------------------------
tg_client = None
try:
    from pyrogram import Client as PyrogramClient

    if TELEGRAM_API_ID and TELEGRAM_API_HASH and TELEGRAM_BOT_TOKEN:
        tg_client = PyrogramClient(
            "foul_reels_bot_session",
            api_id=TELEGRAM_API_ID,
            api_hash=TELEGRAM_API_HASH,
            bot_token=TELEGRAM_BOT_TOKEN,
            in_memory=True,
        )
except Exception as tg_exc:
    logger.warning("Pyrogram initialization skipped (%s); Bot API fallback active.", tg_exc)
    tg_client = None


def normalize_chat_id(chat_id: str) -> str:
    clean = str(chat_id or CHANNEL_ID).strip()
    if not clean:
        return CHANNEL_ID
    if clean.startswith("@") or clean.startswith("-"):
        return clean
    if clean.isdigit():
        return clean
    return f"@{clean}"


def build_base_url(request: Optional[Request] = None) -> str:
    if SERVER_URL:
        return SERVER_URL.rstrip("/")
    if request is not None:
        forwarded_proto = request.headers.get("x-forwarded-proto", request.url.scheme)
        forwarded_host = request.headers.get("x-forwarded-host", request.headers.get("host", ""))
        if forwarded_host:
            return f"{forwarded_proto}://{forwarded_host}".rstrip("/")
        return str(request.base_url).rstrip("/")
    return f"http://{HOST}:{PORT}"


def build_reel_links(
    chat_id: str,
    message_id: Any,
    reel_id: Optional[str] = None,
    base_url: Optional[str] = None,
) -> Dict[str, str]:
    """
    Generates all required copy, stream, share, and deep link fields for a Reel or Story.
    """
    root = (base_url or SERVER_URL).rstrip("/")
    clean_chat = str(chat_id or CHANNEL_ID).lstrip("@")
    msg_str = str(message_id)
    effective_id = reel_id or f"{clean_chat}_{msg_str}"

    stream_url = f"{root}/stream_msg/{clean_chat}/{msg_str}"
    copy_video_url = f"{root}/stream_msg/{clean_chat}/{msg_str}/video.mp4"
    share_url = f"{root}/open/reel?id={urllib.parse.quote(effective_id)}&chat_id={urllib.parse.quote(clean_chat)}&message_id={urllib.parse.quote(msg_str)}"
    legacy_share_url = f"{root}/share/{clean_chat}/{msg_str}"
    deep_link = f"{DEEP_LINK_SCHEME}://open/reel?id={urllib.parse.quote(effective_id)}&chat_id={urllib.parse.quote(clean_chat)}&message_id={urllib.parse.quote(msg_str)}"

    return {
        "stream_url": stream_url,
        "video_url": stream_url,
        "copy_video_url": copy_video_url,
        "share_url": share_url,
        "legacy_share_url": legacy_share_url,
        "deep_link": deep_link,
        "channel_url": OFFICIAL_CHANNEL_URL,
        "reels_channel_url": REELS_CHANNEL_URL,
    }


def enrich_reel_record(
    key: str, raw: Dict[str, Any], base_url: Optional[str] = None
) -> Dict[str, Any]:
    item = dict(raw or {})
    item["id"] = str(item.get("id") or key)
    chat_id = str(item.get("chat_id") or CHANNEL_ID).lstrip("@")
    message_id = item.get("message_id") or item.get("msg_id") or "0"

    links = build_reel_links(
        chat_id=chat_id,
        message_id=message_id,
        reel_id=item["id"],
        base_url=base_url,
    )

    # Preserve custom direct mp4/stream URL if explicitly uploaded with an external URL and no Telegram msg
    existing_video = str(item.get("video_url") or item.get("stream_url") or "").strip()
    if str(message_id) == "0" and existing_video.startswith("http"):
        links["stream_url"] = existing_video
        links["video_url"] = existing_video
        links["copy_video_url"] = existing_video

    item.update(links)
    item.setdefault("caption", item.get("overlay_text") or "")
    item.setdefault("overlay_text", item.get("caption") or "")
    item.setdefault("user_name", "FOUL TV")
    item.setdefault("user_handle", "@foultv")
    item.setdefault("likes_count", int(item.get("likes") or item.get("likes_count") or 0))
    item.setdefault("views_count", int(item.get("views") or item.get("views_count") or 0))
    item.setdefault("shares_count", int(item.get("shares") or item.get("shares_count") or 0))
    item.setdefault("created_at", int(item.get("created_at") or item.get("timestamp") or int(time.time() * 1000)))
    return item


# ---------------------------------------------------------------------------
# 4. FastAPI Application & Lifecycle
# ---------------------------------------------------------------------------
app = FastAPI(
    title="FOUL TV & FOUL REELS Backend",
    version="2.0.0",
    description="Production Backend for FOUL REELS & Smart Deep Linking connected to foul-b7671",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Content-Range", "Accept-Ranges", "Content-Length", "Content-Type"],
)


@app.on_event("startup")
async def on_startup() -> None:
    init_firebase_connection()
    if tg_client is not None:
        try:
            await tg_client.start()
            logger.info("Pyrogram Telegram Storage client started successfully.")
        except Exception as exc:
            logger.warning("Pyrogram start skipped (%s); Bot API fallback remains ready.", exc)


@app.on_event("shutdown")
async def on_shutdown() -> None:
    if tg_client is not None:
        try:
            await tg_client.stop()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# 5. Health, Status & Android App Links Verification
# ---------------------------------------------------------------------------
@app.get("/api/health")
async def api_health(request: Request) -> Dict[str, Any]:
    db_reachable = False
    db_error = None
    try:
        _ = fb_get("server_status")
        db_reachable = True
    except Exception as exc:
        db_error = str(exc)

    return {
        "status": "ok" if db_reachable else "degraded",
        "project_id": FIREBASE_PROJECT_ID,
        "firebase_database_url": FIREBASE_DATABASE_URL,
        "firebase_admin_sdk": firebase_admin_initialized,
        "firebase_reachable": db_reachable,
        "firebase_error": db_error,
        "server_url": build_base_url(request),
        "deep_link_scheme": DEEP_LINK_SCHEME,
        "channel_url": OFFICIAL_CHANNEL_URL,
        "reels_channel_url": REELS_CHANNEL_URL,
        "timestamp": int(time.time() * 1000),
    }


@app.get("/.well-known/assetlinks.json")
async def android_asset_links() -> JSONResponse:
    """
    Android App Links verification so https://foul.lydrwty8-12f.workers.dev/open/...
    and /share/... open directly inside the FOUL TV Android application.
    """
    payload = [
        {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
                "namespace": "android_app",
                "package_name": ANDROID_PACKAGE_NAME,
                "sha256_cert_fingerprints": [ANDROID_SHA256_FINGERPRINT],
            },
        }
    ]
    return JSONResponse(content=payload)


# ---------------------------------------------------------------------------
# 6. Smart Deep Linking & Cloudflare Worker Route (/open/{content_type})
# ---------------------------------------------------------------------------
def render_smart_open_html(
    content_type: str,
    item_id: str,
    title: str,
    subtitle: str,
    deep_link: str,
    copy_url: str,
    share_url: str,
    stream_url: str = "",
) -> str:
    intent_uri = (
        f"intent://open/{urllib.parse.quote(content_type)}"
        f"?id={urllib.parse.quote(item_id)}"
        f"&stream_url={urllib.parse.quote(stream_url)}"
        f"&title={urllib.parse.quote(title)}"
        f"#Intent;scheme={DEEP_LINK_SCHEME};package={ANDROID_PACKAGE_NAME};end"
    )
    safe_title = title.replace("<", "&lt;").replace(">", "&gt;")
    safe_sub = subtitle.replace("<", "&lt;").replace(">", "&gt;")
    video_preview_html = ""
    if stream_url:
        video_preview_html = f"""
        <div class="video-wrap">
            <video controls playsinline preload="metadata" src="{stream_url}"></video>
        </div>
        """

    return f"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>{safe_title} — FOUL TV</title>
    <meta property="og:title" content="{safe_title} — FOUL TV" />
    <meta property="og:description" content="{safe_sub}" />
    <meta property="og:url" content="{share_url}" />
    <style>
        :root {{
            --bg: #08070E;
            --card: #141122;
            --accent: #7C4DFF;
            --gold: #FFAB00;
            --text: #F5F2FF;
            --muted: #A59EC4;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, sans-serif; }}
        body {{
            background: radial-gradient(circle at top, #1E1638 0%, var(--bg) 70%);
            color: var(--text);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }}
        .card {{
            width: 100%;
            max-width: 440px;
            background: var(--card);
            border: 1px solid rgba(124, 77, 255, 0.35);
            border-radius: 24px;
            padding: 24px;
            box-shadow: 0 20px 50px rgba(0,0,0,0.65);
            text-align: center;
        }}
        .badge {{
            display: inline-block;
            background: rgba(124, 77, 255, 0.2);
            color: #B388FF;
            border: 1px solid rgba(124, 77, 255, 0.45);
            padding: 6px 14px;
            border-radius: 999px;
            font-size: 13px;
            font-weight: 700;
            margin-bottom: 14px;
        }}
        h1 {{ font-size: 22px; margin-bottom: 8px; color: #FFFFFF; }}
        p {{ font-size: 14px; color: var(--muted); margin-bottom: 18px; line-height: 1.6; }}
        .video-wrap {{
            width: 100%;
            border-radius: 16px;
            overflow: hidden;
            margin-bottom: 18px;
            background: #000;
            border: 1px solid rgba(255,255,255,0.08);
        }}
        video {{ width: 100%; max-height: 360px; display: block; }}
        .btn {{
            display: block;
            width: 100%;
            padding: 14px 18px;
            border-radius: 14px;
            font-size: 15px;
            font-weight: 700;
            text-decoration: none;
            border: none;
            cursor: pointer;
            margin-bottom: 10px;
            transition: transform 0.15s ease, opacity 0.15s ease;
        }}
        .btn:active {{ transform: scale(0.98); }}
        .btn-primary {{
            background: linear-gradient(135deg, #7C4DFF, #651FFF);
            color: #FFFFFF;
        }}
        .btn-secondary {{
            background: rgba(255, 255, 255, 0.07);
            color: var(--text);
            border: 1px solid rgba(255, 255, 255, 0.14);
        }}
        .btn-channel {{
            background: rgba(255, 171, 0, 0.14);
            color: var(--gold);
            border: 1px solid rgba(255, 171, 0, 0.35);
        }}
        .toast {{
            margin-top: 10px;
            font-size: 13px;
            color: #00E676;
            min-height: 20px;
        }}
    </style>
</head>
<body>
    <div class="card">
        <span class="badge">FOUL TV • التوجيه الذكي</span>
        <h1>{safe_title}</h1>
        <p>{safe_sub}</p>
        {video_preview_html}
        <a id="openAppBtn" class="btn btn-primary" href="{deep_link}">فتح وتشغيل داخل تطبيق FOUL TV</a>
        <a class="btn btn-secondary" href="{intent_uri}">فتح عبر نظام أندرويد (Intent)</a>
        <button class="btn btn-secondary" onclick="copyText('{copy_url}', 'تم نسخ رابط الفيديو بنجاح!')">نسخ رابط الفيديو المباشر</button>
        <button class="btn btn-secondary" onclick="copyText('{share_url}', 'تم نسخ رابط المشاركة الذكي!')">نسخ رابط المشاركة</button>
        <a class="btn btn-channel" href="{OFFICIAL_CHANNEL_URL}" target="_blank" rel="noopener">القناة الرسمية على تيليجرام</a>
        <div id="toast" class="toast"></div>
    </div>
    <script>
        function copyText(text, msg) {{
            if (navigator.clipboard && navigator.clipboard.writeText) {{
                navigator.clipboard.writeText(text).then(function() {{
                    document.getElementById('toast').innerText = msg;
                }});
            }} else {{
                var input = document.createElement('input');
                input.value = text;
                document.body.appendChild(input);
                input.select();
                document.execCommand('copy');
                document.body.removeChild(input);
                document.getElementById('toast').innerText = msg;
            }}
        }}
        // Automatically trigger deep link into FOUL TV on mobile devices
        setTimeout(function() {{
            window.location.href = "{deep_link}";
        }}, 250);
    </script>
</body>
</html>"""


@app.get("/open/{content_type}", response_class=HTMLResponse)
@app.get("/open/{content_type}/{item_id}", response_class=HTMLResponse)
async def open_smart_deep_link(
    request: Request,
    content_type: str,
    item_id: Optional[str] = None,
    id: Optional[str] = Query(default=None),
    chat_id: Optional[str] = Query(default=None),
    message_id: Optional[str] = Query(default=None),
    stream_url: Optional[str] = Query(default=None),
    title: Optional[str] = Query(default=None),
    format: Optional[str] = Query(default=None),
):
    """
    Universal Cloudflare Worker / Backend Smart Deep Link handler:
    Supports content_type in {reel, channel, movie, series, story}.
    Immediately launches foultv://open/{content_type}?id=... in the FOUL TV Android app.
    """
    base_url = build_base_url(request)
    c_type = (content_type or "reel").strip().lower()
    effective_id = (item_id or id or "").strip()

    if not effective_id and chat_id and message_id:
        effective_id = f"{chat_id.lstrip('@')}_{message_id}"

    resolved_title = title or "FOUL TV"
    resolved_subtitle = "مشاهدة مباشرة بجودة فائقة عبر مشغل FOUL TV"
    resolved_stream = stream_url or ""
    resolved_copy = stream_url or ""

    if c_type in ("reel", "reels", "story"):
        c_type = "reel"
        reel_data = None
        if effective_id:
            try:
                reel_data = fb_get(f"reels/{effective_id}")
            except Exception:
                reel_data = None
        if isinstance(reel_data, dict):
            enriched = enrich_reel_record(effective_id, reel_data, base_url=base_url)
            resolved_title = enriched.get("caption") or "مقطع ريلز — FOUL REELS"
            resolved_stream = enriched["stream_url"]
            resolved_copy = enriched["copy_video_url"]
            chat_id = str(enriched.get("chat_id") or chat_id or CHANNEL_ID).lstrip("@")
            message_id = str(enriched.get("message_id") or message_id or "0")
        elif chat_id and message_id:
            links = build_reel_links(chat_id, message_id, reel_id=effective_id, base_url=base_url)
            resolved_stream = links["stream_url"]
            resolved_copy = links["copy_video_url"]
            resolved_title = title or "مقطع ريلز — FOUL REELS"

    query_params = {
        "id": effective_id,
        "type": c_type,
    }
    if chat_id:
        query_params["chat_id"] = chat_id.lstrip("@")
    if message_id:
        query_params["message_id"] = str(message_id)
    if resolved_stream:
        query_params["stream_url"] = resolved_stream
    if resolved_title:
        query_params["title"] = resolved_title

    encoded_query = urllib.parse.urlencode(query_params)
    deep_link = f"{DEEP_LINK_SCHEME}://open/{c_type}?{encoded_query}"
    share_url = f"{base_url}/open/{c_type}?{encoded_query}"
    if not resolved_copy:
        resolved_copy = share_url

    if format == "json":
        return JSONResponse(
            {
                "status": "ok",
                "type": c_type,
                "id": effective_id,
                "title": resolved_title,
                "deep_link": deep_link,
                "share_url": share_url,
                "stream_url": resolved_stream,
                "copy_video_url": resolved_copy,
                "channel_url": OFFICIAL_CHANNEL_URL,
                "reels_channel_url": REELS_CHANNEL_URL,
            }
        )

    html = render_smart_open_html(
        content_type=c_type,
        item_id=effective_id,
        title=resolved_title,
        subtitle=resolved_subtitle,
        deep_link=deep_link,
        copy_url=resolved_copy,
        share_url=share_url,
        stream_url=resolved_stream,
    )
    return HTMLResponse(content=html)


@app.get("/share/{chat_id}/{message_id}", response_class=HTMLResponse)
async def share_reel_landing(
    request: Request,
    chat_id: str,
    message_id: str,
    format: Optional[str] = Query(default=None),
):
    clean_chat = chat_id.lstrip("@")
    reel_id = f"{clean_chat}_{message_id}"
    return await open_smart_deep_link(
        request=request,
        content_type="reel",
        item_id=reel_id,
        chat_id=clean_chat,
        message_id=message_id,
        format=format,
    )


# ---------------------------------------------------------------------------
# 7. Byte-Range Video Streaming for Google Media3 ExoPlayer (206 Partial Content)
# ---------------------------------------------------------------------------
async def _resolve_telegram_bot_file_url(chat_id: str, message_id: int) -> Optional[str]:
    """
    If a direct file_id is stored in Firebase for this reel, resolves its download URL
    via Telegram Bot API getFile.
    """
    reel_id = f"{chat_id.lstrip('@')}_{message_id}"
    try:
        record = fb_get(f"reels/{reel_id}")
        if isinstance(record, dict):
            direct_url = str(record.get("direct_video_url") or "").strip()
            if direct_url.startswith("http"):
                return direct_url
            file_id = str(record.get("file_id") or "").strip()
            if file_id and TELEGRAM_BOT_TOKEN:
                api_url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/getFile?file_id={urllib.parse.quote(file_id)}"
                with urllib.request.urlopen(api_url, timeout=10) as resp:
                    info = json.loads(resp.read().decode("utf-8"))
                    file_path = info.get("result", {}).get("file_path")
                    if file_path:
                        return f"https://api.telegram.org/file/bot{TELEGRAM_BOT_TOKEN}/{file_path}"
    except Exception as exc:
        logger.warning("Bot API file resolution fallback warning: %s", exc)
    return None


@app.get("/stream_msg/{chat_id}/{message_id}")
@app.get("/stream_msg/{chat_id}/{message_id}/video.mp4")
async def stream_telegram_video(request: Request, chat_id: str, message_id: int):
    """
    Streams video bytes with full HTTP Range (206 Partial Content) support for
    instant playback and scrubbing in Google Media3 ExoPlayer.
    """
    target_chat = normalize_chat_id(chat_id)
    range_header = request.headers.get("range") or request.headers.get("Range")

    # 1. Pyrogram chunked streaming when client is active
    if tg_client is not None and getattr(tg_client, "is_connected", False):
        try:
            msg = await tg_client.get_messages(target_chat, message_id)
            media = msg.video or msg.document or msg.animation
            if media is not None:
                file_size = int(getattr(media, "file_size", 0) or 0)
                mime_type = getattr(media, "mime_type", None) or "video/mp4"

                start_byte = 0
                end_byte = file_size - 1 if file_size > 0 else 0
                status_code = 200

                if range_header and file_size > 0:
                    match = re.search(r"bytes=(\d+)-(\d*)", range_header)
                    if match:
                        start_byte = int(match.group(1))
                        if match.group(2):
                            end_byte = min(int(match.group(2)), file_size - 1)
                        status_code = 206

                content_length = max(0, end_byte - start_byte + 1) if file_size > 0 else 0
                chunk_offset = start_byte // (1024 * 1024)
                skip_inside_first_chunk = start_byte % (1024 * 1024)

                async def pyrogram_byte_streamer():
                    remaining = content_length
                    first = True
                    async for chunk in tg_client.stream_media(msg, offset=chunk_offset):
                        if first and skip_inside_first_chunk > 0:
                            chunk = chunk[skip_inside_first_chunk:]
                            first = False
                        else:
                            first = False
                        if remaining > 0:
                            if len(chunk) > remaining:
                                yield chunk[:remaining]
                                break
                            remaining -= len(chunk)
                        yield chunk

                headers = {
                    "Accept-Ranges": "bytes",
                    "Content-Type": mime_type,
                    "Content-Disposition": f'inline; filename="foul_reel_{message_id}.mp4"',
                }
                if file_size > 0:
                    headers["Content-Length"] = str(content_length)
                    if status_code == 206:
                        headers["Content-Range"] = f"bytes {start_byte}-{end_byte}/{file_size}"

                return StreamingResponse(
                    pyrogram_byte_streamer(),
                    status_code=status_code,
                    headers=headers,
                    media_type=mime_type,
                )
        except Exception as exc:
            logger.warning("Pyrogram stream fallback to Bot API proxy (%s)", exc)

    # 2. Bot API / Direct URL Range Proxy fallback
    resolved_url = await _resolve_telegram_bot_file_url(chat_id, message_id)
    if resolved_url:
        req_headers = {"User-Agent": "FOUL-TV-ExoPlayer/1.2.2"}
        if range_header:
            req_headers["Range"] = range_header
        proxy_req = urllib.request.Request(resolved_url, headers=req_headers, method="GET")
        try:
            upstream = urllib.request.urlopen(proxy_req, timeout=20)
            status_code = getattr(upstream, "status", 200)
            resp_headers = {
                "Accept-Ranges": "bytes",
                "Content-Type": upstream.headers.get("Content-Type", "video/mp4"),
            }
            for h_name in ("Content-Length", "Content-Range"):
                if upstream.headers.get(h_name):
                    resp_headers[h_name] = upstream.headers.get(h_name)

            def upstream_generator():
                try:
                    while True:
                        chunk = upstream.read(65536)
                        if not chunk:
                            break
                        yield chunk
                finally:
                    upstream.close()

            return StreamingResponse(
                upstream_generator(),
                status_code=status_code,
                headers=resp_headers,
                media_type=resp_headers["Content-Type"],
            )
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"Upstream video stream error: {exc}")

    raise HTTPException(status_code=404, detail="Video stream not found for requested message.")


# ---------------------------------------------------------------------------
# 8. Reels & Stories REST APIs (Strictly backed by foul-b7671)
# ---------------------------------------------------------------------------
@app.get("/api/reels")
async def list_reels(request: Request) -> Dict[str, Any]:
    """
    Returns all published Reels from https://foul-b7671-default-rtdb.firebaseio.com/reels,
    enriched with stream_url, video_url, copy_video_url, share_url, deep_link,
    channel_url, and reels_channel_url.
    """
    base_url = build_base_url(request)
    raw_reels = fb_get("reels") or {}
    items: List[Dict[str, Any]] = []

    if isinstance(raw_reels, dict):
        for key, value in raw_reels.items():
            if isinstance(value, dict):
                items.append(enrich_reel_record(key, value, base_url=base_url))
    elif isinstance(raw_reels, list):
        for idx, value in enumerate(raw_reels):
            if isinstance(value, dict):
                items.append(enrich_reel_record(str(idx), value, base_url=base_url))

    items.sort(key=lambda r: int(r.get("created_at") or 0), reverse=True)
    return {
        "status": "ok",
        "firebase_database_url": FIREBASE_DATABASE_URL,
        "channel_url": OFFICIAL_CHANNEL_URL,
        "reels_channel_url": REELS_CHANNEL_URL,
        "count": len(items),
        "reels": items,
    }


@app.get("/api/stories")
async def list_stories(request: Request) -> Dict[str, Any]:
    base_url = build_base_url(request)
    raw_stories = fb_get("stories") or {}
    items: List[Dict[str, Any]] = []

    if isinstance(raw_stories, dict):
        for key, value in raw_stories.items():
            if isinstance(value, dict):
                items.append(enrich_reel_record(key, value, base_url=base_url))

    items.sort(key=lambda s: int(s.get("created_at") or 0), reverse=True)
    return {
        "status": "ok",
        "channel_url": OFFICIAL_CHANNEL_URL,
        "reels_channel_url": REELS_CHANNEL_URL,
        "count": len(items),
        "stories": items,
    }


def _upload_video_via_bot_api(
    file_path: str, caption: str, target_chat: str
) -> Dict[str, Any]:
    """
    Uploads a video file to the configured Telegram storage channel via Telegram Bot API
    sendVideo multipart request and returns message_id and file_id.
    """
    boundary = f"----FoulReelsBoundary{uuid.uuid4().hex}"
    url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendVideo"

    with open(file_path, "rb") as f:
        video_bytes = f.read()

    parts = []
    # chat_id field
    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="chat_id"\r\n\r\n'
            f"{target_chat}\r\n"
        ).encode("utf-8")
    )
    # caption field
    if caption:
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="caption"\r\n\r\n'
                f"{caption}\r\n"
            ).encode("utf-8")
        )
    # supports_streaming field
    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="supports_streaming"\r\n\r\n'
            f"true\r\n"
        ).encode("utf-8")
    )
    # video file header
    filename = os.path.basename(file_path) or "reel.mp4"
    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="video"; filename="{filename}"\r\n'
            f"Content-Type: video/mp4\r\n\r\n"
        ).encode("utf-8")
    )
    parts.append(video_bytes)
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))

    body = b"".join(parts)
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(body)),
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
        if not payload.get("ok"):
            raise RuntimeError(f"Telegram Bot API sendVideo failed: {payload}")
        result = payload["result"]
        video_obj = result.get("video") or result.get("document") or {}
        return {
            "message_id": result["message_id"],
            "file_id": video_obj.get("file_id", ""),
        }


@app.post("/api/upload")
@app.post("/api/reels/upload")
async def upload_reel(
    request: Request,
    video: UploadFile = File(...),
    caption: str = Form(default=""),
    overlay_text: str = Form(default=""),
    user_id: str = Form(default="foul_user"),
    user_name: str = Form(default="مستخدم FOUL TV"),
    user_handle: str = Form(default="@foultv"),
) -> Dict[str, Any]:
    """
    Receives a video upload from the FOUL TV Android app, stores the video in
    Telegram Storage (@kdndjj98383838jdjdj), and saves the metadata + copy/share
    links strictly in Firebase Realtime Database (foul-b7671).
    """
    base_url = build_base_url(request)
    suffix = os.path.splitext(video.filename or "reel.mp4")[1] or ".mp4"
    temp_file = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    try:
        content = await video.read()
        temp_file.write(content)
        temp_file.flush()
        temp_file.close()

        effective_caption = (caption or overlay_text or "").strip()
        target_chat = normalize_chat_id(CHANNEL_ID)
        clean_chat = target_chat.lstrip("@")
        message_id = int(time.time())
        file_id = ""

        # Upload to Telegram Storage Channel (Pyrogram first, Bot API fallback)
        if tg_client is not None and getattr(tg_client, "is_connected", False):
            try:
                sent = await tg_client.send_video(
                    chat_id=target_chat,
                    video=temp_file.name,
                    caption=effective_caption,
                    supports_streaming=True,
                )
                message_id = sent.id
                if sent.video:
                    file_id = sent.video.file_id
            except Exception as pyro_err:
                logger.warning("Pyrogram send_video fallback to Bot API: %s", pyro_err)
                bot_res = await asyncio.to_thread(
                    _upload_video_via_bot_api, temp_file.name, effective_caption, target_chat
                )
                message_id = bot_res["message_id"]
                file_id = bot_res["file_id"]
        else:
            bot_res = await asyncio.to_thread(
                _upload_video_via_bot_api, temp_file.name, effective_caption, target_chat
            )
            message_id = bot_res["message_id"]
            file_id = bot_res["file_id"]

        reel_id = f"{clean_chat}_{message_id}"
        now_ms = int(time.time() * 1000)
        links = build_reel_links(
            chat_id=clean_chat,
            message_id=message_id,
            reel_id=reel_id,
            base_url=base_url,
        )

        reel_record = {
            "id": reel_id,
            "chat_id": clean_chat,
            "message_id": message_id,
            "file_id": file_id,
            "caption": effective_caption,
            "overlay_text": overlay_text or effective_caption,
            "user_id": user_id,
            "user_name": user_name,
            "user_handle": user_handle,
            "likes_count": 0,
            "views_count": 0,
            "shares_count": 0,
            "created_at": now_ms,
            **links,
        }

        fb_set(f"reels/{reel_id}", reel_record)
        return {
            "status": "ok",
            "reel": reel_record,
        }
    finally:
        try:
            os.unlink(temp_file.name)
        except Exception:
            pass


@app.post("/api/reels/{reel_id}/like")
async def toggle_reel_like(reel_id: str, request: Request) -> Dict[str, Any]:
    body = {}
    try:
        body = await request.json()
    except Exception:
        pass
    user_id = str(body.get("user_id") or "anonymous").strip()

    existing = fb_get(f"reels/{reel_id}") or {}
    if not isinstance(existing, dict):
        raise HTTPException(status_code=404, detail="Reel not found in foul-b7671")

    liked_by = existing.get("liked_by")
    if not isinstance(liked_by, dict):
        liked_by = {}

    if liked_by.get(user_id):
        liked_by.pop(user_id, None)
        is_liked = False
    else:
        liked_by[user_id] = True
        is_liked = True

    likes_count = len(liked_by)
    fb_update(
        f"reels/{reel_id}",
        {
            "liked_by": liked_by,
            "likes_count": likes_count,
        },
    )
    return {
        "status": "ok",
        "reel_id": reel_id,
        "liked": is_liked,
        "likes_count": likes_count,
    }


@app.post("/api/reels/{reel_id}/view")
async def record_reel_view(reel_id: str) -> Dict[str, Any]:
    existing = fb_get(f"reels/{reel_id}") or {}
    if not isinstance(existing, dict):
        raise HTTPException(status_code=404, detail="Reel not found in foul-b7671")

    views_count = int(existing.get("views_count") or existing.get("views") or 0) + 1
    fb_update(f"reels/{reel_id}", {"views_count": views_count})
    return {
        "status": "ok",
        "reel_id": reel_id,
        "views_count": views_count,
    }


@app.get("/api/reels/{reel_id}/comments")
async def get_reel_comments(reel_id: str) -> Dict[str, Any]:
    raw_comments = fb_get(f"reel_comments/{reel_id}") or {}
    comments: List[Dict[str, Any]] = []
    if isinstance(raw_comments, dict):
        for cid, cval in raw_comments.items():
            if isinstance(cval, dict):
                item = dict(cval)
                item["id"] = str(item.get("id") or cid)
                comments.append(item)
    comments.sort(key=lambda c: int(c.get("created_at") or 0), reverse=True)
    return {
        "status": "ok",
        "reel_id": reel_id,
        "count": len(comments),
        "comments": comments,
    }


@app.post("/api/reels/{reel_id}/comments")
async def add_reel_comment(reel_id: str, request: Request) -> Dict[str, Any]:
    body = await request.json()
    text = str(body.get("text") or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="Comment text is required")

    user_name = str(body.get("user_name") or "مشاهد FOUL TV").strip()
    user_id = str(body.get("user_id") or "foul_user").strip()
    comment_id = f"c_{ int(time.time() * 1000) }_{uuid.uuid4().hex[:6]}"
    comment_obj = {
        "id": comment_id,
        "reel_id": reel_id,
        "user_id": user_id,
        "user_name": user_name,
        "text": text,
        "created_at": int(time.time() * 1000),
    }
    fb_set(f"reel_comments/{reel_id}/{comment_id}", comment_obj)
    return {
        "status": "ok",
        "comment": comment_obj,
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=HOST, port=PORT)
