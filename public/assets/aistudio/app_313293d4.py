import os
import re
import time
import json
import asyncio
import logging
import mimetypes
import tempfile
import urllib.request
import urllib.error

from pathlib import Path
from datetime import datetime, timezone
from contextlib import asynccontextmanager
from typing import Optional, Tuple

from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse, Response
from fastapi.middleware.cors import CORSMiddleware
from starlette.datastructures import UploadFile

from hydrogram import Client


# ============================================================
# FIREBASE
# ============================================================

try:
    import firebase_admin
    from firebase_admin import credentials, db, auth
except Exception:
    firebase_admin = None
    credentials = None
    db = None
    auth = None


# ============================================================
# ENVIRONMENT HELPERS
# ============================================================

def env_str(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def env_int(name: str, default: int) -> int:
    value = env_str(name, str(default))
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


# ============================================================
# SERVER (مضبوط على دومين Cloudflare الجديد والبورت 20240)
# ============================================================

SERVER_URL = env_str(
    "SERVER_URL",
    "https://foul.lydrwty8-12f.workers.dev"
).rstrip("/")

HOST = env_str("HOST", "0.0.0.0")

PORT = env_int(
    "SERVER_PORT",
    env_int("PORT", 20240)
)


# ============================================================
# TELEGRAM STORAGE
# ============================================================

CHANNEL_ID = "@" + env_str(
    "CHANNEL_ID",
    "@kdndjj98383838jdjdj"
).lstrip("@")

PROFILE_CHANNEL = "@" + env_str(
    "PROFILE_CHANNEL",
    "@kdjdjsj93"
).lstrip("@")

STORY_VIDEO_CHANNEL = env_str("STORY_VIDEO_CHANNEL", CHANNEL_ID)
STORY_IMAGE_CHANNEL = env_str("STORY_IMAGE_CHANNEL", PROFILE_CHANNEL)


# ============================================================
# TELEGRAM BOT AUTH (بدون SESSION_STRING)
# ============================================================

API_ID = env_int("API_ID", 30364859)

API_HASH = env_str(
    "API_HASH",
    "b5959007ad4a52fd5f4922714b61f14d"
)

BOT_TOKEN = env_str(
    "BOT_TOKEN",
    "9228657664:AAG3ATkyVoshvHxn2-Xsv2duoormdm8Blsk"
)


# ============================================================
# FIREBASE CONFIG (قاعدة البيانات الجديدة: foul-b7671)
# ============================================================

FIREBASE_DATABASE_URL = env_str(
    "FIREBASE_DATABASE_URL",
    "https://foul-b7671-default-rtdb.firebaseio.com"
).rstrip("/")

FIREBASE_API_KEY = env_str(
    "FIREBASE_API_KEY",
    "AIzaSyBHiiT5qUlFLe215BdEG_0XdM9jG8juv0s"
)

FIREBASE_AUTH_DOMAIN = env_str(
    "FIREBASE_AUTH_DOMAIN",
    "foul-b7671.firebaseapp.com"
)

FIREBASE_PROJECT_ID = env_str(
    "FIREBASE_PROJECT_ID",
    "foul-b7671"
)

FIREBASE_STORAGE_BUCKET = env_str(
    "FIREBASE_STORAGE_BUCKET",
    "foul-b7671.firebasestorage.app"
)

FIREBASE_MESSAGING_SENDER_ID = env_str(
    "FIREBASE_MESSAGING_SENDER_ID",
    "442910499881"
)

FIREBASE_APP_ID = env_str(
    "FIREBASE_APP_ID",
    "1:442910499881:web:a822d0ad7d594fb4d52838"
)

FIREBASE_MEASUREMENT_ID = env_str(
    "FIREBASE_MEASUREMENT_ID",
    "G-0BMWYP2WTS"
)

FIREBASE_CREDENTIALS_JSON = env_str("FIREBASE_CREDENTIALS_JSON", "")
FIREBASE_CREDENTIALS_PATH = env_str("FIREBASE_CREDENTIALS", "")
GOOGLE_APPLICATION_CREDENTIALS = env_str("GOOGLE_APPLICATION_CREDENTIALS", "")
FIREBASE_SERVICE_ACCOUNT_FILE = env_str("FIREBASE_SERVICE_ACCOUNT_FILE", "")
FIREBASE_CLIENT_EMAIL = env_str("FIREBASE_CLIENT_EMAIL", "")
FIREBASE_PRIVATE_KEY = os.getenv("FIREBASE_PRIVATE_KEY", "").replace("\\n", "\n")
FIREBASE_PRIVATE_KEY_ID = env_str("FIREBASE_PRIVATE_KEY_ID", "")
FIREBASE_CLIENT_ID = env_str("FIREBASE_CLIENT_ID", "")
FIREBASE_CLIENT_X509_CERT_URL = env_str("FIREBASE_CLIENT_X509_CERT_URL", "")
FIREBASE_REST_TIMEOUT = max(5, env_int("FIREBASE_REST_TIMEOUT", 10))

DEFAULT_BASE_DIR = (
    Path("/home/container/loop_data")
    if Path("/home/container").exists()
    else Path(__file__).resolve().parent / "loop_data"
)

LOCAL_DB_PATH = Path(
    env_str("LOCAL_DB_PATH", str(DEFAULT_BASE_DIR / "loop_database.json"))
)


# ============================================================
# STORY CONFIG & LIMITS
# ============================================================

STORY_TTL_HOURS = max(1, env_int("STORY_TTL_HOURS", 24))
STORY_CLEANUP_INTERVAL = max(60, env_int("STORY_CLEANUP_INTERVAL", 300))

CHUNK_SIZE = 1024 * 1024
MAX_VIDEO_SIZE = 300 * 1024 * 1024
MAX_IMAGE_SIZE = 10 * 1024 * 1024

MAX_CONCURRENT_UPLOADS = max(1, env_int("MAX_CONCURRENT_UPLOADS", 1))
MAX_CONCURRENT_STREAMS = max(1, env_int("MAX_CONCURRENT_STREAMS", 3))
TELEGRAM_TIMEOUT = max(5, env_int("TELEGRAM_TIMEOUT", 30))

UPLOAD_DIR = Path(
    env_str("UPLOAD_DIR", str(DEFAULT_BASE_DIR / "uploads"))
)
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


# ============================================================
# LOGGING & GLOBAL STATE
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | Loop | %(message)s"
)
logger = logging.getLogger("Loop")

telegram_app: Optional[Client] = None
firebase_enabled = False
firebase_error = ""
firebase_mode = "none"

local_db_lock = asyncio.Lock()
cleanup_task: Optional[asyncio.Task] = None

upload_semaphore = asyncio.Semaphore(MAX_CONCURRENT_UPLOADS)
stream_semaphore = asyncio.Semaphore(MAX_CONCURRENT_STREAMS)
firebase_lock = asyncio.Lock()


# ============================================================
# BASIC UTILITIES
# ============================================================

def now_ms() -> int:
    return int(time.time() * 1000)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def safe_key(value) -> str:
    result = re.sub(r"[.#$\[\]/]", "_", str(value or "").strip())
    return result[:180] or "unknown"


def normalize_public_url(url: str) -> str:
    raw = str(url or "").strip()
    if not raw:
        return ""

    prefixes = (
        "https://vaeron.lydrwty8.workers.dev",
        "http://51.75.118.171:20240",
        "https://51.75.118.171:20240",
        "http://51.68.234.157:20116",
        "https://51.68.234.157:20116",
        "http://51.75.118.5:20239",
        "https://51.75.118.5:20239",
    )

    for prefix in prefixes:
        if raw.startswith(prefix):
            return SERVER_URL + raw[len(prefix):]

    if raw.startswith("/"):
        return SERVER_URL + raw

    return raw


# ============================================================
# TELEGRAM CHAT CONVERSION
# ============================================================

def get_chat_param(chat_id) -> str:
    value = str(chat_id).strip()
    if value.startswith("-100"):
        return "c_" + value[4:]
    return value.replace("@", "")


def resolve_telegram_chat(value: str):
    value = str(value or "").strip()

    if value.startswith("c_"):
        return int("-100" + value[2:])

    if value.startswith("-"):
        try:
            return int(value)
        except ValueError:
            pass

    if value.lstrip("-").isdigit():
        try:
            return int(value)
        except ValueError:
            pass

    return "@" + value.lstrip("@")


async def warm_up_bot_channels():
    if telegram_app is None:
        return

    channels = {
        CHANNEL_ID,
        PROFILE_CHANNEL,
        STORY_VIDEO_CHANNEL,
        STORY_IMAGE_CHANNEL,
    }
    for ch in channels:
        if not ch:
            continue
        try:
            chat_obj = await telegram_app.get_chat(resolve_telegram_chat(ch))
            logger.info(
                "Bot verified access to channel %s (id=%s)",
                ch,
                getattr(chat_obj, "id", "unknown")
            )
        except Exception as exc:
            logger.warning(
                "Bot could not pre-load channel %s: %s",
                ch,
                exc
            )


