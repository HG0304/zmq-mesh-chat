import sqlite3
from pathlib import Path
from threading import Lock
from typing import List


class ChatDB:
    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = Lock()
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_schema(self) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS logins (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    login_ts_ms INTEGER NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    created_ts_ms INTEGER NOT NULL,
                    created_by TEXT NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS publications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    channel_name TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    sent_by TEXT NOT NULL,
                    request_ts_ms INTEGER NOT NULL,
                    published_ts_ms INTEGER NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS request_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id INTEGER NOT NULL,
                    operation TEXT NOT NULL,
                    username TEXT,
                    request_ts_ms INTEGER NOT NULL,
                    handled_ts_ms INTEGER NOT NULL,
                    ok INTEGER NOT NULL,
                    error_code TEXT,
                    details TEXT
                )
                """
            )
            conn.commit()

    def register_login(self, username: str, ts_ms: int) -> None:
        with self._lock:
            with self._connect() as conn:
                conn.execute(
                    "INSERT INTO logins (username, login_ts_ms) VALUES (?, ?)",
                    (username, ts_ms),
                )
                conn.commit()

    def create_channel(self, channel_name: str, created_by: str, ts_ms: int) -> bool:
        with self._lock:
            with self._connect() as conn:
                cur = conn.cursor()
                cur.execute("SELECT 1 FROM channels WHERE name = ?", (channel_name,))
                if cur.fetchone() is not None:
                    return False
                cur.execute(
                    "INSERT INTO channels (name, created_ts_ms, created_by) VALUES (?, ?, ?)",
                    (channel_name, ts_ms, created_by),
                )
                conn.commit()
                return True

    def list_channels(self) -> List[str]:
        with self._connect() as conn:
            cur = conn.cursor()
            cur.execute("SELECT name FROM channels ORDER BY name ASC")
            return [row["name"] for row in cur.fetchall()]

    def channel_exists(self, channel_name: str) -> bool:
        with self._connect() as conn:
            cur = conn.cursor()
            cur.execute("SELECT 1 FROM channels WHERE name = ?", (channel_name,))
            return cur.fetchone() is not None

    def save_publication(
        self,
        channel_name: str,
        message_text: str,
        sent_by: str,
        request_ts_ms: int,
        published_ts_ms: int,
    ) -> int:
        with self._lock:
            with self._connect() as conn:
                cur = conn.cursor()
                cur.execute(
                    """
                    INSERT INTO publications
                    (channel_name, message_text, sent_by, request_ts_ms, published_ts_ms)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (channel_name, message_text, sent_by, request_ts_ms, published_ts_ms),
                )
                conn.commit()
                return int(cur.lastrowid)

    def log_request(
        self,
        request_id: int,
        operation: str,
        username: str,
        request_ts_ms: int,
        handled_ts_ms: int,
        ok: bool,
        error_code: str,
        details: str,
    ) -> None:
        with self._lock:
            with self._connect() as conn:
                conn.execute(
                    """
                    INSERT INTO request_logs
                    (request_id, operation, username, request_ts_ms, handled_ts_ms, ok, error_code, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        request_id,
                        operation,
                        username,
                        request_ts_ms,
                        handled_ts_ms,
                        1 if ok else 0,
                        error_code,
                        details,
                    ),
                )
                conn.commit()
