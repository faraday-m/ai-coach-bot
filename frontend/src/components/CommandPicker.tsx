import type { Command } from '../types'

interface Props {
  commands: Command[]
  filter: string           // the text after "/" typed by the user
  onSelect: (trigger: string) => void
  onDismiss: () => void
}

/**
 * Floating command picker — appears when the user types "/" in the input.
 * Filtered by what follows the slash.
 */
export function CommandPicker({ commands, filter, onSelect, onDismiss }: Props) {
  const lower = filter.toLowerCase()
  const visible = commands.filter(
    (c) =>
      c.trigger.toLowerCase().includes(lower) ||
      c.description.toLowerCase().includes(lower),
  )

  if (visible.length === 0) return null

  return (
    <div className="absolute bottom-full left-0 right-0 mb-2 mx-2 bg-white border border-gray-200 rounded-xl shadow-lg overflow-hidden z-10">
      <div className="px-3 py-1.5 text-xs font-medium text-gray-500 border-b border-gray-100 bg-gray-50">
        Commands
      </div>
      <ul>
        {visible.map((cmd) => (
          <li key={cmd.trigger}>
            <button
              type="button"
              className="w-full text-left px-3 py-2 flex items-baseline gap-2 hover:bg-blue-50 transition-colors"
              onClick={() => {
                onSelect(cmd.trigger + ' ')
                onDismiss()
              }}
            >
              <span className="font-mono text-sm text-blue-600 shrink-0">{cmd.trigger}</span>
              <span className="text-xs text-gray-500 truncate">{cmd.description}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
