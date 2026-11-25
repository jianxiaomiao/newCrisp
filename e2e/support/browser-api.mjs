export function browserApi(page, path, init) {
  return page.evaluate(async ({ path, init }) => {
    const role = path.match(/^\/api\/v1\/(staff|customer)\//)?.[1];
    const response = role ? await (await import('/web-session.js')).webSession(role).request(path, init)
      : await fetch(path, { cache: 'no-store', ...init });
    const data = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
    return { status: response.status, data };
  }, { path, init });
}
