# `.github/workflows-pending/` — one `git mv` away from running

These are finished, reviewed GitHub Actions workflows. They are **not running**,
because they are not in `.github/workflows/`.

## Why they are parked here

The credential that lands commits in this repo is the `gh` CLI OAuth token, whose
scopes are `admin:public_key, delete_repo, gist, read:org, repo, write:packages`.
GitHub refuses to let an OAuth app create or update anything under
`.github/workflows/` without the `workflow` scope — over git push and over the
Contents API alike:

    ! [remote rejected] refusing to allow an OAuth App to create or update workflow
      `.github/workflows/refresh-feeds.yml` without `workflow` scope

That is a deliberate GitHub protection (a token that can write code should not
silently gain the ability to write CI that runs with repo secrets), so the fix is to
grant the scope, not to work around it.

## Activating them

```sh
gh auth refresh -s workflow          # one browser confirmation
git mv .github/workflows-pending/refresh-feeds.yml .github/workflows/
git commit -m "ci: activate weekly feed re-measurement" && git push
```

Then check the first scheduled run under the repo's Actions tab, or trigger it by
hand with `gh workflow run refresh-feeds.yml`.

## Why parked in the repo rather than left outside it

A workflow that exists only on someone's laptop is worse than one that exists but is
switched off: the second is reviewable, diffable and one command from working, while
the first quietly evaporates. Parking them here also keeps the honest state visible —
ADR-2607253200 records that the freshness gap is designed and written but **not yet
closed**, and an empty `.github/workflows/` is what that looks like from the outside.