# ============================================================
# OWNER & MEDIA HELPERS
# ============================================================

def make_owner(uid="", username="", display_name="", avatar_url=""):
    uid = str(uid or "").strip()[:180]
    username = str(username or "").strip().lstrip("@")[:80]
    display_name = str(display_name or "").strip()[:120]
    avatar_url = normalize_public_url(str(avatar_url or "").strip()[:3000])

    if not display_name:
        display_name = username or "مستخدم FOUL"

    return {
        "uid": uid,
        "username": username,
        "display_name": display_name,
        "displayName": display_name,
        "avatar_url": avatar_url,
        "avatarUrl": avatar_url
    }


def get_media(message):
    if not message:
        return None
    for attr in ("photo", "video", "animation", "document"):
        value = getattr(message, attr, None)
        if value:
            return value
    return None


def get_media_type(message) -> str:
    if getattr(message, "photo", None):
        return "image"
    if getattr(message, "video", None) or getattr(message, "animation", None):
        return "video"
    document = getattr(message, "document", None)
    if document:
        mime = get_media_mime(document)
        if mime.startswith("image/"):
            return "image"
        if mime.startswith("video/"):
            return "video"
    return "unknown"


def get_media_mime(media) -> str:
    mime = str(getattr(media, "mime_type", "") or "").strip()
    if mime and mime != "application/octet-stream":
        return mime
    filename = getattr(media, "file_name", None)
    if filename:
        guessed, _ = mimetypes.guess_type(filename)
        if guessed:
            return guessed
    if getattr(media, "width", None) is not None:
        return "image/jpeg"
    return "video/mp4"


def parse_range(range_header: Optional[str], file_size: int) -> Optional[Tuple[int, int]]:
    if not range_header:
        return None
    if file_size <= 0:
        raise HTTPException(status_code=416, detail="الملف غير صالح")

    match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
    if not match:
        raise HTTPException(status_code=416, detail="Invalid Range header")

    start_text, end_text = match.groups()
    if not start_text:
        length = int(end_text or 0)
        if length <= 0:
            raise HTTPException(status_code=416, detail="Invalid Range")
        length = min(length, file_size)
        return (file_size - length, file_size - 1)

    start = int(start_text)
    if start >= file_size:
        raise HTTPException(status_code=416, detail="Range Not Satisfiable")

    end = min(int(end_text), file_size - 1) if end_text else file_size - 1
    if end < start:
        raise HTTPException(status_code=416, detail="Invalid Range")

    return (start, end)


# ============================================================
# DATABASE FALLBACK & FIREBASE INITIALIZATION
# ============================================================

def ensure_local_db_file():
    try:
        LOCAL_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        if not LOCAL_DB_PATH.exists():
            LOCAL_DB_PATH.write_text(json.dumps({}, ensure_ascii=False), encoding="utf-8")
    except Exception as exc:
        logger.error("Cannot initialize local database: %s", exc)


def local_db_read():
    ensure_local_db_file()
    try:
        value = json.loads(LOCAL_DB_PATH.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def local_db_write(value: dict):
    ensure_local_db_file()
    temp = LOCAL_DB_PATH.with_suffix(".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    temp.replace(LOCAL_DB_PATH)


def local_get(path: str):
    data = local_db_read()
    current = data
    for part in path.strip("/").split("/"):
        if not part:
            continue
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def local_set(path: str, value):
    data = local_db_read()
    parts = [p for p in path.strip("/").split("/") if p]
    if not parts:
        return
    current = data
    for part in parts[:-1]:
        if not isinstance(current.get(part), dict):
            current[part] = {}
        current = current[part]
    current[parts[-1]] = value
    local_db_write(data)


def local_update(path: str, value: dict):
    current = local_get(path)
    if not isinstance(current, dict):
        current = {}
    current.update(value)
    local_set(path, current)


def local_delete(path: str):
    data = local_db_read()
    parts = [p for p in path.strip("/").split("/") if p]
    if not parts:
        return
    current = data
    for part in parts[:-1]:
        if not isinstance(current, dict) or part not in current:
            local_db_write(data)
            return
        current = current[part]
    current.pop(parts[-1], None)
    local_db_write(data)


def firebase_rest_url(path: str) -> str:
    clean = path.strip("/")
    return f"{FIREBASE_DATABASE_URL}/{clean}.json" if clean else f"{FIREBASE_DATABASE_URL}/.json"


def firebase_rest_request(method: str, path: str, value=None):
    url = firebase_rest_url(path)
    data = json.dumps(value, ensure_ascii=False).encode("utf-8") if value is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=FIREBASE_REST_TIMEOUT) as response:
        raw = response.read()
        return json.loads(raw.decode("utf-8")) if raw else None


def _credential_candidates():
    candidates = []
    seen = set()

    def add(path):
        try:
            path = Path(path).expanduser().resolve()
        except Exception:
            return
        key = str(path)
        if key in seen:
            return
        seen.add(key)
        if path.is_file() and path.suffix.lower() == ".json":
            candidates.append(path)

    if FIREBASE_SERVICE_ACCOUNT_FILE:
        add(FIREBASE_SERVICE_ACCOUNT_FILE)
        add(Path(__file__).resolve().parent / FIREBASE_SERVICE_ACCOUNT_FILE)
        add(Path.cwd() / FIREBASE_SERVICE_ACCOUNT_FILE)

    if FIREBASE_CREDENTIALS_PATH:
        add(FIREBASE_CREDENTIALS_PATH)

    if GOOGLE_APPLICATION_CREDENTIALS:
        add(GOOGLE_APPLICATION_CREDENTIALS)

    for root in [Path(__file__).resolve().parent, Path.cwd(), Path("/home/container")]:
        try:
            for path in sorted(root.glob("*.json")):
                add(path)
        except Exception:
            pass

    return candidates


def _valid_service_account_file(path: Path) -> bool:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return bool(
            isinstance(data, dict)
            and data.get("type") == "service_account"
            and data.get("project_id")
            and data.get("client_email")
            and data.get("private_key")
        )
    except Exception:
        return False


def _discover_service_account_file():
    for path in _credential_candidates():
        if _valid_service_account_file(path):
            return path
    return None


def _activate_local_fallback(admin_error="", rest_error=""):
    global firebase_enabled, firebase_error, firebase_mode
    ensure_local_db_file()
    firebase_enabled = True
    firebase_mode = "local"
    firebase_error = f"Admin: {admin_error}; REST: {rest_error}".strip()
    logger.warning("Local database fallback active at %s", LOCAL_DB_PATH)


def initialize_firebase():
    global firebase_enabled, firebase_error, firebase_mode

    firebase_enabled = False
    firebase_error = ""
    firebase_mode = "none"

    if firebase_admin is None or credentials is None or db is None:
        try:
            firebase_rest_request("GET", "/")
            firebase_enabled = True
            firebase_mode = "rest"
            logger.info("Firebase RTDB connected via REST mode (%s)", FIREBASE_DATABASE_URL)
            return True
        except Exception as rest_exc:
            _activate_local_fallback("firebase-admin unavailable", str(rest_exc))
            return True

    try:
        if firebase_admin._apps:
            firebase_enabled = True
            firebase_mode = "admin"
            return True

        credential = None
        if FIREBASE_CREDENTIALS_JSON:
            credential = credentials.Certificate(json.loads(FIREBASE_CREDENTIALS_JSON))
        elif FIREBASE_CLIENT_EMAIL and FIREBASE_PRIVATE_KEY:
            credential = credentials.Certificate({
                "type": "service_account",
                "project_id": FIREBASE_PROJECT_ID,
                "private_key_id": FIREBASE_PRIVATE_KEY_ID,
                "private_key": FIREBASE_PRIVATE_KEY,
                "client_email": FIREBASE_CLIENT_EMAIL,
                "client_id": FIREBASE_CLIENT_ID,
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token",
                "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
                "client_x509_cert_url": FIREBASE_CLIENT_X509_CERT_URL,
            })
        else:
            selected_file = _discover_service_account_file()
            if selected_file is not None:
                credential = credentials.Certificate(str(selected_file))

        if credential is not None:
            firebase_admin.initialize_app(credential, {"databaseURL": FIREBASE_DATABASE_URL})
            firebase_enabled = True
            firebase_mode = "admin"
            logger.info("Firebase Admin connected successfully")
            return True

        firebase_rest_request("GET", "/")
        firebase_enabled = True
        firebase_mode = "rest"
        logger.info("Firebase RTDB connected via REST mode (%s)", FIREBASE_DATABASE_URL)
        return True
    except Exception as admin_exc:
        try:
            firebase_rest_request("GET", "/")
            firebase_enabled = True
            firebase_mode = "rest"
            logger.info("Firebase RTDB connected via REST mode (%s)", FIREBASE_DATABASE_URL)
            return True
        except Exception as rest_exc:
            _activate_local_fallback(str(admin_exc), str(rest_exc))
            return True


# ============================================================
# FIREBASE CRUD OPERATIONS
# ============================================================

async def firebase_get(path: str):
    if not firebase_enabled:
        return None
    async with firebase_lock:
        try:
            if firebase_mode == "admin":
                return await asyncio.to_thread(lambda: db.reference(path).get())
            if firebase_mode == "rest":
                return await asyncio.to_thread(lambda: firebase_rest_request("GET", path))
            return await asyncio.to_thread(lambda: local_get(path))
        except Exception:
            if firebase_mode != "local":
                return await asyncio.to_thread(local_get, path)
            return None


async def firebase_set(path: str, value):
    if not firebase_enabled:
        return False
    async with firebase_lock:
        try:
            if firebase_mode == "admin":
                await asyncio.to_thread(lambda: db.reference(path).set(value))
                return True
            if firebase_mode == "rest":
                await asyncio.to_thread(lambda: firebase_rest_request("PUT", path, value))
                return True
            await asyncio.to_thread(local_set, path, value)
            return True
        except Exception:
            try:
                await asyncio.to_thread(local_set, path, value)
                return True
            except Exception:
                return False


async def firebase_update(path: str, value: dict):
    if not firebase_enabled:
        return False
    async with firebase_lock:
        try:
            if firebase_mode == "admin":
                await asyncio.to_thread(lambda: db.reference(path).update(value))
                return True
            if firebase_mode == "rest":
                await asyncio.to_thread(lambda: firebase_rest_request("PATCH", path, value))
                return True
            await asyncio.to_thread(local_update, path, value)
            return True
        except Exception:
            try:
                await asyncio.to_thread(local_update, path, value)
                return True
            except Exception:
                return False


async def firebase_delete(path: str):
    if not firebase_enabled:
        return False
    async with firebase_lock:
        try:
            if firebase_mode == "admin":
                await asyncio.to_thread(lambda: db.reference(path).delete())
                return True
            if firebase_mode == "rest":
                await asyncio.to_thread(lambda: firebase_rest_request("DELETE", path))
                return True
            await asyncio.to_thread(local_delete, path)
            return True
        except Exception:
            try:
                await asyncio.to_thread(local_delete, path)
                return True
            except Exception:
                return False


async def get_user(uid: str) -> dict:
    if not uid:
        return {}
    value = await firebase_get("users/" + safe_key(uid))
    return value if isinstance(value, dict) else {}


async def save_user(uid: str, owner: dict, extra: Optional[dict] = None):
    if not firebase_enabled or not uid:
        return False
    data = {
        "uid": uid,
        "username": owner.get("username", ""),
        "displayName": owner.get("displayName", "مستخدم FOUL"),
        "display_name": owner.get("display_name", "مستخدم FOUL"),
        "avatarUrl": owner.get("avatarUrl", ""),
        "avatar_url": owner.get("avatar_url", ""),
        "updatedAt": now_ms()
    }
    if extra:
        data.update(extra)
    return await firebase_update("users/" + safe_key(uid), data)


async def resolve_owner(uid="", username="", display_name="", avatar_url="", message=None):
    user = await get_user(uid) if uid else {}
    owner = make_owner(
        uid or user.get("uid") or user.get("id"),
        username or user.get("username") or user.get("userName"),
        display_name or user.get("displayName") or user.get("display_name") or user.get("name"),
        avatar_url or user.get("avatarUrl") or user.get("avatar_url") or user.get("photoURL") or user.get("photoUrl")
    )
    if not owner["uid"] and message is not None:
        from_user = getattr(message, "from_user", None)
        if from_user:
            first_name = getattr(from_user, "first_name", "") or ""
            last_name = getattr(from_user, "last_name", "") or ""
            owner = make_owner(
                getattr(from_user, "id", ""),
                getattr(from_user, "username", "") or "",
                (first_name + " " + last_name).strip(),
                ""
            )
    return owner


async def get_records(path: str) -> list:
    value = await firebase_get(path)
    if not isinstance(value, dict) and firebase_mode != "local":
        value = await asyncio.to_thread(local_get, path)
    if not isinstance(value, dict):
        return []
    return [item for item in value.values() if isinstance(item, dict)]


# ============================================================
# TELEGRAM OPERATIONS
# ============================================================

async def get_telegram_message(chat_id: str, message_id: int):
    if telegram_app is None:
        raise HTTPException(status_code=503, detail="Telegram bot is not connected")

    target_chat = resolve_telegram_chat(chat_id)
    try:
        message = await asyncio.wait_for(
            telegram_app.get_messages(target_chat, int(message_id)),
            timeout=TELEGRAM_TIMEOUT
        )
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="Telegram request timeout")
    except Exception:
        await warm_up_bot_channels()
        try:
            message = await asyncio.wait_for(
                telegram_app.get_messages(target_chat, int(message_id)),
                timeout=TELEGRAM_TIMEOUT
            )
        except Exception as retry_exc:
            logger.error("Telegram get_messages failed: %s", retry_exc)
            raise HTTPException(status_code=404, detail="Telegram message not found")

    media = get_media(message)
    if not message or media is None:
        raise HTTPException(status_code=404, detail="Media not found")
    return (message, media)


