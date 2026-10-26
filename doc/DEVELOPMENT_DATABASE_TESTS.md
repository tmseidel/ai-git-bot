# Database contract tests

The regular Spring `test` profile disables Flyway and lets Hibernate create tables.
It does not validate production migrations. `FlywayDatabaseContractTest` instead
uses the full shipped `classpath:db/migration/h2` and
`classpath:db/migration/postgresql` chains on their **actual database engines**.
It checks Flyway validation and migration replay, validates all current JPA
entities with Hibernate (`validate`, never schema generation), and proves a real
JPA commit/reload/rollback. No application context, SSH setup or provider calls run.

JPA validation derives naming defaults from the pinned Spring Boot 4.0.5
`HibernateProperties`: `PhysicalNamingStrategySnakeCaseImpl` (the same physical
strategy previously set explicitly) and `SpringImplicitNamingStrategy`. The
fixture intentionally auto-detects and asserts the dialect of each actual engine:
`H2Dialect` for H2, `PostgreSQLDialect` for PostgreSQL, matching the shipped
`application.properties` and `application-docker.properties` respectively. It does
not apply the base H2 dialect to PostgreSQL or load application profiles, external
`spring.jpa.*` overrides or customizers. This is a fresh-schema mapping and
transaction contract, not a full production application-startup check.

## Local checks (Linux, Java 21+, Maven 3.9+)

```bash
mvn --batch-mode '-Dtest=org.remus.giteabot.database.*ContractTest' clean verify
```

H2 always runs. Without `TEST_POSTGRES_URL`, PostgreSQL cases are explicitly
reported as skipped. Setting `TEST_POSTGRES_REQUIRED=true` turns missing
configuration into a failure. A configured but unreachable server always fails;
there is no fallback to H2. An explicit `TEST_POSTGRES_USER` is required when a
URL is present; `TEST_POSTGRES_PASSWORD` is optional for test-only trust auth.
Credentials belong in environment variables, not JDBC URLs, source or logs.

For an isolated, disposable PostgreSQL 17 instance, run this from a Linux shell:

```bash
set -euo pipefail
container=$(docker run --detach --rm \
  --publish 127.0.0.1::5432 \
  --env POSTGRES_DB=ai_git_bot_contracts \
  --env POSTGRES_USER=contract_test \
  --env POSTGRES_HOST_AUTH_METHOD=trust \
  --health-cmd='pg_isready -U contract_test -d ai_git_bot_contracts' \
  --health-interval=1s --health-timeout=5s --health-retries=30 \
  postgres:17-alpine)
trap 'docker stop "$container" >/dev/null' EXIT
for attempt in $(seq 1 30); do
  if [ "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy ]; then
    break
  fi
  sleep 1
done
test "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy
port=$(docker port "$container" 5432/tcp | sed 's/.*://')
TEST_POSTGRES_REQUIRED=true \
TEST_POSTGRES_URL="jdbc:postgresql://127.0.0.1:${port}/ai_git_bot_contracts" \
TEST_POSTGRES_USER=contract_test \
  mvn --batch-mode '-Dtest=org.remus.giteabot.database.*ContractTest' clean verify
```

The container exposes only loopback, has no mounted user data, and is removed on
exit. Trust auth is for this ephemeral test instance only, never deployment.
Do not point the fixture at an existing user or production database. The URL must
name `ai_git_bot_contracts` and must not contain credentials or query parameters.

## Isolation and later database contracts

`FlywayDatabase.open("h2")` owns a unique in-memory database, kept alive until
close. `open("postgresql")` owns a freshly generated `rr_contract_<uuid>` schema;
all its JDBC connections use that schema. Always use try-with-resources and close
caller-owned connections/transactions before closing the fixture. PostgreSQL
cleanup drops **only that generated schema**, even after a migration fails; it
never drops a database or calls Flyway clean. A sibling-schema sentinel test
checks isolation and scoped cleanup. The PostgreSQL role needs schema-creation
permission in this disposable database, not permission to create/drop databases.

Keep full-chain migrations serial in this dedicated database: existing PostgreSQL
guards (for example V12/V14) inspect constraint names without filtering schema.
Concurrent migrated sibling schemas can therefore mask missing constraints. The
contract class uses JUnit `@Isolated`, preventing overlap with other JUnit test
classes even if parallel execution is enabled. Apply it to every later contract
class that migrates or retains a migrated fixture; `@Execution(SAME_THREAD)` alone
does not prevent overlap with other classes. Do not run separate test JVMs against
the same disposable database. Transaction race tests can still use separate
connections inside one migrated fixture.

Use `flyway()` to migrate and `dataSource()` for real JDBC/JPA transactions in
later control, lease and CAS contract tests. Add `*ContractTest` classes under
`org.remus.giteabot.database` so Surefire and the dedicated CI job collect them;
no Testcontainers dependency or speculative ledger model is needed.

## CI and failures

`Database contracts / H2 and PostgreSQL contracts` runs on every pull request to
`main` or `develop`, without path filters or continue-on-error. Its own ephemeral
PostgreSQL service is loopback-bound, and `TEST_POSTGRES_REQUIRED=true` prevents a
missing connection from turning the job green through skipped tests. Surefire
reports are uploaded even on failure. Repository branch protection must require
this check separately; adding a workflow does not change hosting settings.

Migration or Hibernate validation failures are release blockers, not a reason to
skip old migrations, enable out-of-order execution, edit historical checksums or
relax production settings. Record the failing engine/version/migration from the
test report and handle existing SQL defects in a separately approved change.
