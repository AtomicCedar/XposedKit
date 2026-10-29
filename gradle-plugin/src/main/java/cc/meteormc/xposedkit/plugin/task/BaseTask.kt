package cc.meteormc.xposedkit.plugin.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.Properties

@DisableCachingByDefault
abstract class BaseTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadataInput: RegularFileProperty

    @TaskAction
    abstract fun execute()

    protected fun parseMetadata(): Properties {
        val metadata = Properties()
        metadataInput.get().asFile.bufferedReader().use { stream ->
            metadata.load(stream)
        }
        return metadata
    }
}