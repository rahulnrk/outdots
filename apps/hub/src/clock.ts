export type Clock = () => string;

/** Real wall-clock time as an ISO string. Approval expiry and dispatch rechecks depend on this being live. */
export const systemClock: Clock = () => new Date().toISOString();
