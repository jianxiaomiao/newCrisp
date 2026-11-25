import { SessionError } from './web-session';

export async function responseError(response: Response) {
  const problem = await response.json().catch(() => null);
  return new SessionError(response.status, problem?.code ?? 'REQUEST_FAILED');
}
