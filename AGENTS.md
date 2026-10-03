# Repository Guidelines

## Project Structure & Module Organization

This repository currently has no source code, tests, assets, or build configuration. Establish a clear layout when adding the first implementation: keep application code in `src/`, automated tests in `tests/`, static assets in `assets/`, and supporting documentation in `docs/`. Adapt these directories to the chosen framework and explain the actual layout in `README.md`.

## Build, Test, and Development Commands

No build, test, or local development commands are configured yet. When introducing tooling, add reproducible commands to the project manifest and document them in `README.md`, including required runtime versions and dependency installation steps. Commands such as `npm run dev`, `npm test`, and `npm run build` are examples only; use them only if the project adopts the corresponding scripts.

## Coding Style & Naming Conventions

Follow the conventions of the language and framework selected for the project. Configure a formatter and linter alongside the initial implementation, and use their settings consistently. Keep names descriptive, organize modules by responsibility, and avoid unrelated formatting changes. Use UTF-8 for text files and keep Markdown instructions concise, with fenced code blocks for runnable examples.

## Testing Guidelines

No testing framework or coverage threshold is established. Add an appropriate test runner with the first executable code. Test observable behavior, include relevant edge cases, and add regression tests for bug fixes. Use descriptive test names and the selected framework's file naming conventions. Document the exact test command and any required fixtures or environment settings.

## Commit & Pull Request Guidelines

No Git history is available to establish a commit convention. Use short, imperative commit subjects, such as `Add initial project setup`, and keep each commit focused. Pull requests should explain the purpose, summarize changes, link relevant issues, and report validation performed. Include screenshots for visual changes and identify checks that could not be run.

## Security & Configuration

Keep credentials, local environment files, dependencies, and generated outputs out of version control. Add appropriate ignore rules when tooling is introduced. Provide placeholder configuration examples and document required variables without including real secrets.

## Agent Workflow

After completing each requested set of changes in this repository, run appropriate checks, review the diff, and create one Git commit representing that version. Include the completed changes and use a concise, descriptive commit message. If there are no changes, do not create an empty commit. Keep commits local unless the user requests a push.
