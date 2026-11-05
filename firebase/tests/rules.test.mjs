// Run with: npm test (starts the Firebase emulators and runs this file). Covers section 9 of the design: every allowed action and its rejection.
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing'
import { get, ref, serverTimestamp, set, update } from 'firebase/database'
import { doc, getDoc, setDoc } from 'firebase/firestore'
import { readFileSync } from 'node:fs'
import { after, before, beforeEach, test } from 'node:test'

const CODE = 'ABC234'
let env

const [dbHost, dbPort] = (process.env.FIREBASE_DATABASE_EMULATOR_HOST ?? '127.0.0.1:9000').split(':')
const [fsHost, fsPort] = (process.env.FIRESTORE_EMULATOR_HOST ?? '127.0.0.1:8080').split(':')

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-cosmotune',
    database: { host: dbHost, port: Number(dbPort), rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8') },
    firestore: { host: fsHost, port: Number(fsPort), rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  })
})
after(async () => env?.cleanup())
beforeEach(async () => {
  await env.clearDatabase()
  await env.clearFirestore()
})

const db = (uid) => env.authenticatedContext(uid).database()
const member = (uid, name = uid) => ({ uid, name, deviceId: `dev-${uid}`, joinedAt: Date.now(), lastSeen: Date.now(), mic: 'muted' })
const track = { videoId: 'abcdefghijk', title: 'A song', artist: 'An artist', durationMs: 200000 }
const stateAt = (seq, extra = {}) => ({ seq, positionMs: 0, anchorMs: Date.now(), isPlaying: true, updatedBy: 'host', track, ...extra })

const createRoom = (host = 'host', maxMembers = 3) =>
  update(ref(db(host)), {
    [`rooms/${CODE}/meta`]: { hostUid: host, createdAt: serverTimestamp(), maxMembers },
    [`rooms/${CODE}/members/0`]: member(host),
    [`rooms/${CODE}/uids/${host}`]: '0',
    [`userRoom/${host}`]: CODE,
  })

const join = (uid, slot) =>
  update(ref(db(uid)), { [`rooms/${CODE}/members/${slot}`]: member(uid), [`rooms/${CODE}/uids/${uid}`]: String(slot) })

test('a signed-in user can create a room and becomes its host and first member', async () => {
  await assertSucceeds(createRoom())
})

test('a user with a live room cannot create a second one', async () => {
  await assertSucceeds(createRoom())
  await assertFails(
    update(ref(db('host')), {
      'rooms/ZZZ999/meta': { hostUid: 'host', createdAt: serverTimestamp(), maxMembers: 3 },
      'rooms/ZZZ999/members/0': member('host'),
      'rooms/ZZZ999/uids/host': '0',
      'userRoom/host': 'ZZZ999',
    }),
  )
})

test('a room cannot be created in the name of another host', async () => {
  await assertFails(set(ref(db('mallory'), `rooms/${CODE}/meta`), { hostUid: 'host', createdAt: serverTimestamp(), maxMembers: 3 }))
})

test('a signed-out caller can do nothing', async () => {
  const anon = env.unauthenticatedContext().database()
  await assertFails(get(ref(anon, `rooms/${CODE}/meta`)))
})

test('a stranger with the code sees who is inside but not the playback, queue or chat', async () => {
  await createRoom()
  await assertSucceeds(get(ref(db('stranger'), `rooms/${CODE}/meta`)))
  await assertSucceeds(get(ref(db('stranger'), `rooms/${CODE}/members`)))
  await assertFails(get(ref(db('stranger'), `rooms/${CODE}/state`)))
  await assertFails(get(ref(db('stranger'), `rooms/${CODE}/chat`)))
  await assertFails(get(ref(db('stranger'), 'rooms')))
})

test('a member reads the whole room in one go; a stranger cannot read the room itself', async () => {
  await createRoom()
  await join('ann', 1)
  await assertSucceeds(get(ref(db('ann'), `rooms/${CODE}`)))
  await assertFails(get(ref(db('stranger'), `rooms/${CODE}`)))
  await assertSucceeds(get(ref(db('stranger'), `rooms/${CODE}/members`))) // still open to anyone who knows the code
})

test('joining takes a free seat; a full room, a taken seat and a seat for someone else are refused', async () => {
  await createRoom('host', 3)
  await assertSucceeds(join('ann', 1))
  await assertSucceeds(join('bob', 2))
  await assertFails(join('cat', 3)) // maxMembers is 3: seats 0 to 2 only
  await assertFails(join('cat', 1)) // taken
  await assertFails(set(ref(db('cat'), `rooms/${CODE}/members/1`), member('ann'))) // a seat in someone else's name
})

