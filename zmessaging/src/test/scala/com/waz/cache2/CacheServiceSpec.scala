package com.waz.cache2

import java.io.ByteArrayInputStream

import com.waz.cache2.CacheServiceImpl.{AESEncryption, NoEncryption}
import com.waz.model.AESKey
import com.waz.threading.CancellableFuture
import com.waz.utils.IoUtils
import com.waz.{FilesystemUtils, ZIntegrationSpec}

import scala.concurrent.Future
import scala.concurrent.duration._

class CacheServiceSpec extends ZIntegrationSpec {

  private val testContent = (1 to 200).map(_.toByte).toArray

  import com.waz.utils.events.EventContext.Implicits.global

  private def createCacheService(directorySizeThreshold: Long = 1024): UnsafeCacheService =
    new LruCacheServiceImpl(FilesystemUtils.createDirectoryForTest(), directorySizeThreshold)

  feature("CacheService") {

    scenario("Putting something in cache and getting back") {
      val cache = createCacheService()
      val key = "key"
      for {
        _ <- cache.put(key, new ByteArrayInputStream(testContent))(NoEncryption)
        fromCache <- cache.get(key)(NoEncryption)
      } yield {
        fromCache.nonEmpty shouldBe true
        IoUtils.toByteArray(fromCache.get) shouldBe testContent
      }
    }

    scenario("Putting something in cache and getting back with encryption") {
      val cache = createCacheService()
      val key = "key"
      val encryption = AESEncryption(AESKey.random)
      for {
        _ <- cache.putBytes(key, testContent)(encryption)
        fromCache <- cache.getBytes(key)(encryption)
      } yield {
        fromCache.nonEmpty shouldBe true
        fromCache.get shouldBe testContent
      }
    }

    scenario("Lru functionality.") {
      val puttingTimeout = 1.second //https://bugs.openjdk.java.net/browse/JDK-8177809
      val directoryMaxSize = 1024
      val keys = (0 to (directoryMaxSize / testContent.length) + 1).map(i => s"key$i")
      def timeoutFor(key: String): Future[Unit] = CancellableFuture.delay(puttingTimeout*keys.indexOf(key)).future
      val cache = createCacheService(directoryMaxSize)
      for {
        _ <- Future.sequence { keys.map(key => timeoutFor(key).flatMap(_ => cache.putBytes(key, testContent)(NoEncryption))) }
        fromCache0 <- cache.get(keys(0))(NoEncryption)
        fromCache1 <- cache.get(keys(1))(NoEncryption)
      } yield {
        fromCache0.isEmpty shouldBe true
        fromCache1.nonEmpty shouldBe true
        IoUtils.toByteArray(fromCache1.get) shouldBe testContent
      }
    }

  }


}
