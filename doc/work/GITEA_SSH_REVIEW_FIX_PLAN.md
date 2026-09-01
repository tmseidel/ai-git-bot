# Gitea SSH Review Fix Plan

## Ziel

Die Findings aus dem Review von `feature/gitea-ssh-transport` gegen
`origin/develop` vollständig beheben. Der Umfang umfasst die konkreten
Verhaltensfehler, die harten Standardsabweichungen sowie die beiden bestätigten
Designverbesserungen:

- Checkout-Details hinter einer tieferen Repository-Seam verbergen.
- Die duplizierte Managed-Key-Zustandsprüfung zentralisieren.

## Leitentscheidungen

- `WorkspaceService` erhält einen `RepositoryApiClient`, nicht mehr separat URL
  und Credentials.
- `RepositoryApiClient` löst die vollständige, credential-freie Repository-URL
  provider-spezifisch auf.
- Es entsteht kein zusätzlicher Checkout-DTO und keine neue Workspace-Fassade.
- Remote-Key-Operationen bleiben retryfähig und werden erst nach einem lokal
  persistierten sicheren Zustand ausgeführt.
- `V47` bis `V50` werden nicht verändert. Neue Schemaänderungen erfolgen als
  `V51` für H2 und PostgreSQL.
- Textuell gerenderte Übersetzungen bleiben reine Texte; es wird kein
  `innerHTML` oder zusätzliches HTML-Rendering eingeführt.

## 1. Checkout-Seam vertiefen

### Repository-Interface

`RepositoryApiClient` erhält eine eindeutig benannte owner-spezifische Methode,
die immer eine vollständige Repository-Remote-URL zurückgibt. Die
HTTP-Standardimplementierung übernimmt die bisher in `WorkspaceService`
liegende Zusammensetzung aus Provider-Basis-URL, Owner und Repository.

`GiteaApiClient` überschreibt diese Auflösung bei SSH und liest weiterhin das
repository-spezifische `ssh_url` aus der Gitea-API.

### Workspace-Interface

Die öffentliche Signatur wird auf den semantischen Checkout-Kontext reduziert:

```java
WorkspaceResult prepareWorkspace(
        RepositoryApiClient repositoryClient,
        String owner,
        String repo,
        String branch,
        Long prNumber);
```

`WorkspaceService` löst URL und Credentials genau einmal auf, bevor ein
Workspace angelegt wird. Fehler bei der Provider-Auflösung werden geloggt und
als `WorkspaceResult.failure(...)` zurückgegeben. Der bisherige öffentliche
Overload mit separater URL und `RepositoryCredentials` entfällt.

Alle elf Produktionsaufrufe in den Issue-, Writer-, Triage-, Review-, I18n-,
README-, Unit-Test- und E2E-Promotion-Flows werden auf die neue Seam umgestellt.
Die zugehörigen Mockito-Tests prüfen danach nicht mehr die internen URL- und
Credential-Parameter des Workspace-Moduls.

## 2. SSH-Remote validieren

Die Endpoint-Parsing-Logik aus `SshCommandService` wird als gemeinsamer,
provider-neutraler `SshEndpoint` im Repository-Modul bereitgestellt.
`SshCommandService`, `GiteaApiClient` und die SSH-Erkennung in
`WorkspaceService` verwenden anschließend dieselbe Implementierung.

Für jedes von Gitea gelieferte `ssh_url` gelten folgende Regeln:

- Zulässig sind nur `ssh://`- und SCP-artige SSH-Remotes.
- Leere Werte, HTTP(S), `file://`, lokale Pfade und Git-Optionen werden vor dem
  Workspace-Aufruf abgelehnt.
- Host und Port werden bei kanonischen, automatisch erzeugten
  `known_hosts`-Einträgen vorab gegen den bestätigten Endpoint geprüft.
- Gehashte oder wildcard-basierte manuelle `known_hosts`-Einträge bleiben
  kompatibel; dort erzwingt OpenSSH die Bindung über
  `StrictHostKeyChecking=yes`.

Damit kann ein API-Wert den bestätigten SSH-Pfad nicht mehr durch ein anderes
Git-Protokoll umgehen.

## 3. Managed-Key-Zustand zentralisieren

`GitIntegration` erhält ein einziges Prädikat:

```java
boolean hasManagedSshKeyTracking();
```

