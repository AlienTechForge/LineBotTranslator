FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# Fonts used to draw the translations: Noto Sans (Latin), Noto Sans CJK (TC / SC / JP / KR faces) and DejaVu Sans as the fallback.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-core fonts-noto-cjk fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt requirements-runtime.txt requirements-inpaint.txt ./
# requirements-inpaint.txt adds onnxruntime, for the LaMa inpainting model (INPAINT_MODEL=lama); the root Dockerfile
# (production, Mode A and B) installs requirements-runtime.txt only and keeps Telea.
RUN pip install -r requirements.txt -r requirements-inpaint.txt

# The LaMa model (about 200 MB, Carve/LaMa-ONNX, Apache-2.0) is downloaded at build time into /app/models and is not
# part of the repository. modelfetch.py is copied alone, ahead of the app, so that a code change does not download
# it again. The checksum is that of the file named by the default URL: pass INPAINT_MODEL_SHA256= (empty) with another URL.
ARG INPAINT_MODEL_URL=https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx
ARG INPAINT_MODEL_SHA256=1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6
COPY app/modelfetch.py /tmp/modelfetch.py
RUN python /tmp/modelfetch.py "$INPAINT_MODEL_URL" /app/models/lama_fp32.onnx "$INPAINT_MODEL_SHA256" \
    && rm /tmp/modelfetch.py

COPY pytest.ini .
COPY app ./app
COPY tests ./tests

# This standalone image is the lab: it runs as root on purpose so that it can read the 0400 / uid 10001
# Vision credentials in the shared linebot-translator-secrets volume without copying them anywhere. The
# production image (inside the Java container, bound to 127.0.0.1) is a later change. BIND_HOST is 0.0.0.0
# here because Docker publishes the port; do not expose it to the internet without LAB_TOKEN and SERVICE_TOKEN.
ENV LAB_DATA_DIR=/app/data \
    LAB_PORT=4041 \
    BIND_HOST=0.0.0.0 \
    INPAINT_MODEL_PATH=/app/models/lama_fp32.onnx
RUN mkdir -p /app/data
EXPOSE 4041

# /healthz needs no token and shows no secret
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import os, urllib.request as u; u.urlopen('http://127.0.0.1:%s/healthz' % (os.environ.get('PORT') or os.environ.get('LAB_PORT', '4041')), timeout=4)"

# access log is off in app/__main__.py because URLs may carry ?token=
CMD ["python", "-m", "app"]
