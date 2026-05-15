import base64
import os
import re
import time
from dataclasses import dataclass
from typing import Set

import zmq

from db import ChatDB
from generated import chat_pb2

CHANNEL_PATTERN = re.compile(r"^[A-Za-z0-9_-]{3,20}$")
SERVER_SYNC_TOPIC = b"servers"
REPLICATION_TOPIC = b"__replication__"
DEFAULT_SYNC_BIND_ADDR = "tcp://*:5561"
DEFAULT_SYNC_CONNECT_TEMPLATE = "tcp://{server_name}:5561"
DEFAULT_SYNC_TIMEOUT_MS = 800


@dataclass
class ServerState:
    logical_clock: int
    clock_offset_ms: int
    server_rank: int
    received_client_messages: int
    coordinator_name: str
    known_ranks: dict[str, int]
    last_reported_times: dict[str, int]


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


def encode_control_message(*parts: str) -> bytes:
    return "|".join(parts).encode("utf-8")


def decode_control_message(raw: bytes) -> list[str]:
    return raw.decode("utf-8").split("|")


def encode_replication_field(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")

def decode_replication_field(value: str) -> str:
    return base64.b64decode(value.encode("ascii")).decode("utf-8")


def is_coordinator(name: str, coordinator_name: str) -> bool:
    return bool(name) and name == coordinator_name


def refresh_servers(
    ref_socket: zmq.Socket,
    logical_clock: int,
    clock_offset_ms: int,
) -> tuple[int, dict[str, int]]:
    logical_clock, list_res = send_reference_request(
        ref_socket,
        logical_clock,
        chat_pb2.ReferenceRequest(
            timestamp_ms=now_ms(clock_offset_ms),
            list_servers=chat_pb2.RefListServersRequest(),
        ),
    )
    ranks = {info.server_name: info.rank for info in list_res.list_servers.servers}
    return logical_clock, ranks


def request_clock_from_coordinator(
    context: zmq.Context,
    coordinator_name: str,
    sender_name: str,
    logical_clock: int,
    clock_offset_ms: int,
    timeout_ms: int,
    connect_template: str,
) -> tuple[int, int | None]:
    if not coordinator_name:
        return logical_clock, None

    socket = context.socket(zmq.REQ)
    socket.linger = 0
    socket.rcvtimeo = timeout_ms
    socket.sndtimeo = timeout_ms
    socket.connect(connect_template.format(server_name=coordinator_name))

    logical_clock += 1
    socket.send(
        encode_control_message(
            "CLOCK",
            sender_name,
            str(logical_clock),
            str(now_ms(clock_offset_ms)),
        )
    )

    try:
        raw = socket.recv()
    except zmq.Again:
        socket.close(0)
        return logical_clock, None
    finally:
        socket.close(0)

    parts = decode_control_message(raw)
    if len(parts) < 3:
        return logical_clock, None

    msg_type, payload, received_clock = parts[0], parts[1], parts[2]
    if received_clock.isdigit():
        logical_clock = sync_with_received(logical_clock, int(received_clock))

    if msg_type != "TIME":
        return logical_clock, None

    try:
        return logical_clock, int(payload)
    except ValueError:
        return logical_clock, None


def compute_berkeley_time(
    state: ServerState,
    sender_name: str,
    sender_time_ms: int,
    coordinator_time_ms: int,
) -> int:
    if sender_name:
        state.last_reported_times[sender_name] = sender_time_ms
    times = [coordinator_time_ms]
    times.extend(state.last_reported_times.values())
    if not times:
        return coordinator_time_ms
    return int(sum(times) / len(times))


def send_election_request(
    context: zmq.Context,
    target_name: str,
    sender_name: str,
    sender_rank: int,
    logical_clock: int,
    timeout_ms: int,
    connect_template: str,
) -> tuple[int, bool]:
    socket = context.socket(zmq.REQ)
    socket.linger = 0
    socket.rcvtimeo = timeout_ms
    socket.sndtimeo = timeout_ms
    socket.connect(connect_template.format(server_name=target_name))

    logical_clock += 1
    socket.send(
        encode_control_message("ELECTION", sender_name, str(sender_rank), str(logical_clock))
    )

    try:
        raw = socket.recv()
    except zmq.Again:
        socket.close(0)
        return logical_clock, False
    finally:
        socket.close(0)

    parts = decode_control_message(raw)
    if len(parts) < 2:
        return logical_clock, False

    msg_type, received_clock = parts[0], parts[1]
    if received_clock.isdigit():
        logical_clock = sync_with_received(logical_clock, int(received_clock))

    return logical_clock, msg_type == "OK"


def publish_replication_event(
    pub_socket: zmq.Socket,
    kind: str,
    fields: list[str],
) -> None:
    payload = "|".join([kind, *fields]).encode("utf-8")
    pub_socket.send_multipart([REPLICATION_TOPIC, payload])


def apply_replication_event(
    payload: bytes,
    db: ChatDB,
    active_users: Set[str],
    logical_clock: int,
) -> int:
    parts = payload.decode("utf-8").split("|")
    kind = parts[0]

    if kind == "LOGIN" and len(parts) == 4:
        username = decode_replication_field(parts[1])
        login_ts_ms = int(parts[2])
        received_clock = int(parts[3])
        active_users.add(username)
        db.register_login(username=username, ts_ms=login_ts_ms)
        return sync_with_received(logical_clock, received_clock)

    if kind == "CREATE_CHANNEL" and len(parts) == 5:
        channel_name = decode_replication_field(parts[1])
        created_by = decode_replication_field(parts[2])
        created_ts_ms = int(parts[3])
        received_clock = int(parts[4])
        db.create_channel(
            channel_name=channel_name,
            created_by=created_by,
            ts_ms=created_ts_ms,
        )
        return sync_with_received(logical_clock, received_clock)

    if kind == "PUBLICATION" and len(parts) == 7:
        channel_name = decode_replication_field(parts[1])
        message_text = decode_replication_field(parts[2])
        sent_by = decode_replication_field(parts[3])
        request_ts_ms = int(parts[4])
        published_ts_ms = int(parts[5])
        received_clock = int(parts[6])
        db.save_publication(
            channel_name=channel_name,
            message_text=message_text,
            sent_by=sent_by,
            request_ts_ms=request_ts_ms,
            published_ts_ms=published_ts_ms,
        )
        return sync_with_received(logical_clock, received_clock)

    return logical_clock


def handle_sync_request(
    raw: bytes,
    sync_rep_socket: zmq.Socket,
    server_name: str,
    coordinator_name: str,
    clock_offset_ms: int,
    logical_clock: int,
    server_rank: int,
    state: ServerState,
    start_election_cb,
) -> tuple[int, str]:
    parts = decode_control_message(raw)
    msg_type = parts[0] if parts else ""

    if msg_type == "CLOCK":
        return handle_clock_request(
            parts,
            sync_rep_socket,
            server_name,
            coordinator_name,
            clock_offset_ms,
            logical_clock,
            state,
        )

    if msg_type == "ELECTION":
        return handle_election_request(
            parts,
            sync_rep_socket,
            coordinator_name,
            logical_clock,
            server_rank,
            start_election_cb,
        )

    sync_rep_socket.send(encode_control_message("ERROR", "0"))
    return logical_clock, coordinator_name


def handle_clock_request(
    parts: list[str],
    sync_rep_socket: zmq.Socket,
    server_name: str,
    coordinator_name: str,
    clock_offset_ms: int,
    logical_clock: int,
    state: ServerState,
) -> tuple[int, str]:
    sender_name = parts[1] if len(parts) > 1 else ""
    received_clock = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else 0
    sender_time_ms = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else 0
    logical_clock = sync_with_received(logical_clock, received_clock)
    logical_clock += 1

    if is_coordinator(server_name, coordinator_name):
        coordinator_time_ms = now_ms(clock_offset_ms)
        berkeley_time_ms = compute_berkeley_time(
            state,
            sender_name,
            sender_time_ms or coordinator_time_ms,
            coordinator_time_ms,
        )
        sync_rep_socket.send(
            encode_control_message(
                "TIME",
                str(berkeley_time_ms),
                str(logical_clock),
            )
        )
    else:
        sync_rep_socket.send(
            encode_control_message(
                "NOT_COORDINATOR",
                coordinator_name,
                str(logical_clock),
            )
        )
    print(
        f"[{server_name}] clock_req_from={sender_name} coordinator={coordinator_name}",
        flush=True,
    )
    return logical_clock, coordinator_name


def handle_election_request(
    parts: list[str],
    sync_rep_socket: zmq.Socket,
    coordinator_name: str,
    logical_clock: int,
    server_rank: int,
    start_election_cb,
) -> tuple[int, str]:
    received_clock = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else 0
    logical_clock = sync_with_received(logical_clock, received_clock)
    logical_clock += 1
    sync_rep_socket.send(encode_control_message("OK", str(logical_clock)))

    sender_rank = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else 0
    if server_rank > sender_rank:
        start_election_cb()
    return logical_clock, coordinator_name


def handle_client_request(
    req: chat_pb2.ClientRequest,
    db: ChatDB,
    active_users: Set[str],
    pub_socket: zmq.Socket,
    clock_offset_ms: int,
    logical_clock: int,
) -> tuple[int, chat_pb2.ServerResponse, str, str, str]:
    res = chat_pb2.ServerResponse(
        request_id=req.request_id,
        timestamp_ms=now_ms(clock_offset_ms),
        ok=False,
    )
    operation = "unknown"
    username = ""
    details = ""

    payload = req.WhichOneof("payload")
    if payload == "login":
        operation = "login"
        username, details = handle_login(req, res, active_users, db)
        if res.ok:
            publish_replication_event(
                pub_socket,
                "LOGIN",
                [
                    encode_replication_field(username),
                    str(res.timestamp_ms),
                    str(logical_clock),
                ],
            )
    elif payload == "create_channel":
        operation = "create_channel"
        username, details = handle_create_channel(req, res, db)
        if res.ok:
            publish_replication_event(
                pub_socket,
                "CREATE_CHANNEL",
                [
                    encode_replication_field(req.create_channel.channel_name),
                    encode_replication_field(req.create_channel.requested_by),
                    str(res.timestamp_ms),
                    str(logical_clock),
                ],
            )
    elif payload == "list_channels":
        operation = "list_channels"
        details = handle_list_channels(res, db)
    elif payload == "publish_in_channel":
        operation = "publish_in_channel"
        logical_clock, username, details = handle_publish_in_channel(
            req,
            res,
            db,
            pub_socket,
            clock_offset_ms,
            logical_clock,
        )
    else:
        res.error_code = "UNKNOWN_REQUEST"
        res.error_message = "request payload not recognized"

    return logical_clock, res, operation, username, details


def handle_login(
    req: chat_pb2.ClientRequest,
    res: chat_pb2.ServerResponse,
    active_users: Set[str],
    db: ChatDB,
) -> tuple[str, str]:
    username = req.login.username
    details = f"username={username}"
    if username in active_users:
        res.error_code = "USER_ACTIVE"
        res.error_message = f"username '{username}' already active"
        return username, details

    active_users.add(username)
    db.register_login(username=username, ts_ms=res.timestamp_ms)
    res.ok = True
    res.login.username = username
    return username, details


def handle_create_channel(
    req: chat_pb2.ClientRequest,
    res: chat_pb2.ServerResponse,
    db: ChatDB,
) -> tuple[str, str]:
    channel_name = req.create_channel.channel_name
    requested_by = req.create_channel.requested_by
    details = f"channel={channel_name}"
    if not CHANNEL_PATTERN.fullmatch(channel_name):
        res.error_code = "INVALID_CHANNEL_NAME"
        res.error_message = "channel must match ^[A-Za-z0-9_-]{3,20}$"
        return requested_by, details

    created = db.create_channel(
        channel_name=channel_name,
        created_by=requested_by,
        ts_ms=res.timestamp_ms,
    )
    if not created:
        res.error_code = "CHANNEL_EXISTS"
        res.error_message = f"channel '{channel_name}' already exists"
        return requested_by, details

    res.ok = True
    res.create_channel.channel_name = channel_name
    return requested_by, details


def handle_list_channels(
    res: chat_pb2.ServerResponse,
    db: ChatDB,
) -> str:
    channels = db.list_channels()
    res.ok = True
    res.list_channels.channels.extend(channels)
    return "all"


def handle_publish_in_channel(
    req: chat_pb2.ClientRequest,
    res: chat_pb2.ServerResponse,
    db: ChatDB,
    pub_socket: zmq.Socket,
    clock_offset_ms: int,
    logical_clock: int,
) -> tuple[int, str, str]:
    channel_name = req.publish_in_channel.channel_name
    message_text = req.publish_in_channel.message
    requested_by = req.publish_in_channel.requested_by
    details = f"channel={channel_name};message_len={len(message_text)}"

    if not db.channel_exists(channel_name):
        res.error_code = "CHANNEL_NOT_FOUND"
        res.error_message = f"channel '{channel_name}' does not exist"
        return logical_clock, requested_by, details

    if not message_text.strip():
        res.error_code = "EMPTY_MESSAGE"
        res.error_message = "message must not be empty"
        return logical_clock, requested_by, details

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
    pub_socket.send_multipart([channel_name.encode("utf-8"), event.SerializeToString()])
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
    publish_replication_event(
        pub_socket,
        "PUBLICATION",
        [
            encode_replication_field(channel_name),
            encode_replication_field(message_text),
            encode_replication_field(requested_by),
            str(req.timestamp_ms),
            str(publish_ts),
            str(logical_clock),
        ],
    )
    return logical_clock, requested_by, details


def announce_coordinator(pub_socket: zmq.Socket, coordinator_name: str) -> None:
    if coordinator_name:
        pub_socket.send_multipart(
            [SERVER_SYNC_TOPIC, coordinator_name.encode("utf-8")]
        )


def start_election(
    context: zmq.Context,
    ref_socket: zmq.Socket,
    pub_socket: zmq.Socket,
    server_name: str,
    state: ServerState,
    server_sync_timeout_ms: int,
    server_sync_connect_template: str,
) -> None:
    state.logical_clock, state.known_ranks = refresh_servers(
        ref_socket,
        state.logical_clock,
        state.clock_offset_ms,
    )

    candidates = [name for name in state.known_ranks.keys() if name != server_name]
    higher_rank = [
        name
        for name in candidates
        if state.known_ranks.get(name, 0) > state.known_ranks.get(server_name, 0)
    ]

    if not higher_rank:
        state.coordinator_name = server_name
        announce_coordinator(pub_socket, state.coordinator_name)
        return

    responders = []
    for target in higher_rank:
        state.logical_clock, ok = send_election_request(
            context,
            target,
            server_name,
            state.server_rank,
            state.logical_clock,
            server_sync_timeout_ms,
            server_sync_connect_template,
        )
        if ok:
            responders.append(target)

    if not responders:
        state.coordinator_name = server_name
        announce_coordinator(pub_socket, state.coordinator_name)
    else:
        state.coordinator_name = ""


def process_sub_event(
    sub_socket: zmq.Socket,
    server_name: str,
    state: ServerState,
    db: ChatDB,
    active_users: Set[str],
) -> None:
    while True:
        try:
            topic, payload = sub_socket.recv_multipart(flags=zmq.DONTWAIT)
        except zmq.Again:
            break

        if topic == SERVER_SYNC_TOPIC:
            state.coordinator_name = payload.decode("utf-8")
            print(
                f"[{server_name}] coordinator={state.coordinator_name}",
                flush=True,
            )
        elif topic == REPLICATION_TOPIC:
            try:
                state.logical_clock = apply_replication_event(
                    payload,
                    db,
                    active_users,
                    state.logical_clock,
                )
            except Exception:
                continue


def process_sync_event(
    sync_rep_socket: zmq.Socket,
    server_name: str,
    state: ServerState,
    start_election_cb,
) -> None:
    raw = sync_rep_socket.recv()
    state.logical_clock, state.coordinator_name = handle_sync_request(
        raw,
        sync_rep_socket,
        server_name,
        state.coordinator_name,
        state.clock_offset_ms,
        state.logical_clock,
        state.server_rank,
        state,
        start_election_cb,
    )


def process_client_event(
    rep_socket: zmq.Socket,
    req: chat_pb2.ClientRequest,
    server_name: str,
    db: ChatDB,
    active_users: Set[str],
    pub_socket: zmq.Socket,
    state: ServerState,
) -> None:
    state.logical_clock = sync_with_received(state.logical_clock, req.logical_clock)
    state.received_client_messages += 1

    state.logical_clock, res, operation, username, details = handle_client_request(
        req,
        db,
        active_users,
        pub_socket,
        state.clock_offset_ms,
        state.logical_clock,
    )

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
    state.logical_clock += 1
    res.timestamp_ms = now_ms(state.clock_offset_ms)
    res.logical_clock = state.logical_clock
    rep_socket.send(res.SerializeToString())


def maybe_send_heartbeat(
    ref_socket: zmq.Socket,
    server_name: str,
    state: ServerState,
) -> None:
    if state.received_client_messages % 10 != 0:
        return

    state.logical_clock, _ = send_reference_request(
        ref_socket,
        state.logical_clock,
        chat_pb2.ReferenceRequest(
            timestamp_ms=now_ms(state.clock_offset_ms),
            heartbeat=chat_pb2.RefHeartbeatRequest(
                server_name=server_name,
                rank=state.server_rank,
            ),
        ),
    )


def maybe_sync_clock(
    context: zmq.Context,
    server_name: str,
    state: ServerState,
    server_sync_timeout_ms: int,
    server_sync_connect_template: str,
    start_election_cb,
) -> None:
    if state.received_client_messages % 15 != 0:
        return

    if not state.coordinator_name or state.coordinator_name not in state.known_ranks:
        start_election_cb()

    state.logical_clock, coordinator_ts = request_clock_from_coordinator(
        context,
        state.coordinator_name,
        server_name,
        state.logical_clock,
        state.clock_offset_ms,
        server_sync_timeout_ms,
        server_sync_connect_template,
    )
    if coordinator_ts is None:
        start_election_cb()
        return

    state.clock_offset_ms = coordinator_ts - int(time.time() * 1000)


def run_server() -> None:
    server_name = os.getenv("SERVER_NAME", "py-server")
    broker_backend_addr = os.getenv("BROKER_BACKEND_ADDR", "tcp://broker:5556")
    pubsub_xsub_addr = os.getenv("PUBSUB_XSUB_ADDR", "tcp://pubsub-proxy:5557")
    pubsub_xpub_addr = os.getenv("PUBSUB_XPUB_ADDR", "tcp://pubsub-proxy:5558")
    reference_addr = os.getenv("REFERENCE_ADDR", "tcp://reference:5560")
    db_path = os.getenv("DB_PATH", "/data/chat.db")
    server_sync_bind_addr = os.getenv("SERVER_SYNC_BIND_ADDR", DEFAULT_SYNC_BIND_ADDR)
    server_sync_connect_template = os.getenv(
        "SERVER_SYNC_CONNECT_TEMPLATE", DEFAULT_SYNC_CONNECT_TEMPLATE
    )
    server_sync_timeout_ms = int(
        os.getenv("SERVER_SYNC_TIMEOUT_MS", str(DEFAULT_SYNC_TIMEOUT_MS))
    )

    db = ChatDB(db_path)
    active_users: Set[str] = set()
    state = ServerState(
        logical_clock=0,
        clock_offset_ms=0,
        server_rank=0,
        received_client_messages=0,
        coordinator_name="",
        known_ranks={},
        last_reported_times={},
    )

    context = zmq.Context()
    rep_socket = context.socket(zmq.REP)
    rep_socket.connect(broker_backend_addr)

    pub_socket = context.socket(zmq.PUB)
    pub_socket.connect(pubsub_xsub_addr)

    sub_socket = context.socket(zmq.SUB)
    sub_socket.connect(pubsub_xpub_addr)
    sub_socket.setsockopt(zmq.SUBSCRIBE, SERVER_SYNC_TOPIC)
    sub_socket.setsockopt(zmq.SUBSCRIBE, REPLICATION_TOPIC)

    sync_rep_socket = context.socket(zmq.REP)
    sync_rep_socket.bind(server_sync_bind_addr)

    ref_socket = context.socket(zmq.REQ)
    ref_socket.connect(reference_addr)

    state.logical_clock, register_res = send_reference_request(
        ref_socket,
        state.logical_clock,
        chat_pb2.ReferenceRequest(
            timestamp_ms=now_ms(state.clock_offset_ms),
            register_server=chat_pb2.RefRegisterServerRequest(server_name=server_name),
        ),
    )
    if register_res.ok:
        state.server_rank = register_res.register_server.rank
        state.clock_offset_ms = register_res.reference_timestamp_ms - int(time.time() * 1000)

    state.logical_clock, state.known_ranks = refresh_servers(
        ref_socket,
        state.logical_clock,
        state.clock_offset_ms,
    )

    if state.known_ranks:
        state.coordinator_name = max(
            state.known_ranks,
            key=lambda name: state.known_ranks[name],
        )

    print(
        (
            f"[{server_name}] connected backend={broker_backend_addr} pubsub_xsub={pubsub_xsub_addr} "
            f"reference={reference_addr} rank={state.server_rank} known_servers={len(state.known_ranks)} db={db_path}"
        ),
        flush=True,
    )

    poller = zmq.Poller()
    poller.register(rep_socket, zmq.POLLIN)
    poller.register(sync_rep_socket, zmq.POLLIN)
    poller.register(sub_socket, zmq.POLLIN)

    def start_election_cb() -> None:
        start_election(
            context,
            ref_socket,
            pub_socket,
            server_name,
            state,
            server_sync_timeout_ms,
            server_sync_connect_template,
        )

    if state.coordinator_name == server_name:
        announce_coordinator(pub_socket, state.coordinator_name)

    while True:
        events = dict(poller.poll(1000))
        if sub_socket in events:
            process_sub_event(sub_socket, server_name, state, db, active_users)

        if sync_rep_socket in events:
            process_sync_event(sync_rep_socket, server_name, state, start_election_cb)

        if rep_socket in events:
            raw = rep_socket.recv()
            req = chat_pb2.ClientRequest()
            req.ParseFromString(raw)
            process_client_event(
                rep_socket,
                req,
                server_name,
                db,
                active_users,
                pub_socket,
                state,
            )
            maybe_send_heartbeat(ref_socket, server_name, state)
            maybe_sync_clock(
                context,
                server_name,
                state,
                server_sync_timeout_ms,
                server_sync_connect_template,
                start_election_cb,
            )


def main() -> None:
    run_server()


if __name__ == "__main__":
    main()
