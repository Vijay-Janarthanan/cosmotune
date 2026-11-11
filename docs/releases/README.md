# Release notes

Each release has a notes file here, `<version>.md`. The Release workflow publishes the whole file as the GitHub release body, so
the release page keeps the full notes. The apps show much less: their update card and their "what's new" card show only the
**Highlights**, with a "Full release notes" link to the release page.

## Highlights

Put a `## Highlights` section at the top of every new notes file, right under the title (and under a one-line introduction, if
there is one). It is a list of at most five items, one line each:

```
## Highlights

- <!-- icon: timer --> **Reliable sleep timer** — Stops on time, even with the screen off.
```

- **Icon.** The comment names the icon by one keyword. GitHub does not show it on the release page. The keywords are `look`,
  `watch`, `live`, `channels`, `timer`, `playback`, `lyrics`, `audio`, `playlist`, `sync`, `check`, `read`, `podcast`, `tv`,
  `wear`, `download`, `search`, `together`, `settings` and `new`.
- **Title.** Two to four words in bold.
- **Sentence.** One sentence after the dash, at most 48 characters so it fits one phone line, written for a listener rather than a
  developer.

Choose what a listener notices: a new mode, a new look, something that now works properly. Leave out internal fixes, tests,
build and CI changes, and anything that needs a technical word to explain. Those belong in the rest of the notes, under the next
heading (`## Details` when the notes have no headings of their own).

`ReleaseWorkflowTest` checks the Highlights of the current release and of every notes file that has them.

## Older releases

Notes from before 1.0.17 have no Highlights. For those, the apps show the first three top-level bullets (or the first sentences
of the paragraphs, when there are no bullets), as plain text cut to one line each. They never show the whole body.

A release that is already published keeps the body it was published with. To show the Highlights for it, paste the updated notes
file into the release's description on GitHub.
