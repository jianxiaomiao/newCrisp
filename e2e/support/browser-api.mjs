export function browserApi(page, path, init) {
  return page.evaluate(async ({ path, init }) => {
    const response = await fetch(path, { cache: 'no-store', ...init });
    const data = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
    return { status: response.status, data };
  }, { path, init });
}
