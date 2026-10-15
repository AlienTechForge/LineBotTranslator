FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# Fonts used to draw the translations: Noto Sans CJK (TC / SC / JP / KR faces) and DejaVu Sans.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY pytest.ini .
COPY app ./app
COPY tests ./tests

# This is a lab tool, not the production service: it runs as root on purpose so that it can read
# the 0400 / uid 10001 Vision credentials in the shared linebot-translator-secrets volume
# without copying them anywhere. Do not expose it to the internet without LAB_TOKEN.
ENV LAB_DATA_DIR=/app/data \
    LAB_PORT=4041
RUN mkdir -p /app/data
EXPOSE 4041

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import os, urllib.request as u; u.urlopen(u.Request('http://127.0.0.1:%s/' % os.environ.get('LAB_PORT', '4041'), headers={'X-Lab-Token': os.environ.get('LAB_TOKEN', '')}), timeout=4)"

# access log is off in app/__main__.py because URLs may carry ?token=
CMD ["python", "-m", "app"]
