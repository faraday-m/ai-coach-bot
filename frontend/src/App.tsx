import { useCallback, useEffect, useRef, useState } from 'react'
import { loadAgents, loadCommands, loadHistory, sendMessage, streamUrl } from './api'
import { ChatMessage } from './components/ChatMessage'
import { MessageInput } from './components/MessageInput'
import { TypingIndicator } from './components/TypingIndicator'
import { getSessionToken } from './session'
import type { Agent, Command, Message } from './types'

/** Stable browser identity — routes SSE replies back to this tab. */
const SESSION = getSessionToken()

/** localStorage key for the last selected agent. */
const AGENT_KEY = 'selectedAgentId'

export default function App() {
  const [agents,    setAgents]    = useState<Agent[]>([])
  const [agentId,   setAgentId]   = useState<string>('')
  const [messages,  setMessages]  = useState<Message[]>([])
  const [commands,  setCommands]  = useState<Command[]>([])
  const [typing,    setTyping]    = useState(false)
  const [sending,   setSending]   = useState(false)
  const [connected, setConnected] = useState(false)
  const bottomRef     = useRef<HTMLDivElement>(null)
  const eventSrcRef   = useRef<EventSource | null>(null)
  const reconnectRef  = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** True once the first SSE open event fires — distinguishes "Connecting" from "Reconnecting". */
  const everConnected = useRef(false)

  // ── Load agents on mount ───────────────────────────────────────────────────
  useEffect(() => {
    loadAgents().then(loaded => {
      setAgents(loaded)
      if (loaded.length === 0) return
      const stored  = localStorage.getItem(AGENT_KEY)
      const valid   = loaded.find(a => a.id === stored)
      setAgentId(valid ? valid.id : loaded[0].id)
    })
  }, [])

  // ── When agentId changes: clear messages, reload history + commands ────────
  useEffect(() => {
    if (!agentId) return
    setMessages([])
    setTyping(false)
    setSending(false)
    loadHistory(agentId, SESSION).then(setMessages)
    loadCommands(agentId).then(setCommands)
  }, [agentId])

  // ── Auto-scroll on new messages ───────────────────────────────────────────
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, typing])

  // ── SSE connection — reconnects whenever agentId changes ──────────────────
  useEffect(() => {
    if (!agentId) return

    // Reset connection state for the new agent
    everConnected.current = false
    setConnected(false)

    function connect() {
      const es = new EventSource(streamUrl(agentId, SESSION))
      eventSrcRef.current = es

      es.addEventListener('open', () => {
        setConnected(true)
        everConnected.current = true
      })

      es.addEventListener('message', (e: MessageEvent<string>) => {
        setTyping(false)
        setSending(false)
        setMessages(prev => [
          ...prev,
          { id: crypto.randomUUID(), role: 'assistant', content: e.data, timestamp: Date.now() },
        ])
      })

      es.addEventListener('typing', () => {
        setTyping(true)
      })

      es.addEventListener('error', () => {
        setConnected(false)
        es.close()
        // Auto-reconnect after 3 seconds
        reconnectRef.current = setTimeout(connect, 3_000)
      })
    }

    connect()

    return () => {
      if (reconnectRef.current) clearTimeout(reconnectRef.current)
      eventSrcRef.current?.close()
    }
  }, [agentId])

  // ── Agent selection ────────────────────────────────────────────────────────
  const handleAgentChange = useCallback((newId: string) => {
    localStorage.setItem(AGENT_KEY, newId)
    setAgentId(newId)
  }, [])

  // ── Send message ───────────────────────────────────────────────────────────
  const handleSend = useCallback(async (text: string) => {
    if (!agentId) return
    const userMsg: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
    }
    setMessages(prev => [...prev, userMsg])
    setSending(true)
    setTyping(false)

    try {
      await sendMessage(agentId, text, SESSION)
    } catch (err) {
      console.error('Send failed:', err)
      setSending(false)
      setMessages(prev => [
        ...prev,
        {
          id: crypto.randomUUID(),
          role: 'assistant',
          content: '⚠️ Failed to send message. Please try again.',
          timestamp: Date.now(),
        },
      ])
    }
  }, [agentId])

  // ── Derived ────────────────────────────────────────────────────────────────
  const currentAgent  = agents.find(a => a.id === agentId)
  const multiAgent    = agents.length > 1
  const isLoading     = agents.length === 0

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div className="flex flex-col h-dvh max-w-2xl mx-auto">
      {/* Header */}
      <header className="flex items-center gap-3 px-4 py-3 bg-white border-b border-gray-200 shadow-sm">
        <div className="w-9 h-9 rounded-full bg-blue-600 flex items-center justify-center text-white font-bold text-sm shrink-0">
          AI
        </div>
        <div className="flex-1 min-w-0">
          {/* Agent selector — shown only when multiple agents are available */}
          {multiAgent ? (
            <select
              value={agentId}
              onChange={e => handleAgentChange(e.target.value)}
              className="font-semibold text-gray-900 bg-transparent border-none outline-none cursor-pointer hover:text-blue-600 transition-colors w-full truncate pr-4"
              aria-label="Select agent"
            >
              {agents.map(a => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
          ) : (
            <h1 className="font-semibold text-gray-900 truncate">
              {isLoading ? 'Loading…' : (currentAgent?.name ?? 'Coach Bot')}
            </h1>
          )}

          {/* Connection status */}
          <p className="text-xs text-gray-500">
            {!agentId ? (
              <span className="flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-gray-300 inline-block" />
                Loading agents…
              </span>
            ) : connected ? (
              <span className="flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-green-500 inline-block" />
                Connected
              </span>
            ) : everConnected.current ? (
              <span className="flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-yellow-400 inline-block" />
                Reconnecting…
              </span>
            ) : (
              <span className="flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-gray-300 inline-block" />
                Connecting…
              </span>
            )}
          </p>
        </div>
      </header>

      {/* Message list */}
      <main className="flex-1 overflow-y-auto chat-scroll py-2 bg-gray-50">
        {messages.length === 0 && !typing && agentId && (
          <div className="flex flex-col items-center justify-center h-full text-center px-8 text-gray-400">
            <div className="text-4xl mb-3">💬</div>
            <p className="text-sm">Start the conversation — ask anything!</p>
            {commands.length > 0 && (
              <p className="text-xs mt-1">
                Type <span className="font-mono bg-gray-100 px-1 rounded">/</span> to see available commands
              </p>
            )}
          </div>
        )}

        {messages.map(msg => (
          <ChatMessage key={msg.id} message={msg} />
        ))}

        {typing && <TypingIndicator />}
        <div ref={bottomRef} />
      </main>

      {/* Input */}
      <MessageInput
        commands={commands}
        disabled={sending || !agentId}
        onSend={handleSend}
      />
    </div>
  )
}
