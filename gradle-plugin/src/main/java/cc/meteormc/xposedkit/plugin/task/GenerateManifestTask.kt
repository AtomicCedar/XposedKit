package cc.meteormc.xposedkit.plugin.task

import cc.meteormc.xposedkit.plugin.util.Metadata
import cc.meteormc.xposedkit.plugin.util.XmlUtil
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@CacheableTask
abstract class GenerateManifestTask : BaseTask() {
    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allManifests: ListProperty<RegularFile>

    @get:OutputFile
    abstract val output: RegularFileProperty

    override fun execute() {
        val metadata = parseMetadata()
        val document = XmlUtil.newDocument()

        val manifest = document.createElement("manifest")
        document.appendChild(manifest)

        val application = document.createElement("application")
        manifest.appendChild(application)

        val description = scanDescription()
        if (!description.isNullOrEmpty()) {
            document.createElement("meta-data").apply {
                setAttributeNS(ANDROID_NS, "name", "xposeddescription")
                setAttributeNS(ANDROID_NS, "value", description)
                application.appendChild(this)
            }
        }

        document.createElement("meta-data").apply {
            setAttributeNS(ANDROID_NS, "name", "xposedminversion")
            setAttributeNS(ANDROID_NS, "value", metadata.getProperty(Metadata.MIN_API))
            application.appendChild(this)
        }

        val settingsActivity = metadata.getProperty(Metadata.SETTINGS_ACTIVITY)
        if (!settingsActivity.isNullOrBlank()) {
            val activity = document.createElement("activity").apply {
                setAttributeNS(ANDROID_NS, "name", metadata.getProperty(Metadata.SETTINGS_ACTIVITY))
                setAttributeNS(ANDROID_NS, "exported", "true")
                application.appendChild(this)
            }
            val filter = document.createElement("intent-filter").apply {
                activity.appendChild(this)
            }
            document.createElement("action").apply {
                setAttributeNS(ANDROID_NS, "name", "android.intent.action.MAIN")
                filter.appendChild(this)
            }
            document.createElement("category").apply {
                setAttributeNS(ANDROID_NS, "name", "de.robv.android.xposed.category.MODULE_SETTINGS")
                filter.appendChild(this)
            }
        }

        val outputFile = output.get().asFile
        outputFile.parentFile.mkdirs()
        TransformerFactory.newInstance()
            .newTransformer()
            .transform(
                DOMSource(document),
                StreamResult(outputFile)
            )
    }

    fun scanDescription(): String? {
        return allManifests.get()
            .map { it.asFile }
            .filter { it.exists() }
            .firstNotNullOfOrNull {
                XmlUtil.parseDocument(it)
                    .getElementsByTagName("application")
                    .item(0)
                    ?.attributes
                    ?.getNamedItemNS(ANDROID_NS, "description")
                    ?.nodeValue
            }
    }
}