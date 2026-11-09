package android.content.res

import java.io.FileNotFoundException
import java.io.InputStream

/** Ресурсы игры берутся из classpath (jar) по id, которые выдаёт com.tilcayo.fat.R. */
class Resources(private val paths: Map<Int, String>) {
    fun openRawResource(id: Int): InputStream {
        val path = paths[id] ?: throw FileNotFoundException("Ресурс $id не найден")
        return javaClass.getResourceAsStream(path) ?: throw FileNotFoundException(path)
    }
}
