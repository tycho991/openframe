# Contributing

Thank you for your interest in openframe. It is a one-person project, so replies can take a while.

- **Licence.** Contributions are accepted under the [MIT licence](LICENSE) of this repository. By opening a pull
  request you agree that your contribution is licensed under it.
- **Security issues** are never reported as a public issue or pull request; see [SECURITY.md](SECURITY.md).
- **Bigger changes** start as an issue, so that the idea can be discussed before you write code. Changes are planned
  with OpenSpec (see "Workflow with OpenSpec" in the [README](README.md)).
- **Checks.** `./gradlew check` and, in `webadmin/`, `npm run lint`, `npm run typecheck`, `npm test` and
  `npm run e2e` must pass. CI runs the same checks; for pull requests from outside contributors it starts only
  after the maintainer has approved the run.
- **Language.** Code, comments, docs and commit messages are in English; German appears only in the German
  translations of the interface.
