"""ASGI entrypoint."""

from shortlink_agent.api.app import create_app

app = create_app()
