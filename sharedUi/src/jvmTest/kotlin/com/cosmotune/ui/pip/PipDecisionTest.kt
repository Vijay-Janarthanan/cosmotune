package com.cosmotune.ui.pip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Who holds picture-in-picture: the one decision Video, Watch and the song's music video all go through. */
class PipDecisionTest {
    private fun claim(owner: PipOwner, ready: Boolean, since: Long) = PipClaim(owner, ready, since)

    @Test fun nobodyReadyMeansNobody() {
        assertNull(PipDecision.owner(emptyList(), showing = null))
        assertNull(PipDecision.owner(listOf(claim(PipOwner.WATCH, false, 1), claim(PipOwner.MUSIC_VIDEO, false, 2)), showing = null))
    }

    @Test fun theOneReadyPlayerHoldsIt() {
        val claims = listOf(claim(PipOwner.WATCH, false, 3), claim(PipOwner.MUSIC_VIDEO, true, 1))
        assertEquals(PipOwner.MUSIC_VIDEO, PipDecision.owner(claims, showing = null))
    }

    @Test fun aFilmOrAVideoBeatsTheSongsVideoWhicheverCameLast() {
        val claims = listOf(claim(PipOwner.WATCH, true, 1), claim(PipOwner.MUSIC_VIDEO, true, 9))
        assertEquals(PipOwner.WATCH, PipDecision.owner(claims, showing = null))
        val video = listOf(claim(PipOwner.MUSIC_VIDEO, true, 9), claim(PipOwner.VIDEO, true, 2))
        assertEquals(PipOwner.VIDEO, PipDecision.owner(video, showing = null))
    }

    @Test fun betweenEqualsTheNewestWins() {
        val claims = listOf(claim(PipOwner.VIDEO, true, 4), claim(PipOwner.WATCH, true, 7))
        assertEquals(PipOwner.WATCH, PipDecision.owner(claims, showing = null))
        assertEquals(PipOwner.VIDEO, PipDecision.owner(listOf(claim(PipOwner.VIDEO, true, 8), claim(PipOwner.WATCH, true, 7)), showing = null))
    }

    @Test fun thePlayerInTheWindowKeepsItEvenPausedAndAgainstABetterRankedOne() {
        val claims = listOf(claim(PipOwner.MUSIC_VIDEO, false, 1), claim(PipOwner.WATCH, true, 5))
        assertEquals(PipOwner.MUSIC_VIDEO, PipDecision.owner(claims, showing = PipOwner.MUSIC_VIDEO))
        assertEquals(PipOwner.MUSIC_VIDEO, PipDecision.owner(claims, showing = PipOwner.MUSIC_VIDEO, asked = PipOwner.WATCH), "an ask does not take it")
    }

    @Test fun aPlayerThatLeftTheWindowNoLongerHoldsIt() {
        val claims = listOf(claim(PipOwner.WATCH, true, 5))
        assertEquals(PipOwner.WATCH, PipDecision.owner(claims, showing = PipOwner.VIDEO), "Video's page went: the next ready one")
    }

    @Test fun aButtonAsksAndGetsItWhenNobodyIsInTheWindow() {
        val claims = listOf(claim(PipOwner.WATCH, true, 5), claim(PipOwner.MUSIC_VIDEO, false, 1))
        assertEquals(PipOwner.MUSIC_VIDEO, PipDecision.owner(claims, showing = null, asked = PipOwner.MUSIC_VIDEO), "the menu's item, the music paused")
    }

    // ---- the board that keeps the wishes ----------------------------------------------------------------------------------------

    @Test fun theBoardKeepsTheLastSpeakerOfEachOwnerAndForgetsItWhenItLeaves() {
        val board = PipCoordinator()
        val page = Any()
        val other = Any()
        board.update(page, PipOwner.WATCH, PipWish(ready = true))
        board.update(other, PipOwner.WATCH, PipWish(ready = false))
        board.withdraw(page, PipOwner.WATCH)
        assertTrue(board.claims(PipOwner.WATCH), "the page that spoke last keeps the wish when an older one leaves")
        board.withdraw(other, PipOwner.WATCH)
        assertFalse(board.claims(PipOwner.WATCH))
        assertNull(board.owner())
    }

