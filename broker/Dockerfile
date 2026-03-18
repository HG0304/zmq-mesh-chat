FROM python:3.12-slim

WORKDIR /app

COPY broker/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY broker/broker.py /app/broker.py

CMD ["python", "/app/broker.py"]
