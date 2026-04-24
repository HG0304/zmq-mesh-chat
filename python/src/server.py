import os
import re
import time
from typing import Set

import zmq

from db import ChatDB
from generated import chat_pb2

CHANNEL_PATTERN = re.compile(r"^[A-Za-z0-9_-]{3,20}$")


def now_ms(offset_ms: int = 0) -> int:
    return int(time.time() * 1000) + offset_ms


def sync_with_received(local_clock: int, received_clock: int) -> int:
    return max(local_clock, received_clock)


def send_reference_request(
    ref_socket: zmq.Socket,
    logical_clock: int,
    request: chat_pb2.ReferenceRequest,
) -> tuple[int, chat_pb2.ReferenceResponse]:
    logical_clock += 1
    request.logical_clock = logical_clock
    ref_socket.send(request.SerializeToString())

    raw = ref_socket.recv()
    response = chat_pb2.ReferenceResponse()
    response.ParseFromString(raw)
    logical_clock = sync_with_received(logical_clock, response.logical_clock)
    return logical_clock, response


def main() -> None:
    server_name = os.getenv("SERVER_NAME", "py-server")
    broker_backend_addr = os.getenv("BROKER_BACKEND_ADDR", "tcp://broker:5556")
    pubsub_xsub_addr = os.getenv("PUBSUB_XSUB_ADDR", "tcp://pubsub-proxy:5557")
    reference_addr = os.getenv("REFERENCE_ADDR", "tcp://reference:5560")
    db_path = os.getenv("DB_PATH", "/data/chat.db")

    db = ChatDB(db_path)
    active_users: Set[str] = set()
    logical_clock = 0
    clock_offset_ms = 0
    server_rank = 0
    received_client_messages = 0

    context = zmq.Context()
    rep_socket = context.socket(zmq.REP)
    rep_socket.connect(broker_backend_addr)

    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect(pubsub_xsub_addr)

    ref_socket = context.socket(zmq.REQ)
    ref_socket.connect(reference_addr)

    logical_clock, register_res = send_reference_request(
        ref_socket,
        logical_clock,
        chat_pb2.ReferenceRequest(
            timestamp_ms=now_ms(clock_offset_ms),
            register_server=chat_pb2.RefRegisterServerRequest(server_name=server_name),
        ),
    )
    if register_res.ok:
        server_rank = register_res.register_server.rank
        clock_offset_ms = register_res.reference_timestamp_ms - int(time.time() * 1000)

    logical_clock, list_res = send_reference_request(
        ref_socket,
        logical_clock,
        chat_pb2.ReferenceRequest(
            timestamp_ms=now_ms(clock_offset_ms),
            list_servers=chat_pb2.RefListServersRequest(),
        ),
    )

    print(
        (
            f"[{server_name}] connected backend={broker_backend_addr} pubsub_xsub={pubsub_xsub_addr} "
            f"reference={reference_addr} rank={server_rank} known_servers={len(list_res.list_servers.servers)} db={db_path}"
        ),
        flush=True,
    )

    while True:
        raw = rep_socket.recv()
        req = chat_pb2.ClientRequest()
        req.ParseFromString(raw)

        logical_clock = sync_with_received(logical_clock, req.logical_clock)
        received_client_messages += 1

        res = chat_pb2.ServerResponse(
            request_id=req.request_id,
            timestamp_ms=now_ms(clock_offset_ms),
            ok=False,
        )
        operation = "unknown"
        username = ""
        details = ""

        if req.HasField("login"):
            operation = "login"
            username = req.login.username
            details = f"username={username}"
            if username in active_users:
                res.error_code = "USER_ACTIVE"
                res.error_message = f"username '{username}' already active"
            else:
                active_users.add(username)
                db.register_login(username=username, ts_ms=res.timestamp_ms)
                res.ok = True
                res.login.username = username

        elif req.HasField("create_channel"):
            operation = "create_channel"
            channel_name = req.create_channel.channel_name
            requested_by = req.create_channel.requested_by
            username = requested_by
            details = f"channel={channel_name}"
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
            operation = "list_channels"
            details = "all"
            channels = db.list_channels()
            res.ok = True
            res.list_channels.channels.extend(channels)

        elif req.HasField("publish_in_channel"):
            operation = "publish_in_channel"
            channel_name = req.publish_in_channel.channel_name
            message_text = req.publish_in_channel.message
            requested_by = req.publish_in_channel.requested_by
            username = requested_by
            details = f"channel={channel_name};message_len={len(message_text)}"

            if not db.channel_exists(channel_name):
                res.error_code = "CHANNEL_NOT_FOUND"
                res.error_message = f"channel '{channel_name}' does not exist"
            elif not message_text.strip():
                res.error_code = "EMPTY_MESSAGE"
                res.error_message = "message must not be empty"
            else:
                logical_clock += 1
                publish_ts = now_ms(clock_offset_ms)
                event = chat_pb2.ChannelMessageEvent(
                    channel_name=channel_name,
                    message=message_text,
                    sent_by=requested_by,
                    request_timestamp_ms=req.timestamp_ms,
                    published_timestamp_ms=publish_ts,
                    logical_clock=logical_clock,
                )
                pub_socket.send_multipart(
                    [channel_name.encode("utf-8"), event.SerializeToString()]
                )
                db.save_publication(
                    channel_name=channel_name,
                    message_text=message_text,
                    sent_by=requested_by,
                    request_ts_ms=req.timestamp_ms,
                    published_ts_ms=publish_ts,
                )
                res.ok = True
                res.publish_in_channel.channel_name = channel_name
                res.publish_in_channel.published_timestamp_ms = publish_ts

        else:
            res.error_code = "UNKNOWN_REQUEST"
            res.error_message = "request payload not recognized"

        db.log_request(
            request_id=req.request_id,
            operation=operation,
            username=username,
            request_ts_ms=req.timestamp_ms,
            handled_ts_ms=res.timestamp_ms,
            ok=res.ok,
            error_code=res.error_code,
            details=details,
        )

        print(
            f"[{server_name}] recv={req} send={res}",
            flush=True,
        )
        logical_clock += 1
        res.timestamp_ms = now_ms(clock_offset_ms)
        res.logical_clock = logical_clock
        rep_socket.send(res.SerializeToString())

        if received_client_messages % 10 == 0:
            logical_clock, hb_res = send_reference_request(
                ref_socket,
                logical_clock,
                chat_pb2.ReferenceRequest(
                    timestamp_ms=now_ms(clock_offset_ms),
                    heartbeat=chat_pb2.RefHeartbeatRequest(
                        server_name=server_name,
                        rank=server_rank,
                    ),
                ),
            )
            if hb_res.ok:
                clock_offset_ms = hb_res.reference_timestamp_ms - int(time.time() * 1000)


if __name__ == "__main__":
    main()
