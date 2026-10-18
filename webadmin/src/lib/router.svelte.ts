export type Route = "photos" | "albums" | "settings" | "devices";

export const ROUTES: Route[] = ["photos", "albums", "settings", "devices"];

function parse(pathname: string): Route {
  const first = pathname.replace(/^\/+/, "").split("/")[0] as Route;
  return ROUTES.includes(first) ? first : "photos";
}

/** Routen über die History-API. Der Rahmen liefert für jeden Pfad ohne Dateiendung `index.html`. */
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
