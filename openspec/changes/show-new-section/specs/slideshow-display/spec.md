## ADDED Requirements

### Requirement: Current part of the photo on screen
When the photo on screen shows a part made for an older section, fill mode or orientation, the frame SHALL render its current part at once and switch to it as soon as it is ready, while the photo is still on screen. This SHALL hold for a section set or removed while the photo is on screen and for a photo brought back by a step. The switch SHALL NOT start the photo's interval over.

#### Scenario: Section of the photo on screen
- **WHEN** a client sets a section on the photo on screen
- **THEN** within a few seconds the frame shows that photo with the new section, and the next photo follows after the interval that was already running

#### Scenario: Back with previous
- **WHEN** a photo's section changed while another photo was on screen, and the user steps back to it with "previous"
- **THEN** the frame shows the photo, and within a few seconds it shows it with the new section, not the old part for as long as it stays on screen

#### Scenario: Section removed on screen
- **WHEN** a client removes the section of the photo on screen
- **THEN** within a few seconds the frame shows that photo in the fill mode again

#### Scenario: Next photo before the switch
- **WHEN** the slideshow moves on before the new part of the photo on screen is ready
- **THEN** the next photo appears as usual, and the photo shows its new part when it appears again
