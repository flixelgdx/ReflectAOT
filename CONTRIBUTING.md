# Contributing

## Branching

Use `develop` or `main` as the integration target for pull requests, depending on what this repository’s maintainers use as the default line of development.

## Build and tests

Run the full test suite from the repository root:

```bash
./gradlew test
```

Fix failures before opening or updating a pull request.

## Commits

Write commit messages in the imperative mood (for example “Add local name tracing to scanner”), in American English, and keep each commit focused on one logical change.

If the organization requires a Developer Certificate of Origin sign-off, use:

```bash
git commit -s
```

## Style

Follow the repository `.editorconfig`: two-space indentation where configured, UTF-8, LF endings, and no star imports for Java.

Public Java API on `reflectaot-runtime` should have clear Javadoc consistent with existing classes.

## Reviews

Keep changes easy to review: prefer small, well-explained diffs over large mixed refactors.
