# Proposal

## Why

Today the first pairing means copying two things off the frame's display: the address of the admin interface and a six-digit code. Both are error-prone to type on a phone, and every further device needs another trip to the frame. A QR code can carry the address and the pairing in one scan with the phone's camera app. A device that is already paired can offer the same to another device, so nobody has to walk to the frame.

## What Changes

- **Pairing by QR code on the frame**: Wherever the frame shows the pairing code today (empty state, hint after a tap, on request from the web UI), it also shows a QR code. The QR code holds the address of the admin interface and a long one-time secret in the URL fragment. Scanning it with the camera app opens the web UI, which pairs on its own.
- **Code as fallback**: The six-digit code stays below the QR code and works as before. The lockout after failed attempts applies only to the code. "Show code on frame" keeps working during the lockout and then shows the QR code alone.
- **Invitation from a paired device**: The web UI can create a QR code and a link with which one further device pairs once.
- **Device name without typing**: When pairing by QR code, the name is derived from the user agent. Every device in the list of paired devices can be renamed.
- API: `POST /api/v1/pairing` accepts a `secret` as an alternative to `code`. New are `POST /api/v1/pairing/invites` and `PATCH /api/v1/clients/{clientId}`, both with token. The set of endpoints reachable without a token stays the same.

Out of scope for this change:

- Pairing for Home Assistant and devices without a camera by request and approval, discovery on the network via mDNS/NSD. Both come with the Home Assistant change.
- Controlling the slideshow through the API. Operation on the frame stays unchanged.
- A QR scanner inside the web UI, and TLS.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `admin-access`: Pairing by QR secret and by invitation, lockout only for the code, "Show code on frame" during the lockout, renaming paired devices, invitations when revoking and resetting.
- `slideshow-display`: Hint and empty state show the QR code next to the code.
- `web-admin`: Pairing on its own from a pairing link, inviting, renaming.

## Impact

- `api/openapi.yaml`: `PairingRequest` with `code` or `secret`, new endpoints for invitations and renaming. Existing clients stay compatible.
- `:core`: QR encoder as a new dependency (must pass the API 21 check), `PairingManager` with secret, lockout per path and invitations, `ClientRegistry` with renaming, the admin address built in `:core` instead of `DisplayActivity`.
- `:android`: Hint overlay and empty state with a QR bitmap.
- `webadmin/`: Reading the fragment, invitation dialog, renaming in the device list.
- Stored tokens and clients stay valid, no migration.
- The deltas modify requirements that `bootstrap-openframe-core` adds. That change has to be archived before this one.
