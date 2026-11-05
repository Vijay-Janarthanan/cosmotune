# Security policy

## Supported versions

Only the latest release of Cosmotune gets security fixes. Please update to the newest version from the
[releases page](https://github.com/Vijay-Janarthanan/cosmotune/releases/latest) before reporting, and say which
version and platform (Android, Windows, macOS, Linux, TV, Wear OS, browser companion) you saw the problem on.

| Version | Supported |
| ------- | --------- |
| Latest release | Yes |
| Older releases | No |

## Reporting a vulnerability

Please report it **privately**, not in a public issue.

- Preferred: use GitHub's **Report a vulnerability** button on the repository's Security tab (private vulnerability reporting).
- Otherwise email vijaybfriendly@gmail.com with "Cosmotune security" in the subject.

Include what you found, the steps to reproduce it, the version and platform, and what an attacker could do with it.
Please do not include other people's personal data, and do not test against other people's accounts or rooms.

What to expect: this is a one-person, free project, so these are goals and not guarantees. You will get a reply within
7 days. If the report is accepted, a fix is released as soon as it can be made safely and you are credited in the release
notes unless you prefer not to be. If it is declined, you will be told why. Please keep the details private until a fix
is out.

## What is in scope

The Cosmotune apps, the browser companion, cloud sync (accounts, the Firestore and Realtime Database security rules in
`firebase/`), and shared rooms (including the voice connection setup).

## What is not a vulnerability

- The Firebase web API key and project identifiers in the source. They are public by design; the security rules are what
  protect the data, and a bypass of those rules is exactly what we want to hear about.
- Problems in services Cosmotune talks to (YouTube, Google, Last.fm, ListenBrainz, radio and podcast hosts) or in other
  people's apps. Report those to their owners.
- Reports that need physical access to an unlocked device, or a rooted or modified device.
- A room's code being shared: anyone with a code can join that room, which is how rooms work.
