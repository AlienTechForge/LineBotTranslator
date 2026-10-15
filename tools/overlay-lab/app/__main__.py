"""`python -m app` starts the lab server (access log off: URLs may carry ?token=)."""

import uvicorn

from .config import REQUEST_TIMEOUT_S, get_settings

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=get_settings().port,
        timeout_keep_alive=int(REQUEST_TIMEOUT_S),
        access_log=False,
        log_level="info",
    )
