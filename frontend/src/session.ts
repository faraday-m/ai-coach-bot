/** Returns a stable UUID for this browser session, persisted in localStorage. */
export function getSessionToken(): string {
  const key = 'coach-bot-session-token'
  let token = localStorage.getItem(key)
  if (!token) {
    token = crypto.randomUUID()
    localStorage.setItem(key, token)
  }
  return token
}
