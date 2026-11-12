"""A stub of what a waiting worker asks the API: login, announce, claim and the event stream.

The event stream is the part that matters. Every ``GET /v1/events`` is answered from a
:class:`Plan` (the next unused one, or an open stream when the plans ran out) and, when
it is a stream, handed to the test as a :class:`Connection` it can push events into, end
cleanly or cut. The stream is chunked like the real one, so the client reads what it
reads from the Go API.
"""

from __future__ import annotations

import json
import queue
import select
import socket
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

PREAMBLE = b": connected\nretry: 3000\n\n"


@dataclass
class Plan:
    """How one request for the event stream is answered."""

    status: int = 200
    content_type: str = "text/event-stream"
    preamble: bytes = PREAMBLE
    # "hold" keeps the stream open for the test to push into, "end" finishes the body
    # at once and "cut" drops the connection without finishing it.
    then: str = "hold"
    body: bytes = b""


def event(name: str, **data: Any) -> bytes:
    return f"event: {name}\ndata: {json.dumps(data)}\n\n".encode()


def queued(analysis_id: str = "an1", recording: str = "rec1") -> bytes:
    return event("analysis", id=analysis_id, recording=recording, status="queued")


class Connection:
    """An open event stream, from the side of the test."""

    def __init__(self, token: str) -> None:
        self.token = token
        self.outbox: queue.Queue[bytes | str] = queue.Queue()
        self.gone = threading.Event()  # the client closed its end

    def send(self, data: bytes | str) -> None:
        self.outbox.put(data if isinstance(data, bytes) else data.encode())

    def end(self) -> None:
        self.outbox.put("end")

    def cut(self) -> None:
        self.outbox.put("cut")


class EventServer:
    def __init__(self) -> None:
        self.plans: list[Plan] = []
        self.requests: list[dict[str, Any]] = []  # one per GET /v1/events
        self.connections: list[Connection] = []
        self.claims: list[str] = []  # the token of every claim
        self.logins = 0
        self.revoked: set[str] = set()  # tokens the API answers 401 to
        self.lock = threading.Lock()
        self.stopping = threading.Event()

        stub = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args: Any) -> None:
                pass

            def _token(self) -> str:
                return self.headers.get("Authorization", "").removeprefix("Bearer ")

            def _answer(self, status: int, payload: Any = None) -> None:
                body = json.dumps(payload).encode() if payload is not None else b""
                self.send_response(status)
                if body:
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                if body:
                    self.wfile.write(body)
                self.close_connection = True

            def _authorized(self) -> bool:
                if self._token() in stub.revoked or not self._token():
                    self._answer(401, {"code": "unauthorized", "message": "the token is not valid"})
                    return False
                return True

            def _drain(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                if length:
                    self.rfile.read(length)

            def do_POST(self) -> None:
                self._drain()
                if self.path == "/api/collections/workers/auth-with-password":
                    with stub.lock:
                        stub.logins += 1
                        token = f"token-{stub.logins}"
                    self._answer(200, {"token": token, "record": {"id": "w1", "email": "w@example.com"}})
                elif self.path == "/v1/analyses/claim" and self._authorized():
                    with stub.lock:
                        stub.claims.append(self._token())
                    self._answer(204)
                else:
                    self._answer(404, {"code": "not_found", "message": "no such route"})

            def do_PUT(self) -> None:
                self._drain()
                if self.path == "/v1/workers/me" and self._authorized():
                    self._answer(204)
                elif self.path != "/v1/workers/me":
                    self._answer(404, {"code": "not_found", "message": "no such route"})

            def do_GET(self) -> None:
                if self.path != "/v1/events":
                    self._answer(404, {"code": "not_found", "message": "no such route"})
                    return
                with stub.lock:
                    plan = stub.plans.pop(0) if stub.plans else Plan()
                    stub.requests.append(
                        {
                            "token": self._token(),
                            "accept": self.headers.get("Accept"),
                            "accept_encoding": self.headers.get("Accept-Encoding"),
                        }
                    )
                if not self._authorized():
                    return
                if plan.status != 200:
                    self.send_response(plan.status)
                    self.send_header("Content-Type", plan.content_type)
                    self.send_header("Content-Length", str(len(plan.body)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(plan.body)
                    self.close_connection = True
                    return
                self._stream(plan)

            def _chunk(self, data: bytes) -> None:
                self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
                self.wfile.flush()

            def _client_gone(self) -> bool:
                readable, _, _ = select.select([self.connection], [], [], 0)
                if not readable:
                    return False
                try:
                    return self.connection.recv(1, socket.MSG_PEEK) == b""
                except OSError:
                    return True

            def _stream(self, plan: Plan) -> None:
                connection = Connection(self._token())
                self.send_response(200)
                self.send_header("Content-Type", plan.content_type)
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                if plan.content_type != "text/event-stream":
                    self._chunk(plan.body)
                    self.wfile.write(b"0\r\n\r\n")
                    return
                with stub.lock:
                    stub.connections.append(connection)
                try:
                    self._chunk(plan.preamble)
                    if plan.then in ("end", "cut"):
                        connection.outbox.put(plan.then)
                    while not stub.stopping.is_set():
                        try:
                            item = connection.outbox.get(timeout=0.05)
                        except queue.Empty:
                            if self._client_gone():
                                return
                            continue
                        if item == "end":
                            self.wfile.write(b"0\r\n\r\n")
                            return
                        if item == "cut":
                            return
                        self._chunk(item if isinstance(item, bytes) else item.encode())
                except OSError:
                    pass
                finally:
                    connection.gone.set()

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_port}"
        self._thread = threading.Thread(
            target=self.httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True
        )
        self._thread.start()

    def close(self) -> None:
        self.stopping.set()
        self.httpd.shutdown()
        self.httpd.server_close()

    def wait_for_connections(self, count: int, timeout_s: float = 10.0) -> list[Connection]:
        """Block until ``count`` streams were opened; returns all of them."""

        if not wait_until(lambda: len(self.connections) >= count, timeout_s):
            raise AssertionError(f"expected {count} event stream(s), saw {len(self.connections)}")
        with self.lock:
            return list(self.connections)


def wait_until(condition: Any, timeout_s: float = 10.0, interval_s: float = 0.01) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(interval_s)
    return bool(condition())
