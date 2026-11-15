package eu.teele.neugesplitter.ui.invite

import eu.teele.neugesplitter.ui.backend.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvitationTest {

    private val location =
        Location(
            endpoint = "https://s3.example",
            bucket = "splits",
            prefix = "trip/2026",
            region = "eu-central-1",
            pathStyle = true,
            accessKey = "AK",
            secretKey = "S/K+=&?",
        )

    @Test
    fun `an invitation reads back as it was written, passphrase and all (BE-18)`() {
        val invitation = Invitation(location, "fluss kabel & nebel ü")
        assertEquals(invitation, Invitation.parse(invitation.encoded()))
    }

    @Test
    fun `an unencrypted group's invitation carries no passphrase (BE-18)`() {
        val encoded = Invitation(location).encoded()
        assertFalse("passphrase" in encoded)
        assertNull(Invitation.parse(encoded)?.passphrase)
    }

    @Test
    fun `a bare host is written as the HTTPS address it stands for (BE-18)`() {
        val parsed = Invitation.parse(Invitation(location.copy(endpoint = "s3.example")).encoded())
        assertEquals("https://s3.example", parsed?.location?.endpoint)
        assertTrue(parsed?.location?.complete == true)
    }

    @Test
    fun `anything else is no invitation (BE-18)`() {
        assertNull(Invitation.parse("https://s3.example"))
        assertNull(Invitation.parse("neugesplitter:join?endpoint=s3.example&bucket=splits"))
        assertNull(Invitation.parse("neugesplitter:join?endpoint=%zz&bucket=b"))
    }

    @Test
    fun `an invitation does not show its secrets when logged (BE-18)`() {
        val text = Invitation(location, "geheim").toString()
        assertFalse("S/K" in text || "geheim" in text)
    }
}
