import os
import random
import time

import zmq

from generated import chat_pb2


def now_ms() -> int:
    return int(time.time() * 1000)


def main() -> None:
    client_name = os.getenv("CLIENT_NAME", "py-client")
    username = os.getenv("USERNAME", client_name)
    broker_frontend_addr = os.getenv("BROKER_FRONTEND_ADDR", "tcp://broker:5555")
    create_channel_name = os.getenv("CREATE_CHANNEL_NAME", f"{username}_ch")
    request_id = random.randint(1, 10_000)

    context = zmq.Context()
    socket = context.socket(zmq.REQ)
    socket.connect(broker_frontend_addr)

    print(
        f"[{client_name}] connected frontend={broker_frontend_addr} username={username}",
        flush=True,
    )

    while True:
        request_id += 1
        login_req = chat_pb2.ClientRequest(
            request_id=request_id,
            timestamp_ms=now_ms(),
            login=chat_pb2.LoginRequest(username=username),
        )
        print(f"[{client_name}] send={login_req}", flush=True)
        socket.send(login_req.SerializeToString())
        login_res = chat_pb2.ServerResponse()
        login_res.ParseFromString(socket.recv())
        print(f"[{client_name}] recv={login_res}", flush=True)

        if not login_res.ok:
            time.sleep(5)
            continue

        request_id += 1
        create_req = chat_pb2.ClientRequest(
            request_id=request_id,
            timestamp_ms=now_ms(),
            create_channel=chat_pb2.CreateChannelRequest(
                channel_name=create_channel_name,
                requested_by=username,
            ),
        )
        print(f"[{client_name}] send={create_req}", flush=True)
        socket.send(create_req.SerializeToString())
        create_res = chat_pb2.ServerResponse()
        create_res.ParseFromString(socket.recv())
        print(f"[{client_name}] recv={create_res}", flush=True)

        request_id += 1
        list_req = chat_pb2.ClientRequest(
            request_id=request_id,
            timestamp_ms=now_ms(),
            list_channels=chat_pb2.ListChannelsRequest(),
        )
        print(f"[{client_name}] send={list_req}", flush=True)
        socket.send(list_req.SerializeToString())
        list_res = chat_pb2.ServerResponse()
        list_res.ParseFromString(socket.recv())
        print(f"[{client_name}] recv={list_res}", flush=True)

        time.sleep(10)


if __name__ == "__main__":
    main()
