#!/usr/bin/env python3
"""Pi stdio MCP adapter for the Lazuli localhost bridge."""
import json
import os
import sys
import urllib.error
import urllib.request

PORT = int(os.environ.get("LAZULI_MCP_PORT", "8765"))
URL = f"http://127.0.0.1:{PORT}/call"

TOOLS = [
    {
        "name": "command",
        "description": "Run any Lazuli command exactly as it would be typed in Minecraft chat. All Lazuli commands are supported, including goto, mine, getitem, craft, smelt, brew, trade, build, routines, and chained commands.",
        "inputSchema": {"type": "object", "properties": {"line": {"type": "string", "description": "Command without or with the configured prefix, for example: goto 100 64 -20"}}, "required": ["line"]},
        "annotations": {"destructiveHint": True, "openWorldHint": True},
    },
    {
        "name": "get_state",
        "description": "Read the connected Minecraft player's position, dimension, health, hunger, inventory, experience, and whether Lazuli is busy.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "get_job_status",
        "description": "Read current Lazuli pathing, active process, task, and routine status.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "get_settings",
        "description": "List Lazuli settings and current values. Optionally filter by part of a setting name.",
        "inputSchema": {"type": "object", "properties": {"filter": {"type": "string"}}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "set_setting",
        "description": "Change one Lazuli setting. The value is parsed using the same validation as #set and is saved to the Lazuli config.",
        "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "value": {"type": "string"}}, "required": ["name", "value"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "stop",
        "description": "Stop all Lazuli pathing, processes, tasks, and routines.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
]


def write(message):
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def bridge_call(name, arguments):
    payload = json.dumps({"name": name, "arguments": arguments}).encode("utf-8")
    request = urllib.request.Request(URL, data=payload, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        return {"ok": False, "error": f"Lazuli is not reachable on localhost:{PORT}. Enable mcpEnabled in Lazuli first. ({exc})"}


def result(value, is_error=False):
    text = json.dumps(value, indent=2, sort_keys=True)
    return {"isError": is_error, "content": [{"type": "text", "text": text}], "structuredContent": value}


def handle(message):
    method = message.get("method")
    request_id = message.get("id")

    if method == "notifications/initialized":
        return None
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": request_id, "result": {
            "protocolVersion": "2025-11-25",
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "lazuli", "version": "0.1.0"},
            "instructions": "Use get_state and get_job_status before and after commands. The command tool accepts every Lazuli chat command. Long-running commands return immediately; poll get_job_status.",
        }}
    if method == "ping":
        return {"jsonrpc": "2.0", "id": request_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": request_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        params = message.get("params") or {}
        name = params.get("name", "")
        arguments = params.get("arguments") or {}
        if not any(tool["name"] == name for tool in TOOLS):
            return {"jsonrpc": "2.0", "id": request_id, "result": result({"error": f"Unknown tool: {name}"}, True)}
        value = bridge_call(name, arguments)
        return {"jsonrpc": "2.0", "id": request_id, "result": result(value, not value.get("ok", False))}
    if request_id is not None:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32601, "message": f"Method not found: {method}"}}
    return None


for line in sys.stdin:
    try:
        message = json.loads(line)
        response = handle(message)
        if response is not None:
            write(response)
    except Exception as exc:
        write({"jsonrpc": "2.0", "id": None, "error": {"code": -32603, "message": str(exc)}})
