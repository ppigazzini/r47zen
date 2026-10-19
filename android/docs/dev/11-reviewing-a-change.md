# Reviewing A Change

This page is the checklist for reviewing a change, or auditing code that is
already in the tree, wherever state crosses a seam: it lives in more than one
copy, crosses a thread, or crosses the JNI boundary. It owns the four checks.
The page that owns each seam carries that seam's state inventory, the input
the checks start from.

A finding list per file does not see a seam. Every file can be right on its own
while the copies between them disagree, so run these checks on the whole seam,
not only on the diff, and run them before reading for smells.

## The four checks

1. **Inventory the state.** List every copy of the data the change touches:
   its owner, the thread that writes it, the thread that reads it, and what
   signals a change. Start from the owner page's inventory. Two writers on
   different threads, or a copy with no owner, is the finding; a change that
   adds a copy adds a row to the inventory in the same commit.

   Example: one LCD frame lives in five copies between the core and the screen
   ([06](06-runtime-hot-paths.md#lcd-frame-state-inventory)). A change to the
   row cache is reviewed against the bitmap, the transport buffer, and the
   native snapshot too.

2. **Find the owner of every literal and default.** For each constant,
   initial value, or default the change writes, name the one place that owns
   it. A value equal to something another file owns is a second owner, even
   while it is correct: it stays correct only until the first owner changes,
   and until then the code behaves differently for the one input it matches.

   Example: a view that starts from a color pair written into the view, equal
   to one theme's resolved palette in the policy object, treats that theme
   unlike every other. The alternative is to start from the policy, or from
   no palette at all until the policy supplies one.

3. **For every early return, name the input that takes it.** A guard is right
   only if the state it compares against is the state the user sees. Ask which
   concrete input makes the guard skip, and whether the state the skip relies
   on was ever established.

   Example: `if (palette == current) return` in front of the only full repaint
   skips the repaint for the one setting equal to `current`'s initial value. If
   nothing painted the screen in that initial value, the skip leaves it
   unpainted for that setting alone.

4. **Name the invariant a fix restores, and what holds it.** For a bug fix,
   write down the invariant the bug broke, then say whether the fix holds it by
   construction, by a named test or contract, or still by convention. "By
   convention" is a gap: the owner page says so and says what it costs
   ([10-writing.md](10-writing.md#the-rules), "describe a gap as a gap").

   Example: "the bitmap shows the decode of the cached frame in the current
   palette", kept by three call sites that each remember to repaint, holds by
   convention, and a fix that adds a fourth repaint has added a site. One class
   that owns the frame, the palette, and the bitmap and offers no other write
   path holds it by construction
   ([06](06-runtime-hot-paths.md#lcd-frame-state-inventory)).

## Where the inventories live

| seam | inventory |
|---|---|
| the LCD frame from the core to the screen, and its palette | [06-runtime-hot-paths.md](06-runtime-hot-paths.md#lcd-frame-state-inventory) |
| the activity reference and cached method IDs across the core and main threads | [04-native-core-and-jni.md](04-native-core-and-jni.md#activity-reference-writers-and-readers) |

A seam with no inventory yet gets one in its owner page before the review, not
in the review notes.

## What this does not cover

- No gate runs these checks; they are a reader's job.
  [10-writing.md](10-writing.md#the-gates) lists what the gates settle.
- They do not replace a test. Check 4 names the test or contract that holds an
  invariant; [08-tests-and-contracts.md](08-tests-and-contracts.md) maps them.
- They find disagreement between copies of state. They do not judge whether a
  design should keep that many copies; the owner page decides that, and a
  change to it is a design change reviewed as one.
