# Spec Delta

## ADDED Requirements

### Requirement: Default fill mode
The default value of the fill mode SHALL be `auto`. A frame that already has stored settings when it is updated SHALL keep its stored fill mode.

#### Scenario: New frame
- **WHEN** the app starts without stored settings
- **THEN** the fill mode is `auto`

#### Scenario: Update of a frame in fill
- **WHEN** a frame whose stored fill mode is `fill` is updated to a version with `auto`
- **THEN** its fill mode is still `fill`
