#!/usr/bin/env python3
"""Generate the Postman collection from the OpenAPI contract.

Usage (from the repo root):
    python3 postman/generate_collection.py

Reads src/main/resources/openapi/nyvra-api-v1.yaml and rewrites
postman/nyvra-api-v1.postman_collection.json so the collection never drifts from the contract.
Needs PyYAML (pip install pyyaml).
"""
import json
import pathlib
import re
import uuid

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = ROOT / "src/main/resources/openapi/nyvra-api-v1.yaml"
OUT = ROOT / "postman/nyvra-api-v1.postman_collection.json"

spec = yaml.safe_load(SPEC.read_text())

# collection path segment -> variable holding that resource's id
ID_VARS = {
    "accounts": "accountId", "sources": "sourceId", "entries": "entryId", "expenses": "expenseId",
    "categories": "categoryId", "categorisation-rules": "ruleId", "manual-items": "itemId",
    "consents": "consentId", "fetches": "fetchId", "instruments": "instrumentId",
    "holdings": "holdingId", "goals": "goalId", "contributions": "contributionId",
}

PREREQUEST = r"""
// Fetches a Keycloak token (password grant) when none is set or it is about to expire.
// To use a token you obtained elsewhere, set `accessToken` and leave `tokenExpiresAt` empty.
const exp = Number(pm.variables.get('tokenExpiresAt') || 0);
const manual = pm.variables.get('accessToken') && !exp;
if (manual || (pm.variables.get('accessToken') && Date.now() < exp - 30000)) { return; }
const user = pm.variables.get('username');
if (!user) { return; }
const url = `${pm.variables.get('keycloakUrl')}/realms/${pm.variables.get('realm')}/protocol/openid-connect/token`;
pm.sendRequest({
  url, method: 'POST',
  header: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: { mode: 'urlencoded', urlencoded: [
    { key: 'grant_type', value: 'password' },
    { key: 'client_id', value: pm.variables.get('clientId') },
    { key: 'username', value: user },
    { key: 'password', value: pm.variables.get('password') } ] }
}, (err, res) => {
  if (err || res.code !== 200) { console.warn('Token request failed', err || res.text()); return; }
  const j = res.json();
  pm.collectionVariables.set('accessToken', j.access_token);
  pm.collectionVariables.set('tokenExpiresAt', String(Date.now() + j.expires_in * 1000));
});
""".strip()

TOKEN_TEST = r"""
pm.test('token issued', () => pm.response.to.have.status(200));
const j = pm.response.json();
pm.collectionVariables.set('accessToken', j.access_token);
pm.collectionVariables.set('tokenExpiresAt', String(Date.now() + j.expires_in * 1000));
""".strip()


def deref(node):
    if isinstance(node, dict) and "$ref" in node:
        cur = spec
        for part in node["$ref"].lstrip("#/").split("/"):
            cur = cur[part]
        return deref(cur)
    return node


def example(schema, depth=0, name=None):
    """Build a plausible JSON example from a schema (required fields, or a few if none)."""
    s = deref(schema)
    if "example" in s:
        return s["example"]
    if "allOf" in s:
        merged = {}
        for part in s["allOf"]:
            v = example(part, depth, name)
            if isinstance(v, dict):
                merged.update(v)
            else:
                return v
        return merged
    for key in ("oneOf", "anyOf"):
        if key in s:
            return example(s[key][0], depth, name)
    if "enum" in s:
        return s["enum"][0]
    t = s.get("type")
    f = s.get("format")
    if t == "object" or "properties" in s:
        if depth > 4:
            return {}
        props = s.get("properties", {})
        req = [p for p in s.get("required", []) if p in props]
        names = req or list(props)[:3]
        return {n: example(props[n], depth + 1, n) for n in names if not deref(props[n]).get("readOnly")}
    if t == "array":
        return [example(s.get("items", {}), depth + 1, name)]
    if t == "integer":
        return s.get("minimum", 1) or 1
    if t == "number":
        return 1
    if t == "boolean":
        return True
    if f == "uuid":
        return "{{" + (name if name and name.endswith("Id") else "accountId") + "}}"
    if f == "date":
        return "2026-10-01"
    if f == "date-time":
        return "2026-10-01T09:30:00Z"
    if f == "email":
        return "demo@nyvra.local"
    if name and name.endswith("Id"):
        return "{{" + name + "}}"
    if name == "inrPerUnit":
        return "83.500000"
    if name == "currency":
        return "INR"
    if "\\d" in s.get("pattern", ""):
        return "100.00"
    return "string"


def param_value(p):
    s = deref(p.get("schema", {}))
    name = p["name"]
    if p["in"] == "path":
        return "{{" + name + "}}"
    if "default" in s:
        return str(s["default"])
    if "example" in s:
        return str(s["example"])
    if "enum" in s:
        return str(s["enum"][0])
    if s.get("format") == "date":
        return "2026-10-01"
    if s.get("format") == "uuid":
        return "{{" + name + "}}"
    return {"month": "2026-10", "amount": "1000.00", "from": "2026-01-01", "to": "2026-10-01",
            "currency": "USD", "window": "12m", "q": "search"}.get(name, "")


