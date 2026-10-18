# openspec-project-template

Vorlage für Projekte mit [OpenSpec](https://github.com/Fission-AI/OpenSpec) und
Claude Code (Desktop-App, Code-Tab). Die Maschinen-Seite (mise, Node,
Claude-Code-Einstellungen, globale Skills) kommt aus `ansible-terminal`
(`playbooks/dev_tools.yml`). Planung und Hintergründe:
`Planung OpenSpec + Claude Code Multi-Maschinen-Setup v4.md`.

## Inhalt

| Datei | Zweck |
|---|---|
| `.mise.toml` | Node und OpenSpec exakt gepinnt, Telemetrie aus, Task `openspec-sync` |
| `.gitignore` | `.claude/settings.local.json` (persönliche Freigaben) bleibt lokal |
| `renovate.json` | Updates per PR, 7 Tage Mindestalter, Node-Majors nur nach Freigabe |
| `.github/workflows/openspec-drift.yml` | prüft, dass die committeten Skills und Commands genau zur gepinnten Version passen |

## Neues Projekt

```bash
gh repo create tycho991/<name> --private --template tycho991/openspec-project-template --clone
cd <name>
mise trust
mise install
mise run openspec-profile
openspec init --tools claude
git status
```

`openspec-profile` setzt Profil und Delivery, bevor die ersten Dateien
entstehen. `openspec-sync` ruft es danach jedes Mal selbst auf. OpenSpec 1.14.3 legt an:

- `openspec/` mit Specs, Changes und `config.yaml`
- `.claude/commands/opsx/<id>.md`, in Claude Code als `/opsx:<id>`
- `.claude/skills/openspec-*/` für die Skills

Danach gezielt committen:

```bash
git add openspec .claude
git commit -m "OpenSpec einrichten"
```

`mise trust` ist pro Projekt einmal nötig. Ein pauschales Vertrauen für ganze
Verzeichnisse (`trusted_config_paths`) ist bewusst nicht eingerichtet.

Dann den Projektordner im Code-Tab der Desktop-App öffnen.

## Bestehendes Projekt

`.mise.toml`, `.gitignore`, `renovate.json` und den Workflow (nach
`.github/workflows/`) ins Projekt kopieren, dann wie oben ab `mise trust`. Hat
das Projekt schon einen `openspec/`-Ordner, statt `openspec init` nur
`mise run openspec-sync`.

## OpenSpec aktualisieren

Renovate öffnet einen PR, der `.mise.toml` erhöht. Im Branch dieses PRs:

```bash
mise install
mise run openspec-sync
git add -A && git commit -m "OpenSpec-Skills aktualisieren"
```

Erst dann wird die Drift-Prüfung grün. Für mehrere Projekte auf einmal:
`scripts/bump-openspec.sh` in `ansible-terminal`.

## Eigene Skills im Projekt

- Ordner `.claude/skills/<name>/` mit `SKILL.md`. Der Ordnername ist der
  Skill-Name: `^[a-z0-9]+(-[a-z0-9]+)*$`, nicht mit `openspec-` beginnend.
- `description` im Frontmatter ist Pflicht, sonst wählt Claude den Skill nicht
  von selbst aus. Die Drift-Prüfung meldet beides.
- Persönliche Skills (`~/.claude/skills/`) haben Vorrang vor Projekt-Skills
  gleichen Namens.

## Arbeitsablauf pro Änderung

1. `/opsx:propose` im Code-Tab (Proposal, Tasks und Spec-Deltas unter
   `openspec/changes/<name>/`)
2. `openspec validate <name>` und `openspec show <name>`
3. `/opsx:apply` zum Umsetzen
4. `/opsx:archive` bzw. `openspec archive <name> --yes`

Die genauen Commands stehen in `.claude/commands/opsx/` und richten sich nach
der gepinnten OpenSpec-Version.
