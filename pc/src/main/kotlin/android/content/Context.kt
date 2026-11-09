// Мини-реализация android.content (Context / Intent / SharedPreferences) для ПК-порта.
package android.content

import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import java.io.File

abstract class Context {
    abstract val resources: Resources
    abstract val packageName: String
    abstract val packageManager: PackageManager
    abstract val cacheDir: File
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    abstract fun startActivity(intent: Intent)
    open fun getSystemService(name: String): Any? = null

    companion object {
        const val MODE_PRIVATE = 0
        const val MODE_APPEND = 32768
        const val INPUT_METHOD_SERVICE = "input_method"
        const val WINDOW_SERVICE = "window"
    }
}

class Intent(val action: String?, val data: Uri?) {
    private val flags = HashSet<Int>()
    fun addFlags(flags: Int): Intent { this.flags.add(flags); return this }
    fun hasFlag(flag: Int): Boolean = flags.contains(flag)

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}

interface SharedPreferences {
    fun getLong(key: String, defValue: Long): Long
    fun getInt(key: String, defValue: Int): Int
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getString(key: String, defValue: String?): String?
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putLong(key: String, value: Long): Editor
        fun putInt(key: String, value: Int): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putString(key: String, value: String?): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}
