# Spec Delta

## ADDED Requirements

### Requirement: Caption settings
The display settings SHALL include the caption: album title on or off (default off), place on or off (default off) and the corner (default bottom left). The corner SHALL take the same four values as the clock's position. The comment itself SHALL have no switch: a photo's comment is always shown. Caption and clock SHALL be allowed to share a corner.

#### Scenario: Defaults
- **WHEN** a frame starts without stored settings, or with settings from before the caption existed
- **THEN** album title and place are off, the caption's corner is bottom left, and all other settings keep their values

#### Scenario: Same corner as the clock
- **WHEN** a client sets the caption's corner to the corner of the clock
- **THEN** the change is stored

#### Scenario: Invalid corner
- **WHEN** a client sets the caption's corner to `middle`
- **THEN** the API answers with 400 and names the field `caption.position`
