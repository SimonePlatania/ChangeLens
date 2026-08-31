# ChangeLens

**See what you changed and who wrote what — without leaving the editor.**

ChangeLens is a free, open-source Eclipse plug-in. It puts Git information next to the lines it belongs to: change bars in their own column, the author at the end of each method, and a hover preview that shows you any part of the file — with its errors — without scrolling away from where you are.

No views to open. No panels to arrange.

```
https://simoneplatania.github.io/ChangeLens/
```

<!-- SCREENSHOT: the hover preview open over the overview ruler, showing code + line numbers + author + an error message at the bottom. This is the first thing a visitor should see. -->

---

## What you get

### A preview that reads the file for you

Hover the overview ruler on the right and a panel opens with the code from that point in the file, syntax-coloured exactly as in the editor.

- **It scrolls.** With the pointer over the panel, the wheel moves the preview, not the page underneath — so you can read somewhere else in the file without losing your place.
- **Line numbers** in the margin, and the author of each method at the end of its declaration line.
- **An arrow** points from the panel to the exact spot on the ruler you opened it from. No guessing which marker you're looking at.
- **Colour carries meaning.** Arrow and side stripe turn red or yellow for an error or warning; green, blue or red when those lines are added, rewritten or deleted.
- **Error messages appear at the bottom of the panel** — you no longer need the marker tooltip to find out why that mark is red.

The panel closes as soon as you move away from the ruler.

<!-- SCREENSHOT: preview panel with the error message visible at the bottom -->

### Change bars you can commit from

In the column left of the code, every block of changed lines gets **one rounded bar**, as tall as the block.

| State | Indicator |
| --- | --- |
| Committed and untouched | nothing |
| Added lines | green bar |
| Rewritten lines | blue bar |
| Deleted lines | red dash on the boundary between the surviving lines |

Where two blocks of different colours meet, the ends **fade into each other** — you can see where the rewrite ends and the new lines begin at a glance, without counting.

The bars are anchored to the code: they don't drift when you scroll, and they don't vanish and reappear as you type. Lines you've just touched colour immediately, before the Git comparison has even finished. Fold a region and the bar for what's hidden settles onto the fold line, so you know something in there has changed.

**Click a bar** and it stages *just those lines*, then opens the EGit commit dialog with that piece ready in front of you. The rest of the file's changes stay out of it.

<!-- SCREENSHOT: the change-bar column with a green and a blue block meeting, fade visible -->

### Staging that works on unsaved code

The lines are taken **from the editor, not from the file on disk**, and the file on disk is never touched.

This is the thing `git add -p` cannot do, because it has no buffer. You can commit a block while the file is still unsaved — the asterisk stays in the title bar. (Worth knowing: after a partial commit, Ctrl+Z in the editor takes the text back, but that content stays in history.)

Line endings, trailing newline, BOM and file permissions are taken from how the file already looks in the index, not from your buffer. If the repository keeps LF and you're typing CRLF, LF is what gets staged — including with `core.autocrlf` on, Git's default on Windows.

If staging isn't possible — the block is already staged, the file is in a merge conflict, or it's handled by Git LFS — **the commit dialog does not open.** The reason goes to the status bar and the Error Log. Committing while believing your block is in there would be the worst way to get this wrong.

### The author, at the end of the line

Who wrote a method appears after its declaration, in a grey that doesn't compete with the code.

| Label | Meaning |
| --- | --- |
| `Name` | the method is that person's |
| `Name+2` | two other people have worked on the body |
| `Name*` | there are uncommitted changes |
| `not committed yet*` | code that doesn't exist in history yet |

The icon colour tells you before you read: green where the body has several authors, orange where there are uncommitted changes, blue where the code is committed and untouched.

**Click the name** to open Eclipse's *Revisions* — date and author on every line, coloured per author. Click again to close.

Working next to other people? Privacy mode reduces the name to initials while keeping `+N` and `*`.

<!-- SCREENSHOT: author labels with the three icon colours visible -->

### A scrollbar that gets out of the way

The system vertical scrollbar is replaced by a thin, rounded, semi-transparent tab drawn on the same stripe as the markers.

- **It frees up space on the right**, so the preview opens right against the stripe and has more room for code.
- **One target instead of two.** Errors, warnings and the tab live in the same place, so there's a single thing to aim at — and being semi-transparent, it doesn't hide the markers it passes over.

It drags like a normal scrollbar. Turn it off and the system one comes back.

---

## Install

**Update site** (recommended):

```
https://simoneplatania.github.io/ChangeLens/
```

`Help > Install New Software... > Add...`, paste the address as *Location*, select the **ChangeLens** category, and restart Eclipse. The plug-in is unsigned, so Eclipse will ask you to confirm untrusted content.

**Or drop it in:** download the bundle from [`docs/plugins/`](../docs/plugins) and copy it into your installation's `dropins` folder.

**Requirements:** Eclipse 3.6 or later, Java 8, EGit/JGit installed, and a file inside a Git repository.

---

## Settings

`Preferences > General > Editors > Text Editors > ChangeLens`. Changes take effect immediately in editors that are already open.

- turn the plug-in, the bars, the author label and its icon on or off;
- **privacy**: initials instead of the full name;
- **thin scrollbar**: on or off;
- **colours** for added, rewritten and deleted lines;
- **hide Eclipse's Quick Diff** in managed editors, if you don't want two sets of indicators on the same line. Off by default, and the original state is restored on shutdown.

Eclipse's overview ruler stays exactly as it was — errors, warnings, tasks and bookmarks, nothing more. Git information lives only in the column on the left.

---

## Good to know

- The comparison is always against `HEAD`, not against your last save — indicators don't disappear when you hit Ctrl+S.
- Line endings and trailing whitespace don't count: a file identical to `HEAD` doesn't show as modified.
- A never-committed file isn't painted entirely green. With no version in history, there's nothing to compare against.
- Declaration detection is generic and covers Java, JavaScript/TypeScript and similar syntax.
- Diff, analysis and blame all run in the background. The editor never blocks waiting for them.
- Partial staging holds Git's index lock for roughly 8–13 ms on a normal source file; the work is measured, not assumed.

---

## Building from source

1. `File > Import > Existing Projects into Workspace` and select this folder.
2. `Run As > Eclipse Application`.
3. Open a file from a Git repository.

---

## Contributing

Issues and pull requests are welcome — especially bug reports that come with a repository state I can reproduce. If something behaves oddly around staging, line endings or blame, the details of your repo configuration are usually the whole story.

## Licence

<!-- Fill in: e.g. Eclipse Public License 2.0 -->
