const sessions = new Map();
const sidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const csrfPattern = /^[a-f0-9]{64}$/;

class SessionError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

export function webSession(side) {
  if (!sessions.has(side)) sessions.set(side, new BrowserSession(side));
  return sessions.get(side);
}

class BrowserSession {
  constructor(side) {
    this.side = side;
    this.base = `/api/v1/${side}`;
    this.key = `newcrisp-session:${side}`;
    this.jwt = undefined;
    this.refreshing = undefined;
    this.listeners = new Set();
    window.addEventListener('storage', (event) => {
      if (event.key !== this.key) return;
      const shared = this.shared();
      if (!shared || (this.jwt && (shared.sid !== this.jwt.sessionId || ['closed', 'unknown'].includes(shared.phase)))) {
        this.jwt = undefined;
        this.notify();
      }
    });
  }

  onChange(listener) { this.listeners.add(listener); }

  notify() { for (const listener of this.listeners) listener(); }

  shared() {
    const raw = localStorage.getItem(this.key);
    try { return JSON.parse(raw); } catch { return null; }
  }

  save(sid, phase, launch = this.shared()?.launch) {
    localStorage.setItem(this.key, JSON.stringify({ sid, phase, launch }));
  }

  cookie(launch) {
    const name = `__Host-newcrisp_${launch ? 'launch' : this.side}_csrf=`;
    const raw = document.cookie.split('; ').find((entry) => entry.startsWith(name))?.slice(name.length);
    const [sid, csrf] = raw?.split('.') ?? [];
    return sidPattern.test(sid) && csrfPattern.test(csrf) ? { sid, csrf, launch } : null;
  }

  selection() {
    const current = this.cookie(false);
    const pending = this.side === 'customer' ? this.cookie(true) : null;
    if (pending && pending.sid !== current?.sid && pending.sid !== this.shared()?.launch) return pending;
    return current;
  }

  lock(action) {
    if (!navigator.locks) throw new SessionError(503, 'WEB_LOCKS_UNAVAILABLE');
    return navigator.locks.request(this.key, action);
  }

  headers(pair) {
    return {
      'X-NewCrisp-CSRF': pair.csrf,
      ...(pair.launch ? { 'X-NewCrisp-Launch': '1' } : {}),
    };
  }

  install(data) {
    const expires = Date.parse(data?.expiresAt);
    if (typeof data?.accessToken !== 'string' || !sidPattern.test(data.sessionId) || !Number.isFinite(expires)
        || this.cookie(false)?.sid !== data.sessionId) throw new SessionError(503, 'SESSION_RESULT_UNKNOWN');
    return this.jwt = { ...data, expires };
  }

  async login(credentials) {
    return this.lock(async () => {
      const response = await fetch(`${this.base}/session`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(credentials), cache: 'no-store', signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) throw new SessionError(response.status, 'LOGIN_FAILED');
      const token = this.install(await response.json());
      this.save(token.sessionId, 'ready');
    });
  }

  async access(force = false) {
    const pair = this.selection();
    const shared = this.shared();
    if (!pair || shared?.sid === pair.sid && ['closed', 'unknown'].includes(shared.phase)) {
      this.jwt = undefined;
      throw new SessionError(401, 'SESSION_REQUIRED');
    }
    if (!force && !pair.launch && this.jwt?.sessionId === pair.sid && this.jwt.expires > Date.now() + 1_000) return this.jwt;
    if (this.refreshing) return this.refreshing;
    const work = this.lock(() => this.rotate());
    this.refreshing = work;
    try { return await work; }
    finally { if (this.refreshing === work) this.refreshing = undefined; }
  }

  async rotate() {
    // Cookies can change while this tab waits for another tab to finish rotating.
    const pair = this.selection();
    if (!pair) throw new SessionError(401, 'SESSION_REQUIRED');
    if (navigator.onLine === false) throw new SessionError(503, 'OFFLINE');
    const shared = this.shared();
    if (shared?.sid === pair.sid && shared.phase !== 'ready') {
      this.save(pair.sid, 'unknown');
      this.jwt = undefined;
      this.notify();
      throw new SessionError(401, 'REAUTHENTICATION_REQUIRED');
    }
    this.save(pair.sid, 'rotating');
    try {
      const response = await fetch(`${this.base}/session/refresh`, {
        method: 'POST', headers: this.headers(pair), cache: 'no-store', signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => null);
        throw new SessionError(response.status, problem?.code ?? 'SESSION_RESULT_UNKNOWN');
      }
      const token = this.install(await response.json());
      if (token.sessionId !== pair.sid) throw new SessionError(503, 'SESSION_RESULT_UNKNOWN');
      this.save(pair.sid, 'ready', pair.launch ? pair.sid : shared?.launch);
      return token;
    } catch (error) {
      this.jwt = undefined;
      this.save(pair.sid, error.status === 401 ? 'closed' : 'unknown');
      this.notify();
      throw error;
    }
  }

  async request(path, options = {}, expectedSid) {
    const unauthorized = (code) => new Response(JSON.stringify({ code }), {
      status: 401, headers: { 'Content-Type': 'application/json' },
    });
    try {
      const access = await this.access();
      if (expectedSid && access.sessionId !== expectedSid) return unauthorized('SESSION_CHANGED');
      const send = (token) => fetch(path, { ...options, cache: 'no-store',
        headers: { ...options.headers, Authorization: `Bearer ${token.accessToken}` },
      });
      const response = await send(access);
      if (this.selection()?.sid !== access.sessionId) return unauthorized('SESSION_CHANGED');
      const safe = !options.method || options.method === 'GET' || path.endsWith('/ws-ticket');
      if (response.status !== 401 || !safe) return response;
      // One refresh for an expired Access Token. Mutating calls are handled by their own retry rules.
      if (!this.jwt || this.jwt.accessToken === access.accessToken) this.jwt = undefined;
      const retry = await this.access(this.jwt?.accessToken === access.accessToken);
      if (retry.sessionId !== access.sessionId) return unauthorized('SESSION_CHANGED');
      const recovered = await send(retry);
      return this.selection()?.sid === retry.sessionId ? recovered : unauthorized('SESSION_CHANGED');
    } catch (error) {
      if (error.status === 401) return unauthorized(error.code);
      throw error;
    }
  }

  async logout() {
    return this.lock(async () => {
      const pair = this.cookie(false);
      this.jwt = undefined;
      if (!pair) return;
      this.save(pair.sid, 'closed');
      const response = await fetch(`${this.base}/session`, {
        method: 'DELETE', headers: this.headers(pair), cache: 'no-store', signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) throw new SessionError(response.status, 'LOGOUT_UNCONFIRMED');
    });
  }

  currentId() { return this.jwt?.sessionId; }
}
