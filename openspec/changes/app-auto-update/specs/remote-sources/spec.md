# Spec Delta

## MODIFIED Requirements

### Requirement: Access to the sources
All endpoints for sources SHALL require a valid token. For its sources, the frame SHALL send requests only to the servers of its sources and, while a login is being set up, to the address given for it. Requests for app updates are governed by `app-update`.

#### Scenario: Without token
- **WHEN** a client without a token lists the sources
- **THEN** the API answers 401
