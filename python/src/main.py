import os


def main() -> None:
    mode = os.getenv("APP_MODE", "server").lower()
    if mode == "server":
        from server import main as server_main

        server_main()
    elif mode == "client":
        from client import main as client_main

        client_main()
    else:
        raise ValueError("APP_MODE must be 'server' or 'client'")


if __name__ == "__main__":
    main()
