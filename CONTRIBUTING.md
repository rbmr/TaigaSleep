# Contributing

- Read [`docs/vision.md`](docs/vision.md) first. Changes should fit what the
  project is for.
- `main` is protected. After the initial commit, open a pull request from a
  feature branch; do not push directly to `main`. Merges are merge commits only;
  squash and rebase are rejected.
- Use [Conventional Commits](https://www.conventionalcommits.org/):
  `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.
- Keep pull requests focused and small.
- There are no version numbers yet. Every push to `main` replaces the `latest`
  release and republishes the backend images; see
  [`docs/releases.md`](docs/releases.md).
- Never commit recordings, tokens, or other personal/health data.
- Do not commit generated build output.