def make_stream_url(chat_id, message_id):
    return normalize_public_url(
        f"{SERVER_URL}/stream_msg/{get_chat_param(chat_id)}/{int(message_id)}"
    )


async def upload_media_to_telegram(file: UploadFile, target_chat, media_kind: str, caption: str):
    if telegram_app is None:
        raise HTTPException(status_code=503, detail="Telegram bot is not connected")

    filename = file.filename or ("media.jpg" if media_kind == "image" else "media.mp4")
    suffix = Path(filename).suffix.lower() or (".jpg" if media_kind == "image" else ".mp4")
    size_limit = MAX_IMAGE_SIZE if media_kind == "image" else MAX_VIDEO_SIZE
    resolved_target = resolve_telegram_chat(target_chat)
    temp_path = None

    try:
        async with upload_semaphore:
            with tempfile.NamedTemporaryFile(delete=False, suffix=suffix, dir=UPLOAD_DIR) as temporary_file:
                temp_path = Path(temporary_file.name)

            total_size = 0
            with open(temp_path, "wb") as output:
                while True:
                    chunk = await file.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    total_size += len(chunk)
                    if total_size > size_limit:
                        raise HTTPException(status_code=413, detail="حجم الملف أكبر من الحد المسموح")
                    output.write(chunk)

            if total_size <= 0:
                raise HTTPException(status_code=400, detail="الملف فارغ")

            if media_kind == "image":
                message = await telegram_app.send_photo(resolved_target, photo=str(temp_path), caption=caption)
            else:
                message = await telegram_app.send_video(
                    resolved_target,
                    video=str(temp_path),
                    caption=caption,
                    supports_streaming=True
                )

            if not message:
                raise RuntimeError("Telegram returned no message")
            return message
    finally:
        try:
            await file.close()
        except Exception:
            pass
        if temp_path:
            try:
                temp_path.unlink(missing_ok=True)
            except Exception:
                pass


# ============================================================
# ENGAGEMENT HELPERS
# ============================================================

async def request_payload(request: Request) -> dict:
    content_type = (request.headers.get("content-type") or "").lower()
    data = {}
    try:
        if "application/json" in content_type:
            body = await request.json()
            if isinstance(body, dict):
                data = body
        elif "application/x-www-form-urlencoded" in content_type or "multipart/form-data" in content_type:
            form = await request.form()
            data = dict(form)
    except Exception:
        data = {}

    if not isinstance(data, dict):
        data = {}

    for key in ("uid", "username", "displayName", "display_name", "avatarUrl", "avatar_url", "reaction", "text"):
        if key not in data:
            value = request.query_params.get(key)
            if value is not None:
                data[key] = value
    return data


async def get_request_user(request: Request, payload: Optional[dict] = None) -> dict:
    payload = payload or {}
    supplied_uid = str(payload.get("uid") or payload.get("user_id") or request.headers.get("x-uid") or "").strip()

    auth_header = (request.headers.get("authorization") or "").strip()
    token_uid = ""
    if auth_header.lower().startswith("bearer ") and auth is not None and firebase_mode == "admin":
        token = auth_header[7:].strip()
        if token:
            try:
                decoded = await asyncio.to_thread(auth.verify_id_token, token)
                token_uid = str(decoded.get("uid") or "").strip()
                if supplied_uid and token_uid and supplied_uid != token_uid:
                    raise HTTPException(status_code=403, detail="UID لا يطابق حساب Firebase المصادق عليه")
            except HTTPException:
                raise
            except Exception:
                raise HTTPException(status_code=401, detail="جلسة Firebase غير صالحة أو منتهية")

    uid = token_uid or supplied_uid
    if not uid:
        raise HTTPException(status_code=401, detail="uid مطلوب")

    owner = await resolve_owner(
        uid,
        payload.get("username"),
        payload.get("displayName") or payload.get("display_name"),
        payload.get("avatarUrl") or payload.get("avatar_url"),
    )
    if not owner.get("uid"):
        owner = make_owner(
            uid,
            payload.get("username"),
            payload.get("displayName") or payload.get("display_name"),
            payload.get("avatarUrl") or payload.get("avatar_url"),
        )
    await save_user(uid, owner)
    return owner


