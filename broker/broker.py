import os

import zmq


def main() -> None:
    frontend_addr = os.getenv("BROKER_FRONTEND_ADDR", "tcp://*:5555")
    backend_addr = os.getenv("BROKER_BACKEND_ADDR", "tcp://*:5556")

    context = zmq.Context()
    frontend = context.socket(zmq.ROUTER)
    backend = context.socket(zmq.DEALER)

    frontend.bind(frontend_addr)
    backend.bind(backend_addr)

    print(f"[broker] bound frontend={frontend_addr} backend={backend_addr}")

    try:
        zmq.proxy(frontend, backend)
    except KeyboardInterrupt:
        print("[broker] interrupted")
    finally:
        frontend.close(0)
        backend.close(0)
        context.term()


if __name__ == "__main__":
    main()
