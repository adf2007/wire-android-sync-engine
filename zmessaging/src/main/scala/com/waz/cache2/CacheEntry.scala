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

import com.waz.db.Col._
import com.waz.db.Dao
import com.waz.model.{AESKey, CacheKey, Mime}
import com.waz.utils.wrappers.{DB, DBCursor}

case class CacheEntry(key: CacheKey,
                      mimeType: Mime,
                      length: Long,
                      lastUsed: Long,
                      encryptionKey: Option[AESKey] = None)

object CacheEntry {

  implicit object CacheEntryDao extends Dao[CacheEntry, CacheKey] {
    val Key = id[CacheKey]('key, "PRIMARY KEY").apply(_.key)
    val MimeType = text[Mime]('mime, _.str, Mime(_)).apply(_.mimeType)
    val Length = long('length)(_.length)
    val LastUsed = long('lastUsed)(_.lastUsed)
    val EncryptionKey = opt(text[AESKey]('enc_key, _.str, AESKey(_)))(_.encryptionKey)

    override val idCol = Key
    override val table = Table("CacheEntry2", Key, MimeType, Length, LastUsed, EncryptionKey)

    override def apply(implicit cursor: DBCursor): CacheEntry =
      CacheEntry(Key, MimeType, Length, LastUsed, EncryptionKey)

    def getByKey(key: CacheKey)(implicit db: DB): Option[CacheEntry] = getById(key)

    def deleteByKey(key: CacheKey)(implicit db: DB): Int = delete(key)

    def findAll(implicit db: DB): Seq[CacheEntry] = list

    def findOneNotUsed(implicit db: DB): Option[CacheEntry] = {
      single(db.query(table.name, null, null, Array.empty, null, null, orderBy = LastUsed.name))
    }

  }

}
