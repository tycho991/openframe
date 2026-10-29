# Spec Delta

## MODIFIED Requirements

### Requirement: Attribution of the place data
The interface SHALL name GeoNames as the source of the place names with its licence (CC BY 4.0), next to the place setting, with a link to the licence text and a note on the changes made to the data: converted, positions rounded, one German name chosen per place. The attribution SHALL be available offline, and the full source note SHALL be reachable offline from the licence menu.

#### Scenario: Settings open
- **WHEN** the user opens the caption settings without internet access
- **THEN** the attribution of GeoNames and its licence is shown next to the place setting

#### Scenario: Licence and changes
- **WHEN** the user opens the caption settings
- **THEN** the licence name links to the text of CC BY 4.0 and the attribution says how the data was changed
- **AND** the licence menu opens the full source note of the place data without internet access
