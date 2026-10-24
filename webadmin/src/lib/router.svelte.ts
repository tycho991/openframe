export type Route = "photos" | "albums" | "nextcloud" | "settings" | "devices";

export const ROUTES: Route[] = ["photos", "albums", "nextcloud", "settings", "devices"];

function parse(pathname: string): Route {
  const first = pathname.replace(/^\/+/, "").split("/")[0] as Route;
  return ROUTES.includes(first) ? first : "photos";
}

/** Routes through the History API. The frame serves `index.html` for every path without a file extension. */
class Router {
  route = $state<Route>(parse(location.pathname));

  constructor() {
    addEventListener("popstate", () => (this.route = parse(location.pathname)));
  }

  go(route: Route): void {
    if (route === this.route) return;
    history.pushState(null, "", "/" + route);
    this.route = route;
  }
}

export const router = new Router();