    @Test fun becomingReadyAgainMakesAWishTheNewest() {
        val board = PipCoordinator()
        val video = Any()
        val watch = Any()
        board.update(video, PipOwner.VIDEO, PipWish(ready = true))
        board.update(watch, PipOwner.WATCH, PipWish(ready = true))
        assertEquals(PipOwner.WATCH, board.owner())
        board.update(video, PipOwner.VIDEO, PipWish(ready = false))
        board.update(video, PipOwner.VIDEO, PipWish(ready = true))
        assertEquals(PipOwner.VIDEO, board.owner(), "paused and played again: Video's wish is the newest")
        board.update(watch, PipOwner.WATCH, PipWish(ready = true, aspectWidth = 4, aspectHeight = 3))
        assertEquals(PipOwner.VIDEO, board.owner(), "a ready wish that only changed its shape keeps its place")
    }

    @Test fun enteringRecordsWhoIsInTheWindowAndAnAskWins() {
        val board = PipCoordinator()
        val watch = Any()
        val song = Any()
        board.update(watch, PipOwner.WATCH, PipWish(ready = true))
        board.update(song, PipOwner.MUSIC_VIDEO, PipWish(ready = false))
        assertTrue(board.ask(PipOwner.MUSIC_VIDEO))
        assertEquals(PipOwner.MUSIC_VIDEO, board.entered())
        assertNull(board.asked, "the ask is spent")
        assertFalse(board.ask(PipOwner.WATCH), "another player is in the window")
        assertEquals(PipOwner.MUSIC_VIDEO, board.owner())
        assertEquals(PipOwner.MUSIC_VIDEO, board.left())
        assertNull(board.showing)
        assertEquals(PipOwner.WATCH, board.owner(), "out of the window, the ready film holds it again")
    }

    @Test fun aWindowEnteredWithNobodyReadyBelongsToTheLastThatSpoke() {
        val board = PipCoordinator()
        board.update(Any(), PipOwner.VIDEO, PipWish(ready = false))
        board.update(Any(), PipOwner.WATCH, PipWish(ready = false))
        assertEquals(PipOwner.WATCH, board.entered())
    }

    @Test fun aFailedAskIsForgotten() {
        val board = PipCoordinator()
        board.update(Any(), PipOwner.WATCH, PipWish(ready = true))
        assertTrue(board.ask(PipOwner.VIDEO))
        board.cancelAsk()
        assertEquals(PipOwner.WATCH, board.owner())
    }