async def firebase_claim_once(path: str, value: dict) -> tuple[bool, object]:
    if not firebase_enabled:
        return False, None

    async with firebase_lock:
        try:
            if firebase_mode == "admin":
                holder = {"claimed": False}

                def txn(current):
                    if current is None:
                        holder["claimed"] = True
                        return value
                    return current

                result = await asyncio.to_thread(lambda: db.reference(path).transaction(txn))
                return bool(holder["claimed"]), result

            if firebase_mode == "rest":
                current = await asyncio.to_thread(lambda: firebase_rest_request("GET", path))
                if current is None:
                    await asyncio.to_thread(lambda: firebase_rest_request("PUT", path, value))
                    return True, value
                return False, current

            current = await asyncio.to_thread(local_get, path)
            if current is None:
                await asyncio.to_thread(local_set, path, value)
                return True, value
            return False, current
        except Exception:
            return False, None


async def firebase_atomic_increment(path: str, delta: int, minimum: int = 0) -> int:
    if not firebase_enabled:
        return 0

    async with firebase_lock:
        try:
            if firebase_mode == "admin":
                def txn(current):
                    try:
                        current_value = int(current or 0)
                    except (TypeError, ValueError):
                        current_value = 0
                    return max(minimum, current_value + int(delta))

                result = await asyncio.to_thread(lambda: db.reference(path).transaction(txn))
                return int(result or 0)

            if firebase_mode == "rest":
                current = await asyncio.to_thread(lambda: firebase_rest_request("GET", path))
                try:
                    number = int(current or 0)
                except (TypeError, ValueError):
                    number = 0
                number = max(minimum, number + int(delta))
                await asyncio.to_thread(lambda: firebase_rest_request("PUT", path, number))
                return number

            current = await asyncio.to_thread(local_get, path)
            try:
                number = int(current or 0)
            except (TypeError, ValueError):
                number = 0
            number = max(minimum, number + int(delta))
            await asyncio.to_thread(local_set, path, number)
            return number
        except Exception:
            return 0


def enrich_reel_record(reel: dict) -> dict:
    if not isinstance(reel, dict):
        return reel

    for key in ("stream_url", "video_url", "videoUrl", "url"):
        if reel.get(key):
            reel[key] = normalize_public_url(reel[key])

    try:
        message_id = int(reel.get("message_id") or reel.get("id"))
    except (TypeError, ValueError):
        message_id = 0

    chat_id = reel.get("chat_id") or get_chat_param(CHANNEL_ID)

    if message_id:
        reel["id"] = message_id
        reel["message_id"] = message_id
        reel["chat_id"] = chat_id
        canonical = make_stream_url(chat_id, message_id)
        reel["stream_url"] = canonical
        reel["video_url"] = canonical
        reel["videoUrl"] = canonical
        reel["url"] = canonical

    owner = reel.get("owner")
    if not isinstance(owner, dict):
        owner = make_owner(
            reel.get("uid", ""),
            reel.get("username", ""),
            reel.get("displayName") or reel.get("display_name", ""),
            reel.get("avatarUrl") or reel.get("avatar_url", ""),
        )

    reel["owner"] = owner
    reel["uid"] = owner.get("uid", reel.get("uid", ""))
    reel["username"] = owner.get("username", reel.get("username", ""))
    reel["displayName"] = owner.get("displayName", reel.get("displayName", "مستخدم FOUL"))
    reel["display_name"] = owner.get("display_name", reel.get("display_name", "مستخدم FOUL"))
    reel["avatarUrl"] = owner.get("avatarUrl", reel.get("avatarUrl", ""))
    reel["avatar_url"] = owner.get("avatar_url", reel.get("avatar_url", ""))
    return reel


def enrich_story_record(story: dict) -> dict:
    if not isinstance(story, dict):
        return story

    for key in ("stream_url", "video_url", "videoUrl", "url"):
        if story.get(key):
            story[key] = normalize_public_url(story[key])

    try:
        message_id = int(story.get("message_id") or story.get("id"))
    except (TypeError, ValueError):
        message_id = 0

    chat_id = story.get("chat_id") or get_chat_param(
        STORY_VIDEO_CHANNEL if story.get("media_type") == "video" else STORY_IMAGE_CHANNEL
    )

    if message_id:
        story["id"] = message_id
        story["message_id"] = message_id
        story["chat_id"] = chat_id
        canonical = make_stream_url(chat_id, message_id)
        story["stream_url"] = canonical
        story["video_url"] = canonical
        story["videoUrl"] = canonical
        story["url"] = canonical

    return story


async def ensure_story_view(story: dict, owner: dict) -> tuple[bool, dict]:
    story_id = str(story.get("id") or story.get("message_id") or "")
    uid = str(owner.get("uid") or "")
    if not story_id or not uid:
        return False, {}

    viewer = {
        "uid": uid,
        "username": owner.get("username", ""),
        "displayName": owner.get("displayName", "مستخدم FOUL"),
        "display_name": owner.get("display_name", owner.get("displayName", "مستخدم FOUL")),
        "avatarUrl": owner.get("avatarUrl", ""),
        "avatar_url": owner.get("avatar_url", ""),
        "viewedAt": now_ms(),
        "reaction": "",
        "reacted": False,
    }
    created, final_value = await firebase_claim_once(
        f"storyViews/{safe_key(story_id)}/{safe_key(uid)}",
        viewer,
    )
    if created:
        new_views = await firebase_atomic_increment(f"stories/{safe_key(story_id)}/views", 1, 0)
        await firebase_update(
            f"stories/{safe_key(story_id)}",
            {"views": new_views, "viewerCount": new_views, "updatedAt": now_ms()},
        )
        viewer_value = viewer
    else:
        viewer_value = final_value if isinstance(final_value, dict) else viewer
    return created, viewer_value


async def get_story(story_id: str) -> Optional[dict]:
    story = await firebase_get(f"stories/{safe_key(story_id)}")
    if isinstance(story, dict):
        story = enrich_story_record(story)
        story.setdefault("views", 0)
        story.setdefault("reactionsCount", 0)
        story.setdefault("repliesCount", 0)
        story.setdefault("viewerCount", story.get("views", 0))
        story.setdefault("reactionCount", story.get("reactionsCount", 0))
        return story
    return None


async def save_story_metrics(story_id: str) -> dict:
    story = await get_story(story_id)
    if not story:
        return {}
    views = await firebase_get(f"stories/{safe_key(story_id)}/views")
    reactions = await firebase_get(f"stories/{safe_key(story_id)}/reactionsCount")
    replies = await firebase_get(f"stories/{safe_key(story_id)}/repliesCount")
    try:
        views = int(views or 0)
    except (TypeError, ValueError):
        views = int(story.get("views") or 0)
    try:
        reactions = int(reactions or 0)
    except (TypeError, ValueError):
        reactions = int(story.get("reactionsCount") or 0)
    try:
        replies = int(replies or 0)
    except (TypeError, ValueError):
        replies = int(story.get("repliesCount") or 0)
    story.update({
        "views": views,
        "viewerCount": views,
        "reactionsCount": reactions,
        "reactionCount": reactions,
        "repliesCount": replies
    })
    return story


async def cleanup_expired_stories():
    if not firebase_enabled:
        return

    stories = await get_records("stories")
    current = now_ms()

    for story in stories:
        try:
            message_id = int(story.get("message_id") or story.get("id"))
            expires_at = int(story.get("expiresAt") or 0)
        except (TypeError, ValueError):
            continue

        if not expires_at or expires_at > current:
            continue

        if telegram_app:
            try:
                await telegram_app.delete_messages(
                    resolve_telegram_chat(story.get("chat_id", "")),
                    message_id
                )
            except Exception:
                pass

        await firebase_delete("stories/" + safe_key(message_id))
        await firebase_delete("storyViews/" + safe_key(message_id))
        await firebase_delete("storyReactions/" + safe_key(message_id))
        await firebase_delete("storyReplies/" + safe_key(message_id))

        uid = str(story.get("uid", ""))
        if uid:
            await firebase_delete("userStories/" + safe_key(uid) + "/" + safe_key(message_id))


