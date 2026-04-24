import os


def main() -> None:
    mode = os.getenv("APP_MODE", "server").lower()
    if mode == "server":
        from server import main as server_main

        server_main()
    elif mode == "client":
        from client import main as client_main

        client_main()
    elif mode == "reference":
        from reference import main as reference_main

        reference_main()
    else:
        raise ValueError("APP_MODE must be 'server', 'client' or 'reference'")


if __name__ == "__main__":
    main()
