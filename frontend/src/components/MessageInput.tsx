import { useRef, useState } from 'react'
import type { Command } from '../types'
import { CommandPicker } from './CommandPicker'

interface Props {
  commands: Command[]
  disabled: boolean
  onSend: (text: string) => void
}

export function MessageInput({ commands, disabled, onSend }: Props) {
  const [value, setValue] = useState('')
  const [showPicker, setShowPicker] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Show command picker when input starts with "/"
  const pickerFilter = value.startsWith('/') ? value.slice(1) : ''

  function handleChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const v = e.target.value
    setValue(v)
    setShowPicker(v.startsWith('/'))
    // Auto-resize textarea
    e.target.style.height = 'auto'
    e.target.style.height = Math.min(e.target.scrollHeight, 160) + 'px'
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
    if (e.key === 'Escape') {
      setShowPicker(false)
    }
  }

  function submit() {
    const text = value.trim()
    if (!text || disabled) return
    onSend(text)
    setValue('')
    setShowPicker(false)
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }

  function handleCommandSelect(trigger: string) {
    setValue(trigger)
    textareaRef.current?.focus()
  }

  return (
    <div className="relative border-t border-gray-200 bg-white px-4 py-3">
      {/* Command picker floats above the input */}
      {showPicker && commands.length > 0 && (
        <CommandPicker
          commands={commands}
          filter={pickerFilter}
          onSelect={handleCommandSelect}
          onDismiss={() => setShowPicker(false)}
        />
      )}

      <div className="flex items-end gap-2">
        <textarea
          ref={textareaRef}
          rows={1}
          value={value}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          placeholder="Type a message… (Shift+Enter for new line)"
          className="flex-1 resize-none rounded-xl border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:opacity-50 max-h-40 leading-relaxed"
        />
        <button
          type="button"
          onClick={submit}
          disabled={disabled || !value.trim()}
          className="w-9 h-9 rounded-full bg-blue-600 text-white flex items-center justify-center hover:bg-blue-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0"
          aria-label="Send"
        >
          {/* Send icon */}
          <svg className="w-4 h-4 translate-x-px" viewBox="0 0 24 24" fill="currentColor">
            <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
          </svg>
        </button>
      </div>
    </div>
  )
}
