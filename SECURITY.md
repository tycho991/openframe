# Security policy

## Supported versions

Only the latest release gets security fixes. Until the first release, that is the current `main`.

## Reporting a vulnerability

Please do not open a public issue. Report it privately through GitHub's
[private vulnerability reporting](https://github.com/tycho991/openframe/security/advisories/new)
("Security" › "Report a vulnerability").

openframe is a one-person project. You get a reply within 14 days on a best-effort basis; a fix and an advisory
follow as time allows, and you are credited unless you prefer not to be.

## Scope

In scope:

- the HTTP API on the LAN and the static files of the web UI served by the frame;
- pairing (code, QR code, invitations) and the handling of tokens and image signatures;
- the update check and the verification of its signature, once the updater exists;
- the stored Nextcloud credentials (app passwords, share links and share passwords).

Out of scope:

- attacks that need physical access to the frame, or a rooted or otherwise modified device;
- a frame exposed to the internet through port forwarding or UPnP, which the README warns against;
- vulnerabilities of Android itself, for example in its native image decoders; the README names the residual risk.

What the frame protects and what it does not is described in the [security model](README.md#security-model) of the
README.