    @Test fun theWishTakesTheSourceRectangleWithoutBecomingAnotherWishWhenItIsTheSame() {
        val wish = PipWish(ready = true, aspectWidth = 1778, aspectHeight = 1000)
        assertSame(wish, wish.withSourceRect(null))
        val placed = wish.withSourceRect(androidx.compose.ui.geometry.Rect(0f, 10f, 100f, 66f))
        assertEquals(1778, placed.aspectWidth)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 10f, 100f, 66f), placed.sourceRect)
        assertEquals(1778, PipWish.aspectWidth(1.7778f))
        assertEquals(1778, PipWish.aspectWidth(0f), "16:9 before the picture's own shape is known")
        assertEquals(563, PipWish.aspectWidth(0.5625f), "a vertical picture")
    }

    @Test fun theBindingSendsTheBoundsInWholePixelsAndOnlyWhenTheyMove() {
        val sent = mutableListOf<PipWish>()
        val handle = object : PipHandle {
            override val supported = true
            override val active = false
            override fun update(wish: PipWish) { sent += wish }
            override fun enter() = false
        }
        val binding = PipBinding(handle)
        binding.bounds(androidx.compose.ui.geometry.Rect(0f, 0f, 10f, 10f))
        assertTrue(sent.isEmpty(), "nothing to send before the page has said what it wishes")
        binding.update(PipWish(ready = true))
        binding.bounds(androidx.compose.ui.geometry.Rect(0.4f, 63.2f, 1080.6f, 670.9f))
        binding.bounds(androidx.compose.ui.geometry.Rect(0.1f, 63.4f, 1080.2f, 670.1f))
        assertEquals(2, sent.size, "a sub-pixel move sends nothing")
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 63f, 1080f, 670f), sent.last().sourceRect)
    }

    // ---- the page goes, picture-in-picture stays -------------------------------------------------------------------------------

    @Test fun aPageThatLeavesWhileItsPlayerIsInTheWindowLeavesItsSessionThere() {
        val board = PipCoordinator()
        val page = Any()
        var closed = 0
        board.update(page, PipOwner.WATCH, PipWish(ready = true, keep = { PipWish(ready = false, onClosed = { closed++ }) }))
        assertTrue(board.ask(PipOwner.WATCH))
        board.entered()
        board.withdraw(page, PipOwner.WATCH)
        assertTrue(board.claims(PipOwner.WATCH), "the page went, the film plays on in the window")
        assertTrue(board.detached(PipOwner.WATCH))
        assertEquals(PipOwner.WATCH, board.owner(), "it still holds the window")
        board.update(Any(), PipOwner.MUSIC_VIDEO, PipWish(ready = true))
        assertEquals(PipOwner.WATCH, board.owner(), "a song's video starting elsewhere does not take it")
        board.wish(PipOwner.WATCH)!!.onClosed()
        assertEquals(1, closed, "the kept session's own close (stopping the film) is the one that runs")
        assertEquals(PipOwner.WATCH, board.left())
        board.dropDetached(PipOwner.WATCH)
        assertFalse(board.claims(PipOwner.WATCH), "out of the window, nothing is left of it")
    }

    @Test fun aPageThatLeavesOutsideTheWindowOrWithoutASessionTakesItsWishWithIt() {
        val board = PipCoordinator()
        val page = Any()
        board.update(page, PipOwner.WATCH, PipWish(ready = true, keep = { PipWish(ready = false) }))
        board.withdraw(page, PipOwner.WATCH)
        assertFalse(board.claims(PipOwner.WATCH), "not in the window: the page's leaving ends it")
        val video = Any()
        board.update(video, PipOwner.VIDEO, PipWish(ready = true))
        board.ask(PipOwner.VIDEO)
        board.entered()
        board.withdraw(video, PipOwner.VIDEO)
        assertFalse(board.claims(PipOwner.VIDEO), "no session to keep")
    }

    @Test fun aPageThatComesBackSpeaksForItsSessionAgain() {
        val board = PipCoordinator()
        val page = Any()
        board.update(page, PipOwner.WATCH, PipWish(ready = true, keep = { PipWish(ready = false) }))
        board.ask(PipOwner.WATCH)
        board.entered()
        board.withdraw(page, PipOwner.WATCH)
        board.update(Any(), PipOwner.WATCH, PipWish(ready = true))
        assertFalse(board.detached(PipOwner.WATCH), "the page is back")
        board.dropDetached(PipOwner.WATCH)
        assertTrue(board.claims(PipOwner.WATCH), "and its wish is not dropped with the session")
    }

    @Test fun anotherPlayersButtonTakesTheWindowFromASessionWhosePageHasGoneButNotFromAPage() {
        val board = PipCoordinator()
        val watch = Any()
        board.update(watch, PipOwner.WATCH, PipWish(ready = true, keep = { PipWish(ready = false) }))
        board.ask(PipOwner.WATCH)
        board.entered()
        board.update(Any(), PipOwner.VIDEO, PipWish(ready = false))
        assertFalse(board.ask(PipOwner.VIDEO), "the film's page is still there")
        board.withdraw(watch, PipOwner.WATCH)
        assertTrue(board.ask(PipOwner.VIDEO), "only its session is left: Video's button may take the window")
        assertEquals(PipOwner.VIDEO, board.owner())
        assertEquals(PipOwner.VIDEO, PipDecision.owner(listOf(claim(PipOwner.WATCH, false, 1).copy(detached = true)), PipOwner.WATCH, asked = PipOwner.VIDEO))
        assertEquals(PipOwner.WATCH, PipDecision.owner(listOf(claim(PipOwner.WATCH, false, 1).copy(detached = true)), PipOwner.WATCH), "nobody asking: it stays")
    }
}
