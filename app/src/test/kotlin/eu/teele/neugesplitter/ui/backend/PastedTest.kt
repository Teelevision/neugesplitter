package eu.teele.neugesplitter.ui.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PastedTest {

    @Test
    fun `the app's own keys fill every field`() {
        val text =
            """
            endpoint: https://s3.example
            bucket: splits
            prefix: household/
            region: eu-central-1
            path_style: true
            access_key: AK
            secret_key: SK
            """
                .trimIndent()
        assertEquals(
            Pasted("https://s3.example", "splits", "household/", "eu-central-1", true, "AK", "SK"),
            pasted(text),
        )
    }

    @Test
    fun `AWS and env names are understood, with export and quotes`() {
        val text =
            """
            export AWS_ACCESS_KEY_ID="AK"
            export AWS_SECRET_ACCESS_KEY='S/K+='
            AWS_DEFAULT_REGION=auto
            AWS_ENDPOINT_URL=https://abc.r2.cloudflarestorage.com
            """
                .trimIndent()
        assertEquals(
            Pasted(
                endpoint = "https://abc.r2.cloudflarestorage.com",
                region = "auto",
                accessKey = "AK",
                secretKey = "S/K+=",
            ),
            pasted(text),
        )
    }

    @Test
    fun `rclone's addressing and JSON-like lines are understood`() {
        val text =
            """
            [minio]
            type = s3
            endpoint = http://nas.local:9000
            force_path_style = true
            "bucket": "splits",
            """
                .trimIndent()
        assertEquals(
            Pasted(endpoint = "http://nas.local:9000", bucket = "splits", pathStyle = true),
            pasted(text),
        )
        assertEquals(true, pasted("addressing_style = path").pathStyle)
        assertEquals(false, pasted("addressing_style = virtual").pathStyle)
    }

    @Test
    fun `the first mention counts, and unknown or empty lines are skipped`() {
        val text = "bucket: first\nbucket: second\nsecret_key:\nflavour: vanilla\nnot a setting"
        assertEquals(Pasted(bucket = "first"), pasted(text))
        assertTrue(pasted("hello\nworld").empty)
    }
}
