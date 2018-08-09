package com.waz.cache2

import java.io.{ByteArrayInputStream, File}

import com.waz.cache2.CacheService.{AESEncryption, NoEncryption}
import com.waz.model.AESKey
import com.waz.threading.CancellableFuture
import com.waz.utils.IoUtils
import com.waz.{FilesystemUtils, ZIntegrationSpec}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class CacheServiceSpec extends ZIntegrationSpec {

  import com.waz.utils.events.EventContext.Implicits.global

  private def testContent(size: Int): Array[Byte] = {
    val bytes = Array.ofDim[Byte](size)
    new Random().nextBytes(bytes)
    bytes
  }

  private def createCacheService(directorySizeThreshold: Long = 1024, sizeCheckingInterval: FiniteDuration = 0.seconds): CacheService =
    new LruCacheServiceImpl(FilesystemUtils.createDirectoryForTest(), directorySizeThreshold, sizeCheckingInterval)

  feature("CacheService") {

    scenario("Putting something in cache and getting back without encryption") {
      val key = "key"
      val content = testContent(200)

      val cache = createCacheService()
      for {
        _ <- cache.putBytes(key, content)(NoEncryption)
        fromCache <- cache.getBytes(key)(NoEncryption)
      } yield {
        fromCache.nonEmpty shouldBe true
        fromCache.get shouldBe content
      }
    }

    scenario("Putting something in cache and getting back with encryption") {
      val key = "key"
      val content = testContent(200)
      val encryption = AESEncryption(AESKey.random)

      val cache = createCacheService()
      for {
        _ <- cache.putBytes(key, content)(encryption)
        fromCache <- cache.getBytes(key)(encryption)
      } yield {
        fromCache.nonEmpty shouldBe true
        fromCache.get shouldBe content
      }
    }

    scenario("Putting file directly in cache") {
      val fileKey = "key"
      val content = testContent(200)
      val fileDirectory = FilesystemUtils.createDirectoryForTest()
      val file = new File(fileDirectory, "temporary_file_name")
      IoUtils.copy(new ByteArrayInputStream(content), file)

      val cache = createCacheService()
      for {
        _ <- cache.putEncrypted(fileKey, file)
        fromCache <- cache.getBytes(fileKey)(NoEncryption)
      } yield {
        file.exists() shouldBe false
        fromCache.nonEmpty shouldBe true
        fromCache.get shouldBe content
      }
    }

    scenario("Putting file directly in cache should trigger cache cleanup if it is needed") {
      val fileKey = "key"
      val (key1, key2) = ("key1", "key2")
      val content = testContent(200)
      val fileDirectory = FilesystemUtils.createDirectoryForTest()
      val file = new File(fileDirectory, "temporary_file_name")
      IoUtils.copy(new ByteArrayInputStream(content), file)

      val cache = createCacheService(directorySizeThreshold = content.length * 2, sizeCheckingInterval = 0.seconds)
      for {
        _ <- cache.putBytes(key1, content)(NoEncryption)
        _ <- CancellableFuture.delay(1.second).future
        _ <- cache.putBytes(key2, content)(NoEncryption)
        _ <- cache.putEncrypted(fileKey, file)
        _ <- CancellableFuture.delay(1.second).future //make sure that cache service has enough time to finish cleanup
        fromCache1 <- cache.getBytes(key1)(NoEncryption)
        fromCache2 <- cache.getBytes(key2)(NoEncryption)
        fromCacheFile <- cache.getBytes(fileKey)(NoEncryption)
      } yield {
        fromCache1.nonEmpty shouldBe false
        fromCache2.nonEmpty shouldBe true
        fromCache2.get shouldBe content
        fromCacheFile.nonEmpty shouldBe true
        fromCacheFile.get shouldBe content
      }
    }

    scenario("Lru functionality.") {
      val puttingTimeout = 1.second //https://bugs.openjdk.java.net/browse/JDK-8177809
      val directoryMaxSize = 1024
      val contentLength = 200
      val cacheCapacity = directoryMaxSize / contentLength
      val keys = (0 until cacheCapacity).map(i => s"key$i")
      val contents = keys.map(_ -> testContent(contentLength)).toMap
      def timeoutFor(key: String): Future[Unit] = CancellableFuture.delay(puttingTimeout*keys.indexOf(key)).future

      val cache = createCacheService(directoryMaxSize, sizeCheckingInterval = 0.seconds)
      for {
        _ <- Future.sequence { keys.map(key => timeoutFor(key).flatMap(_ => cache.putBytes(key, contents(key))(NoEncryption))) }
        fromCache0 <- cache.getBytes(keys(0))(NoEncryption)
        overflowKey = "overflow"
        overflowContent = testContent(contentLength)
        _ <- cache.putBytes(overflowKey, overflowContent)(NoEncryption) //this action should trigger cache cleanup process
        _ <- CancellableFuture.delay(1.second).future //make sure that cache service has enough time to finish cleanup
        fromCache1 <- cache.getBytes(keys(1))(NoEncryption)
        fromCacheOverflow <- cache.getBytes(overflowKey)(NoEncryption)
      } yield {
        fromCache0.nonEmpty shouldBe true
        fromCache0.get shouldBe contents(keys(0))
        fromCache1 shouldBe None
        fromCacheOverflow.nonEmpty shouldBe true
        fromCacheOverflow.get shouldBe overflowContent
      }
    }

  }


}
