FROM python:3.12-slim

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*

COPY python/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY proto /app/proto
COPY python/src /app/src

RUN mkdir -p /app/src/generated \
    && protoc -I /app/proto --python_out=/app/src/generated /app/proto/chat.proto \
    && touch /app/src/generated/__init__.py

ENV PYTHONPATH=/app/src
CMD ["python", "/app/src/main.py"]
