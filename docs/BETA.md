# Beta checklist

What has to be true before a build is handed to somebody who is not the author.

This is not a wish list. Every line here exists because it is a way the app can
fail on a stranger's phone while looking perfectly healthy on the developer's.
The app is used by people with delayed sleep phase and ADHD, which means a
failure that produces a confidently wrong bedtime is worse than a crash: a crash
gets reported, a wrong bedtime gets believed.

## Before tagging

- [ ] `gradle testReleaseUnitTest` passes locally, not only in CI.
- [ ] CI is green, including the broken character check.
- [ ] Installed over the previous version, not a fresh install. A migration that
      wipes the database is the one bug that cannot be undone by the user.
- [ ] Database opened after the update with at least a week of real history in
      it, and the history is still there.
- [ ] Backup written on the old version restores on the new one.

## On a real phone, once

- [ ] Fresh install, no permissions granted yet: the app opens, explains itself
      and does not crash on any tab.
- [ ] Health Connect not installed at all: the app still opens and says so.
- [ ] Notifications denied: nothing crashes, no silent failure loop.
- [ ] Light sampler running, then killed from the task switcher, then left an
      hour: it comes back on its own and says how many samples it has.
- [ ] Widget added to the home screen; the times are readable and not cut off at
      the largest system font size.
- [ ] Both languages checked on every tab for clipped text and for the diamond
      with a question mark that means a mangled letter.

## The questions

- [ ] Without Telegram set up: the morning question and the evening drink
      question both arrive as notifications and both save when tapped from the
      shade with the app closed.
- [ ] With Telegram set up: those notifications do not arrive, because the chat
      is already asking. Nothing is ever asked twice.
- [ ] Drink questions turned off in settings: nothing asks about drinks anywhere,
      including the evening question.
- [ ] An answer given in the app, in the chat and in a notification produce the
      same stored row.

## The model

- [ ] Cold start, no nights: the app says it cannot forecast yet instead of
      showing a number.
- [ ] A week of missing days: the forecast widens and says so, and the tab still
      loads.
- [ ] A logged daytime sleep pushes tonight's window later, not earlier.
- [ ] A drink logged at nine in the evening changes tonight's window; the same
      drink logged at nine in the morning barely does.
- [ ] Time zone changed on the device: predictions move with it and do not jump
      by a day.
- [ ] Clock set forward and back: no rows dated in the future survive a sync.

## Honesty

- [ ] The confidence percentage moves after a week of use. A number that never
      changes is decoration.
- [ ] Nothing in the app claims to diagnose, treat or measure anything medical.
- [ ] `LICENSE` is present and the README says GPLv3 or later.
- [ ] Known gaps in the README match what is actually missing.
