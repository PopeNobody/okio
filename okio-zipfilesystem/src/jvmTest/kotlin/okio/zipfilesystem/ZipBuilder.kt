/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio.zipfilesystem

import okio.ExperimentalFileSystem
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.sink
import org.junit.Assume.assumeTrue

/**
 * Execute the `zip` command line program to create reference zip files for testing.
 *
 * Unfortunately the `zip` command line is limited in its ability to create exactly the zip files
 * we want for testing. In particular, the only way it will create zip64 archives is if the input
 * file is received from a UNIX pipe. (In such cases the file length is unknown in advance, and so
 * the tool uses zip64 for the possibility of a very large file.) Files received from a pipe are
 * always named `-`.
 */
@ExperimentalFileSystem
class ZipBuilder(
  private val directory: Path
) {
  private val fileSystem = FileSystem.SYSTEM

  val options = mutableListOf<String>()
  val entries = mutableListOf<Entry>()
  var archiveComment: String = ""

  fun build(): Path {
    assumeTrue("ZipBuilder doesn't work on Windows", Path.DIRECTORY_SEPARATOR == "/")

    val archive = directory / "${randomToken()}.zip"
    val anyZip64 = entries.any { it.zip64 }

    require(!anyZip64 || entries.size == 1) {
      "ZipBuilder permits at most one zip64 entry"
    }
    require(!anyZip64 || archiveComment.isEmpty()) {
      "Cannot combine archiveComment with zip64"
    }

    val promptForComments = entries.any { it.comment.isNotEmpty() }
    if (promptForComments) {
      options += "--entry-comments"
    }
    if (archiveComment.isNotEmpty()) {
      options += "--archive-comment"
    }

    val command = mutableListOf<String>()
    command += "zip"
    command += options
    command += archive.toString()

    for (entry in entries) {
      if (!entry.zip64) {
        val absolutePath = directory / entry.path
        fileSystem.createDirectories(absolutePath.parent!!)

        if (entry.directory) {
          fileSystem.createDirectories(absolutePath)
        } else {
          fileSystem.write(absolutePath) {
            writeUtf8(entry.content!!)
          }
        }

        if (entry.modifiedAt != null) {
          val exitCode = ProcessBuilder()
            .command("touch", "-m", "-t", entry.modifiedAt, absolutePath.toString())
            .apply { environment()["TZ"] = "UTC" }
            .start()
            .waitFor()
          require(exitCode == 0)
        }
      }
      command += entry.path
    }

    val process = ProcessBuilder()
      .command(command)
      .directory(directory.toFile())
      .redirectOutput(ProcessBuilder.Redirect.INHERIT)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()

    process.outputStream.sink().buffer().use { sink ->
      if (anyZip64) {
        sink.writeUtf8(entries.single().content!!)
      }
      if (promptForComments) {
        for (entry in entries) {
          sink.writeUtf8(entry.comment)
          sink.writeUtf8("\n")
          sink.flush()
        }
      }
      sink.writeUtf8(archiveComment)
    }

    val result = process.waitFor()
    require(result == 0) { "process failed: $command" }

    return archive
  }

  class Entry(
    val path: String,
    val content: String? = null,
    val directory: Boolean = false,
    val comment: String = "",
    val modifiedAt: String? = null,
    val zip64: Boolean = false
  ) {
    init {
      require(directory != (content != null)) { "must be a directory or have content" }
      if (zip64) {
        require(path == "-") { "zip64 file name must be '-'" }
        require(comment == "") { "zip64 must not have comments" }
        require(modifiedAt == null) { "zip64 must not have modifiedAt" }
      }
    }
  }
}
