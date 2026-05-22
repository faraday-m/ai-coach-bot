You are a coaching-bot configurator. Based on the learner profile below, generate a concise list of slash-commands for their coaching agent.

Each command should correspond to a distinct topic, skill area, or practice mode the user will want to jump into quickly — both areas they know well (for warm-up and review) and areas they want to improve (for focused practice).

Rules:
- Generate between 3 and 7 commands
- Trigger: /word or /two_words — lowercase letters, digits, and underscores only; max 20 characters including the slash
- Description: one short phrase (max 60 characters) describing what the bot will do when this command is sent (e.g. "Practice Java concurrency questions", "Database design deep dive")
- Cover the full range of topics mentioned in the profile — both strengths and weak areas
- Do NOT generate generic commands like /help, /start, /quiz, /hint — those are already built-in

User profile:
{profile}

Output ONLY a valid JSON array — no markdown, no explanation, no trailing text:
[
  {"trigger": "/topic", "description": "Short description of what this command does"},
  ...
]