async def cleanup_loop():
    while True:
        try:
            await cleanup_expired_stories()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("Story cleanup failed: %s", exc)
        await asyncio.sleep(STORY_CLEANUP_INTERVAL)


async def read_multipart(request: Request):
    form = await request.form()
    files = []
    fields = {}
    for key, value in form.items():
        if isinstance(value, UploadFile):
            files.append(value)
        else:
            fields[key] = str(value)
    return (files, fields)


# ============================================================
# LIFESPAN
# ============================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    global telegram_app, cleanup_task

    logger.info("==================================================")
    logger.info("Loop Backend Starting (Bot-Only Mode)")
    logger.info("Server URL: %s", SERVER_URL)
    logger.info("Listening on: %s:%s", HOST, PORT)
    logger.info("Firebase URL: %s", FIREBASE_DATABASE_URL)
    logger.info("Telegram channel: %s", CHANNEL_ID)
    logger.info("Story video channel: %s", STORY_VIDEO_CHANNEL)
    logger.info("Story image channel: %s", STORY_IMAGE_CHANNEL)
    logger.info("==================================================")

    initialize_firebase()
    logger.info("Firebase connected: %s (mode=%s)", firebase_enabled, firebase_mode)

    if BOT_TOKEN and API_HASH:
        try:
            telegram_app = Client(
                "loop_bot",
                api_id=API_ID,
                api_hash=API_HASH,
                bot_token=BOT_TOKEN,
                in_memory=True
            )
            await telegram_app.start()
            logger.info("Telegram bot connected and active as primary media client")
            await warm_up_bot_channels()
        except Exception as exc:
            telegram_app = None
            logger.exception("Telegram bot startup failed: %s", exc)
    else:
        logger.error("BOT_TOKEN or API_HASH is missing")

    cleanup_task = asyncio.create_task(cleanup_loop())

    logger.info("==================================================")
    logger.info(
        "READY | Database=%s | Firebase=%s | Telegram=%s | Port=%s",
        firebase_mode,
        firebase_enabled,
        telegram_app is not None,
        PORT
    )
    logger.info("==================================================")

    yield

    if cleanup_task:
        cleanup_task.cancel()
        try:
            await cleanup_task
        except asyncio.CancelledError:
            pass

    if telegram_app:
        try:
            await telegram_app.stop()
        except Exception:
            pass
        telegram_app = None


# ============================================================
# FASTAPI APP & ENDPOINTS
# ============================================================

app = FastAPI(
    title="FOUL Loop Backend",
    version="14.2.0",
    description="Firebase database (foul-b7671) + Telegram Bot media storage",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"]
)


@app.get("/")
async def root():
    return {
        "ok": True,
        "name": "FOUL Loop",
        "version": "14.2.0",
        "database": "Firebase",
        "firebase_project_id": FIREBASE_PROJECT_ID,
        "firebase_database_url": FIREBASE_DATABASE_URL,
        "media_storage": "Telegram Bot",
        "firebase_connected": firebase_enabled and firebase_mode in {"admin", "rest"},
        "database_mode": firebase_mode,
        "telegram_connected": telegram_app is not None,
        "firebase_error": firebase_error if firebase_mode in {"local", "none"} else "",
        "public_base_url": SERVER_URL,
        "listen_host": HOST,
        "listen_port": PORT,
        "endpoints": {
            "health": "/health",
            "reels": "/api/reels",
            "reel_upload": "/api/upload",
            "stories": "/api/stories",
            "story_upload": "/api/stories/upload",
            "story_feed": "/api/stories/feed",
            "profile": "/api/profile/{uid}",
            "profile_upload": "/api/profile/photo",
            "stream": "/stream_msg/{chat_id}/{message_id}"
        }
    }


@app.get("/health")
async def health():
    return {
        "ok": True,
        "status": "online",
        "version": "14.2.0",
        "firebase_project_id": FIREBASE_PROJECT_ID,
        "firebase_connected": firebase_enabled and firebase_mode in {"admin", "rest"},
        "database_mode": firebase_mode,
        "telegram_connected": telegram_app is not None,
        "ready_for_database": firebase_enabled and firebase_mode in {"admin", "rest"},
        "ready_for_media": telegram_app is not None,
        "public_base_url": SERVER_URL,
        "listen_host": HOST,
        "listen_port": PORT
    }


@app.get("/api/profile/{uid}")
async def get_profile(uid: str):
    profile = await get_user(uid)
    if not profile:
        raise HTTPException(status_code=404, detail="المستخدم غير موجود")
    return {"ok": True, "profile": profile}


@app.post("/api/profile/photo")
async def upload_profile_photo(request: Request):
    if telegram_app is None:
        raise HTTPException(status_code=503, detail="Telegram bot is not connected")

    files, fields = await read_multipart(request)
    if not files:
        raise HTTPException(status_code=400, detail="file مطلوب")

    file = files[0]
    uid = fields.get("uid", "").strip()
    mime = (file.content_type or "").split(";", 1)[0].lower()

    if not mime.startswith("image/"):
        raise HTTPException(status_code=415, detail="الملف يجب أن يكون صورة")

    old_user = await get_user(uid)
    message = await upload_media_to_telegram(
        file,
        PROFILE_CHANNEL,
        "image",
        "LOOP_PROFILE\nuid=" + uid
    )

    message_id = int(message.id)
    chat_param = get_chat_param(PROFILE_CHANNEL)
    avatar_url = normalize_public_url(f"{SERVER_URL}/api/profile/avatar/{chat_param}/{message_id}")

    owner = make_owner(
        uid,
        fields.get("username") or old_user.get("username"),
        fields.get("displayName") or fields.get("display_name") or old_user.get("displayName") or old_user.get("display_name"),
        avatar_url
    )

    await save_user(
        uid,
        owner,
        {
            "profileMessageId": message_id,
            "profileChatId": chat_param,
            "profileUpdatedAt": now_ms()
        }
    )

    return {
        "ok": True,
        "message": "تم حفظ صورة الحساب بنجاح",
        "message_id": message_id,
        "chat_id": chat_param,
        "image_url": avatar_url,
        "avatar_url": avatar_url,
        "avatarUrl": avatar_url,
        "owner": owner
    }


@app.get("/api/profile/avatar/{chat_id}/{message_id}")
async def profile_avatar(chat_id: str, message_id: int):
    message, media = await get_telegram_message(chat_id, message_id)
    if get_media_type(message) != "image":
        raise HTTPException(status_code=404, detail="Image not found")

    async def generator():
        async with stream_semaphore:
            async for chunk in telegram_app.stream_media(message):
                yield chunk

    return StreamingResponse(
        generator(),
        status_code=200,
        media_type=get_media_mime(media),
        headers={
            "Cache-Control": "public,max-age=86400",
            "Access-Control-Allow-Origin": "*",
            "X-Content-Type-Options": "nosniff"
        }
    )


@app.get("/api/profile/avatar/{uid}")
async def profile_avatar_by_uid(uid: str):
    user = await get_user(uid)
    if not user:
        raise HTTPException(status_code=404, detail="Profile not found")

    chat = user.get("profileChatId") or get_chat_param(PROFILE_CHANNEL)
    message_id = user.get("profileMessageId")
    if not message_id:
        raise HTTPException(status_code=404, detail="Profile image not found")

    return await profile_avatar(str(chat), int(message_id))


@app.post("/api/upload")
async def upload_reel(request: Request):
    if telegram_app is None:
        raise HTTPException(status_code=503, detail="Telegram bot is not connected")

    files, fields = await read_multipart(request)
    if not files:
        raise HTTPException(status_code=400, detail="video مطلوب")

    file = files[0]
    uid = (fields.get("uid") or fields.get("user_id") or "").strip()
    mime = (file.content_type or "").split(";", 1)[0].lower()

    if not mime.startswith("video/"):
        raise HTTPException(status_code=415, detail="الملف يجب أن يكون فيديو")

    owner = await resolve_owner(
        uid,
        fields.get("username"),
        fields.get("displayName") or fields.get("display_name"),
        fields.get("avatarUrl") or fields.get("avatar_url")
    )

    caption = (fields.get("caption") or "").strip()[:900] or "🎬 مقطع جديد"
    overlay_text = (fields.get("overlay_text") or "").strip()[:120]
    overlay_position = fields.get("overlay_position") or "center"
    if overlay_position not in {"top", "center", "bottom"}:
        overlay_position = "center"

    message = await upload_media_to_telegram(file, CHANNEL_ID, "video", caption)
    message_id = int(message.id)
    telegram_chat = getattr(getattr(message, "chat", None), "id", CHANNEL_ID)
    c = get_chat_param(telegram_chat)

    await save_user(uid, owner)

    reel = {
        "id": message_id,
        "uid": uid,
        "owner": owner,
        "username": owner["username"],
        "displayName": owner["displayName"],
        "display_name": owner["display_name"],
        "avatarUrl": owner["avatarUrl"],
        "avatar_url": owner["avatar_url"],
        "chat_id": c,
        "message_id": message_id,
        "media_type": "video",
        "mime_type": mime,
        "stream_url": make_stream_url(c, message_id),
        "caption": caption,
        "overlay_text": overlay_text,
        "overlay_position": overlay_position,
        "createdAt": now_ms(),
        "created_at": now_iso(),
        "views": 0,
        "likes": 0,
        "comments": 0,
        "viewerCount": 0,
        "likeCount": 0
    }

    await firebase_set("reels/" + safe_key(message_id), reel)
    reel = enrich_reel_record(reel)

    return {
        "ok": True,
        "message": "تم نشر الريلز بنجاح",
        "reel": reel,
        "id": message_id,
        "message_id": message_id,
        "stream_url": reel["stream_url"],
        "video_url": reel["video_url"],
        "videoUrl": reel["videoUrl"]
    }