test('a member can leave and the host can remove a member; others cannot remove anyone', async () => {
  await createRoom()
  await join('ann', 1)
  await join('bob', 2)
  await assertFails(set(ref(db('bob'), `rooms/${CODE}/members/1`), null))
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/members/1`), null))
  await assertSucceeds(update(ref(db('bob')), { [`rooms/${CODE}/members/2`]: null, [`rooms/${CODE}/uids/bob`]: null }))
})

test('any member can change the playback, but only with the next sequence number', async () => {
  await createRoom()
  await join('ann', 1)
  await assertSucceeds(set(ref(db('ann'), `rooms/${CODE}/state`), stateAt(1)))
  await assertFails(set(ref(db('host'), `rooms/${CODE}/state`), stateAt(3))) // skipped one
  await assertFails(set(ref(db('host'), `rooms/${CODE}/state`), stateAt(1))) // not greater
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/state`), stateAt(2)))
})

test('a stranger cannot change the playback', async () => {
  await createRoom()
  await assertFails(set(ref(db('stranger'), `rooms/${CODE}/state`), stateAt(1)))
})

test('with host-only control on, members are refused and the host is not', async () => {
  await createRoom()
  await join('ann', 1)
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/meta/hostOnlyControl`), true))
  await assertFails(set(ref(db('ann'), `rooms/${CODE}/state`), stateAt(1)))
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/state`), stateAt(1)))
})

test('only the host can change the room settings', async () => {
  await createRoom()
  await join('ann', 1)
  await assertFails(set(ref(db('ann'), `rooms/${CODE}/meta/maxMembers`), 8))
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/meta/maxMembers`), 4))
})

test('a shared item can be a video, a live station or an episode, and its link must be https', async () => {
  await createRoom()
  const withKind = (extra) => stateAt(1, { track: { ...track, ...extra } })
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/state`), withKind({ kind: 'video' })))
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/state`), { ...withKind({ kind: 'radio', url: 'https://stream.example.org/live.mp3', videoId: '123e4567-e89b-12d3-a456-426614174000' }), seq: 2 }))
  await assertFails(set(ref(db('host'), `rooms/${CODE}/state`), { ...withKind({ kind: 'podcast', url: 'http://example.org/ep.mp3' }), seq: 3 }))
  await assertFails(set(ref(db('host'), `rooms/${CODE}/state`), { ...withKind({ kind: 'movie' }), seq: 3 }))
})

test('the queue holds 26 tracks at most', async () => {
  await createRoom()
  const items = (n) => Object.fromEntries(Array.from({ length: n }, (_, i) => [i, { videoId: `id${String(i).padStart(9, '0')}`, title: `Song ${i}` }]))
  await assertSucceeds(set(ref(db('host'), `rooms/${CODE}/queue`), { seq: 1, items: items(26) }))
  await assertFails(set(ref(db('host'), `rooms/${CODE}/queue`), { seq: 2, items: items(27) }))
})

test('chat: a member can post as themselves, not as someone else, not too long, and strangers cannot read it', async () => {
  await createRoom()
  await join('ann', 1)
  const msg = (uid, text = 'hello') => ({ uid, name: uid, kind: 'text', text, t: serverTimestamp() })
  await assertSucceeds(set(ref(db('ann'), `rooms/${CODE}/chat/m1`), msg('ann')))
  await assertFails(set(ref(db('ann'), `rooms/${CODE}/chat/m2`), msg('host'))) // impersonation
  await assertFails(set(ref(db('ann'), `rooms/${CODE}/chat/m3`), msg('ann', 'x'.repeat(501))))
  await assertFails(set(ref(db('stranger'), `rooms/${CODE}/chat/m4`), msg('stranger')))
  await assertFails(get(ref(db('stranger'), `rooms/${CODE}/chat`)))
  await assertSucceeds(get(ref(db('host'), `rooms/${CODE}/chat`)))
  await assertFails(set(ref(db('host'), `rooms/${CODE}/chat/m1`), msg('ann', 'edited'))) // no edits
})

test('voice setup messages go into the recipient\'s inbox, and only the recipient can read them', async () => {
  await createRoom()
  await join('ann', 1)
  const signal = { type: 'offer', payload: 'v=0', t: Date.now() }
  await assertSucceeds(set(ref(db('ann'), `signals/${CODE}/host/ann/s1`), signal))
  await assertFails(set(ref(db('ann'), `signals/${CODE}/host/host/s2`), signal)) // from someone else
  await assertFails(set(ref(db('ann'), `signals/${CODE}/nobody/ann/s3`), signal)) // recipient is not in the room
  await assertSucceeds(get(ref(db('host'), `signals/${CODE}/host/ann/s1`)))
  await assertFails(get(ref(db('stranger'), `signals/${CODE}/host/ann/s1`)))
  await assertSucceeds(set(ref(db('host'), `signals/${CODE}/host/ann/s1`), null)) // the recipient clears it
})

test('cloud sync: a user reaches only their own documents', async () => {
  const ann = env.authenticatedContext('ann').firestore()
  const bob = env.authenticatedContext('bob').firestore()
  await assertSucceeds(setDoc(doc(ann, 'users/ann/library/likes'), { updatedAt: 1 }))
  await assertSucceeds(getDoc(doc(ann, 'users/ann/library/likes')))
  await assertFails(getDoc(doc(bob, 'users/ann/library/likes')))
  await assertFails(setDoc(doc(bob, 'users/ann/playlists/p1'), { name: 'x' }))
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users/ann')))
})
