// The ports of the test server, shared by playwright.config.ts and the helpers. Parallel runs, for example from two
// worktrees, each need their own `OPENFRAME_E2E_PORT`; the control port defaults to the next one.

function port(name: string, fallback: number): number {
  const value = Number(process.env[name] || fallback);
  if (!Number.isInteger(value) || value < 1 || value > 65535) {
    throw new Error(`${name} is not a port: ${process.env[name]}`);
  }
  return value;
}

export const FRAME_PORT = port("OPENFRAME_E2E_PORT", 8090);
export const CONTROL_PORT = port("OPENFRAME_E2E_CONTROL_PORT", FRAME_PORT + 1);

/** The frame's address as the browser and the frame itself see it. */
export const FRAME_ORIGIN = `http://127.0.0.1:${FRAME_PORT}`;

/** The test server's control port, see TestServerMain. */
export const CONTROL = `http://127.0.0.1:${CONTROL_PORT}`;