@app.get("/api/reels")
async def get_reels(limit: int = 20, uid: str = "", username: str = ""):
    try:
        limit = int(limit)
    except (TypeError, ValueError):
        limit = 20

    limit = max(1, min(limit, 100))
    normalized_username = username.strip().lstrip("@").lower()
    stored = await get_records("reels")

    by_id = {}
    for row in stored:
        if not isinstance(row, dict):
            continue
        try:
            mid = int(row.get("message_id") or row.get("id"))
        except (TypeError, ValueError):
            continue
        current = by_id.get(mid, {})
        current.update(row)
        by_id[mid] = current

    result = []
    for reel in by_id.values():
        reel = enrich_reel_record(reel)
        owner = reel.get("owner") or make_owner()

        if reel.get("uid"):
            owner = await resolve_owner(
                reel.get("uid", ""),
                owner.get("username", ""),
                owner.get("displayName", ""),
                owner.get("avatarUrl", ""),
            )

        reel["owner"] = owner
        reel["uid"] = owner.get("uid", reel.get("uid", ""))
        reel["username"] = owner.get("username", reel.get("username", ""))
        reel["displayName"] = owner.get("displayName", "مستخدم FOUL")
        reel["display_name"] = owner.get("display_name", "مستخدم FOUL")
        reel["avatarUrl"] = owner.get("avatarUrl", "")
        reel["avatar_url"] = owner.get("avatar_url", "")

        try:
            reel["views"] = int(reel.get("views") or 0)
        except (TypeError, ValueError):
            reel["views"] = 0
        try:
            reel["likes"] = int(reel.get("likes") or 0)
        except (TypeError, ValueError):
            reel["likes"] = 0
        reel["viewerCount"] = reel["views"]
        reel["likeCount"] = reel["likes"]

        if uid and reel["uid"] != uid:
            continue
        if normalized_username and reel["username"].lower() != normalized_username:
            continue

        result.append(reel)

    result.sort(key=lambda item: int(item.get("createdAt", 0) or 0), reverse=True)
    result = result[:limit]

    return {
        "ok": True,
        "count": len(result),
        "reels": result,
        "source": "telegram+firebase" if firebase_mode in {"admin", "rest"} else "telegram+local",
    }


@app.post("/api/reels/{message_id}/view")
async def record_reel_view(message_id: int, request: Request):
    payload = await request_payload(request)
    owner = await get_request_user(request, payload)
    reel = await firebase_get(f"reels/{safe_key(message_id)}")
    if not isinstance(reel, dict):
        raise HTTPException(status_code=404, detail="الريلز غير موجود")

    uid = str(owner.get("uid") or "")
    viewer = {
        "uid": uid,
        "username": owner.get("username", ""),
        "displayName": owner.get("displayName", "مستخدم FOUL"),
        "display_name": owner.get("display_name", owner.get("displayName", "مستخدم FOUL")),
        "avatarUrl": owner.get("avatarUrl", ""),
        "avatar_url": owner.get("avatar_url", ""),
        "viewedAt": now_ms(),
    }
    created, _ = await firebase_claim_once(
        f"reelViews/{safe_key(message_id)}/{safe_key(uid)}",
        viewer,
    )
    if created:
        views = await firebase_atomic_increment(f"reels/{safe_key(message_id)}/views", 1, 0)
        await firebase_update(
            f"reels/{safe_key(message_id)}",
            {"views": views, "viewerCount": views, "updatedAt": now_ms()}
        )
    else:
        views = await firebase_get(f"reels/{safe_key(message_id)}/views")
        try:
            views = int(views or 0)
        except (TypeError, ValueError):
            views = int(reel.get("views") or 0)

    return {"ok": True, "counted": created, "views": views, "viewerCount": views, "uid": uid}


@app.post("/api/reels/{message_id}/like")
async def like_reel(message_id: int, request: Request):
    payload = await request_payload(request)
    owner = await get_request_user(request, payload)
    reel = await firebase_get(f"reels/{safe_key(message_id)}")
    if not isinstance(reel, dict):
        raise HTTPException(status_code=404, detail="الريلز غير موجود")

    uid = str(owner.get("uid") or "")
    marker = {
        "uid": uid,
        "username": owner.get("username", ""),
        "displayName": owner.get("displayName", "مستخدم FOUL"),
        "avatarUrl": owner.get("avatarUrl", ""),
        "createdAt": now_ms(),
    }
    created, _ = await firebase_claim_once(f"reelLikes/{safe_key(message_id)}/{safe_key(uid)}", marker)
    if created:
        likes = await firebase_atomic_increment(f"reels/{safe_key(message_id)}/likes", 1, 0)
    else:
        current = await firebase_get(f"reels/{safe_key(message_id)}/likes")
        try:
            likes = int(current or 0)
        except (TypeError, ValueError):
            likes = int(reel.get("likes") or 0)

    await firebase_update(
        f"reels/{safe_key(message_id)}",
        {"likes": likes, "likeCount": likes, "updatedAt": now_ms()}
    )
    return {"ok": True, "liked": True, "changed": created, "likes": likes, "likeCount": likes, "uid": uid}


@app.delete("/api/reels/{message_id}/like")
async def unlike_reel(message_id: int, request: Request):
    payload = await request_payload(request)
    owner = await get_request_user(request, payload)
    reel = await firebase_get(f"reels/{safe_key(message_id)}")
    if not isinstance(reel, dict):
        raise HTTPException(status_code=404, detail="الريلز غير موجود")

    uid = str(owner.get("uid") or "")
    marker_path = f"reelLikes/{safe_key(message_id)}/{safe_key(uid)}"
    existing = await firebase_get(marker_path)
    if existing is not None:
        await firebase_delete(marker_path)
        likes = await firebase_atomic_increment(f"reels/{safe_key(message_id)}/likes", -1, 0)
    else:
        current = await firebase_get(f"reels/{safe_key(message_id)}/likes")
        try:
            likes = int(current or 0)
        except (TypeError, ValueError):
            likes = int(reel.get("likes") or 0)

    await firebase_update(
        f"reels/{safe_key(message_id)}",
        {"likes": likes, "likeCount": likes, "updatedAt": now_ms()}
    )
    return {"ok": True, "liked": False, "changed": existing is not None, "likes": likes, "likeCount": likes, "uid": uid}


@app.get("/api/reels/{message_id}/engagement")
async def reel_engagement(message_id: int, request: Request):
    payload = await request_payload(request)
    uid = str(payload.get("uid") or request.headers.get("x-uid") or "").strip()
    reel = await firebase_get(f"reels/{safe_key(message_id)}")
    if not isinstance(reel, dict):
        raise HTTPException(status_code=404, detail="الريلز غير موجود")

    views = await firebase_get(f"reels/{safe_key(message_id)}/views")
    likes = await firebase_get(f"reels/{safe_key(message_id)}/likes")
    try:
        views = int(views or 0)
    except (TypeError, ValueError):
        views = int(reel.get("views") or 0)
    try:
        likes = int(likes or 0)
    except (TypeError, ValueError):
        likes = int(reel.get("likes") or 0)

    liked = bool(uid and await firebase_get(f"reelLikes/{safe_key(message_id)}/{safe_key(uid)}") is not None)
    viewed = bool(uid and await firebase_get(f"reelViews/{safe_key(message_id)}/{safe_key(uid)}") is not None)
    return {
        "ok": True,
        "views": views,
        "viewerCount": views,
        "likes": likes,
        "likeCount": likes,
        "liked": liked,
        "viewed": viewed
    }


ALLOWED_STORY_REACTIONS = {"❤️", "😂", "😍", "🔥", "😢", "😮", "👏", "👍"}


