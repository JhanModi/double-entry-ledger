# Security policy

This is a portfolio project. It isn't deployed anywhere, and it handles no real money and no real personal data. Its security model is still taken seriously: see [design.md §8](docs/design.md#8-security) and the ADRs it links to.

## Reporting a vulnerability

Please report it privately, through GitHub's private vulnerability reporting ("Report a vulnerability" on the repository's Security tab), not in a public issue. Include the steps to reproduce it and what you think the impact is.

Only the `main` branch is maintained.

## Known limitations

These are deliberate, documented, and scheduled ([roadmap](docs/roadmap.md)):
- **No rate limiting yet** (M15b). Authentication and money-moving endpoints can be called without limit, so the app must not be deployed publicly before then.
- **Migrations run at application startup,** so the app process holds the database owner's credentials. SQL injection through the app's restricted login is contained; code execution inside the app process isn't. Running migrations as a separate deployment step closes this (M16, [ADR-0015](docs/adr/0015-least-privilege-database-roles.md)).
- **No TLS in local development.** API keys travel over plain HTTP on `localhost`; any deployment must terminate TLS in front of the app.
