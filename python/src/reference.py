import os
import time

import zmq

from generated import chat_pb2


def now_ms() -> int:
    return int(time.time() * 1000)


def main() -> None:
    bind_addr = os.getenv("REFERENCE_BIND_ADDR", "tcp://*:5560")
    ttl_ms = int(os.getenv("REFERENCE_TTL_MS", "30000"))

    context = zmq.Context()
    socket = context.socket(zmq.REP)
    socket.bind(bind_addr)

    print(f"[reference] listening={bind_addr} ttl_ms={ttl_ms}", flush=True)

    ranks_by_name: dict[str, int] = {}
    heartbeat_ts_by_name: dict[str, int] = {}
    logical_clock = 0

    while True:
        raw = socket.recv()
        req = chat_pb2.ReferenceRequest()
        req.ParseFromString(raw)

        logical_clock = max(logical_clock, req.logical_clock)

        current_ms = now_ms()
        stale = [
            name
            for name, ts in heartbeat_ts_by_name.items()
            if (current_ms - ts) > ttl_ms
        ]
        for name in stale:
            heartbeat_ts_by_name.pop(name, None)

        logical_clock += 1
        res = chat_pb2.ReferenceResponse(
            timestamp_ms=current_ms,
            logical_clock=logical_clock,
            ok=False,
            reference_timestamp_ms=current_ms,
        )

        if req.HasField("register_server"):
            server_name = req.register_server.server_name
            if server_name not in ranks_by_name:
                ranks_by_name[server_name] = len(ranks_by_name) + 1
            heartbeat_ts_by_name[server_name] = current_ms
            res.ok = True
            res.register_server.rank = ranks_by_name[server_name]

        elif req.HasField("list_servers"):
            res.ok = True
            for server_name, rank in sorted(
                ranks_by_name.items(), key=lambda item: item[1]
            ):
                if server_name in heartbeat_ts_by_name:
                    info = res.list_servers.servers.add()
                    info.server_name = server_name
                    info.rank = rank

        elif req.HasField("heartbeat"):
            server_name = req.heartbeat.server_name
            rank = req.heartbeat.rank

            if server_name not in ranks_by_name:
                ranks_by_name[server_name] = rank if rank > 0 else len(ranks_by_name) + 1

            if rank > 0 and ranks_by_name.get(server_name) != rank:
                res.error_code = "RANK_MISMATCH"
                res.error_message = (
                    f"server '{server_name}' expected rank={ranks_by_name[server_name]} got={rank}"
                )
            else:
                heartbeat_ts_by_name[server_name] = current_ms
                res.ok = True
                res.heartbeat.status = "OK"

        else:
            res.error_code = "UNKNOWN_REQUEST"
            res.error_message = "reference request payload not recognized"

        print(
            f"[reference] recv={req} send={res} active={len(heartbeat_ts_by_name)}",
            flush=True,
        )
        socket.send(res.SerializeToString())


if __name__ == "__main__":
    main()
