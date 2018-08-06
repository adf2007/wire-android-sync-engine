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
package com.waz.utils.crypto

import java.io._
import java.security.{DigestInputStream, DigestOutputStream, MessageDigest}
import java.util.Base64

import javax.crypto.{BadPaddingException, Cipher, CipherInputStream, CipherOutputStream}
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import com.waz.model.{AESKey, Sha256}
import com.waz.utils.{IoUtils, returning}

/**
  * Utils for symmetric encryption.
  *
  * Uses standard AES (usually 256, depending on used key) in CBC mode with PKCS#5/7 padding and the initialization vector (IV) prepended to the ciphertext.
  */
object AESUtils {

  //TODO Why not SecureRandom?
  lazy val randomBytes = new RandomBytes()

  def base64(key: Array[Byte]): String = Base64.getEncoder.encodeToString(key)
  def base64(key: String): Array[Byte] = Base64.getDecoder.decode(key)

  def randomKey(): AESKey = AESKey(randomBytes(32))
  def randomKey128(): AESKey = AESKey(randomBytes(16))

  def cipher(key: AESKey, iv: Array[Byte], mode: Int) =
    returning(Cipher.getInstance("AES/CBC/PKCS5Padding")) { _.init(mode, new SecretKeySpec(key.bytes, "AES"), new IvParameterSpec(iv)) }

  def decrypt(key: AESKey, input: Array[Byte]): Array[Byte] =
    cipher(key, input.take(16), Cipher.DECRYPT_MODE).doFinal(input.drop(16))

  def encrypt(key: AESKey, bytes: Array[Byte]): (Sha256, Array[Byte]) = {
    val os = new ByteArrayOutputStream()
    val sha = encrypt(key, new ByteArrayInputStream(bytes), os)
    (sha, os.toByteArray)
  }

  def encrypt(key: AESKey, is: InputStream, os: OutputStream): Sha256 = {
    val out = new DigestOutputStream(os, MessageDigest.getInstance("SHA-256"))
    IoUtils.copy(is, outputStream(key, out))
    Sha256(out.getMessageDigest.digest())
  }

  def decrypt(key: AESKey, is: InputStream, os: OutputStream): Sha256 = {
    val shaStream = new DigestInputStream(is, MessageDigest.getInstance("SHA-256"))
    IoUtils.copy(inputStream(key, shaStream), os)
    Sha256(shaStream.getMessageDigest.digest())
  }

  def outputStream(key: AESKey, os: OutputStream) = {
//    val iv = randomBytes(16)
    val iv = Array.ofDim[Byte](16)
    ZSecureRandom.nextBytes(iv)
    os.write(iv)
    new CipherOutputStream(os, cipher(key, iv, Cipher.ENCRYPT_MODE))
  }

  def inputStream(key: AESKey, is: InputStream) = {
    val iv = returning(new Array[Byte](16))(IoUtils.readFully(is, _, 0, 16))

    new CipherInputStream(is, cipher(key, iv, Cipher.DECRYPT_MODE)) {
      // close behaviour was changed in Java8, it now throws exception if stream wasn't properly decrypted,
      // this exception will also happen if stream wasn't fully read, we don't want that.
      // In some cases we want to only read part of a stream and be able to stop reading without unexpected errors.
      override def close(): Unit = try {
        super.close()
      } catch {
        case io: IOException =>
          io.getCause match {
            case _: BadPaddingException => //ignore
            case e => throw e
          }
      }

      private val skipBuffer = Array.ofDim[Byte](4096)

      // skip is not supported in some android versions (as well as on some JVMs), so we just read required number of bytes instead
      override def skip(n: Long): Long =
        read(skipBuffer, 0, math.min(skipBuffer.length, n.toInt))
    }
  }
}
