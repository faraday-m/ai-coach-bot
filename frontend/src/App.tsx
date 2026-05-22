import { useCallback, useEffect, useRef, useState } from 'react'
import { loadCommands, loadHistory, sendMessage, streamUrl } from './api'
import { ChatMessage } from './components/ChatMessage'
import { MessageInput } from './components/MessageInput'
import { TypingIndicator } from './components/TypingIndicator'
import { getSessionToken } from './session'
import type { Command, Message } from './types'

// Agent ID is injected at build time via VITE_AGENT_ID env var (default: "default")
const AGENT_ID = import.meta.env.VITE_AGENT_ID ?? 'default'
const SESSION  = getSessionToken()

export default function App() {
  const [messages,  setMessages]  = useState<Message[]>([])
  const [commands,  setCommands]  = useState<Command[]>([])
  const [typing,    setTyping]    = useState(false)
  const [sending,   setSending]   = useState(false)
  const [connected, setConnected] = useState(false)
  const bottomRef      = useRef<HTMLDivElement>(null)
  const eventSrcRef    = useRef<EventSource | null>(null)
  /** True once the first SSE open event fires — distinguishes "Connecting" from "Reconnecting". */
  const everConnected  = useRef(false)

  // ── Load history + commands on mount ──────────────────────────────────────
  useEffect(() => {
    loadHistory(AGENT_ID, SESSION).then(setMessages)
    loadCommands(AGENT_ID).then(setCommands)
  }, [])

  // ── Auto-scroll on new messages ───────────────────────────────────────────
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, typing])

  // ── SSE connection ─────────────────────────────────────────────────────────
  useEffect(() => {
    function connect() {
      const es = new EventSource(streamUrl(AGENT_ID, SESSION))
      eventSrcRef.current = es

      es.addEventListener('open', () => {
        setConnected(true)
        everConnected.current = true
      })

      es.addEventListener('message', (e: MessageEvent<string>) => {
        setTyping(false)
        setSending(false)
        setMessages((prev) => [
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
        setTimeout(connect, 3_000)
      })
    }

    connect()
    return () => {
      eventSrcRef.current?.close()
    }
  }, [])

  // ── Send message ──────────────────────────────────────────────────────────
  const handleSend = useCallback(async (text: string) => {
    const userMsg: Message = {
      id: crypto.randomUUID(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
    }
    setMessages((prev) => [...prev, userMsg])
    setSending(true)
    setTyping(false)

    try {
      await sendMessage(AGENT_ID, text, SESSION)
    } catch (err) {
      console.error('Send failed:', err)
      setSending(false)
      setMessages((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          role: 'assistant',
          content: '⚠️ Failed to send message. Please try again.',
          timestamp: Date.now(),
        },
      ])
    }
  }, [])

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="flex flex-col h-dvh max-w-2xl mx-auto">
      {/* Header */}
      <header className="flex items-center gap-3 px-4 py-3 bg-white border-b border-gray-200 shadow-sm">
        <div className="w-9 h-9 rounded-full bg-blue-600 flex items-center justify-center text-white font-bold text-sm shrink-0">
          AI
        </div>
        <div className="flex-1 min-w-0">
          <h1 className="font-semibold text-gray-900 truncate">Coach Bot</h1>
          <p className="text-xs text-gray-500">
            {connected ? (
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
        {messages.length === 0 && !typing && (
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

        {messages.map((msg) => (
          <ChatMessage key={msg.id} message={msg} />
        ))}

        {typing && <TypingIndicator />}
        <div ref={bottomRef} />
      </main>

      {/* Input */}
      <MessageInput
        commands={commands}
        disabled={sending}
        onSend={handleSend}
      />
    </div>
  )
}