Es liefert `true`, sobald mindestens eines der Felder `sshRemoteKeyId`,
`sshRemoteKeyOwnerId` oder `sshRemoteKeyTitle` gesetzt ist. Das ist wichtig,
weil auch teilweise persistierte Creation-Marker absichtlich retryfähig sind.

Das Prädikat ersetzt die duplizierten Prüfungen in:

- `GitIntegrationController`
- `GiteaSshSetupService`
- `git-integrations/form.html`

Tests decken den leeren Zustand sowie jedes einzelne gesetzte Tracking-Feld ab.

## 4. Veraltete Formulare und Löschrennen verhindern

### Schema und Entity

Die neuen Migrationen
`h2/V51__git_integration_concurrency.sql` und
`postgresql/V51__git_integration_concurrency.sql` ergänzen:

```sql
lock_version BIGINT NOT NULL DEFAULT 0
deletion_pending BOOLEAN NOT NULL DEFAULT FALSE
```

`GitIntegration` bildet `lock_version` mit `@Version` ab. Das Edit-Formular und
die SSH-Bestätigungsseite übermitteln die geladene Version als Hidden Field.

### Verwaltete Updates

Formgebundene, detached Entities werden nicht mehr direkt gespeichert.
`GitIntegrationService` lädt die aktuelle Entity unter Lock, prüft die
übermittelte Version und übernimmt nur editierbare Felder. Transport-,
Credential- und Remote-Key-Übergänge bleiben dadurch serverseitig kontrolliert.

Jeder mehrstufige Setup- oder Cleanup-Schritt übergibt die nach dem vorherigen
Persistieren entstandene Version an den nächsten Schritt. Eine Abweichung wird
vor dem nächsten Remote-Aufruf abgelehnt. Wurde bereits ein neuer Gitea-Key
angelegt, wird er über den vorhandenen Rollback-Pfad wieder entfernt.

### Dauerhafte Löschsperre

`GitIntegrationRepository` erhält einen `PESSIMISTIC_WRITE`-Lookup nach dem im
Projekt vorhandenen Agent-Session-Muster.

`beginDelete(id)` führt in einer kurzen Transaktion aus:

1. Integration sperren.
2. Bot-Referenzen prüfen.
3. `deletion_pending=true` setzen.
4. Lokal auf HTTP zurückfallen und SSH-Credentials löschen.
5. Remote-Key-Tracking für Cleanup und Retry behalten.

Danach erfolgt die idempotente Remote-Key-Löschung. Bei Erfolg sperrt
`completeDelete(id)` erneut, prüft den Zustand und löscht die Integration. Bei
Fehler oder Prozessabbruch bleibt die Integration sicher deaktiviert und kann
über denselben Delete-Flow erneut bereinigt werden.

`BotService.save()` sperrt die ausgewählte Git-Integration innerhalb derselben
Bot-Transaktion und lehnt `deletion_pending` ab. Dadurch gilt unabhängig von
der Reihenfolge:

- Gewinnt die Bot-Zuweisung, sieht die Löschung die Referenz vor jedem
  Remote-Aufruf.
- Gewinnt die Löschung, kann nach dem persistierten Fence kein Bot mehr
  zugewiesen werden.

Normale Saves und SSH-Setup werden für eine löschende Integration ebenfalls
abgelehnt. Ein erneuter Delete-Aufruf bleibt als Cleanup-Retry erlaubt.

## 5. Restliche Findings beheben

### Best-effort-Promotion

`SuitePromotionService.promote()` wird ein kleiner `try/catch`-Wrapper um eine
private Implementierung. Unerwartete Runtime-Fehler werden geloggt und als
`Outcome.failure(...)` zurückgegeben, sodass der bereits abgeschlossene
E2E-Workflow seinen Terminalstatus behält.

### Übersetzungen

Aus `help.ai.apiKeyRequiredNew` und `help.hook.customHeaders` werden in allen
sieben Message-Bundles ausschließlich die `<code>`-Tags entfernt. Die
Templates bleiben bei `textContent` beziehungsweise `th:text`.

### Native Voraussetzungen

`doc/LOCAL_DEVELOPMENT.md` und `doc/GITEA_SETUP.md` dokumentieren, dass native
oder JAR-basierte SSH-Nutzung `ssh`, `ssh-keygen` und `ssh-keyscan` auf `PATH`
benötigt. Die offizielle Docker-Image-Installation von `openssh-client` wird
erwähnt.

