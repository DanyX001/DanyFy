package app.myspoti.host.android

import app.myspoti.host.android.LoginLoad.Blank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginLoadTest {

    @Test fun `spotify sign-in pages are login pages`() {
        assertTrue(LoginLoad.isLoginUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"))
        assertTrue(LoginLoad.isLoginUrl("https://accounts.spotify.com/authorize?client_id=x"))
        assertTrue(LoginLoad.isLoginUrl("https://challenge.spotify.com/c/abc"))
        assertTrue(LoginLoad.isLoginUrl("https://open.spotify.com/login"))
        assertTrue(LoginLoad.isLoginUrl("https://www.spotify.com/es/login/"))
    }

    @Test fun `continue with google facebook apple stays mid sign-in`() {
        assertTrue(LoginLoad.isLoginUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
        assertTrue(LoginLoad.isLoginUrl("https://www.facebook.com/login.php?x=1"))
        assertTrue(LoginLoad.isLoginUrl("https://m.facebook.com/dialog/oauth"))
        assertTrue(LoginLoad.isLoginUrl("https://appleid.apple.com/auth/authorize"))
    }

    @Test fun `the player is not a login page, whatever its query says`() {
        assertFalse(LoginLoad.isLoginUrl("https://open.spotify.com/"))
        assertFalse(LoginLoad.isLoginUrl("https://open.spotify.com/intl-es/?flow_ctx=36e1%3A1792946057&ubi=CAIQ"))
        // The old check read the whole address and took these for sign-in pages.
        assertFalse(LoginLoad.isLoginUrl("https://open.spotify.com/intl-fr/?continue=https://accounts.spotify.com/login"))
        assertFalse(LoginLoad.isLoginUrl("https://open.spotify.com/?next=facebook.com"))
        assertFalse(LoginLoad.isLoginUrl("https://evilfacebook.com/"))
    }

    @Test fun `nothing and nonsense are not login pages`() {
        assertFalse(LoginLoad.isLoginUrl(null))
        assertFalse(LoginLoad.isLoginUrl(""))
        assertFalse(LoginLoad.isLoginUrl("about:blank"))
        assertFalse(LoginLoad.isLoginUrl("not a url at all"))
    }

    @Test fun `only accounts spotify is the sign-in's own host`() {
        assertTrue(LoginLoad.isSpotifyAccounts("https://accounts.spotify.com/en/login"))
        assertFalse(LoginLoad.isSpotifyAccounts("https://accounts.google.com/"))
        assertFalse(LoginLoad.isSpotifyAccounts("https://open.spotify.com/?u=accounts.spotify.com"))
        assertFalse(LoginLoad.isSpotifyAccounts(null))
    }

    @Test fun `the page's answer says whether it painted`() {
        assertTrue(LoginLoad.painted("42"))
        assertTrue(LoginLoad.painted("\"7\""))
        assertFalse(LoginLoad.painted("0"))
        assertFalse(LoginLoad.painted("null"))
        assertFalse(LoginLoad.painted(null))
        assertFalse(LoginLoad.painted(""))
    }

    @Test fun `a blank page is waited on, loaded again once, then given up`() {
        assertEquals(Blank.WAIT, LoginLoad.blank(1, reloaded = false))
        assertEquals(Blank.WAIT, LoginLoad.blank(5, reloaded = false))
        assertEquals(Blank.RELOAD, LoginLoad.blank(6, reloaded = false))
        // After the reload the count starts over: the second page gets its own wait, then the error.
        assertEquals(Blank.WAIT, LoginLoad.blank(1, reloaded = true))
        assertEquals(Blank.WAIT, LoginLoad.blank(9, reloaded = true))
        assertEquals(Blank.FAIL, LoginLoad.blank(10, reloaded = true))
    }

    @Test fun `the wait counts only the time the screen is in front`() {
        val w = SessionWait(readyBudgetMs = 6_000, maxMs = 60_000)
        w.tick(10_000, inFront = false, ready = true)
        assertEquals(0L, w.elapsedMs)
        assertFalse(w.expired)
    }

    @Test fun `ready time is the short budget, a player that is not ready gets the long one`() {
        val ready = SessionWait(6_000, 60_000)
        repeat(12) { ready.tick(500, inFront = true, ready = true) }
        assertTrue(ready.expired)
        assertFalse(ready.playerNeverReady)

        val booting = SessionWait(6_000, 60_000)
        repeat(119) { booting.tick(500, inFront = true, ready = false) }
        assertFalse(booting.expired)
        booting.tick(500, inFront = true, ready = false)
        assertTrue(booting.expired)
        // Out of time without the player ever being ready: not "signed out".
        assertTrue(booting.playerNeverReady)
        assertEquals(0L, booting.waitedMs)
    }

    @Test fun `a turn that waited on the page counts its real time`() {
        // Each turn asks the page for its token and waits the bridge's 20 s: three turns are a minute,
        // not three turns of half a second.
        val w = SessionWait(6_000, 60_000)
        repeat(3) { w.tick(20_500, inFront = true, ready = false) }
        assertTrue(w.expired)
    }

    @Test fun `a gap while the phone slept is not time waited`() {
        val w = SessionWait(6_000, 60_000)
        w.tick(5 * 60_000, inFront = true, ready = false)
        assertEquals(SessionWait.MAX_TURN_MS, w.elapsedMs)
        assertFalse(w.expired)
    }

    @Test fun `regional google auth hosts are recognized as login and google auth pages`() {
        val urls = listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=1",
            "https://accounts.google.ro/v3/signin/identifier",
            "https://accounts.google.co.uk/o/oauth2/v2/auth",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://myaccount.google.com/",
        )
        for (u in urls) {
            assertTrue("expected login url: $u", LoginLoad.isLoginUrl(u))
            assertTrue("expected google auth url: $u", LoginLoad.isGoogleAuthUrl(u))
        }
        assertFalse(LoginLoad.isGoogleAuthUrl("https://accounts.spotify.com/en/login"))
        assertFalse(LoginLoad.isGoogleAuthUrl("https://open.spotify.com/"))
        assertFalse(LoginLoad.isGoogleAuthUrl("https://accounts.google.com.evil.com/login"))
        assertFalse(LoginLoad.isGoogleAuthUrl("https://evilgoogle.com/"))
    }

    @Test fun `allowed navigation enforces https and trusted spotify or federated auth domains`() {
        val allowed = listOf(
            "https://accounts.spotify.com/en/login",
            "https://challenge.spotify.com/c/123",
            "https://open.spotify.com/intl-fr/",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://accounts.google.ro/v3/signin",
            "https://accounts.google.co.uk/o/oauth2/v2/auth",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://ssl.gstatic.com/accounts/static/",
            "https://www.recaptcha.net/recaptcha/enterprise.js",
            "https://open.spotifycdn.com/cdn/build/web-player/",
            "https://m.facebook.com/dialog/oauth",
            "https://appleid.apple.com/auth/authorize",
        )
        for (u in allowed) {
            assertTrue("expected allowed: $u", LoginLoad.isAllowedNavigationUrl(u))
        }
        val refused = listOf(
            "http://accounts.spotify.com/en/login",
            "http://accounts.google.com/",
            "javascript:alert(1)",
            "file:///data/local/tmp/x.html",
            "https://evilspotify.com/",
            "https://accounts.google.com.evil.com/login",
            null,
            "",
        )
        for (u in refused) {
            assertFalse("expected refused: $u", LoginLoad.isAllowedNavigationUrl(u))
        }
    }

    @Test fun `shareable entry url rejects post-redirect cookie-bound google v3 signin urls`() {
        assertTrue(LoginLoad.isShareableEntryUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"))
        assertTrue(LoginLoad.isShareableEntryUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=123&redirect_uri=https%3A%2F%2Faccounts.spotify.com"))
        assertTrue(LoginLoad.isShareableEntryUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"))
        assertFalse(LoginLoad.isShareableEntryUrl("https://accounts.google.com/v3/signin/identifier?dsh=S123&ifkv=Abc123&flowName=GlifWebSignIn"))
        assertFalse(LoginLoad.isShareableEntryUrl("https://accounts.google.ro/v3/signin/challenge/pwd?ifkv=Abc123"))
        assertFalse(LoginLoad.isShareableEntryUrl("https://accounts.google.com/signin/rejected?rrk=46"))
        assertFalse(LoginLoad.isShareableEntryUrl(null))
        assertFalse(LoginLoad.isShareableEntryUrl(""))
    }
}
