import "./app.css";
import { mount } from "svelte";
import App from "./App.svelte";
import { applyTheme, theme } from "./lib/theme.svelte";

const target = document.getElementById("app");
if (!target) throw new Error("#app is missing in index.html");

// Before the first render; the CSP allows no inline script in index.html, which would be earlier still.
applyTheme(theme.choice);

export default mount(App, { target });
