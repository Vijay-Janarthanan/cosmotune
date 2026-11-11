package com.cosmotune.video.signed

/**
 * What this session learned about the signed clients, so the next video does not wait on one that keeps failing. A client is blamed
 * when it failed for a video another client then answered (the video could be played; that client could not); [STRIKES] blames in a
 * row bench it for [BENCH_MS], and an answer clears its record. A video every client refuses blames nobody: that is the video.
 *
 * [waves] orders a race ([SignedPlayerResolver.resolve]): the first client asked alone, then the others of the usual order together,
 * then the benched ones and the last resorts ([SignedPlayerClient.lastResort]), which are asked only when everything before them failed.
 * What is learned belongs to one account: [clear] forgets it.
 */
internal class ClientRanking(private val clock: () -> Long) {
    private val blames = HashMap<SignedPlayerClient, Int>()
    private val benchedUntil = HashMap<SignedPlayerClient, Long>()

    /** A race's three waves: [first] asked alone, [rest] together after it, [late] only when both failed. Any may be empty. */
    class Waves(val first: List<SignedPlayerClient>, val rest: List<SignedPlayerClient>, val late: List<SignedPlayerClient>)

    /** The waves of a race over [clients], their order kept inside each wave. */
    @Synchronized
    fun waves(clients: List<SignedPlayerClient>): Waves {
        val benched = benchedNow()
        val (late, usual) = clients.partition { it.lastResort || it in benched }
        // Nothing usual left (all benched or last resorts): the late ones go one by one in their order, never raced.
        if (usual.isEmpty()) return Waves(late.take(1), emptyList(), late.drop(1))
        return Waves(usual.take(1), usual.drop(1), late)
    }

    /** [winner] answered a video that [failedFirst] had failed: they are blamed, [winner]'s record is clear. */
    @Synchronized
    fun answered(winner: SignedPlayerClient, failedFirst: Collection<SignedPlayerClient>) {
        blames.remove(winner)
        benchedUntil.remove(winner)
        for (client in failedFirst) {
            if (client == winner) continue
            val count = (blames[client] ?: 0) + 1
            blames[client] = count
            if (count >= STRIKES) benchedUntil[client] = clock() + BENCH_MS
        }
    }

    /** The clients benched now. */
    @Synchronized
    fun benched(): Set<SignedPlayerClient> = benchedNow()

    /** Forgets everything (another account). */
    @Synchronized
    fun clear() {
        blames.clear()
        benchedUntil.clear()
    }

    private fun benchedNow(): Set<SignedPlayerClient> {
        val now = clock()
        val over = benchedUntil.filterValues { it <= now }.keys
        for (client in over) {
            benchedUntil.remove(client)
            blames.remove(client)
        }
        return benchedUntil.keys.toSet()
    }

    companion object {
        /** Blames in a row that bench a client. */
        const val STRIKES = 2

        /** How long a benched client waits at the back: long enough to stop paying for it, short enough for a blip to pass. */
        const val BENCH_MS = 15 * 60_000L
    }
}
