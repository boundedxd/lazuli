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
        "name": "chat",
        "description": "FALLBACK ONLY: send normal player chat when no Lazuli command can do the requested task. Do not use this for Lazuli commands.",
        "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"], "additionalProperties": False},
        "annotations": {"destructiveHint": True, "openWorldHint": True},
    },
    {
        "name": "server_command",
        "description": "FALLBACK ONLY: send a vanilla server slash-command when Lazuli has no equivalent. This can have server-admin effects.",
        "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"], "additionalProperties": False},
        "annotations": {"destructiveHint": True, "openWorldHint": True},
    },
    {
        "name": "recent_messages",
        "description": "Read recent received chat and game messages, including server shop responses and command feedback.",
        "inputSchema": {"type": "object", "properties": {"limit": {"type": "integer", "minimum": 1, "maximum": 100}}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "look",
        "description": "FALLBACK ONLY: directly set player yaw and pitch when Lazuli has no supported look action. Do not use while a Lazuli job is active.",
        "inputSchema": {"type": "object", "properties": {"yaw": {"type": "number"}, "pitch": {"type": "number", "minimum": -90, "maximum": 90}}, "required": ["yaw", "pitch"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "move",
        "description": "FALLBACK ONLY: hold direct movement keys for a small number of ticks when Lazuli cannot perform the action. Do not use for navigation Lazuli supports.",
        "inputSchema": {"type": "object", "properties": {"forward": {"type": "boolean"}, "back": {"type": "boolean"}, "left": {"type": "boolean"}, "right": {"type": "boolean"}, "jump": {"type": "boolean"}, "sneak": {"type": "boolean"}, "sprint": {"type": "boolean"}, "ticks": {"type": "integer", "minimum": 1, "maximum": 200}}, "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "select_hotbar",
        "description": "FALLBACK ONLY: select a hotbar slot for an unsupported direct interaction.",
        "inputSchema": {"type": "object", "properties": {"slot": {"type": "integer", "minimum": 0, "maximum": 8}}, "required": ["slot"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "attack",
        "description": "FALLBACK ONLY: attack a nearby entity by UUID or the current crosshair target when Lazuli has no supported combat/job command.",
        "inputSchema": {"type": "object", "properties": {"uuid": {"type": "string"}}, "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "use_item",
        "description": "FALLBACK ONLY: use the held item when no Lazuli command supports the requested action.",
        "inputSchema": {"type": "object", "properties": {"hand": {"type": "string", "enum": ["main", "off"]}}, "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "break_block",
        "description": "FALLBACK ONLY: directly break one reachable block when Lazuli has no supported command. Prefer mine, tunnel, build, or sel first.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "place_block",
        "description": "FALLBACK ONLY: directly place the selected block against a reachable block face when Lazuli has no supported command.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}, "face": {"type": "string", "enum": ["DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"]}}, "required": ["x", "y", "z"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "interact_entity",
        "description": "FALLBACK ONLY: right-click an NPC or other nearby entity by UUID when Lazuli has no supported interaction. Use inspect/list tools first.",
        "inputSchema": {"type": "object", "properties": {"uuid": {"type": "string"}, "hand": {"type": "string", "enum": ["main", "off"]}}, "required": ["uuid"], "additionalProperties": False},
        "annotations": {"destructiveHint": True, "openWorldHint": True},
    },
    {
        "name": "inspect_block",
        "description": "Read a block's id, state, and block-entity type at coordinates.",
        "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["x", "y", "z"], "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "list_entities",
        "description": "Read nearby loaded entities, including UUIDs, type ids, names, coordinates, and distances for NPC interaction.",
        "inputSchema": {"type": "object", "properties": {"radius": {"type": "number", "minimum": 1, "maximum": 128}}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "inspect_gui",
        "description": "Read the currently open GUI/container, title, handler id, cursor stack, every slot, custom names, lore, and component data. Supports custom shop and sell GUIs.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "click_gui_slot",
        "description": "FALLBACK ONLY: click a slot in the currently open GUI using vanilla pickup, shift/quick-move, throw, swap, or clone actions. Inspect the GUI first and do not use while Lazuli is busy.",
        "inputSchema": {"type": "object", "properties": {"slot": {"type": "integer"}, "action": {"type": "string", "enum": ["pickup", "quick_move", "throw", "swap", "clone"]}, "button": {"type": "integer"}}, "required": ["slot"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "click_gui_button",
        "description": "FALLBACK ONLY: press a button in the currently open custom GUI after inspecting it, when Lazuli has no equivalent.",
        "inputSchema": {"type": "object", "properties": {"button": {"type": "integer", "minimum": 0}}, "required": ["button"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "close_gui",
        "description": "FALLBACK ONLY: close the currently open GUI when no Lazuli process owns it.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "routine_save",
        "description": "Create or replace a validated Lazuli routine from step strings. Use this when the user asks for a routine, then use routine_run only when asked to start it.",
        "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "steps": {"type": "array", "items": {"type": "string"}}}, "required": ["name", "steps"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "routine_check",
        "description": "Validate a saved routine or proposed routine steps without running it.",
        "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "steps": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "routine_list",
        "description": "List saved Lazuli routines and their steps.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "routine_run",
        "description": "Run a saved Lazuli routine after validation. Long-running routines should be monitored with get_job_status.",
        "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "times": {"type": "integer", "minimum": 0}}, "required": ["name"], "additionalProperties": False},
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "routine_delete",
        "description": "Delete a saved Lazuli routine.",
        "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"], "additionalProperties": False},
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
            "instructions": "Lazuli-first policy: use the command tool for any task Lazuli supports. Only use tools explicitly marked FALLBACK ONLY when Lazuli has no command or process for the requested action; do not use fallback movement, combat, block, entity, or GUI tools to replace Lazuli. Use get_state and get_job_status before and after jobs. Inspect entities before NPC interaction and inspect_gui before any GUI click. Long-running commands return immediately; poll get_job_status. Routines can be created with routine_save, checked with routine_check, and run only when the user asks.",
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
