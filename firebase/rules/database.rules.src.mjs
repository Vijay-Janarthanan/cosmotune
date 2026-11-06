// Source of firebase/database.rules.json (run: node rules/database.rules.src.mjs). The Realtime Database rules language has no
// functions, so the repeated checks are built here once and written out. Design: docs/superpowers/specs/2026-11-05-cosmotune-accounts-sync-rooms-design.md
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const DAY_MS = 86_400_000
const CODE = '/^[A-Z0-9]{6}$/'

const room = "root.child('rooms').child($code)"
const signedIn = 'auth != null'
// the room stops answering 24 hours after it was made
const alive = `(${room}.child('meta').child('createdAt').val() + ${DAY_MS} > now)`
const member = `${room}.child('uids').child(auth.uid).exists()`
const host = `${room}.child('meta').child('hostUid').val() === auth.uid`
const control = `(${room}.child('meta').child('hostOnlyControl').val() !== true || ${host})`
// the same checks on the data as it will be after this write, for the writes that make the room and join it in one update
const postRoom = "newData.parent().parent()"
const postAlive = `(${postRoom}.child('meta').child('createdAt').val() + ${DAY_MS} > now)`

const str = (max) => ({ '.validate': `newData.isString() && newData.val().length <= ${max}` })
const num = { '.validate': 'newData.isNumber()' }
const bool = { '.validate': 'newData.isBoolean()' }

const track = {
  '.validate': "newData.hasChildren(['videoId', 'title'])",
  // a song or video id is short; a radio station id is a 36-character uuid and a podcast episode id can be longer
  videoId: str(64),
  title: str(200),
  artist: str(200),
  thumbnailUrl: str(500),
  durationMs: num,
  fromAutoplay: bool,
  // what the item is, and for radio and podcasts where to fetch it: https only, because every member's device fetches it
  kind: { '.validate': "newData.isString() && (newData.val() === 'music' || newData.val() === 'video' || newData.val() === 'radio' || newData.val() === 'podcast' || newData.val() === 'watch')" },
  url: { '.validate': "newData.isString() && newData.val().length <= 500 && newData.val().matches(/^https:[/][/].+/)" }, // slashes in a character class, so no escape has to survive the string
  // a Watch item (kind 'watch'): ids and the host's release, each short; never a stream address, a server or a credential
  watch: {
    tmdb: num,
    imdb: str(16),
    season: num,
    episode: num,
    year: num,
    runtimeMin: num,
    size: num,
    name: str(200),
    durationMs: num,
    binge: str(100),
    plugin: str(32),
    tvgId: str(64),
    channel: str(80),
    $other: { '.validate': false },
  },
  $other: { '.validate': false },
}

