package android.net

class Uri private constructor(private val value: String) {
    override fun toString(): String = value
    val scheme: String? get() = value.substringBefore(':', "").ifBlank { null }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic
        fun fromFile(path: String): Uri = Uri("file://$path")
    }
}