async def create_story(request: Request):
    if telegram_app is None:
        raise HTTPException(status_code=503, detail="Telegram bot is not connected")

    files, fields = await read_multipart(request)
    if not files:
        raise HTTPException(status_code=400, detail="لم يتم إرسال صورة أو فيديو للقصة")

    file = files[0]
    uid = (fields.get("uid") or fields.get("user_id") or "").strip()
    if not uid:
        raise HTTPException(status_code=400, detail="uid مطلوب")

    owner = await resolve_owner(
        uid,
        fields.get("username"),
        fields.get("displayName") or fields.get("display_name"),
        fields.get("avatarUrl") or fields.get("avatar_url")
    )

    if not owner["uid"]:
        raise HTTPException(status_code=400, detail="بيانات صاحب القصة ناقصة")

    content_type = (file.content_type or "").split(";", 1)[0].lower()

    if content_type.startswith("image/"):
        media_kind = "image"
    elif content_type.startswith("video/"):
        media_kind = "video"
    else:
        guessed, _ = mimetypes.guess_type(file.filename or "")
        if guessed and guessed.startswith("image/"):
            media_kind = "image"
            content_type = guessed
        elif guessed and guessed.startswith("video/"):
            media_kind = "video"
            content_type = guessed
        else:
            raise HTTPException(status_code=415, detail="القصة يجب أن تكون صورة أو فيديو")

    caption = (fields.get("caption") or "").strip()[:900]
    target_channel = STORY_IMAGE_CHANNEL if media_kind == "image" else STORY_VIDEO_CHANNEL

    telegram_caption = "LOOP_STORY\nuid=" + uid
    if caption:
        telegram_caption += "\n" + caption

    message = await upload_media_to_telegram(file, target_channel, media_kind, telegram_caption)
    message_id = int(message.id)
    actual_chat = getattr(getattr(message, "chat", None), "id", target_channel)
    c = get_chat_param(actual_chat)

    created_at = now_ms()
    expires_at = created_at + (STORY_TTL_HOURS * 60 * 60 * 1000)

    await save_user(uid, owner)

    story = {
        "id": message_id,
        "uid": uid,
        "owner": owner,
        "username": owner["username"],
        "displayName": owner["displayName"],
        "display_name": owner["display_name"],
        "avatarUrl": owner["avatarUrl"],
        "avatar_url": owner["avatar_url"],
        "chat_id": c,
        "message_id": message_id,
        "media_type": media_kind,
        "mime_type": content_type,
        "stream_url": make_stream_url(c, message_id),
        "caption": caption,
        "createdAt": created_at,
        "created_at": now_iso(),
        "expiresAt": expires_at,
        "expires_at": datetime.fromtimestamp(expires_at / 1000, timezone.utc).isoformat(),
        "views": 0,
        "viewerCount": 0,
        "reactionsCount": 0,
        "reactionCount": 0,
        "repliesCount": 0
    }

    await firebase_set("stories/" + safe_key(message_id), story)
    await firebase_set("userStories/" + safe_key(uid) + "/" + safe_key(message_id), story)

    story = enrich_story_record(story)

    return {
        "ok": True,
        "message": "تم نشر القصة بنجاح",
        "story": story,
        "id": message_id,
        "message_id": message_id,
        "stream_url": story.get("stream_url", ""),
        "video_url": story.get("video_url", ""),
        "videoUrl": story.get("videoUrl", "")
    }


@app.post("/api/stories")
async def upload_story(request: Request):
    return await create_story(request)


@app.post("/api/stories/upload")
async def upload_story_alias(request: Request):
    return await create_story(request)


@app.get("/api/stories")
async def get_stories(uid: str = "", username: str = "", include_expired: bool = False):
    await cleanup_expired_stories()
    normalized_username = username.strip().lstrip("@").lower()
    current = now_ms()
    stories = await get_records("stories")
    result = []

    for story in stories:
        try:
            expires_at = int(story.get("expiresAt") or 0)
        except (TypeError, ValueError):
            expires_at = 0

        if not include_expired and expires_at and expires_at <= current:
            continue

        owner = story.get("owner") or make_owner(
            story.get("uid"),
            story.get("username"),
            story.get("displayName") or story.get("display_name"),
            story.get("avatarUrl") or story.get("avatar_url")
        )

        if uid and owner.get("uid") != uid:
            continue
        if normalized_username and owner.get("username", "").lower() != normalized_username:
            continue

        story["owner"] = owner
        result.append(enrich_story_record(story))

    result.sort(key=lambda item: item.get("createdAt", 0), reverse=True)
    return {"ok": True, "count": len(result), "stories": result}


@app.get("/api/stories/feed")
async def stories_feed():
    await cleanup_expired_stories()
    current = now_ms()
    stories = await get_records("stories")
    grouped = {}

    for story in stories:
        try:
            expires_at = int(story.get("expiresAt") or 0)
        except (TypeError, ValueError):
            expires_at = 0

        if expires_at and expires_at <= current:
            continue

        owner = story.get("owner") or make_owner(
            story.get("uid"),
            story.get("username"),
            story.get("displayName") or story.get("display_name"),
            story.get("avatarUrl") or story.get("avatar_url")
        )

        group_key = owner.get("uid") or owner.get("username") or "unknown"

        if group_key not in grouped:
            grouped[group_key] = {
                "uid": owner.get("uid", ""),
                "username": owner.get("username", ""),
                "displayName": owner.get("displayName", "مستخدم FOUL"),
                "display_name": owner.get("display_name", "مستخدم FOUL"),
                "avatarUrl": owner.get("avatarUrl", ""),
                "avatar_url": owner.get("avatar_url", ""),
                "has_story": True,
                "story_count": 0,
                "stories": []
            }

        story["owner"] = owner
        story = enrich_story_record(story)
        grouped[group_key]["stories"].append(story)

    users = list(grouped.values())
    for user in users:
        user["stories"].sort(key=lambda item: item.get("createdAt", 0))
        user["story_count"] = len(user["stories"])
        user["latest_createdAt"] = user["stories"][-1].get("createdAt", 0) if user["stories"] else 0

    users.sort(key=lambda item: item.get("latest_createdAt", 0), reverse=True)
    return {"ok": True, "count": len(users), "users": users, "stories": users}


@app.post("/api/stories/{story_id}/view")
async def record_story_view(story_id: int, request: Request):
    payload = await request_payload(request)
    viewer_owner = await get_request_user(request, payload)
    story = await get_story(str(story_id))
    if not story:
        raise HTTPException(status_code=404, detail="القصة غير موجودة")

    expires_at = int(story.get("expiresAt") or 0)
    if expires_at and expires_at <= now_ms():
        raise HTTPException(status_code=410, detail="انتهت صلاحية القصة")

    created, viewer = await ensure_story_view(story, viewer_owner)
    metrics = await save_story_metrics(str(story_id))
    return {
        "ok": True,
        "counted": created,
        "viewer": viewer,
        "views": metrics.get("views", 0),
        "viewerCount": metrics.get("viewerCount", 0)
    }


@app.get("/api/stories/{story_id}/viewers")
async def story_viewers(story_id: int, request: Request):
    payload = await request_payload(request)
    requester = await get_request_user(request, payload)
    story = await get_story(str(story_id))
    if not story:
        raise HTTPException(status_code=404, detail="القصة غير موجودة")

    story_uid = str(story.get("uid") or (story.get("owner") or {}).get("uid") or "")
    if story_uid and requester.get("uid") != story_uid:
        raise HTTPException(status_code=403, detail="قائمة مشاهدي القصة متاحة لصاحب القصة فقط")

    raw = await firebase_get(f"storyViews/{safe_key(story_id)}")
    reactions = await firebase_get(f"storyReactions/{safe_key(story_id)}")
    raw = raw if isinstance(raw, dict) else {}
    reactions = reactions if isinstance(reactions, dict) else {}

    viewers = []
    for uid, row in raw.items():
        if not isinstance(row, dict):
            row = {"uid": uid}
        item = dict(row)
        item["uid"] = str(item.get("uid") or uid)
        reaction_row = reactions.get(str(uid))
        reaction = reaction_row.get("reaction", "") if isinstance(reaction_row, dict) else (reaction_row or "")
        item["reaction"] = reaction
        item["reacted"] = bool(reaction)
        item["reaction_emoji"] = reaction
        viewers.append(item)

    viewers.sort(key=lambda x: int(x.get("viewedAt", 0) or 0), reverse=True)
    metrics = await save_story_metrics(str(story_id))
    return {
        "ok": True,
        "count": len(viewers),
        "viewers": viewers,
        "views": metrics.get("views", len(viewers)),
        "viewerCount": metrics.get("viewerCount", len(viewers)),
    }


