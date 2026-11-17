import json

from tests.conftest import headers_for


class StubService:
    """Duck-typed ComposioService for the snapshot logic."""

    def __init__(self, connections, tools, schemas, results):
        self.available = True
        self._connections = connections
        self._tools = tools
        self._schemas = schemas
        self._results = results

    async def list_connections(self, user_id, db):
        return self._connections

    async def search_tools(self, query, toolkits=None, limit=25):
        return self._tools

    async def get_tool_schema(self, slug):
        return self._schemas.get(slug)

    async def execute(self, user_id, db, slug, args):
        return self._results.get(slug, {"successful": False, "data_excerpt": None})


def _install(app, service):
    app.state.composio = service


def test_no_connections_reports_not_connected(app, client, user_a):
    _install(app, StubService([], [], {}, {}))
    out = client.get("/v1/home/data", headers=headers_for(user_a)).json()
    assert out["calendar"]["status"] == "not_connected"
    assert out["mail"]["status"] == "not_connected"
    assert out["calendar"]["rows"] == []


def test_calendar_events_rendered_and_mail_not_connected(app, client, user_a):
    events = {"events": [{"summary": "Standup", "start": {"dateTime": "2026-11-18T09:00:00+00:00"},
                          "end": {"dateTime": "2026-11-18T09:30:00+00:00"}, "location": "Ring"}]}
    stub = StubService(
        connections=[{"id": "a1", "toolkit": "googlecalendar", "status": "ACTIVE", "created_at": ""}],
        tools=[{"slug": "GOOGLECALENDAR_LIST_EVENTS", "name": "List events", "toolkit": "googlecalendar"}],
        schemas={"GOOGLECALENDAR_LIST_EVENTS": {"slug": "GOOGLECALENDAR_LIST_EVENTS",
                                                "toolkit": "googlecalendar",
                                                "input_parameters": [{"name": "time_min", "required": False}]}},
        results={"GOOGLECALENDAR_LIST_EVENTS": {"successful": True,
                                                "data_excerpt": json.dumps(events)}},
    )
    _install(app, stub)
    out = client.get("/v1/home/data", headers=headers_for(user_a)).json()
    assert out["calendar"]["status"] == "ok"
    assert out["calendar"]["rows"][0]["title"] == "Standup"
    assert out["calendar"]["rows"][0]["start"].startswith("2026-11-18T09:00")
    assert out["mail"]["status"] == "not_connected"


def test_expired_connection_asks_for_reconnect(app, client, user_a):
    stub = StubService(
        connections=[{"id": "a1", "toolkit": "googlecalendar", "status": "EXPIRED", "created_at": ""}],
        tools=[], schemas={}, results={},
    )
    _install(app, stub)
    out = client.get("/v1/home/data", headers=headers_for(user_a)).json()
    assert out["calendar"]["status"] == "reconnect"


def test_composio_unavailable_degrades(app, client, user_a):
    class Off:
        available = False

    _install(app, Off())
    out = client.get("/v1/home/data", headers=headers_for(user_a)).json()
    assert out["calendar"]["status"] == "unavailable"
    assert out["mail"]["status"] == "unavailable"
