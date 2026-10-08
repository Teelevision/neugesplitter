package eu.teele.neugesplitter.ui.backend

/**
 * The location fields a pasted text names, null for those it leaves out (ui-design 6). One `key:
 * value` or `key = value` per line; the keys are this app's own, or what AWS, rclone and the usual
 * `.env` files call them. Anything else is skipped (ui-design 6).
 */
data class Pasted(
    val endpoint: String? = null,
    val bucket: String? = null,
    val prefix: String? = null,
    val region: String? = null,
    val pathStyle: Boolean? = null,
    val accessKey: String? = null,
    val secretKey: String? = null,
) {
    val empty: Boolean
        get() = this == Pasted()
}

private enum class Field {
    ENDPOINT,
    BUCKET,
    PREFIX,
    REGION,
    PATH_STYLE,
    ACCESS_KEY,
    SECRET_KEY,
}

private val KEYS =
    mapOf(
        Field.ENDPOINT to listOf("endpoint", "endpointurl", "awsendpointurl", "s3endpoint", "url"),
        Field.BUCKET to listOf("bucket", "bucketname", "awsbucket", "s3bucket"),
        Field.PREFIX to listOf("prefix", "path", "folder"),
        Field.REGION to listOf("region", "awsregion", "awsdefaultregion", "s3region"),
        Field.PATH_STYLE to
            listOf(
                "pathstyle",
                "forcepathstyle",
                "usepathstyle",
                "addressingstyle",
                "s3forcepathstyle",
            ),
        Field.ACCESS_KEY to listOf("accesskey", "accesskeyid", "awsaccesskeyid", "keyid"),
        Field.SECRET_KEY to
            listOf("secretkey", "secretaccesskey", "awssecretaccesskey", "secret", "applicationkey"),
    )

private val FIELDS = KEYS.flatMap { (field, keys) -> keys.map { it to field } }.toMap()

private val LINE =
    Regex("""^\s*(?:export\s+)?"?([A-Za-z][A-Za-z0-9_\- ]*?)"?\s*[:=]\s*(.*?)\s*,?\s*$""")

/** Reads the fields out of [text], the first mention of each counting. */
fun pasted(text: String): Pasted {
    val found = mutableMapOf<Field, String>()
    for (line in text.lineSequence()) {
        val match = LINE.matchEntire(line) ?: continue
        val (key, raw) = match.destructured
        val field = FIELDS[key.lowercase().filter { it.isLetterOrDigit() }] ?: continue
        val value = raw.removeSurrounding("\"").removeSurrounding("'")
        if (value.isNotEmpty()) found.putIfAbsent(field, value)
    }
    return Pasted(
        endpoint = found[Field.ENDPOINT],
        bucket = found[Field.BUCKET],
        prefix = found[Field.PREFIX],
        region = found[Field.REGION],
        pathStyle =
            found[Field.PATH_STYLE]?.lowercase()?.let {
                it in setOf("true", "yes", "on", "1", "path")
            },
        accessKey = found[Field.ACCESS_KEY],
        secretKey = found[Field.SECRET_KEY],
    )
}
