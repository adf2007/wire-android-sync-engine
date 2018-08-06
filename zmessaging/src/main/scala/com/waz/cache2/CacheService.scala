/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.cache2

import java.io._
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.cache2.CacheServiceImpl.Encryption
import com.waz.model.AESKey
import com.waz.utils.IoUtils
import com.waz.utils.crypto.AESUtils
import com.waz.utils.events._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

trait CacheService {
  implicit def ec: ExecutionContext

  protected def getOutputStream(key: String): OutputStream
  protected def getInputStream(key: String): Option[InputStream]

  def get(key: String)(encryption: Encryption): Future[Option[InputStream]] =
    Future(getInputStream(key).map(encryption.decrypt))
  def put(key: String, in: InputStream)(encryption: Encryption): Future[Unit] =
    Future(IoUtils.copy(in, encryption.encrypt(getOutputStream(key))))

  def getBytes(key: String)(encryption: Encryption): Future[Option[Array[Byte]]] =
    get(key)(encryption).map(_.map(IoUtils.toByteArray))
  def putBytes(key: String, bytes: Array[Byte])(encryption: Encryption): Future[Unit] =
    put(key, new ByteArrayInputStream(bytes))(encryption)

}

trait UnsafeCacheService extends CacheService {
  def putEncrypted(key: String, file: File): Future[Unit]
}

object FileUtils {

  def getDirectorySize(directory: File)(implicit ec: ExecutionContext): Future[Long] = Future {
    var size = 0L
    Files.walkFileTree(Paths.get(directory.toURI), new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        super.visitFile(file, attrs)
        size += attrs.size()
        FileVisitResult.CONTINUE
      }
    })

    size
  }

  def getFilesWithAttributes(directory: File)(implicit ec: ExecutionContext): Seq[(Path, BasicFileAttributes)] = {
    val buffer = ArrayBuffer.empty[(Path, BasicFileAttributes)]
    Files.walkFileTree(Paths.get(directory.toURI), new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        buffer.append(file -> attrs)
        FileVisitResult.CONTINUE
      }
      override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
        verbose(s"Error occurred while file visiting process. $exc")
        FileVisitResult.CONTINUE
      }
    })

    buffer
  }

}


object LoggingUtils {

  def formatSize(size: Long): String = {
    if (size <= 0) return "0"
    val units = Array[String]("B", "kB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size) / Math.log10(1024)).toInt
    new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units(digitGroups)
  }

}

class LruCacheServiceImpl(cacheDirectory: File, directorySizeThreshold: Long)
                         (implicit override val ec: ExecutionContext, ev: EventContext) extends UnsafeCacheService {

  private val directorySize: SourceSignal[Long] = Signal()
  directorySize
    .filter { size =>
      verbose(s"Current cache size: ${LoggingUtils.formatSize(size)}")
      size > directorySizeThreshold
    } { size =>
      var shouldBeCleared = size - directorySizeThreshold
      verbose(s"Cache directory size threshold reached. Current size: ${LoggingUtils.formatSize(size)}.")
      FileUtils.getFilesWithAttributes(cacheDirectory)
        .sortBy(_._2.lastModifiedTime())
        .takeWhile { case (filePath, attr) =>
          val file = filePath.toFile
          if (file.delete()) {
            verbose(s"File '${file.getName}' removed. Cleared ${LoggingUtils.formatSize(attr.size())}.")
            shouldBeCleared -= attr.size()
          } else {
            verbose(s"File '${file.getName}' can not be removed. Not cleared ${LoggingUtils.formatSize(attr.size())}.")
          }
          shouldBeCleared > 0
        }
    }

  updateDirectorySize()

  def updateDirectorySize(): Unit =
    FileUtils.getDirectorySize(cacheDirectory).foreach(size => directorySize ! size)

  private def getFile(key: String): File = new File(cacheDirectory, key)

  override protected def getOutputStream(key: String): OutputStream = {
    val file = getFile(key)
    file.createNewFile()
    new FileOutputStream(file)
  }

  override protected def getInputStream(key: String): Option[InputStream] = {
    val file = getFile(key)
    if (file.exists()) Some(new FileInputStream(file))
    else None
  }

  override def put(key: String, in: InputStream)(encryption: Encryption): Future[Unit] = {
    super.put(key, in)(encryption).map(_ => updateDirectorySize())
  }

  def putEncrypted(key: String, file: File): Future[Unit] = Future {
    Files.move(Paths.get(file.toURI), Paths.get(getFile(key).toURI))
    directorySize ! (directorySize.currentValue.getOrElse(0L) + file.length())
  }

}

object CacheServiceImpl {

  trait Encryption {
    def decrypt(is: InputStream): InputStream
    def encrypt(os: OutputStream): OutputStream
  }

  case object NoEncryption extends Encryption {
    override def decrypt(is: InputStream): InputStream = is
    override def encrypt(os: OutputStream): OutputStream = os
  }

  class AESEncryption(val key: AESKey) extends Encryption {
    override def decrypt(is: InputStream): InputStream = AESUtils.inputStream(key, is)
    override def encrypt(os: OutputStream): OutputStream = AESUtils.outputStream(key, os)
  }

  object AESEncryption {
    def apply(key: AESKey): AESEncryption = new AESEncryption(key)
  }


}
