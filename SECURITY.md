# Security Policy

Strimma handles medical data (continuous glucose monitor readings). Security issues are taken seriously.

## Reporting a Vulnerability

**Do not open a public issue for security vulnerabilities.**

Instead, use [GitHub's private vulnerability reporting](https://github.com/psjostrom/strimma/security/advisories/new) to report the issue confidentially.

Include:
- Description of the vulnerability
- Steps to reproduce
- Impact assessment (data exposure, integrity, availability)

You'll receive a response within 7 days.

## Scope

- Nightscout credential handling (API secret storage and transmission)
- Notification data parsing (glucose values from other apps)
- Local database access (Room)
- Any path that could leak health data to unintended recipients
