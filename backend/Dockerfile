# Slim runtime image. Every runtime dependency (asyncpg, bcrypt, grpcio via
# firebase-admin) ships a manylinux wheel for CPython 3.12, so there is no
# compiler in here and nothing to strip out afterwards.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

# The `migrate` service in docker-compose.yml overrides this with
# `alembic upgrade head`; the api service runs the server.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
