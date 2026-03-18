import os
import re
import time
from typing import Set

import zmq

from db import ChatDB
from generated import chat_pb2

CHANNEL_PATTERN = re.compile(r"^[A-Za-z0-9_-]{3,20}$")


def now_ms() -> int:
    return int(time.time() * 1000)


def main() -> None:
    server_name = os.getenv("SERVER_NAME", "py-server")
    broker_backend_addr = os.getenv("BROKER_BACKEND_ADDR", "tcp://broker:5556")
    db_path = os.getenv("DB_PATH", "/data/chat.db")

    db = ChatDB(db_path)
    active_users: Set[str] = set()

    context = zmq.Context()
    socket = context.socket(zmq.REP)
    socket.connect(broker_backend_addr)

    print(
        f"[{server_name}] connected backend={broker_backend_addr} db={db_path}",
        flush=True,
    )

    while True:
        raw = socket.recv()
        req = chat_pb2.ClientRequest()
        req.ParseFromString(raw)

        res = chat_pb2.ServerResponse(
            request_id=req.request_id,
            timestamp_ms=now_ms(),
            ok=False,
        )

        if req.HasField("login"):
            username = req.login.username
            if username in active_users:
                res.error_code = "USER_ACTIVE"
                res.error_message = f"username '{username}' already active"
            else:
                active_users.add(username)
                db.register_login(username=username, ts_ms=res.timestamp_ms)
                res.ok = True
                res.login.username = username

        elif req.HasField("create_channel"):
            channel_name = req.create_channel.channel_name
            requested_by = req.create_channel.requested_by
            if not CHANNEL_PATTERN.fullmatch(channel_name):
                res.error_code = "INVALID_CHANNEL_NAME"
                res.error_message = "channel must match ^[A-Za-z0-9_-]{3,20}$"
            else:
                created = db.create_channel(
                    channel_name=channel_name,
                    created_by=requested_by,
                    ts_ms=res.timestamp_ms,
                )
                if not created:
                    res.error_code = "CHANNEL_EXISTS"
                    res.error_message = f"channel '{channel_name}' already exists"
                else:
                    res.ok = True
                    res.create_channel.channel_name = channel_name

        elif req.HasField("list_channels"):
            channels = db.list_channels()
            res.ok = True
            res.list_channels.channels.extend(channels)

        else:
            res.error_code = "UNKNOWN_REQUEST"
            res.error_message = "request payload not recognized"

        print(
            f"[{server_name}] recv={req} send={res}",
            flush=True,
        )
        socket.send(res.SerializeToString())


if __name__ == "__main__":
    main()
