import type { Agent, Command, Message } from './types'

const BASE = (agentId: string) => `/api/chat/${agentId}`

/** Fetch the list of enabled agents available in the web chat. */
export async function loadAgents(): Promise<Agent[]> {
  const resp = await fetch('/api/agents')
  if (!resp.ok) return []
  return resp.json()
}

/** Send a message. Returns immediately (202 Accepted); reply arrives via SSE. */
export async function sendMessage(agentId: string, text: string, sessionToken: string): Promise<void> {
  const resp = await fetch(`${BASE(agentId)}/message`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, sessionToken }),
  })
  if (!resp.ok) throw new Error(`Send failed: ${resp.status}`)
}

/** Load conversation history for this session. */
export async function loadHistory(agentId: string, sessionToken: string): Promise<Message[]> {
  const resp = await fetch(`${BASE(agentId)}/history?sessionToken=${sessionToken}&limit=50`)
  if (!resp.ok) return []
  const data: Array<{ role: string; content: string }> = await resp.json()
  return data.map((m, i) => ({
    id: `hist-${i}`,
    role: m.role as 'user' | 'assistant',
    content: m.content,
    timestamp: Date.now() - (data.length - i) * 1000,
  }))
}

/** Fetch available slash-commands for this agent. */
export async function loadCommands(agentId: string): Promise<Command[]> {
  const resp = await fetch(`${BASE(agentId)}/commands`)
  if (!resp.ok) return []
  return resp.json()
}

/** Returns the SSE stream URL (used by EventSource). */
export function streamUrl(agentId: string, sessionToken: string): string {
  return `${BASE(agentId)}/stream?sessionToken=${sessionToken}`
}
