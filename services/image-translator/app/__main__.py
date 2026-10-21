"""`python -m app` starts the server: /v1, /healthz and the lab under /lab (access log off: URLs may carry ?token=)."""

import uvicorn

from .config import REQUEST_TIMEOUT_S, get_settings

if __name__ == "__main__":
    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.bind_host,
        port=settings.port,
        timeout_keep_alive=int(REQUEST_TIMEOUT_S),
        access_log=False,
        log_level="info",
    )
