# Spec Delta

## MODIFIED Requirements

### Requirement: Smooth display on weak hardware
Transitions SHALL run without visible stutter on the reference devices (Frameo `PFF_1024`, Android 6.0.1; Lenovo Yoga Tablet 2 1050F, Android 5.0.1). Decoding the next photo SHALL NOT delay a running transition. If a device cannot show a transition smoothly, the frame SHALL change photos instantly instead and keep them sharp; the chosen transition in the settings SHALL stay as it is.

#### Scenario: Next photo is prepared
- **WHEN** a change is due
- **THEN** the next photo is already decoded and the transition starts without waiting

#### Scenario: Frame rate during a transition
- **WHEN** a crossfade runs on a device whose GPU manages it, such as the Lenovo
- **THEN** at most 5 % of the frames are dropped during the transition (measured with `dumpsys gfxinfo` or equivalent)

#### Scenario: A GPU that cannot fade
- **WHEN** blending two textures of the screen's size takes the display more than half a refresh interval per frame
- **THEN** the frame changes photos instantly from then on, also after a restart, and the setting for the transition is unchanged

#### Scenario: A GPU that can fade
- **WHEN** blending two textures of the screen's size takes at most half a refresh interval per frame
- **THEN** the frame shows the transition as set

#### Scenario: Trying again
- **WHEN** the user changes the transition or its duration while the frame changes photos instantly
- **THEN** the frame shows the transition again and measures it anew
