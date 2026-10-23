# Spec Delta

## ADDED Requirements

### Requirement: Note on instant changes
If the frame changes photos instantly because its display could not keep up with a transition, the interface SHALL say so next to the setting for the transition and SHALL say that changing the transition or its duration tries it again.

#### Scenario: Frame with a weak GPU
- **WHEN** the frame has fallen back to instant changes and the settings are open
- **THEN** a note next to the transition names this and how to try again

#### Scenario: Frame that manages the transition
- **WHEN** the frame shows its transitions
- **THEN** no such note appears
