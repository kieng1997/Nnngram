package org.telegram.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Generates the install-identity resources for this build's applicationId: the
 * account type, contact mime types and account-settings intent target (from the
 * templates in identity/xml), plus the launcher label.
 *
 * Android does not expand `${applicationId}` inside res/ files, so a renamed package
 * would otherwise keep registering accounts, contact rows and intents under the old
 * id and collide with its siblings.
 */
@CacheableTask
abstract class GenerateIdentityResTask : DefaultTask() {

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val appLabel: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templates: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        val xmlDir = File(target, "xml").apply { mkdirs() }
        val valuesDir = File(target, "values").apply { mkdirs() }

        // NnnAppLabel rather than NullgramName: the localization pipeline packs
        // NullgramName into the bundled string assets, and a resource generated here
        // cannot also come from src/main/res.
        File(valuesDir, "strings_identity.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="NnnAppLabel" translatable="false">${appLabel.get()}</string>
            </resources>
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val applicationId = applicationId.get()

        templates.get().asFile
            .listFiles { file -> file.isFile && file.extension == "xml" }
            .orEmpty()
            .forEach { template ->
                File(xmlDir, template.name).writeText(
                    template.readText(Charsets.UTF_8).replace("__APPLICATION_ID__", applicationId),
                    Charsets.UTF_8,
                )
            }
    }
}
