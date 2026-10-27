# Spec Delta

## MODIFIED Requirements

### Requirement: Rendered image sizes
The library SHALL provide a thumbnail (longest edge 400 px), a display size and a full size for every photo. The display size SHALL be the part of the photo the frame shows, at screen resolution and never larger than that part of the original. The full size SHALL be the uncropped photo with a longest edge of at most 2048 px, never larger than the original. All sizes SHALL be EXIF-rotated and cached. The response SHALL be cacheable for the long term.

#### Scenario: Portrait photo on a landscape frame
- **WHEN** a photo of 3000×4000 pixels is rendered on a frame with 1920×1200 in `landscape` in the fill mode `fill`
- **THEN** the display size is 1920×1200 pixels and shows the middle 3000×1875 pixels of the photo

#### Scenario: Portrait photo with borders
- **WHEN** the same photo is rendered in the fill mode `auto`
- **THEN** the display size is 1200×1200 pixels and shows the middle 3000×3000 pixels of the photo; the borders are not part of the image

#### Scenario: Fetch thumbnail
- **WHEN** the admin interface fetches a photo's thumbnail
- **THEN** it receives a JPEG with a longest edge of 400 px and `Cache-Control: immutable`

#### Scenario: Repeated request
- **WHEN** the same thumbnail is requested a second time with `If-None-Match`
- **THEN** the API answers with 304 without rendering again

## ADDED Requirements

### Requirement: Section of a photo
It SHALL be possible to set and remove a photo's section through the API: a focus point `x`, `y` (fractions of width and height after EXIF rotation, 0 to 1) and a zoom (greater than 0, at most 4). Invalid values SHALL be rejected with 400 and the field. The section SHALL survive restarts and rescans and SHALL be possible for photos of read-only sources too, without writing to the source.

#### Scenario: Set a section
- **WHEN** a client sets the section x 0.5, y 0.2, zoom 1.5 on a photo
- **THEN** the photo carries this section, and the slideshow shows it with it from the next change on

#### Scenario: Invalid focus point
- **WHEN** a client sets a section with x 1.2
- **THEN** the API answers with 400 and names `crop.x`, and the photo keeps its previous section

#### Scenario: Remove a section
- **WHEN** a client removes a photo's section
- **THEN** the photo has no section, and the fill mode applies to it again

#### Scenario: Photo of a share
- **WHEN** a client sets a section on a photo of a remote source
- **THEN** the photo carries the section, and nothing is written to the source

### Requirement: Section when the file changes
If a photo's file is replaced by one with the same aspect ratio (within 1 %), the photo SHALL keep its section. If the aspect ratio changes, the section SHALL be removed, because its focus point would refer to another picture.

#### Scenario: Edited photo of the same size
- **WHEN** a photo with a section is replaced by an edited file of the same dimensions
- **THEN** its image version increases and its section is kept

#### Scenario: Photo cropped elsewhere
- **WHEN** a photo of 4000×3000 pixels with a section is replaced by a file of 3000×3000 pixels at the same path
- **THEN** the photo has no section any more, and the fill mode applies to it

### Requirement: Largest zoom
The frame SHALL show a section with at most the zoom at which one pixel of the original covers one pixel of the screen, and at most 4. A photo that does not cover the screen at zoom 1 SHALL allow zoom 1. A stored zoom above this limit SHALL be shown with the limit.

#### Scenario: Photo of 12 megapixels
- **WHEN** a photo of 4000×3000 pixels with zoom 3 is shown on a screen of 1920×1200
- **THEN** it is shown with a zoom of about 2.08, at which 1920 original pixels cover the screen's width

#### Scenario: Photo of 48 megapixels
- **WHEN** a photo of 8000×6000 pixels with zoom 4 is shown on a screen of 1920×1200
- **THEN** it is shown with zoom 4

### Requirement: Rendering a section within memory
Rendering a section SHALL get by with the heap of the reference devices, also for a photo of 48 megapixels at the largest zoom.

#### Scenario: Large photo zoomed in
- **WHEN** a photo of 48 megapixels with the largest zoom is rendered on the Frameo
- **THEN** the app does not crash and the display size appears sharp

### Requirement: Section in the photo list
Every entry of the photo list SHALL contain the photo's section or `null`, its presentation (the part of the photo the frame shows with the current settings and screen, as fractions, and the zoom it is shown with) and a signed URL of the full size. The URL of the display size SHALL change whenever the part it shows changes.

#### Scenario: Photo without a section
- **WHEN** a client fetches a photo without a section in the fill mode `fill`
- **THEN** its section is `null` and its presentation is the part that `fill` shows, with zoom 1

#### Scenario: New section, new image
- **WHEN** a client has cached a photo's display size and the photo's section is changed afterwards
- **THEN** the photo list gives a different display URL, and fetching it returns the new part
