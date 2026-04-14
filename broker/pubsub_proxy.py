import os

import zmq


def main() -> None:
    xsub_addr = os.getenv("PUBSUB_XSUB_ADDR", "tcp://*:5557")
    xpub_addr = os.getenv("PUBSUB_XPUB_ADDR", "tcp://*:5558")

    context = zmq.Context()
    xsub = context.socket(zmq.XSUB)
    xpub = context.socket(zmq.XPUB)

    xsub.bind(xsub_addr)
    xpub.bind(xpub_addr)

    print(f"[pubsub-proxy] bound xsub={xsub_addr} xpub={xpub_addr}")

    try:
        zmq.proxy(xsub, xpub)
    except KeyboardInterrupt:
        print("[pubsub-proxy] interrupted")
    finally:
        xsub.close(0)
        xpub.close(0)
        context.term()


if __name__ == "__main__":
    main()