@app.post("/api/stories/{story_id}/reaction")
async def react_to_story(story_id: int, request: Request):
    payload = await request_payload(request)
    viewer_owner = await get_request_user(request, payload)
    reaction = str(payload.get("reaction") or "❤️").strip()
    if reaction not in ALLOWED_STORY_REACTIONS:
        raise HTTPException(status_code=400, detail="تفاعل غير مدعوم")

    story = await get_story(str(story_id))
    if not story:
        raise HTTPException(status_code=404, detail="القصة غير موجودة")

    await ensure_story_view(story, viewer_owner)
    uid = str(viewer_owner.get("uid") or "")
    path = f"storyReactions/{safe_key(story_id)}/{safe_key(uid)}"
    previous = await firebase_get(path)
    was_reacted = isinstance(previous, dict) and bool(previous.get("reaction"))

    await firebase_set(path, {
        "uid": uid,
        "reaction": reaction,
        "createdAt": int(previous.get("createdAt")) if isinstance(previous, dict) and previous.get("createdAt") else now_ms(),
        "updatedAt": now_ms(),
    })

    if not was_reacted:
        count = await firebase_atomic_increment(f"stories/{safe_key(story_id)}/reactionsCount", 1, 0)
    else:
        current = await firebase_get(f"stories/{safe_key(story_id)}/reactionsCount")
        count = int(current or 0)

    await firebase_update(
        f"storyViews/{safe_key(story_id)}/{safe_key(uid)}",
        {"reaction": reaction, "reacted": True, "reaction_emoji": reaction, "updatedAt": now_ms()},
    )
    await firebase_update(
        f"stories/{safe_key(story_id)}",
        {"reactionsCount": count, "reactionCount": count, "updatedAt": now_ms()},
    )
    return {"ok": True, "reacted": True, "reaction": reaction, "reactionsCount": count, "reactionCount": count, "uid": uid}


@app.delete("/api/stories/{story_id}/reaction")
async def remove_story_reaction(story_id: int, request: Request):
    payload = await request_payload(request)
    viewer_owner = await get_request_user(request, payload)
    story = await get_story(str(story_id))
    if not story:
        raise HTTPException(status_code=404, detail="القصة غير موجودة")

    uid = str(viewer_owner.get("uid") or "")
    path = f"storyReactions/{safe_key(story_id)}/{safe_key(uid)}"
    previous = await firebase_get(path)
    changed = isinstance(previous, dict) and bool(previous.get("reaction"))
    if changed:
        await firebase_delete(path)
        count = await firebase_atomic_increment(f"stories/{safe_key(story_id)}/reactionsCount", -1, 0)
    else:
        current = await firebase_get(f"stories/{safe_key(story_id)}/reactionsCount")
        count = int(current or 0)

    await firebase_update(
        f"storyViews/{safe_key(story_id)}/{safe_key(uid)}",
        {"reaction": "", "reacted": False, "reaction_emoji": "", "updatedAt": now_ms()},
    )
    await firebase_update(
        f"stories/{safe_key(story_id)}",
        {"reactionsCount": count, "reactionCount": count, "updatedAt": now_ms()},
    )
    return {"ok": True, "reacted": False, "changed": changed, "reactionsCount": count, "reactionCount": count, "uid": uid}


@app.post("/api/stories/{story_id}/reply")
async def reply_to_story(story_id: int, request: Request):
    payload = await request_payload(request)
    author = await get_request_user(request, payload)
    story = await get_story(str(story_id))
    if not story:
        raise HTTPException(status_code=404, detail="القصة غير موجودة")

    text = str(payload.get("text") or payload.get("message") or payload.get("reply") or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="نص الرد مطلوب")

    reply_id = f"{int(time.time() * 1000)}_{safe_key(author.get('uid', 'user'))}_{os.urandom(3).hex()}"
    row = {
        "id": reply_id,
        "storyId": int(story_id),
        "uid": author.get("uid", ""),
        "username": author.get("username", ""),
        "displayName": author.get("displayName", "مستخدم FOUL"),
        "display_name": author.get("display_name", "مستخدم FOUL"),
        "avatarUrl": author.get("avatarUrl", ""),
        "avatar_url": author.get("avatar_url", ""),
        "text": text,
        "createdAt": now_ms(),
    }
    await firebase_set(f"storyReplies/{safe_key(story_id)}/{safe_key(reply_id)}", row)
    count = await firebase_atomic_increment(f"stories/{safe_key(story_id)}/repliesCount", 1, 0)
    await firebase_update(f"stories/{safe_key(story_id)}", {"repliesCount": count, "updatedAt": now_ms()})
    return {"ok": True, "reply": row, "repliesCount": count}


@app.get("/api/stories/{story_id}/replies")
async def get_story_replies(story_id: int, request: Request):
    story = await get_story(str(story_id))
    if not story:
        raise HTTPException(status_code=404, detail="القصة غير موجودة")

    raw = await firebase_get(f"storyReplies/{safe_key(story_id)}")
    raw = raw if isinstance(raw, dict) else {}
    replies = [dict(row) for row in raw.values() if isinstance(row, dict)]
    replies.sort(key=lambda x: int(x.get("createdAt", 0) or 0), reverse=False)
    return {"ok": True, "count": len(replies), "replies": replies, "repliesCount": len(replies)}


@app.delete("/api/stories/{message_id}")
async def delete_story(message_id: int, uid: str = ""):
    story = await firebase_get("stories/" + safe_key(message_id))
    if not isinstance(story, dict):
        raise HTTPException(status_code=404, detail="القصة غير موجودة")

    story_uid = str(story.get("uid", ""))
    if uid and story_uid and uid != story_uid:
        raise HTTPException(status_code=403, detail="لا يمكنك حذف قصة مستخدم آخر")

    if telegram_app:
        try:
            await telegram_app.delete_messages(
                resolve_telegram_chat(story.get("chat_id", "")),
                message_id
            )
        except Exception:
            pass

    await firebase_delete("stories/" + safe_key(message_id))
    await firebase_delete("storyViews/" + safe_key(message_id))
    await firebase_delete("storyReactions/" + safe_key(message_id))
    await firebase_delete("storyReplies/" + safe_key(message_id))

    if story_uid:
        await firebase_delete("userStories/" + safe_key(story_uid) + "/" + safe_key(message_id))

    return {"ok": True, "message": "تم حذف القصة بنجاح"}


# ============================================================
# MEDIA STREAM (FULL BYTE-RANGE SUPPORT)
# ============================================================

@app.get("/stream_msg/{chat_id}/{message_id}")
async def stream_media(chat_id: str, message_id: int, request: Request):
    message, media = await get_telegram_message(chat_id, message_id)
    file_size = int(getattr(media, "file_size", 0) or 0)
    mime_type = get_media_mime(media)

    requested_range = (
        parse_range(request.headers.get("range"), file_size)
        if file_size
        else None
    )

    if requested_range:
        start, end = requested_range
        content_length = end - start + 1
        status_code = 206
    else:
        start = 0
        end = file_size - 1 if file_size else None
        content_length = file_size if file_size else None
        status_code = 200

    headers = {
        "Accept-Ranges": "bytes",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "public,max-age=3600",
        "X-Content-Type-Options": "nosniff",
        "Access-Control-Expose-Headers": "Content-Length,Content-Range,Accept-Ranges,Content-Type"
    }

    if content_length is not None:
        headers["Content-Length"] = str(content_length)

    if requested_range:
        headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"

    async def media_generator():
        async with stream_semaphore:
            offset_chunks = start // CHUNK_SIZE
            skip_bytes = start % CHUNK_SIZE
            remaining = content_length
            first_chunk = True

            async for chunk in telegram_app.stream_media(message, offset=offset_chunks):
                if remaining is not None and remaining <= 0:
                    break

                if first_chunk and skip_bytes > 0:
                    if len(chunk) <= skip_bytes:
                        skip_bytes -= len(chunk)
                        continue
                    chunk = chunk[skip_bytes:]
                    skip_bytes = 0

                first_chunk = False

                if remaining is not None and len(chunk) > remaining:
                    chunk = chunk[:remaining]

                if not chunk:
                    continue

                if remaining is not None:
                    remaining -= len(chunk)

                yield chunk

                if remaining is not None and remaining <= 0:
                    break

    return StreamingResponse(
        media_generator(),
        status_code=status_code,
        media_type=mime_type,
        headers=headers
    )


@app.head("/stream_msg/{chat_id}/{message_id}")
async def stream_head(chat_id: str, message_id: int):
    _, media = await get_telegram_message(chat_id, message_id)
    file_size = int(getattr(media, "file_size", 0) or 0)

    headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": get_media_mime(media),
        "Access-Control-Allow-Origin": "*"
    }

    if file_size:
        headers["Content-Length"] = str(file_size)

    return Response(status_code=200, headers=headers)


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    del request
    return JSONResponse(
        status_code=exc.status_code,
        content={"ok": False, "error": str(exc.detail), "status_code": exc.status_code}
    )


@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    del request
    logger.exception("Unhandled server error: %s", exc)
    return JSONResponse(
        status_code=500,
        content={"ok": False, "error": "حدث خطأ داخلي في الخادم", "status_code": 500}
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
        log_level="info",
        access_log=True,
        workers=1
    )
