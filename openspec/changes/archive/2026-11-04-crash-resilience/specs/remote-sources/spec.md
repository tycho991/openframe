## ADDED Requirements

### Requirement: Photos kept when the sources cannot be read
If the stored list of sources exists but cannot be read at start, the frame SHALL NOT remove the photos of remote sources, their comments, sections and hidden states, or their downloaded originals. No source syncs until the list can be read again or a source is set up anew.

#### Scenario: Damaged list of sources
- **WHEN** the frame starts and its stored list of sources is damaged
- **THEN** the photos from Nextcloud stay in the library with their comments, and none of them is removed
