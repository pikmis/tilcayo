package android.content.pm

class PackageInfo(val versionCode: Int, val longVersionCode: Long)

/** На ПК версию берём из versionName игры: GameView лишь сравнивает её с remoteCode. */
class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo =
        PackageInfo(VERSION_CODE, VERSION_CODE.toLong())

    companion object {
        const val VERSION_CODE = 7
        const val GET_META_DATA = 128
    }
}
