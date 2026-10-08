package eu.teele.neugesplitter.backend.s3

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AWS Signature Version 4 test suite (TR-28), as botocore keeps it under
 * `tests/unit/auth/aws4_testsuite`.
 */
class SigV4Test {

    private val credentials = Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")

    private val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun signed(
        method: String = "GET",
        path: String = "/",
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        payload: String = "",
    ) =
        Signed(
            method,
            SigV4.encode(path, path = true),
            SigV4.query(query),
            mapOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150830T123600Z") + headers,
            SigV4.sha256(payload.toByteArray()),
        )

    private fun signature(request: Signed): String =
        SigV4.authorization(request, credentials, "us-east-1", "service")
            .substringAfter("Signature=")

    @Test
    fun `get-vanilla, step by step (TR-28)`() {
        val request = signed()
        assertEquals(
            "GET\n/\n\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n$empty",
            SigV4.canonicalRequest(request),
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n20150830T123600Z\n20150830/us-east-1/service/aws4_request\n" +
                "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63",
            SigV4.stringToSign(request, "20150830/us-east-1/service/aws4_request"),
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            SigV4.authorization(request, credentials, "us-east-1", "service"),
        )
    }

    @Test
    fun `get-vanilla-query-order-key-case (TR-28)`() {
        assertEquals(
            "b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500",
            signature(signed(query = listOf("Param2" to "value2", "Param1" to "value1"))),
        )
    }

    @Test
    fun `get-vanilla-query-unreserved (TR-28)`() {
        val unreserved = "-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        assertEquals(
            "9c3e54bfcdf0b19771a7f523ee5669cdf59bc7cc0884027167c21bb143a40197",
            signature(signed(query = listOf(unreserved to unreserved))),
        )
    }

    @Test
    fun `get-utf8 (TR-28)`() {
        assertEquals("/%E1%88%B4", SigV4.encode("/\u1234", path = true))
        assertEquals(
            "8318018e0b0f223aa2bbf98705b62bb787dc9c0e678f255a891fd03141be5d85",
            signature(signed(path = "/\u1234")),
        )
    }

    @Test
    fun `get-header-value-trim (TR-28)`() {
        val headers = mapOf("My-Header1" to " value1", "My-Header2" to " \"a   b   c\"")
        assertEquals(
            "acc3ed3afb60bb290fc8d2dd0098b9911fcaa05412b367055dee359757a9c736",
            signature(signed(headers = headers)),
        )
    }

    @Test
    fun `post-x-www-form-urlencoded (TR-28)`() {
        val request =
            signed(
                method = "POST",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                payload = "Param1=value1",
            )
        assertEquals(
            "ff11897932ad3f4e8b18135d722051e5ac45fc38421b1da7b9d196a0fe09473a",
            signature(request),
        )
    }

    @Test
    fun `a query is encoded strictly, slashes and all (TR-28)`() {
        assertEquals(
            "a=1&b=x%2Fy&b=x%2Fy%20z",
            SigV4.query(listOf("b" to "x/y z", "a" to "1", "b" to "x/y")),
        )
    }
}
