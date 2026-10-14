# Postman collection

`nyvra-api-v1.postman_collection.json` covers every `/api/v1` operation (89 requests, one folder per
contract tag). Each environment file only holds connection values, so the same collection runs anywhere.

## Use it

1. In Postman, import the collection and the environment files you need
   (`local`, `dev`, `prod` `.postman_environment.json`), then select an environment.
2. Local: start the stack with `./start-local-server.sh`; the `local` environment already holds the
   seeded `demo` / `demo` user, and the collection fetches a Keycloak token on its own when
   `username` is set. Switch `username`/`password` to `admin` / `admin` for the ADMIN-only calls
   (record quote, set FX rate, price-feed sync).
3. dev / prod: replace the placeholder `baseUrl` / `keycloakUrl`, then either fill `username` and
   `password` (needs the password grant enabled on the client) or paste a token into `accessToken`
   (leave `tokenExpiresAt` empty). Never commit real credentials; the `password` and `accessToken`
   values are secrets, keep them in your local Postman copy only.

## Variables

| Variable | Where | Purpose |
|---|---|---|
| `baseUrl`, `keycloakUrl`, `realm`, `clientId` | environment | where the API and Keycloak live |
| `username`, `password` | environment | credentials for the automatic token fetch |
| `accessToken`, `tokenExpiresAt` | collection (set by script) | bearer token; refreshed 30 s before expiry |
| `accountId`, `entryId`, `goalId`, ... | collection | ids; create requests store the new id, list requests store the first item, so follow-up calls run in order. Edit by hand to target a specific record |

Writes send `Idempotency-Key: {{$guid}}` where the contract allows it.

## Regenerating

The collection is generated from the contract so it cannot drift:

```bash
pip install pyyaml
python3 postman/generate_collection.py
```

Rerun it whenever `src/main/resources/openapi/nyvra-api-v1.yaml` changes and commit the result.
