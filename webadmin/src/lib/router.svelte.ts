export type Route = "photos" | "albums" | "sources" | "settings" | "devices";

export const ROUTES: Route[] = ["photos", "albums", "sources", "settings", "devices"];

/** Paths of earlier versions that bookmarks may still hold. */
const MOVED: Record<string, Route> = { nextcloud: "sources" };

function parse(pathname: string): Route {
  const first = pathname.replace(/^\/+/, "").split("/")[0] ?? "";
  const route = (MOVED[first] ?? first) as Route;
  return ROUTES.includes(route) ? route : "photos";
}

/** Routes through the History API. The frame serves `index.html` for every path without a file extension. */
class Router {
  route = $state<Route>(parse(location.pathname));

  constructor() {
    const first = location.pathname.replace(/^\/+/, "").split("/")[0] ?? "";
    if (first in MOVED) history.replaceState(null, "", "/" + this.route);
    addEventListener("popstate", () => (this.route = parse(location.pathname)));
  }

  go(route: Route): void {
    if (route === this.route) return;
    history.pushState(null, "", "/" + route);
    this.route = route;
  }
}

export const router = new Router();
