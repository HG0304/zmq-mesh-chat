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
