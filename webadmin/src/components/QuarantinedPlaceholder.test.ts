import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import type { Photo } from "../lib/api/client";
import { i18n } from "../lib/i18n/i18n.svelte";
import PhotoDetail from "./PhotoDetail.svelte";
import QuarantinedPlaceholder from "./QuarantinedPlaceholder.svelte";

/** Spec web-admin, "Quarantined photos in the interface". */
describe("Quarantined photos", () => {
  const photo = {
    id: "local-1",
    source: "local",
    path: "holiday/poison.jpg",
    album: "holiday",
    hidden: false,
    visible: true,
    quarantined: true,
    width: 4000,
    height: 3000,
    takenAt: "2024-06-01T10:15:30Z",
    fileSize: 1_000_000,
    version: 1,
    crop: null,
    presentation: { x: 0, y: 0, width: 1, height: 1, zoom: 1 },
    urls: { thumb: "/api/v1/photos/local-1/image?size=thumb", display: "/d", full: "/f" },
    comment: null,
    location: null,
  } as unknown as Photo;

  const props = {
    photo,
    settings: null,
    onclose: () => {},
    ontogglehidden: () => {},
    ondelete: () => {},
    onsavecrop: async () => {},
    onsavecomment: async () => photo,
    onretry: () => {},
  };

  it("the placeholder names the photo and holds no image", () => {
    i18n.lang = "en";
    const { body } = render(QuarantinedPlaceholder, { props: { name: photo.path } });
    expect(body).not.toContain("<img");
    expect(body).toContain('role="img"');
    expect(body).toContain("holiday/poison.jpg: not shown, it made the image decoder crash");
  });

  it("the detail view shows the placeholder, no image, no section editor, and offers to try again", () => {
    i18n.lang = "en";
    const { body } = render(PhotoDetail, { props });
    expect(body).not.toContain("<img");
    expect(body).toContain("made the frame's image decoder crash");
    expect(body).toContain("Try again");
    expect(body).not.toContain("Choose section");
  });

  it("a photo that is not quarantined shows its image and the section editor", () => {
    i18n.lang = "en";
    const { body } = render(PhotoDetail, { props: { ...props, photo: { ...photo, quarantined: false } } });
    expect(body).toContain('src="/d"');
    expect(body).not.toContain("Try again");
    expect(body).toContain("Choose section");
  });
});
