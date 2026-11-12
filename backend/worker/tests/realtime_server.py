"""A stub of what a waiting worker asks the API: login, announce, claim and realtime.

The realtime stream is the part that matters. Every ``GET /api/realtime`` is answered
from a :class:`Plan` (the next unused one, or an open stream when the plans ran out) and,
when it is a stream, handed to the test as a :class:`Connection` it can push messages
into, end cleanly or cut. The stream is chunked like the real one, so the client reads
what it reads from PocketBase. A ``POST /api/realtime`` is recorded as a subscription,
the way the client has to subscribe after the ``PB_CONNECT`` message.
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
from urllib.parse import urlsplit

from taiga_worker.realtime import SUBSCRIPTIONS

TOPIC = SUBSCRIPTIONS[0]


@dataclass
class Plan:
    """How one request for the realtime stream is answered."""

    status: int = 200
    content_type: str = "text/event-stream"
    # "hold" keeps the stream open for the test to push into, "end" finishes the body
    # at once and "cut" drops the connection without finishing it.
    then: str = "hold"
    body: bytes = b""


def message(topic: str, action: str, record: dict[str, Any]) -> bytes:
    data = json.dumps({"action": action, "record": record})
    return f"id:1\nevent:{topic}\ndata:{data}\n\n".encode()


def change(action: str, **record: Any) -> bytes:
    """One change of an analysis: the worker only needs the record's status."""
    return message(TOPIC, action, record)


def queued(analysis_id: str = "an1", recording: str = "rec1") -> bytes:
    return change("create", id=analysis_id, recording=recording, status="queued")


class Connection:
    """An open realtime stream, from the side of the test."""

    def __init__(self, client_id: str, token: str) -> None:
        self.client_id = client_id
        self.token = token
        self.outbox: queue.Queue[bytes | str] = queue.Queue()
        self.gone = threading.Event()  # the client closed its end

    def send(self, data: bytes | str) -> None:
        self.outbox.put(data if isinstance(data, bytes) else data.encode())

    def end(self) -> None:
        self.outbox.put("end")

    def cut(self) -> None:
        self.outbox.put("cut")


class RealtimeServer:
    def __init__(self) -> None:
        self.plans: list[Plan] = []
        # One per GET /api/realtime, in order.
        self.requests: list[dict[str, Any]] = []
        self.connections: list[Connection] = []
        # One per POST /api/realtime: clientId, subscriptions and the token.
        self.subscriptions: list[dict[str, Any]] = []
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
                    self._answer(401, {"status": 401, "message": "the token is not valid", "data": {}})
                    return False
                return True

            def _drain(self) -> bytes:
                length = int(self.headers.get("Content-Length") or 0)
                return self.rfile.read(length) if length else b""

            def do_POST(self) -> None:
                body = self._drain()
                if self.path == "/api/collections/workers/auth-with-password":
                    with stub.lock:
                        stub.logins += 1
                        token = f"token-{stub.logins}"
                    self._answer(200, {"token": token, "record": {"id": "w1", "email": "w@example.com"}})
                elif self.path == "/api/realtime" and self._authorized():
                    try:
                        payload = json.loads(body or b"{}")
                    except ValueError:
                        payload = {}
                    with stub.lock:
                        stub.subscriptions.append(
                            {
                                "token": self._token(),
                                "clientId": payload.get("clientId"),
                                "subscriptions": payload.get("subscriptions"),
                            }
                        )
                    self._answer(204)
                elif self.path == "/v1/analyses/claim" and self._authorized():
                    with stub.lock:
                        stub.claims.append(self._token())
                    self._answer(204)
                else:
                    self._answer(404, {"status": 404, "message": "no such route", "data": {}})

            def do_PUT(self) -> None:
                self._drain()
                if self.path == "/v1/workers/me" and self._authorized():
                    self._answer(204)
                elif self.path != "/v1/workers/me":
                    self._answer(404, {"status": 404, "message": "no such route", "data": {}})

            def do_GET(self) -> None:
                if urlsplit(self.path).path != "/api/realtime":
                    self._answer(404, {"status": 404, "message": "no such route", "data": {}})
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
                with stub.lock:
                    client_id = f"client-{len(stub.connections) + 1}"
                connection = Connection(client_id, self._token())
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
                    # PocketBase opens every connection with the id to subscribe with.
                    self._chunk(
                        (
                            f"id:{client_id}\nevent:PB_CONNECT\n"
                            f'data:{json.dumps({"clientId": client_id})}\n\n'
                        ).encode()
                    )
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
            raise AssertionError(f"expected {count} realtime stream(s), saw {len(self.connections)}")
        with self.lock:
            return list(self.connections)

    def wait_for_subscriptions(self, count: int, timeout_s: float = 10.0) -> list[dict[str, Any]]:
        """Block until ``count`` subscriptions were made; returns all of them."""

        if not wait_until(lambda: len(self.subscriptions) >= count, timeout_s):
            raise AssertionError(f"expected {count} subscription(s), saw {len(self.subscriptions)}")
        with self.lock:
            return list(self.subscriptions)


def wait_until(condition: Any, timeout_s: float = 10.0, interval_s: float = 0.01) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(interval_s)
    return bool(condition())
