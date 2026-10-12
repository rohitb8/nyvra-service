# openapi/

`nyvra-api-v1.yaml` — the API contract, the source of truth for the frontend's generated client.

It is produced by springdoc from the annotated controllers. To export a static copy:

```bash
./mvnw spring-boot:run                                   # in one terminal (profile: local)
curl -s http://localhost:8080/v3/api-docs.yaml -o src/main/resources/openapi/nyvra-api-v1.yaml
```

Commit the exported file when the contract changes so the frontend can regenerate without a
running backend. Breaking changes go to `/api/v2` — never mutate v1 in place.

## Per-module fragments

A change that must not edit the shared contract while other PRs are open may add a temporary
`fragments/<module>.yaml` (paths + components, refs to shared components as `nyvra-api-v1.yaml#/...`).
Fold it into `nyvra-api-v1.yaml` and delete it once the parallel PRs have merged. No fragments are pending.
