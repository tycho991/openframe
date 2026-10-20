# Spec Delta

## MODIFIED Requirements

### Requirement: Pairing in the interface
Without a valid token the interface SHALL show the pairing dialog, unless it was opened through a pairing link. After successful pairing it SHALL store the token in the browser. On a 401 response it SHALL return to the pairing dialog.

#### Scenario: First visit
- **WHEN** a browser opens the interface for the first time without a pairing link
- **THEN** the user sees the pairing dialog with the hint to scan the QR code on the frame or to enter the code below it

#### Scenario: Returning visit
- **WHEN** a paired browser opens the interface again
- **THEN** the user sees their photos directly

### Requirement: Paired devices
The interface SHALL list the paired devices and allow revoking a pairing and renaming a device. The current device SHALL be recognizable in the list.

#### Scenario: Remove old device
- **WHEN** the user chooses "Revoke pairing" for another device
- **THEN** it disappears from the list and the current device stays paired

#### Scenario: Rename device
- **WHEN** the user chooses "Rename" for the current device and saves a new name
- **THEN** the list shows the new name without reloading

## ADDED Requirements

### Requirement: Pairing from a pairing link
The interface SHALL redeem a pairing link on its own, after removing the secret from the address bar and the history. If the browser already has a valid token, it SHALL discard the secret without redeeming it. After pairing it SHALL show once under which name the device was paired, with the option to rename it.

#### Scenario: Opened through a pairing link
- **WHEN** a browser without a token opens the interface through a valid pairing link
- **THEN** the user sees their photos and the name under which the device was paired, and the address bar no longer contains the secret

#### Scenario: Expired pairing link
- **WHEN** a browser without a token opens an expired pairing link
- **THEN** the interface shows the pairing dialog with the hint to scan the QR code again

#### Scenario: Already paired
- **WHEN** a paired browser opens a valid pairing link
- **THEN** the list of paired devices stays unchanged and the link stays valid

### Requirement: Invite a device
The interface SHALL be able to create an invitation from the list of paired devices. It SHALL show the invitation as a QR code and as a selectable link, together with the remaining validity. After expiry it SHALL offer to create a new invitation.

#### Scenario: Show invitation
- **WHEN** the user chooses "Invite device" in the list of paired devices
- **THEN** the interface shows a QR code, the link and the remaining time

#### Scenario: Invitation accepted
- **WHEN** another device redeems the invitation shown
- **THEN** it appears in the list of paired devices after 30 seconds at the latest