def build_request(path, method, op):
    params = [deref(p) for p in op.get("parameters", [])]
    url_path = re.sub(r"\{(\w+)\}", r"{{\1}}", path).strip("/").split("/")
    query = [{"key": p["name"], "value": param_value(p), "disabled": not p.get("required", False),
              "description": p.get("description", "")} for p in params if p["in"] == "query"]
    headers = []
    for p in params:
        if p["in"] == "header":
            headers.append({"key": p["name"], "value": "{{$guid}}", "description": p.get("description", ""),
                            "disabled": not p.get("required", False)})
    req = {
        "method": method.upper(),
        "header": headers,
        "url": {"raw": "{{baseUrl}}/" + "/".join(url_path) + (
            "?" + "&".join(f"{q['key']}={q['value']}" for q in query if not q["disabled"])
            if any(not q["disabled"] for q in query) else ""),
            "host": ["{{baseUrl}}"], "path": url_path, "query": query,
            "variable": [{"key": p["name"], "value": "{{" + p["name"] + "}}"} for p in params if p["in"] == "path"]},
        "description": (op.get("summary", "") + ("\n\n" + op["description"].strip() if op.get("description") else "")),
    }
    # the path variables are already {{x}} in the path; drop the redundant :var block
    req["url"].pop("variable")
    body = op.get("requestBody")
    if body:
        content = body["content"]
        if "application/json" in content:
            req["header"].append({"key": "Content-Type", "value": "application/json"})
            req["body"] = {"mode": "raw", "raw": json.dumps(example(content["application/json"]["schema"]), indent=2),
                           "options": {"raw": {"language": "json"}}}
        elif "multipart/form-data" in content:
            req["body"] = {"mode": "formdata", "formdata": [{"key": "file", "type": "file", "src": []}]}
    return req


def build_tests(path, method, op):
    """Capture ids from create responses so follow-up requests work without manual copying."""
    segs = path.strip("/").split("/")
    lines = []
    if method == "post" and not segs[-1].startswith("{") and segs[-1] in ID_VARS and "requestBody" in op:
        lines.append(f"if (pm.response.code === 201) pm.collectionVariables.set('{ID_VARS[segs[-1]]}', pm.response.json().id);")
    elif method == "get" and segs[-1] in ID_VARS:
        v = ID_VARS[segs[-1]]
        lines.append(f"if (pm.response.code === 200) {{ const c = pm.response.json().content; if (c && c.length) pm.collectionVariables.set('{v}', c[0].id); }}")
    elif method == "get" and path in ("/api/v1/transactions", "/api/v1/insights"):
        v = "transactionId" if segs[-1] == "transactions" else "insightId"
        lines.append(f"if (pm.response.code === 200) {{ const c = pm.response.json().content; if (c && c.length) pm.collectionVariables.set('{v}', c[0].id); }}")
    out = ["pm.test('status is 2xx', () => pm.expect(pm.response.code).to.be.within(200, 299));"]
    return out + lines


folders = {}
all_vars = set()
for path, item in spec["paths"].items():
    for method in ("get", "post", "put", "patch", "delete"):
        if method not in item:
            continue
        op = item[method]
        tag = (op.get("tags") or ["Other"])[0]
        req = build_request(path, method, op)
        all_vars.update(re.findall(r"\{\{(\w+)\}\}", json.dumps(req)))
        folders.setdefault(tag, []).append({
            "name": f"{op.get('summary', op['operationId'])}",
            "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": build_tests(path, method, op)}}],
            "request": req,
        })

builtin = {"baseUrl", "$guid", "accessToken"}
id_vars = sorted(v for v in all_vars if v not in builtin)

auth_folder = {
    "name": "Auth",
    "item": [{
        "name": "Get access token (Keycloak password grant)",
        "description": "Optional: the collection pre-request script fetches a token automatically when `username` is set. "
                       "Run this to refresh by hand. Use the `admin` user for ADMIN-only endpoints (price/FX writes, price-feed sync).",
        "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": TOKEN_TEST.splitlines()}}],
        "request": {
            "auth": {"type": "noauth"},
            "method": "POST",
            "header": [],
            "url": {"raw": "{{keycloakUrl}}/realms/{{realm}}/protocol/openid-connect/token",
                    "host": ["{{keycloakUrl}}"], "path": ["realms", "{{realm}}", "protocol", "openid-connect", "token"]},
            "body": {"mode": "urlencoded", "urlencoded": [
                {"key": "grant_type", "value": "password"}, {"key": "client_id", "value": "{{clientId}}"},
                {"key": "username", "value": "{{username}}"}, {"key": "password", "value": "{{password}}"}]},
        },
    }],
}

collection = {
    "info": {
        "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "nyvra-api-v1")),
        "name": "nyvra API v1",
        "description": "Every `/api/v1` operation of nyvra-service, generated from `nyvra-api-v1.yaml` by "
                       "`postman/generate_collection.py`. Pick an environment (local / dev / prod), set credentials, send. "
                       "Create requests store the new id in a collection variable so follow-up calls work in order.",
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]},
    "event": [{"listen": "prerequest", "script": {"type": "text/javascript", "exec": PREREQUEST.splitlines()}}],
    "variable": [{"key": "accessToken", "value": ""}, {"key": "tokenExpiresAt", "value": ""}]
                + [{"key": v, "value": ""} for v in id_vars],
    "item": [auth_folder] + [{"name": t, "item": items} for t, items in folders.items()],
}
OUT.write_text(json.dumps(collection, indent=2) + "\n")
print(f"{sum(len(v) for v in folders.values())} requests, {len(folders)} folders, ids: {', '.join(id_vars)}")