### Standards

- Die öffentlichen Javadocs von `WorkspaceService`, `RepositoryApiClient` und
  `RepositoryCredentials` beschreiben HTTP- und SSH-Credentials korrekt.
- Aktuelle, nicht archivierte Sicherheitsdokumentation behauptet Verschlüsselung
  nur noch bei konfiguriertem `APP_ENCRYPTION_KEY`.
- `GiteaClientFactoryTest` verwendet entsprechend `CONTRIBUTING.md`
  `@InjectMocks`.
- Die Verlagerung verwalteter Zustandsübergänge in `GitIntegrationService`
  reduziert gleichzeitig die Feature-Envy-Logik im Controller.

## 6. Tests

### Repository und Workspace

- HTTP-Auflösung liefert eine vollständige Repository-URL.
- Gitea-SSH akzeptiert passende SCP- und `ssh://`-URLs.
- HTTP(S), `file://`, lokale und malformed Gitea-`ssh_url` werden abgelehnt.
- Host- und Port-Abweichungen zu kanonischen `known_hosts` werden abgelehnt.
- `WorkspaceService` fragt URL und Credentials nur einmal ab, auch beim
  PR-Fallback.
- Fehler bei der Checkout-Auflösung erzeugen ein fehlgeschlagenes
  `WorkspaceResult`, ohne einen Workspace anzulegen.

### Integration und Remote-Key-Lifecycle

- Ein veraltetes Edit-Formular löst keinen Remote-Cleanup aus.
- Eine veraltete SSH-Bestätigung registriert keinen neuen Key.
- Ein Versionskonflikt nach Remote-Key-Erstellung löst den Rollback aus.
- Alle partiellen Managed-Key-Marker werden erkannt.
- Cleanup-Fehler behalten Tracking und sicheren HTTP-Zustand.
- Ein erneuter Delete-Aufruf setzt den Cleanup fort.

### Konkurrenz

- Bot-Zuweisung vor Löschbeginn verhindert jeden Remote-Aufruf.
- Löschbeginn vor Bot-Zuweisung blockiert die neue Zuordnung.
- H2-Transaktionstests verwenden zwei Threads und Latches für beide
  Reihenfolgen.

### Weitere Regressionen

- Promotion-Exceptions werden als `Outcome.failure` zurückgegeben.
- Alle unterstützten Locale-Bundles liefern für die beiden Text-Keys kein
  HTML-Markup.
- Bitbucket-Username- und HTTP-Credential-Verhalten bleiben unverändert.
- V50-zu-V51-Migration setzt für bestehende Zeilen `lock_version=0` und
  `deletion_pending=false`.

## 7. Verifikation

Nach der Implementierung werden ausgeführt:

1. Fokussierte Tests für Repository, SSH, Workspace, Git-Integration, Bot und
   Suite-Promotion.
2. Vollständige Maven-Test-Suite; falls Maven lokal fehlt, in einem
   Maven-21-Container.
3. H2-Migrationstest und PostgreSQL-Flyway-Smoke-Test.
4. `docker compose config --quiet`.
5. `git diff --check origin/develop...HEAD`.
6. Kontrolle, dass nur beabsichtigte Dateien geändert wurden und die
   vorhandenen ungetrackten Browser-Artefakte unberührt bleiben.

## Abnahmekriterien

- Kein Workflow zerlegt den Repository-Client mehr in Clone-URL und
  Credentials.
- Jedes Gitea-SSH-Remote ist syntaktisch SSH und kann den bestätigten
  `known_hosts`-Pfad nicht umgehen.
- Managed-Key-Tracking wird an genau einer Stelle definiert.
- Stale Formulare verursachen keine lokalen oder entfernten Nebenwirkungen.
- Bot-Zuweisung und Integrationslöschung sind auch über mehrere App-Instanzen
  deterministisch geordnet.
- Remote-Key-Cleanup bleibt bei Fehlern und Prozessabbrüchen retryfähig.
- Promotion-Fehler verändern keinen bereits erreichten Workflow-Terminalstatus.
- UI-Texte zeigen keine HTML-Tags an.
- Native SSH-Voraussetzungen und Credential-Sicherheitsverhalten sind korrekt
  dokumentiert.
- Die vollständige Test-Suite und beide Flyway-Dialekte sind grün.
