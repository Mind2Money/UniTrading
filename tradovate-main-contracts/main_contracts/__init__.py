"""Standalone, read-only Tradovate completed-session volume leader lookup."""

from .core import ResolutionError
from .service import resolve_all

__all__ = ["ResolutionError", "resolve_all"]
