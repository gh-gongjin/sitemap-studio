# Security Policy

## Reporting a Vulnerability

Please report security issues **privately** via
[GitHub security advisories](https://github.com/gh-gongjin/sitemap-studio/security/advisories/new)
— do **not** open a public issue.

A maintainer will acknowledge reports within a few days and coordinate a fix
and disclosure.

## Scope notes

- The crawler enforces an SSRF guard (`CrawlUrlPolicy`): internal, loopback,
  and `file://` targets are refused by design. Bypassing it is a security bug.
- Push credentials (SFTP/FTP/FTPS passwords) are stored encrypted at rest
  (AES-256-GCM, key in `./data/push.key`, never in the repository).
- Passwords are BCrypt-hashed; reports and auto-update sites are isolated per
  account (cross-account access returns 404).
