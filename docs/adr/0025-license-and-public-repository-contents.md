# ADR-0025: The license, and what the public repository contains

- **Status:** Accepted
- **Date:** 2026-10-04

## Context
M7 makes the repository public. Two decisions had been left open until then (CLAUDE.md, "Still open"):
- **The license.** Without one, the code is public to read but all rights are reserved.
- **Whether `CLAUDE.md` and `docs/learning/` stay in the public repository.** `CLAUDE.md` holds the rules every change follows and how the project is built. `docs/learning/` holds the primers and teach-back questions. The milestone records, now in `docs/milestone-log.md`, are the same kind of file.

**One fact decides most of the second question:** `CLAUDE.md` and `docs/learning/` have been in every commit since `38d7f5b` (M0). Making a repository public publishes its whole history, so deleting files from the current tree doesn't hide them. And the docs cite commit hashes throughout (`cd11a2e`, `47b1c5f`, …), which any rewrite of history would break.

## Options considered

**License:**
- **MIT.** Short and permissive: anyone may use the code if they keep the copyright notice, and there's no warranty.
- **Apache-2.0.** Also permissive, plus an explicit patent grant. It's what Spring uses, but it's longer and has rules for a NOTICE file.
- **No license.** Readable, not reusable. GitHub's terms still let people view and fork it on GitHub.

None of these stops someone copying the code into their own portfolio, so that isn't a factor.

**What's public:**
- **A. Publish everything, history included.**
- **B. Delete `CLAUDE.md` and `docs/learning/` before publishing.** They stay in the history, where anyone can find them, and files visibly removed before publishing read worse than files left in the open.
- **C. Rewrite the history (or publish a fresh repository) without them.** Destructive. It changes every commit hash the docs cite, and loses the milestone-by-milestone commit trail, which itself shows how the system was built up in small, tested steps.
- **D. Keep the repository private** and share it on request. That defeats the purpose of a portfolio.

## Decision
- **MIT,** with the copyright held by Jhan Modi (`LICENSE`). `pom.xml` and `docs/openapi.yaml` name it too.
- **Everything is published, history included (A).** `CLAUDE.md`, `docs/learning/`, and `docs/milestone-log.md` stay.
- **`SECURITY.md`** asks for vulnerabilities to be reported privately, through GitHub's private vulnerability reporting, and lists the known limitations (no rate limiting yet, migrations at startup, no TLS locally).
- **Before the switch, a safety pass.** gitleaks scans every commit, as CI already does on each push. A manual review checks for what gitleaks can't see: `.env` never committed, no personal data, no local paths, and commit emails that are GitHub's no-reply addresses. The results are in the milestone log.
- **The owner makes the repository public,** and turns on GitHub's secret scanning with push protection, Dependabot alerts, private vulnerability reporting, and branch protection on `main` that requires CI.

## Consequences
- **The process is visible.** A reader can see how every change was made: the proposal, the options and the decision, the tests, the planted-bug checks, and the teach-back.
- **The roadmap is now only status and plans.** Working notes go in the milestone log, so the roadmap stays readable for someone new.
- **Anything committed from now on is public at once.** The gitleaks job and push protection are the guard against a committed secret. If one is ever committed, it must be rotated, not just deleted: it stays in the history and in any clone.
- **Every new file needs a license-compatible origin.** Code copied from elsewhere must have a compatible license and keep its notice.

## How to explain it
"I picked MIT because it's the simplest license to explain, and for a portfolio the patent clause in Apache-2.0 doesn't matter. Making a repo public publishes its whole history, so 'hiding' a file by deleting it does nothing. I published everything, including the process docs, and ran a full-history secret scan first."
