export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
}

export interface Command {
  trigger: string
  description: string
}

export interface Agent {
  id: string
  name: string
}
