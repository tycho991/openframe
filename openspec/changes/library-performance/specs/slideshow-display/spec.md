## ADDED Requirements

### Requirement: Bounded rendering ahead
The frame SHALL render display sizes ahead of time only for the next 10 photos of the order. A waiting rendering ahead of a photo that has left this window SHALL be dropped before it starts, and so SHALL the download of a missing original for it that no other rendering waits for. While no photo is rendered, the frame SHALL render at most 3 photos on demand one after another and SHALL try the next ones after the interval.

#### Scenario: Cold cache with 5,000 photos
- **WHEN** the slideshow starts with 5,000 visible photos and no rendered display size
- **THEN** after the first photo appears, at most the next 10 photos are rendered ahead of time, and the rest only when they come near

#### Scenario: Window moves on
- **WHEN** renderings ahead of the next 10 photos wait and the user swipes forward several times
- **THEN** the waiting renderings of photos that are no longer among the next 10 do not run

#### Scenario: Server of all photos unreachable
- **WHEN** every visible photo is a remote photo whose original is not stored and its server cannot be reached
- **THEN** the frame tries at most 3 photos in a row, then waits one interval before it tries the next ones, and keeps trying every interval
