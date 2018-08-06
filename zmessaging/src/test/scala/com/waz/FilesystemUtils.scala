package com.waz

import java.io.File

object FilesystemUtils {

  lazy val globalDirectoryForTests = new File(System.getProperty("java.io.tmpdir"))

  def createDirectoryForTest(directoryName: String = s"directory_for_test_${System.currentTimeMillis()}"): File = {
    val directory = new File(globalDirectoryForTests, directoryName)
    directory.mkdir()
    directory
  }

}
