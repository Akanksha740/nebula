# Setup

## 1. Install Claude Code (if you haven't)

```bash
curl -fsSL https://claude.ai/install.sh | bash
```

## 2. Drop this scaffold into your repo

Unzip the scaffold into your PolyHistorical repo root. After:

```
polyhistorical/
├── CLAUDE.md
├── .claude/
│   ├── agents/
│   │   └── polyhistorical.md
│   └── context/
│       ├── README.md
│       ├── product.md
│       ├── design.md
│       ├── code.md
│       ├── email.md
│       ├── decisions/
│       │   └── README.md
│       └── learnings/
│           └── README.md
└── (your existing code)
```

## 3. Commit

```bash
git add CLAUDE.md .claude/
git commit -m "scaffold: polyhistorical context agent"
```

## 4. Start a session

```bash
cd polyhistorical
claude
```

## 5. Invoke the agent

**Explicit invocation** (recommended at first, to learn the loop):

```
> Use the polyhistorical subagent to draft a Pro trial email for Starter users who haven't called the API in 7 days.
```

**Auto-delegation** (after you trust it): the agent's `description` field is broad enough that Claude Code will route most product / code / design / email work to it automatically. Just ask for what you want.

## 6. Verify the loop is working

After the first real task, check:

```bash
ls -la .claude/context/learnings/
```

You should see a new `YYYY-MM-DD-<slug>.md` file — that's the agent writing back what it learned. If the file isn't there, the agent skipped Phase 3. Remind it in your next prompt; it'll comply.

## 7. Weekly maintenance ritual

Once a week (or when `learnings/` hits ~10 files):

```bash
ls .claude/context/learnings/
```

Read each. Promote stable / repeatedly-confirmed ones into the canonical files (`product.md`, `design.md`, `code.md`, `email.md`). Delete the originals. Commit:

```bash
git add .claude/context/
git commit -m "context: promote learnings to canon"
```

This is the part most people skip and it's the part that makes the system actually work. ~10 minutes a week.

---

## Troubleshooting

**Agent isn't loading context?** Open `.claude/agents/polyhistorical.md` and confirm the Phase 1 instructions are intact. The frontmatter `tools` line must include `Read` and `Glob`.

**Agent isn't writing learnings?** Confirm `Write` is in the `tools` line. Then prompt explicitly: "Don't forget Phase 3 — write any learnings before finishing."

**Want to add another agent?** Drop another file in `.claude/agents/`. E.g., a read-only `polyhistorical-reviewer` agent with `tools: Read, Grep, Glob` for code review without write access.

**Context getting too long?** That's the signal to run the weekly ritual. The top-level files should stay dense; `learnings/` is the firehose that gets distilled.
