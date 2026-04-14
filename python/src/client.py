import os
import random
import string
import time

import zmq

from generated import chat_pb2


def now_ms() -> int:
    return int(time.time() * 1000)


def random_channel_name(username: str) -> str:
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
    base = f"{username}_{suffix}"
    return base[:20]


def random_message() -> str:
    words = [
        "mesh",
        "chat",
        "zero",
        "broker",
        "canal",
        "publish",
        "subscribe",
        "bot",
        "distribuido",
    ]
    random.shuffle(words)
    return " ".join(words[:4])


def drain_sub_messages(sub_socket: zmq.Socket, client_name: str) -> None:
    while True:
        try:
            frames = sub_socket.recv_multipart(flags=zmq.DONTWAIT)
        except zmq.Again:
            break

        recv_ts_ms = now_ms()
        if len(frames) != 2:
            continue

        _, payload = frames
        event = chat_pb2.ChannelMessageEvent()
        event.ParseFromString(payload)
        print(
            (
                f"[{client_name}] sub_recv "
                f"channel={event.channel_name} "
                f"msg={event.message!r} "
                f"sent_ts_ms={event.request_timestamp_ms} "
                f"recv_ts_ms={recv_ts_ms}"
            ),
            flush=True,
        )


def main() -> None:
    client_name = os.getenv("CLIENT_NAME", "py-client")
    username = os.getenv("USERNAME", client_name)
    broker_frontend_addr = os.getenv("BROKER_FRONTEND_ADDR", "tcp://broker:5555")
    pubsub_xpub_addr = os.getenv("PUBSUB_XPUB_ADDR", "tcp://pubsub-proxy:5558")
    create_channel_name = os.getenv("CREATE_CHANNEL_NAME", f"{username}_ch")
    request_id = random.randint(1, 10_000)

    context = zmq.Context()
    req_socket = context.socket(zmq.REQ)
    req_socket.connect(broker_frontend_addr)

    sub_socket = context.socket(zmq.SUB)
    sub_socket.connect(pubsub_xpub_addr)

    print(
        (
            f"[{client_name}] connected frontend={broker_frontend_addr} "
            f"pubsub_xpub={pubsub_xpub_addr} username={username}"
        ),
        flush=True,
    )

    subscribed_channels: set[str] = set()

    while True:
        request_id += 1
        login_req = chat_pb2.ClientRequest(
            request_id=request_id,
            timestamp_ms=now_ms(),
            login=chat_pb2.LoginRequest(username=username),
        )
        print(f"[{client_name}] send={login_req}", flush=True)
        req_socket.send(login_req.SerializeToString())
        login_res = chat_pb2.ServerResponse()
        login_res.ParseFromString(req_socket.recv())
        print(f"[{client_name}] recv={login_res}", flush=True)

        if not login_res.ok:
            time.sleep(5)
            continue

        break

    while True:
        request_id += 1
        list_req = chat_pb2.ClientRequest(
            request_id=request_id,
            timestamp_ms=now_ms(),
            list_channels=chat_pb2.ListChannelsRequest(),
        )
        print(f"[{client_name}] send={list_req}", flush=True)
        req_socket.send(list_req.SerializeToString())
        list_res = chat_pb2.ServerResponse()
        list_res.ParseFromString(req_socket.recv())
        print(f"[{client_name}] recv={list_res}", flush=True)

        channels = list(list_res.list_channels.channels) if list_res.ok else []

        if len(channels) < 5:
            request_id += 1
            new_channel = create_channel_name if create_channel_name not in channels else random_channel_name(username)
            create_req = chat_pb2.ClientRequest(
                request_id=request_id,
                timestamp_ms=now_ms(),
                create_channel=chat_pb2.CreateChannelRequest(
                    channel_name=new_channel,
                    requested_by=username,
                ),
            )
            print(f"[{client_name}] send={create_req}", flush=True)
            req_socket.send(create_req.SerializeToString())
            create_res = chat_pb2.ServerResponse()
            create_res.ParseFromString(req_socket.recv())
            print(f"[{client_name}] recv={create_res}", flush=True)

        request_id += 1
        refresh_req = chat_pb2.ClientRequest(
            request_id=request_id,
            timestamp_ms=now_ms(),
            list_channels=chat_pb2.ListChannelsRequest(),
        )
        print(f"[{client_name}] send={refresh_req}", flush=True)
        req_socket.send(refresh_req.SerializeToString())
        refresh_res = chat_pb2.ServerResponse()
        refresh_res.ParseFromString(req_socket.recv())
        print(f"[{client_name}] recv={refresh_res}", flush=True)
        channels = list(refresh_res.list_channels.channels) if refresh_res.ok else []

        if len(subscribed_channels) < 3:
            candidates = [ch for ch in channels if ch not in subscribed_channels]
            if candidates:
                selected = random.choice(candidates)
                sub_socket.setsockopt(zmq.SUBSCRIBE, selected.encode("utf-8"))
                subscribed_channels.add(selected)
                print(
                    f"[{client_name}] subscribed channel={selected} total={len(subscribed_channels)}",
                    flush=True,
                )
                drain_sub_messages(sub_socket, client_name)

        if not channels:
            time.sleep(1)
            continue

        selected_channel = random.choice(channels)
        for _ in range(10):
            request_id += 1
            publish_req = chat_pb2.ClientRequest(
                request_id=request_id,
                timestamp_ms=now_ms(),
                publish_in_channel=chat_pb2.PublishInChannelRequest(
                    channel_name=selected_channel,
                    message=random_message(),
                    requested_by=username,
                ),
            )
            print(f"[{client_name}] send={publish_req}", flush=True)
            req_socket.send(publish_req.SerializeToString())
            publish_res = chat_pb2.ServerResponse()
            publish_res.ParseFromString(req_socket.recv())
            print(f"[{client_name}] recv={publish_res}", flush=True)
            drain_sub_messages(sub_socket, client_name)
            time.sleep(1)


if __name__ == "__main__":
    main()