const rules = {
  rules: {
    '.read': false,
    '.write': false,

    // the room a user made last; lets a user start a new room after a crash left an old one behind
    userRoom: {
      $uid: {
        '.read': 'auth != null && auth.uid === $uid',
        '.write': 'auth != null && auth.uid === $uid',
        '.validate': `newData.isString() && newData.val().matches(${CODE})`,
      },
    },

    // setup messages for voice, outside the room node so that reading the room does not hand every member everyone's inbox
    signals: {
      $code: {
        $to: {
          '.read': `${signedIn} && ${alive} && ${member} && auth.uid === $to`,
          $from: {
            $id: {
              '.write':
                `${signedIn} && ${alive} && ${member} && (` +
                `(!data.exists() && auth.uid === $from && ${room}.child('uids').child($to).exists()) || ` +
                `(data.exists() && !newData.exists() && (auth.uid === $from || auth.uid === $to))` +
                `)`,
              '.validate': "newData.hasChildren(['type', 'payload', 't'])",
              type: { '.validate': "newData.isString() && (newData.val() === 'offer' || newData.val() === 'answer' || newData.val() === 'ice')" },
              payload: str(8000),
              t: num,
              $other: { '.validate': false },
            },
          },
        },
      },
    },

    rooms: {
      $code: {
        '.validate': `$code.matches(${CODE})`,
        // a member reads the whole room through one stream (every stream counts as a connection on the free plan);
        // a stranger with the code can still read the two nodes below that are open to any signed-in user
        '.read': `${signedIn} && ${alive} && ${member}`,
        // a whole room can be deleted by its host, or by anyone once it has expired
        '.write': `${signedIn} && !newData.exists() && (${host} || !${alive})`,

        // visible to anyone signed in who knows the code: this is what "see who is inside before joining" reads
        meta: {
          '.read': `${signedIn} && ${alive}`,
          '.write':
            `${signedIn} && (newData.exists() ? (` +
            // creating: the caller becomes host, and has no other live room
            `(!data.exists() && newData.child('hostUid').val() === auth.uid && (!root.child('userRoom').child(auth.uid).exists() || !root.child('rooms').child(root.child('userRoom').child(auth.uid).val()).child('meta').exists())) || ` +
            // changing: only the host, who cannot change the creation time
            `(data.exists() && ${host} && newData.child('createdAt').val() === data.child('createdAt').val())` +
            `) : ${host})`,
          '.validate': "newData.hasChildren(['hostUid', 'createdAt', 'maxMembers'])",
          hostUid: str(128),
          createdAt: { '.validate': 'newData.isNumber() && (data.exists() || newData.val() === now)' },
          maxMembers: { '.validate': 'newData.isNumber() && newData.val() >= 2 && newData.val() <= 8' },
          hostOnlyControl: bool,
          autoplay: bool,
          $other: { '.validate': false },
        },

        // one node per seat: the seat number (0 to 7) is the capacity limit, because rules cannot count children
        members: {
          '.read': `${signedIn} && ${alive}`,
          $slot: {
            '.write':
              `${signedIn} && ${postAlive} && (` +
              // taking a free seat below maxMembers
              `(!data.exists() && newData.exists() && newData.child('uid').val() === auth.uid && $slot.matches(/^[0-7]$/) && $slot < ${postRoom}.child('meta').child('maxMembers').val() + '') || ` +
              // updating one's own seat
              `(data.exists() && newData.exists() && data.child('uid').val() === auth.uid && newData.child('uid').val() === auth.uid) || ` +
              // leaving, or being removed by the host
              `(data.exists() && !newData.exists() && (data.child('uid').val() === auth.uid || ${host}))` +
              `)`,
            '.validate': "newData.hasChildren(['uid', 'name', 'deviceId', 'joinedAt', 'lastSeen', 'mic'])",
            uid: str(128),
            name: str(40),
            avatarUrl: str(500),
            deviceId: str(64),
            joinedAt: num,
            lastSeen: num,
            mic: { '.validate': "newData.isString() && (newData.val() === 'muted' || newData.val() === 'live')" },
            $other: { '.validate': false },
          },
        },

        // which seat a user holds; also the membership test every other node uses
        uids: {
          '.read': `${signedIn} && ${alive}`,
          $uid: {
            '.write':
              `${signedIn} && ${postAlive} && (` +
              `(auth.uid === $uid && newData.exists() && ${postRoom}.child('members').child(newData.val()).child('uid').val() === auth.uid) || ` +
              `(auth.uid === $uid && !newData.exists()) || ` +
              `(!newData.exists() && ${host})` +
              `)`,
            '.validate': 'newData.isString()',
          },
        },

        state: {
          '.read': `${signedIn} && ${alive} && ${member}`,
          '.write': `${signedIn} && ${alive} && ${member} && ${control} && newData.child('seq').val() === (data.exists() ? data.child('seq').val() + 1 : 1)`,
          '.validate': "newData.hasChildren(['seq', 'positionMs', 'anchorMs', 'isPlaying', 'updatedBy'])",
          seq: num,
          positionMs: { '.validate': 'newData.isNumber() && newData.val() >= 0' },
          anchorMs: num,
          isPlaying: bool,
          track,
          queueIndex: num,
          queueSeq: num,
          updatedBy: str(128),
          startedBy: str(128),
          startedByName: str(40),
          $other: { '.validate': false },
        },

        queue: {
          '.read': `${signedIn} && ${alive} && ${member}`,
          '.write': `${signedIn} && ${alive} && ${member} && ${control} && newData.child('seq').val() === (data.exists() ? data.child('seq').val() + 1 : 1)`,
          '.validate': "newData.hasChildren(['seq'])",
          seq: num,
          // the playing track plus 25 upcoming
          items: {
            $i: {
              ...track,
              // the position check must not be replaced by the track's own check, so the two are joined here
              '.validate': `$i.matches(/^([0-9]|1[0-9]|2[0-5])$/) && ${track['.validate']}`,
            },
          },
          $other: { '.validate': false },
        },

        chat: {
          '.read': `${signedIn} && ${alive} && ${member}`,
          $id: {
            '.write':
              `${signedIn} && ${alive} && ${member} && (` +
              `(!data.exists() && newData.child('uid').val() === auth.uid && newData.child('t').val() === now) || ` +
              // deleting: one's own message, the host's choice, or anything older than six hours (how the history is trimmed)
              `(data.exists() && !newData.exists() && (data.child('uid').val() === auth.uid || ${host} || data.child('t').val() < now - 21600000))` +
              `)`,
            '.validate': "newData.hasChildren(['uid', 'name', 'kind', 't'])",
            uid: str(128),
            name: str(40),
            kind: { '.validate': "newData.isString() && (newData.val() === 'text' || newData.val() === 'request' || newData.val() === 'system')" },
            text: str(500),
            ref: { '.validate': true },
            t: num,
            $other: { '.validate': false },
          },
        },

        $other: { '.validate': false },
      },
    },
  },
}

const out = fileURLToPath(new URL('../database.rules.json', import.meta.url))
writeFileSync(out, JSON.stringify(rules, null, 2) + '\n')
console.log('wrote', out)
